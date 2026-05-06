import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { execFile, spawn, type ChildProcess } from 'node:child_process';

const defaultGatewayStartupTimeoutMs = 180_000;
const defaultSandboxTimeoutSeconds = 180;
const workspaceNodeModulesDir = path.resolve(process.cwd(), 'node_modules');
const sandboxInteropPatchedPackages = [
  '@mariozechner/pi-agent-core',
  '@mariozechner/pi-ai',
  '@mariozechner/pi-coding-agent',
  '@mariozechner/pi-tui',
];

export const openClawSandboxAgentId = 'jarvis_rewrite';

export interface OpenClawSandboxProviderConfig {
  providerId: string;
  modelId: string;
  modelName: string;
  baseUrl: string;
  api: 'openai-completions';
  apiKey: string;
  reasoning?: boolean;
  input?: Array<'text' | 'image'>;
  contextWindow?: number;
  maxTokens?: number;
}

export interface OpenClawSandboxOptions {
  rootDir?: string;
  workspaceDir?: string;
  port?: number;
  token?: string;
  startupTimeoutMs?: number;
  cleanupOnStop?: boolean;
  provider: OpenClawSandboxProviderConfig;
}

export interface OpenClawSandboxHandle {
  readonly baseUrl: string;
  readonly token: string;
  readonly port: number;
  readonly configPath: string;
  readonly rootDir: string;
  readonly stateDir: string;
  readonly workspaceDir: string;
  stop(): Promise<void>;
}

export async function startOpenClawSandbox(options: OpenClawSandboxOptions): Promise<OpenClawSandboxHandle> {
  return startOpenClawGatewayProcess(options, {
    rootDirPrefix: 'jarvis-openclaw-sandbox-',
    runtimeLabel: 'sandbox',
    patchRuntimeDependencies: true,
  });
}

export async function startOpenClawManagedGateway(options: OpenClawSandboxOptions): Promise<OpenClawSandboxHandle> {
  return startOpenClawGatewayProcess(options, {
    rootDirPrefix: 'jarvis-openclaw-managed-',
    runtimeLabel: 'managed gateway',
    patchRuntimeDependencies: true,
  });
}

async function startOpenClawGatewayProcess(
  options: OpenClawSandboxOptions,
  runtimeOptions: {
    rootDirPrefix: string;
    runtimeLabel: string;
    patchRuntimeDependencies: boolean;
  },
): Promise<OpenClawSandboxHandle> {
  ensureOpenClawRuntimeCompatibility();

  const rootDir = options.rootDir
    ? path.resolve(options.rootDir)
    : fs.mkdtempSync(path.join(os.tmpdir(), runtimeOptions.rootDirPrefix));
  const workspaceDir = path.resolve(options.workspaceDir ?? path.join(rootDir, 'workspace'));
  const stateDir = path.join(rootDir, 'state');
  const configPath = path.join(rootDir, 'openclaw.json');
  const stdoutLogPath = path.join(rootDir, 'openclaw.stdout.log');
  const stderrLogPath = path.join(rootDir, 'openclaw.stderr.log');
  const port = options.port ?? await findAvailablePort();
  const token = options.token ?? `jarvis-sandbox-${Math.random().toString(36).slice(2, 10)}`;
  const cleanupOnStop = options.cleanupOnStop ?? !options.rootDir;

  fs.mkdirSync(rootDir, { recursive: true });
  fs.mkdirSync(workspaceDir, { recursive: true });
  fs.mkdirSync(stateDir, { recursive: true });

  if (runtimeOptions.patchRuntimeDependencies) {
    seedSandboxRuntimeDependency(stateDir, 'json5');
    for (const packageName of sandboxInteropPatchedPackages) {
      seedSandboxRuntimeDependency(stateDir, packageName, patchPackageExportsForRequire);
    }
  }

  const config = buildOpenClawSandboxConfig({
    port,
    token,
    workspaceDir,
    provider: options.provider,
  });

  fs.writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`, 'utf8');
  fs.writeFileSync(stdoutLogPath, '', 'utf8');
  fs.writeFileSync(stderrLogPath, '', 'utf8');

  const command = resolveOpenClawCommand();
  const args = [...command.prefixArgs, 'gateway', '--port', String(port), '--bind', 'loopback'];
  const child = spawn(command.command, args, {
    cwd: process.cwd(),
    shell: false,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: buildOpenClawEnvironment({
      configPath,
      stateDir,
      workspaceDir,
      providerPluginIds: [options.provider.providerId],
    }),
  });

  let stdout = '';
  let stderr = '';

  child.stdout.on('data', (chunk) => {
    const text = chunk.toString();
    stdout += text;
    fs.appendFileSync(stdoutLogPath, text, 'utf8');
  });

  child.stderr.on('data', (chunk) => {
    const text = chunk.toString();
    stderr += text;
    fs.appendFileSync(stderrLogPath, text, 'utf8');
  });

  try {
    await waitForGatewayReady({
      baseUrl: `http://127.0.0.1:${port}`,
      token,
      child,
      runtimeLabel: runtimeOptions.runtimeLabel,
      timeoutMs: options.startupTimeoutMs ?? defaultGatewayStartupTimeoutMs,
      getLogs: () => `${stdout}\n${stderr}`.trim(),
    });
  } catch (error) {
    await stopChildProcess(child).catch(() => {
      child.kill();
    });
    throw error;
  }

  return {
    baseUrl: `http://127.0.0.1:${port}`,
    token,
    port,
    configPath,
    rootDir,
    stateDir,
    workspaceDir,
    async stop() {
      await stopChildProcess(child);
      if (cleanupOnStop) {
        try {
          fs.rmSync(rootDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
        } catch (error) {
          if (!isIgnorableCleanupError(error)) {
            throw error;
          }
        }
      }
    },
  };
}

