import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { generateReport } from '../simulation/android-relay/generate-proof-report';

describe('proof report branding', () => {
  const tempDirs: string[] = [];

  afterEach(() => {
    for (const dir of tempDirs.splice(0)) {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('embeds the branded proof cover and report header', () => {
    const artifactDir = fs.mkdtempSync(path.join(os.tmpdir(), 'devpods-proof-report-'));
    tempDirs.push(artifactDir);

    fs.writeFileSync(
      path.join(artifactDir, 'proof.json'),
      JSON.stringify(
        {
          artifactId: 'demo-artifact',
          proofTier: 'T2',
          status: 'PASSED',
          successfulSessionCount: 18,
          targetSessionCount: 20,
          routeSuccessCount: 19,
          sttSuccessCount: 18,
          sessions: [],
        },
        null,
        2,
      ),
      'utf-8',
    );

    const html = generateReport({
      artifactDir,
      outputPath: path.join(artifactDir, 'proof-report.html'),
    });

    expect(html).toContain('Proof Report');
    expect(html).toContain('class="cover-art"');
    expect(html).toContain('data:image/png;base64');
    expect(html).toContain('DevPods Evidence');
  });
});
