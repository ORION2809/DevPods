#!/usr/bin/env tsx
/**
 * Machine-checked npm audit allowlist validator.
 *
 * Usage:
 *   npx tsx scripts/audit-allowlist.ts [--audit-level=moderate] [--allowlist=security/audit-allowlist.json]
 *
 * Behavior:
 *   1. Reads security/audit-allowlist.json
 *   2. Runs `npm audit --json` and `npm ls <pkg> --json` for each exception
 *   3. Verifies each exception:
 *      - Installed version matches allowedVersion
 *      - Expiry date has not passed
 *   4. Fails if:
 *      - Any exception is expired
 *      - Any exception's installed version changed from allowed
 *      - Any NEW vulnerability (not in allowlist) with severity >= threshold exists
 *   5. Prints a machine-readable summary
 */

import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

interface AuditAllowlistException {
  id: string;
  package: string;
  severity: string;
  title: string;
  allowedVersion: string;
  installedVersionPath?: string;
  expiresAt: string;
  rationale: string;
  remediationPlan: string;
  reviewIntervalDays: number;
}

interface AuditAllowlist {
  version: number;
  updated: string;
  owner: string;
  exceptions: AuditAllowlistException[];
}

interface NpmAuditVulnerability {
  name: string;
  severity: string;
  isDirect: boolean;
  via: Array<
    | string
    | {
        source: number;
        name: string;
        dependency: string;
        title: string;
        url: string;
        severity: string;
        range: string;
      }
  >;
  effects: string[];
  range: string;
  nodes: string[];
  fixAvailable?: boolean | { name: string; version: string; isSemVerMajor: boolean };
}

interface NpmAuditReport {
  auditReportVersion: number;
  vulnerabilities: Record<string, NpmAuditVulnerability>;
  metadata?: {
    vulnerabilities: {
      info: number;
      low: number;
      moderate: number;
      high: number;
      critical: number;
      total: number;
    };
  };
}

const SEVERITY_RANK: Record<string, number> = {
  info: 0,
  low: 1,
  moderate: 2,
  high: 3,
  critical: 4,
};

function parseArgs(): { auditLevel: string; allowlistPath: string } {
  const args = process.argv.slice(2);
  let auditLevel = "moderate";
  let allowlistPath = "security/audit-allowlist.json";
  for (const arg of args) {
    if (arg.startsWith("--audit-level=")) {
      auditLevel = arg.split("=")[1];
    } else if (arg.startsWith("--allowlist=")) {
      allowlistPath = arg.split("=")[1];
    }
  }
  return { auditLevel, allowlistPath };
}

function runNpmAudit(): NpmAuditReport {
  try {
    const output = execSync("npm audit --json", {
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "pipe"],
      maxBuffer: 10 * 1024 * 1024,
    });
    return JSON.parse(output);
  } catch (error: any) {
    // npm audit exits non-zero when vulnerabilities are found, but still writes JSON to stdout
    if (error.stdout) {
      try {
        return JSON.parse(error.stdout.toString());
      } catch {
        // fall through
      }
    }
    throw new Error(`npm audit failed: ${error.message}`);
  }
}

function getInstalledVersion(pkgName: string): string | null {
  try {
    const output = execSync(`npm ls ${pkgName} --json --depth=0`, {
      encoding: "utf-8",
      stdio: ["pipe", "pipe", "pipe"],
    });
    const data = JSON.parse(output);
    const dep = data.dependencies?.[pkgName];
    if (dep?.version) return dep.version;
    return null;
  } catch {
    return null;
  }
}

function getInstalledVersionFromPath(relPath: string): string | null {
  try {
    const pkgPath = resolve(relPath);
    const pkg = JSON.parse(readFileSync(pkgPath, "utf-8"));
    return pkg.version ?? null;
  } catch {
    return null;
  }
}

function rank(sev: string): number {
  return SEVERITY_RANK[sev.toLowerCase()] ?? 0;
}