export function buildOpenClawSandboxConfig(options: {
  port: number;
  token: string;
  workspaceDir: string;
  provider: OpenClawSandboxProviderConfig;
}): Record<string, unknown> {
  const providerModelId = normalizeProviderModelId(options.provider);
  const modelRef = `${options.provider.providerId}/${providerModelId}`;
  return {
    gateway: {
      mode: 'local',
      bind: 'loopback',
      port: options.port,
      auth: {
        mode: 'token',
        token: options.token,
      },
      controlUi: {
        enabled: false,
      },
      http: {
        endpoints: {
          chatCompletions: { enabled: true },
        },
      },
    },
    agents: {
      defaults: {
        workspace: options.workspaceDir,
        skills: [],
        startupContext: {
          enabled: false,
        },
        timeoutSeconds: defaultSandboxTimeoutSeconds,
        model: {
          primary: modelRef,
        },
        models: {
          [modelRef]: {
            alias: 'Jarvis OpenClaw Sandbox',
          },
        },
      },
      list: [
        {
          id: openClawSandboxAgentId,
          workspace: options.workspaceDir,
          skills: [],
          model: {
            primary: modelRef,
          },
        },
      ],
    },
    plugins: {
      enabled: true,
      allow: [options.provider.providerId],
      entries: {
        [options.provider.providerId]: {
          enabled: true,
        },
      },
      slots: {
        memory: 'none',
      },
    },
    models: {
      mode: 'merge',
      providers: {
        [options.provider.providerId]: {
          baseUrl: options.provider.baseUrl,
          api: options.provider.api,
          apiKey: options.provider.apiKey,
          timeoutSeconds: defaultSandboxTimeoutSeconds,
          models: [
            {
              id: providerModelId,
              name: options.provider.modelName,
              reasoning: options.provider.reasoning ?? false,
              input: options.provider.input ?? ['text'],
              cost: {
                input: 0,
                output: 0,
                cacheRead: 0,
                cacheWrite: 0,
              },
              contextWindow: options.provider.contextWindow ?? 128000,
              maxTokens: options.provider.maxTokens ?? 8192,
            },
          ],
        },
      },
    },
  };
}

async function findAvailablePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') {
        reject(new Error('Unable to allocate a free port.'));
        return;
      }

      server.close((error) => {
        if (error) {
          reject(error);
          return;
        }

        resolve(address.port);
      });
    });
    server.on('error', reject);
  });
}

export function resolveOpenClawCommand(): { command: string; prefixArgs: string[] } {
  const localBin = path.resolve(
    process.cwd(),
    'node_modules',
    '.bin',
    process.platform === 'win32' ? 'openclaw.cmd' : 'openclaw',
  );

  if (fs.existsSync(localBin)) {
    if (process.platform === 'win32') {
      return {
        command: process.env.ComSpec ?? 'cmd.exe',
        prefixArgs: ['/d', '/s', '/c', localBin],
      };
    }

    return { command: localBin, prefixArgs: [] };
  }

  if (process.platform === 'win32') {
    return {
      command: process.env.ComSpec ?? 'cmd.exe',
      prefixArgs: ['/d', '/s', '/c', 'npx', '--yes', 'openclaw@latest'],
    };
  }

  return {
    command: 'npx',
    prefixArgs: ['--yes', 'openclaw@latest'],
  };
}

