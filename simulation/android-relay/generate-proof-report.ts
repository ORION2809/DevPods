/**
 * Proof Artifact HTML Report Generator.
 *
 * Reads proof-run JSON, screenshots, dumpsys output, and logcat slices from an
 * artifact directory and generates a single self-contained HTML report.
 */

import * as fs from "fs";
import * as path from "path";

interface ProofArtifact {
  artifactId: string;
  proofTier: string;
  status: string;
  successfulSessionCount?: number;
  targetSessionCount?: number;
  routeSuccessCount?: number;
  sttSuccessCount?: number;
  sessions?: Array<{
    index: number;
    isCompleted: boolean;
    routeSucceeded: boolean;
    sttSucceeded: boolean;
    wrongMicSuspected: boolean;
    endpointReason?: string;
    routeState?: string;
    routeSelectedDeviceType?: string;
    finalTranscript?: string;
    finalTranscriptLength?: number;
    speechDetected?: boolean;
    rmsFrameCount?: number;
    rmsFramesAboveNoiseFloor?: number;
    rmsPeakDb?: number;
    durationMs?: number;
  }>;
  failureReasons?: string[];
  [key: string]: unknown;
}

interface ReportConfig {
  artifactDir: string;
  outputPath: string;
}

const BRAND_COVER_CANDIDATES = [
  path.resolve(process.cwd(), "assets", "brand", "docs", "devpods-proof-report-cover.png"),
  path.resolve(process.cwd(), "assets", "brand", "source", "devpods-wordmark-dark-16x9.png"),
  path.resolve(__dirname, "..", "..", "assets", "brand", "docs", "devpods-proof-report-cover.png"),
  path.resolve(__dirname, "..", "..", "assets", "brand", "source", "devpods-wordmark-dark-16x9.png"),
  path.resolve(__dirname, "..", "..", "..", "assets", "brand", "docs", "devpods-proof-report-cover.png"),
  path.resolve(__dirname, "..", "..", "..", "assets", "brand", "source", "devpods-wordmark-dark-16x9.png"),
];

