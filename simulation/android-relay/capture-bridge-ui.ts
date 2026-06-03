/**
 * Bridge Web UI Screenshot Capture
 *
 * Uses Playwright to capture screenshots of the DevPods bridge web UI
 * (pairing page, health endpoint, etc.) for proof artifacts.
 */

import { chromium, type Page } from "playwright";
import * as fs from "fs";
import * as path from "path";

interface CaptureConfig {
  outputDir: string;
  prefix: string;
  bridgeBaseUrl: string;
}

async function captureBridgeUI(config: CaptureConfig): Promise<string[]> {
  const captured: string[] = [];
  const browser = await chromium.launch({ headless: true });

  try {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 720 },
    });
    const page = await context.newPage();

    // Health endpoint
    const healthPath = path.join(config.outputDir, `${config.prefix}-bridge-health.png`);
    try {
      await page.goto(`${config.bridgeBaseUrl}/health`, { timeout: 5000, waitUntil: "networkidle" });
      await page.screenshot({ path: healthPath, fullPage: true });
      captured.push(healthPath);
      console.log(`[Bridge-UI] OK: ${healthPath}`);
    } catch (e) {
      console.warn(`[Bridge-UI] Health screenshot failed: ${(e as Error).message}`);
    }

    // Pairing page
    const pairingPath = path.join(config.outputDir, `${config.prefix}-bridge-pairing.png`);
    try {
      await page.goto(`${config.bridgeBaseUrl}/pairing`, { timeout: 5000, waitUntil: "networkidle" });
      await page.screenshot({ path: pairingPath, fullPage: true });
      captured.push(pairingPath);
      console.log(`[Bridge-UI] OK: ${pairingPath}`);
    } catch (e) {
      console.warn(`[Bridge-UI] Pairing screenshot failed: ${(e as Error).message}`);
    }

    await context.close();
  } finally {
    await browser.close();
  }

  return captured;
}

async function main() {
  const outputDir = process.argv[2] || path.join(__dirname, "proof-artifacts", "latest");
  const prefix = process.argv[3] || "bridge";
  const bridgeBaseUrl = process.env.BRIDGE_BASE_URL || "http://localhost:4545";

  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  const captured = await captureBridgeUI({ outputDir, prefix, bridgeBaseUrl });
  console.log(`[Bridge-UI] Captured ${captured.length} screenshots`);
}

if (require.main === module) {
  main().catch((e) => {
    console.error("[Bridge-UI] Fatal error:", e);
    process.exit(1);
  });
}

export { captureBridgeUI };