function seedSandboxRuntimeDependency(
  stateDir: string,
  packageName: string,
  rewritePackageJson?: (packageJson: Record<string, unknown>) => Record<string, unknown>,
): void {
  const packageRoot = resolveInstalledPackageRoot(packageName);
  const targetRoot = path.join(stateDir, 'plugin-runtime-deps', 'node_modules', packageName);

  if (fs.existsSync(targetRoot)) {
    return;
  }

  fs.mkdirSync(path.dirname(targetRoot), { recursive: true });
  fs.cpSync(packageRoot, targetRoot, { recursive: true, force: true });

  if (!rewritePackageJson) {
    return;
  }

  const packageJsonPath = path.join(targetRoot, 'package.json');
  const parsedPackageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8')) as Record<string, unknown>;
  fs.writeFileSync(packageJsonPath, `${JSON.stringify(rewritePackageJson(parsedPackageJson), null, 2)}\n`, 'utf8');
}

function resolveInstalledPackageRoot(packageName: string): string {
  const directPackageRoot = path.resolve(process.cwd(), 'node_modules', ...packageName.split('/'));
  if (fs.existsSync(path.join(directPackageRoot, 'package.json'))) {
    return directPackageRoot;
  }

  let currentDir = path.dirname(require.resolve(packageName));

  for (;;) {
    const packageJsonPath = path.join(currentDir, 'package.json');
    if (fs.existsSync(packageJsonPath)) {
      return currentDir;
    }

    const parentDir = path.dirname(currentDir);
    if (parentDir === currentDir) {
      throw new Error(`Could not resolve installed package root for ${packageName}.`);
    }

    currentDir = parentDir;
  }
}

function patchPackageExportsForRequire(packageJson: Record<string, unknown>): Record<string, unknown> {
  const exportsField = packageJson.exports;
  if (!exportsField || typeof exportsField !== 'object' || Array.isArray(exportsField)) {
    return packageJson;
  }

  return {
    ...packageJson,
    exports: Object.fromEntries(
      Object.entries(exportsField as Record<string, unknown>).map(([exportKey, exportValue]) => [
        exportKey,
        patchPackageExportTarget(exportValue),
      ]),
    ),
  };
}

function patchPackageExportTarget(exportValue: unknown): unknown {
  if (!exportValue || typeof exportValue !== 'object' || Array.isArray(exportValue)) {
    return exportValue;
  }

  const normalizedExportValue = { ...(exportValue as Record<string, unknown>) };
  const importTarget = typeof normalizedExportValue.import === 'string'
    ? normalizedExportValue.import
    : typeof normalizedExportValue.default === 'string'
      ? normalizedExportValue.default
      : undefined;

  if (!importTarget) {
    return normalizedExportValue;
  }

  return {
    ...normalizedExportValue,
    require: normalizedExportValue.require ?? importTarget,
    default: normalizedExportValue.default ?? importTarget,
  };
}

export function buildOpenClawEnvironment(options: {
  configPath: string;
  stateDir: string;
  workspaceDir: string;
  providerPluginIds?: string[];
}): NodeJS.ProcessEnv {
  const baseEnv = { ...process.env };
  for (const key of Object.keys(baseEnv)) {
    if (key === 'VITEST' || key.startsWith('VITEST_')) {
      delete baseEnv[key];
    }
  }

  const nvidiaApiKey = process.env.NVIDIA_API_KEY?.trim() || process.env.NVIDIA_NIM_API_KEY?.trim();
  const providerPluginIds = options.providerPluginIds?.map((pluginId) => pluginId.trim()).filter(Boolean) ?? [];
  const nodePath = [
    path.join(options.stateDir, 'plugin-runtime-deps', 'node_modules'),
    workspaceNodeModulesDir,
    process.env.NODE_PATH,
  ].filter(Boolean).join(path.delimiter);

  return {
    ...baseEnv,
    OPENCLAW_CONFIG_PATH: options.configPath,
    OPENCLAW_STATE_DIR: options.stateDir,
    OPENCLAW_WORKSPACE_DIR: options.workspaceDir,
    OPENCLAW_SKIP_CHANNELS: '1',
    OPENCLAW_SKIP_CANVAS_HOST: '1',
    OPENCLAW_DISABLE_LEGACY_IMPLICIT_STARTUP_SIDECARS: '1',
    ...(providerPluginIds.length > 0
      ? { OPENCLAW_TEST_ONLY_PROVIDER_PLUGIN_IDS: providerPluginIds.join(',') }
      : {}),
    ...(nodePath ? { NODE_PATH: nodePath } : {}),
    ...(nvidiaApiKey ? { NVIDIA_API_KEY: nvidiaApiKey } : {}),
  };
}