function findProofArtifact(dir: string): ProofArtifact | null {
  const jsonFiles = fs.readdirSync(dir).filter((fileName) => fileName.endsWith(".json"));

  for (const fileName of jsonFiles) {
    try {
      const candidate = JSON.parse(fs.readFileSync(path.join(dir, fileName), "utf-8")) as ProofArtifact;
      if (
        typeof candidate.artifactId === "string" &&
        typeof candidate.proofTier === "string" &&
        typeof candidate.status === "string"
      ) {
        return candidate;
      }
    } catch {
      // Ignore non-proof JSON files.
    }
  }

  return null;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function base64Image(filePath: string): string | null {
  try {
    const data = fs.readFileSync(filePath);
    const ext = path.extname(filePath).toLowerCase();
    const mime = ext === ".jpg" || ext === ".jpeg" ? "image/jpeg" : "image/png";
    return `data:${mime};base64,${data.toString("base64")}`;
  } catch {
    return null;
  }
}

function resolveBrandCoverDataUri(): string | null {
  for (const candidate of BRAND_COVER_CANDIDATES) {
    if (fs.existsSync(candidate)) {
      return base64Image(candidate);
    }
  }
  return null;
}

function readText(filePath: string, maxLines = 500): string {
  try {
    const content = fs.readFileSync(filePath, "utf-8");
    const lines = content.split("\n");
    if (lines.length > maxLines) {
      return lines.slice(0, maxLines).join("\n") + `\n\n... (${lines.length - maxLines} more lines) ...`;
    }
    return content;
  } catch {
    return "(not available)";
  }
}

function generateReport(config: ReportConfig): string {
  const dir = config.artifactDir;
  const proof = findProofArtifact(dir);
  const brandCoverDataUri = resolveBrandCoverDataUri();

  const screenshots = fs
    .readdirSync(dir)
    .filter((fileName) => fileName.endsWith(".png"))
    .map((fileName) => ({
      name: fileName,
      dataUri: base64Image(path.join(dir, fileName)),
    }))
    .filter((screenshot) => screenshot.dataUri);

  const dumpsysFiles = fs
    .readdirSync(dir)
    .filter((fileName) => fileName.toLowerCase().includes("dumpsys") && fileName.endsWith(".txt"));

  const logcatPath = path.join(dir, "android-logcat.txt");
  const hasLogcat = fs.existsSync(logcatPath);

  const statusColor =
    proof?.status === "PASSED"
      ? "#22c55e"
      : proof?.status === "PARTIAL"
        ? "#f59e0b"
        : "#ef4444";

  const sessionsHtml =
    proof?.sessions
      ?.map(
        (session) => `
    <tr>
      <td>${session.index}</td>
      <td>${session.isCompleted ? "yes" : "no"}</td>
      <td>${session.routeSucceeded ? "yes" : "no"}${session.routeState ? ` (${escapeHtml(session.routeState)})` : ""}</td>
      <td>${session.sttSucceeded ? "yes" : "no"}${session.endpointReason ? ` (${escapeHtml(session.endpointReason)})` : ""}</td>
      <td>${session.wrongMicSuspected ? "yes" : "no"}</td>
      <td>${session.finalTranscriptLength ?? session.finalTranscript?.length ?? "-"}</td>
      <td>${session.rmsFrameCount ?? "-"} / ${session.rmsFramesAboveNoiseFloor ?? "-"}</td>
      <td>${session.rmsPeakDb ?? "-"}</td>
      <td>${session.routeSelectedDeviceType ? escapeHtml(session.routeSelectedDeviceType) : "-"}</td>
      <td>${session.durationMs ?? "-"}</td>
    </tr>`
      )
      .join("") || "";

  const screenshotsHtml = screenshots
    .map(
      (screenshot) => `
    <div class="screenshot">
      <h4>${escapeHtml(screenshot.name)}</h4>
      <img src="${screenshot.dataUri}" alt="${escapeHtml(screenshot.name)}" />
    </div>`
    )
    .join("");

  const dumpsysHtml = dumpsysFiles
    .map((fileName) => {
      const content = readText(path.join(dir, fileName));
      return `
    <details>
      <summary>${escapeHtml(fileName)}</summary>
      <pre>${escapeHtml(content)}</pre>
    </details>`;
    })
    .join("");

  const logcatHtml = hasLogcat
    ? `<details><summary>android-logcat.txt</summary><pre>${escapeHtml(readText(logcatPath, 300))}</pre></details>`
    : "";

  const failureHtml =
    proof?.failureReasons && proof.failureReasons.length > 0
      ? `<div class="failures"><h3>Failure Reasons</h3><ul>${proof.failureReasons
          .map((reason) => `<li>${escapeHtml(reason)}</li>`)
          .join("")}</ul></div>`
      : "";

  const headerVisual = brandCoverDataUri
    ? `<img class="cover-art" src="${brandCoverDataUri}" alt="DevPods proof report cover" />`
    : `<div class="brand-fallback">DevPods</div>`;

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Proof Report - ${proof ? escapeHtml(proof.artifactId) : "Unknown"}</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f5f1e8;
      --canvas: #e9e2d6;
      --surface: rgba(255, 252, 244, 0.88);
      --line: rgba(255, 255, 255, 0.78);
      --ink: #0d1b1e;
      --muted: #62716d;
      --teal: #0f766e;
      --red: #b42318;
      --mint-soft: #ddf8e9;
    }
    * { box-sizing: border-box; }
    body {
      font-family: Inter, system-ui, -apple-system, sans-serif;
      margin: 0;
      background:
        radial-gradient(circle at 10% 10%, rgba(53, 214, 139, 0.18), transparent 28rem),
        radial-gradient(circle at 88% 4%, rgba(244, 184, 96, 0.18), transparent 24rem),
        var(--canvas);
      color: var(--ink);
    }
    .page { max-width: 1200px; margin: 0 auto; padding: 24px; }
    h1, h2, h3 { color: var(--ink); margin-top: 0; }
    .hero {
      border-radius: 28px;
      overflow: hidden;
      background:
        linear-gradient(145deg, rgba(255,255,255,0.70), rgba(255,255,255,0.30)),
        var(--surface);
      border: 1px solid var(--line);
      box-shadow: inset 0 1px 0 rgba(255,255,255,0.84), 0 18px 42px rgba(13,27,30,0.10);
      margin-bottom: 24px;
    }
    .cover-art { display: block; width: 100%; background: #000; }
    .brand-fallback {
      background: #0d1b1e;
      color: white;
      font-size: 2rem;
      font-weight: 800;
      letter-spacing: -0.04em;
      padding: 32px 28px;
    }
    .hero-copy { padding: 24px 24px 26px; }
    .eyebrow { margin: 0 0 10px; color: var(--teal); font-size: 0.9rem; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; }
    .hero-title { font-size: clamp(2rem, 5vw, 3rem); line-height: 1.04; letter-spacing: -0.04em; margin-bottom: 12px; }
    .hero-meta { color: var(--muted); font-size: 0.98rem; }
    .status-badge {
      display: inline-block;
      padding: 8px 16px;
      border-radius: 999px;
      font-weight: 700;
      color: white;
      background: ${statusColor};
      margin-bottom: 14px;
    }
    .section-card {
      background:
        linear-gradient(145deg, rgba(255,255,255,0.74), rgba(255,255,255,0.34)),
        var(--surface);
      border: 1px solid var(--line);
      border-radius: 24px;
      padding: 20px;
      box-shadow: inset 0 1px 0 rgba(255,255,255,0.86), 0 16px 36px rgba(13,27,30,0.08);
      margin-bottom: 24px;
    }
    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; }
    .summary-card {
      background: rgba(255,255,255,0.78);
      padding: 16px;
      border-radius: 20px;
      border: 1px solid rgba(255,255,255,0.84);
      box-shadow: inset 0 1px 0 rgba(255,255,255,0.88);
    }
    .summary-card .value { font-size: 1.55rem; font-weight: 800; color: var(--ink); }
    .summary-card .label { font-size: 0.875rem; color: var(--muted); }
    table { width: 100%; border-collapse: collapse; background: rgba(255,255,255,0.72); border-radius: 18px; overflow: hidden; }
    th, td { padding: 12px; text-align: left; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
    th { background: rgba(221, 248, 233, 0.68); font-weight: 700; }
    .screenshot {
      background: rgba(255,255,255,0.78);
      padding: 16px;
      border-radius: 20px;
      margin-bottom: 16px;
      border: 1px solid rgba(255,255,255,0.84);
    }
    .screenshot img { max-width: 100%; border-radius: 12px; border: 1px solid #d8ded5; }
    details { background: rgba(255,255,255,0.78); padding: 16px; border-radius: 18px; margin-bottom: 12px; border: 1px solid rgba(255,255,255,0.84); }
    summary { font-weight: 700; cursor: pointer; }
    pre { background: rgba(255,255,255,0.66); padding: 16px; border-radius: 12px; overflow-x: auto; font-size: 0.875rem; line-height: 1.5; }
    .failures {
      background: rgba(255, 225, 220, 0.82);
      border: 1px solid rgba(244, 167, 158, 0.85);
      padding: 16px;
      border-radius: 20px;
      color: #991b1b;
      margin-bottom: 24px;
    }
    footer {
      margin-top: 36px;
      padding-top: 24px;
      border-top: 1px solid rgba(13, 27, 30, 0.10);
      color: #6b7c79;
      font-size: 0.875rem;
    }
  </style>
</head>
<body>
  <main class="page">
  <header class="hero">
    ${headerVisual}
    <div class="hero-copy">
      <p class="eyebrow">DevPods Evidence</p>
      <span class="status-badge">${proof?.status || "UNKNOWN"}</span>
      <h1 class="hero-title">Proof Report</h1>
      <div class="hero-meta">
        ${proof ? `Artifact: <strong>${escapeHtml(proof.artifactId)}</strong> | Tier: <strong>${escapeHtml(proof.proofTier)}</strong>` : "No proof artifact found"}
      </div>
    </div>
  </header>

  <section class="section-card">
    <div class="summary-grid">
      <div class="summary-card">
        <div class="value">${proof?.successfulSessionCount ?? "-"} / ${proof?.targetSessionCount ?? "-"}</div>
        <div class="label">Sessions Passed</div>
      </div>
      <div class="summary-card">
        <div class="value">${proof?.routeSuccessCount ?? "-"}</div>
        <div class="label">Route Successes</div>
      </div>
      <div class="summary-card">
        <div class="value">${proof?.sttSuccessCount ?? "-"}</div>
        <div class="label">STT Successes</div>
      </div>
      <div class="summary-card">
        <div class="value">${screenshots.length}</div>
        <div class="label">Screenshots</div>
      </div>
    </div>
  </section>

  ${failureHtml}

  <section class="section-card">
    <h2>Sessions</h2>
    <table>
      <thead>
        <tr><th>Index</th><th>Completed</th><th>Route</th><th>STT</th><th>Wrong Mic</th><th>Transcript Length</th><th>RMS Frames</th><th>RMS Peak dB</th><th>Route Device Type</th><th>Duration (ms)</th></tr>
      </thead>
      <tbody>
        ${sessionsHtml || '<tr><td colspan="10" style="text-align:center;color:#64748b">No session data</td></tr>'}
      </tbody>
    </table>
  </section>

  <section class="section-card">
    <h2>Screenshots</h2>
    ${screenshotsHtml || '<p style="color:#64748b">No screenshots captured.</p>'}
  </section>

  <section class="section-card">
    <h2>System Dumps</h2>
    ${dumpsysHtml || '<p style="color:#64748b">No dumpsys files.</p>'}
    ${logcatHtml}
  </section>

  <section class="section-card">
    <h2>Raw Proof JSON</h2>
    <details>
      <summary>proof artifact</summary>
      <pre>${escapeHtml(proof ? JSON.stringify(proof, null, 2) : "(not available)")}</pre>
    </details>
  </section>

  <footer>
    Generated by DevPods proof harness | ${new Date().toISOString()}
  </footer>
  </main>
</body>
</html>`;
}

async function main() {
  const artifactDir = process.argv[2];
  if (!artifactDir) {
    console.error("Usage: npx tsx generate-proof-report.ts <artifact-dir> [output-html-path]");
    process.exit(1);
  }

  if (!fs.existsSync(artifactDir)) {
    console.error(`Artifact directory not found: ${artifactDir}`);
    process.exit(1);
  }

  const outputPath = process.argv[3] || path.join(artifactDir, "proof-report.html");
  const html = generateReport({ artifactDir, outputPath });
  fs.writeFileSync(outputPath, html, "utf-8");
  console.log(`[Report] Generated: ${outputPath}`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error("[Report] Fatal error:", error);
    process.exit(1);
  });
}

export { generateReport };