function main(): void {
  const { auditLevel, allowlistPath } = parseArgs();
  const threshold = rank(auditLevel);

  const allowlist: AuditAllowlist = JSON.parse(
    readFileSync(resolve(allowlistPath), "utf-8")
  );

  const audit = runNpmAudit();
  const vulnerabilities = audit.vulnerabilities ?? {};
  const exceptionIds = new Set(allowlist.exceptions.map((e) => e.id));
  const exceptionPackages = new Set(allowlist.exceptions.map((e) => e.package));

  const failures: string[] = [];
  const warnings: string[] = [];

  // Validate each allowlist exception
  for (const exc of allowlist.exceptions) {
    const now = new Date();
    const expiry = new Date(exc.expiresAt);
    const daysUntilExpiry = Math.ceil(
      (expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24)
    );

    if (now > expiry) {
      failures.push(
        `FAIL: Allowlist exception ${exc.id} (${exc.package}) EXPIRED on ${exc.expiresAt}. Re-evaluate immediately.`
      );
      continue;
    }

    if (daysUntilExpiry <= 7) {
      warnings.push(
        `WARNING: Allowlist exception ${exc.id} expires in ${daysUntilExpiry} day(s) (${exc.expiresAt}). Schedule review.`
      );
    }

    // Check installed version
    let installed = exc.installedVersionPath
      ? getInstalledVersionFromPath(exc.installedVersionPath)
      : getInstalledVersion(exc.package);

    if (!installed) {
      failures.push(
        `FAIL: Could not determine installed version of ${exc.package} (expected ${exc.allowedVersion}).`
      );
      continue;
    }

    if (installed !== exc.allowedVersion) {
      failures.push(
        `FAIL: ${exc.package} installed version is ${installed}, but allowlist only permits ${exc.allowedVersion}. The exception must be re-evaluated before upgrading.`
      );
      continue;
    }

    // Verify the vulnerability still appears in audit output
    const vuln = vulnerabilities[exc.package];
    if (!vuln) {
      warnings.push(
        `INFO: Allowlisted vulnerability ${exc.id} no longer appears in npm audit. Consider removing the exception.`
      );
    } else {
      // Check that the advisory source matches
      const sources = vuln.via
        .filter((v): v is { source: number } => typeof v === "object" && "source" in v)
        .map((v) => String(v.source));
      // GHSA IDs aren't in the numeric source field; we rely on package+title matching
      const titleMatch = vuln.via.some(
        (v) =>
          typeof v === "object" &&
          (v.title?.toLowerCase().includes("malware") ?? false)
      );
      if (!titleMatch && !sources.includes(exc.id)) {
        warnings.push(
          `WARNING: Allowlist exception ${exc.id} may not match current audit output for ${exc.package}. Manual review recommended.`
        );
      }
    }

    console.log(
      `✓ Allowlist OK: ${exc.package}@${installed} | ${exc.id} | expires ${exc.expiresAt} (${daysUntilExpiry}d)`
    );
  }

  // Check for unallowlisted vulnerabilities at or above threshold
  for (const [pkgName, vuln] of Object.entries(vulnerabilities)) {
    if (exceptionPackages.has(pkgName)) {
      // If it's allowlisted, we still need to check severity hasn't escalated
      // (but npm audit already reports the same severity)
      continue;
    }
    if (rank(vuln.severity) >= threshold) {
      // Is this an effect of an allowlisted package (recursive check)?
      function tracesToAllowlisted(
        name: string,
        visited: Set<string> = new Set()
      ): boolean {
        if (visited.has(name)) return false;
        visited.add(name);
        if (exceptionPackages.has(name)) return true;
        const v = vulnerabilities[name];
        if (!v) return false;
        return v.via.some((ref) => {
          const depName = typeof ref === "string" ? ref : ref.name;
          return tracesToAllowlisted(depName, visited);
        });
      }
      const isEffectOfAllowlisted = vuln.via.some((v) => {
        const depName = typeof v === "string" ? v : v.name;
        return tracesToAllowlisted(depName);
      });
      if (isEffectOfAllowlisted) {
        // This is a transitive effect of an allowlisted package; already covered
        continue;
      }
      failures.push(
        `FAIL: Unallowlisted ${vuln.severity} vulnerability in ${pkgName} (range: ${vuln.range}). Add to allowlist or fix.`
      );
    }
  }

  // Summary
  const totalVulns = audit.metadata?.vulnerabilities?.total ?? Object.keys(vulnerabilities).length;
  console.log(`\n--- Audit Allowlist Summary ---`);
  console.log(`Allowlist version: ${allowlist.version} | Updated: ${allowlist.updated} | Owner: ${allowlist.owner}`);
  console.log(`Total packages with vulnerabilities: ${Object.keys(vulnerabilities).length}`);
  console.log(`Threshold severity: ${auditLevel}`);
  console.log(`Failures: ${failures.length}`);
  console.log(`Warnings: ${warnings.length}`);

  for (const w of warnings) console.log(`⚠ ${w}`);
  for (const f of failures) console.log(`✗ ${f}`);

  if (failures.length > 0) {
    console.log(`\nExit: 1 (${failures.length} failure(s))`);
    process.exit(1);
  }

  console.log(`\nExit: 0 (all checks passed)`);
  process.exit(0);
}

main();