export function ensureOpenClawRuntimeCompatibility(): void {
  ensurePackageSupportsRequire('osc-progress');
}

async function waitForGatewayReady(options: {
  baseUrl: string;
  token: string;
  child: ChildProcess;
  runtimeLabel: string;
  timeoutMs: number;
  getLogs: () => string;
}): Promise<void> {
  const deadline = Date.now() + options.timeoutMs;

  while (Date.now() < deadline) {
    if (options.child.exitCode !== null) {
      throw new Error(`OpenClaw ${options.runtimeLabel} exited early: ${options.getLogs()}`.trim());
    }

    try {
      const response = await fetch(`${options.baseUrl}/healthz`, {
        headers: {
          Authorization: `Bearer ${options.token}`,
        },
        signal: AbortSignal.timeout(2_000),
      });

      if (response.ok) {
        return;
      }
    } catch {
      // Keep polling until timeout.
    }

    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  throw new Error(`Timed out waiting for OpenClaw ${options.runtimeLabel} readiness: ${options.getLogs()}`.trim());
}

async function stopChildProcess(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null) {
    return;
  }

  const pid = child.pid;
  if (process.platform === 'win32' && pid) {
    await new Promise<void>((resolve) => {
      execFile('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true }, () => {
        resolve();
      });
    });

    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, 2_000);
      child.once('close', () => {
        clearTimeout(timer);
        resolve();
      });
    });
    return;
  }

  await new Promise<void>((resolve) => {
    const timer = setTimeout(() => {
      if (child.exitCode === null) {
        child.kill('SIGKILL');
      }
    }, 2_000);

    child.once('close', () => {
      clearTimeout(timer);
      resolve();
    });

    child.kill();
  });
}

function normalizeProviderModelId(provider: OpenClawSandboxProviderConfig): string {
  const providerPrefix = `${provider.providerId}/`;
  return provider.modelId.startsWith(providerPrefix)
    ? provider.modelId.slice(providerPrefix.length)
    : provider.modelId;
}

function ensurePackageSupportsRequire(packageName: string): void {
  const packageJsonPath = path.join(workspaceNodeModulesDir, packageName, 'package.json');
  if (!fs.existsSync(packageJsonPath)) {
    return;
  }

  const packageJson = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8')) as {
    main?: unknown;
    exports?: Record<string, unknown>;
  };
  const rootExport = packageJson.exports?.['.'];

  if (!rootExport || Array.isArray(rootExport) || typeof rootExport !== 'object') {
    return;
  }

  const importTarget = typeof (rootExport as { import?: unknown }).import === 'string'
    ? (rootExport as { import: string }).import
    : typeof packageJson.main === 'string'
      ? packageJson.main
      : null;

  if (!importTarget) {
    return;
  }

  const updatedRootExport = {
    ...(rootExport as Record<string, unknown>),
    require: (rootExport as { require?: unknown }).require ?? importTarget,
    default: (rootExport as { default?: unknown }).default ?? importTarget,
  };

  if (
    (rootExport as { require?: unknown }).require === updatedRootExport.require
    && (rootExport as { default?: unknown }).default === updatedRootExport.default
  ) {
    return;
  }

  packageJson.exports = {
    ...packageJson.exports,
    '.': updatedRootExport,
  };
  fs.writeFileSync(packageJsonPath, `${JSON.stringify(packageJson, null, 2)}\n`, 'utf8');
}

function isIgnorableCleanupError(error: unknown): boolean {
  if (!(error instanceof Error)) {
    return false;
  }

  const code = (error as NodeJS.ErrnoException).code;
  return code === 'EPERM' || code === 'ENOTEMPTY' || code === 'EBUSY';
}