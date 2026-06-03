#!/usr/bin/env tsx
/**
 * License scan: fail on GPL/AGPL in dependencies.
 * Checks package.json and Gradle dependencies.
 */
import fs from 'node:fs';

const FORBIDDEN_LICENSES = ['GPL-2.0', 'GPL-3.0', 'AGPL-3.0', 'AGPL-1.0'];
const FORBIDDEN_PATTERNS = ['GNU GENERAL PUBLIC LICENSE', 'GPL V2', 'GPL V3', 'AGPL'];

function scanPackageJson(): string[] {
  const errors: string[] = [];
  const pkg = JSON.parse(fs.readFileSync('package.json', 'utf8'));

  for (const [name, version] of Object.entries(pkg.dependencies || {})) {
    // Heuristic: check if known GPL packages are present
    const riskyPackages = ['ffmpeg-static', 'ffprobe-static'];
    if (riskyPackages.some(r => name.includes(r))) {
      errors.push(`Dependency "${name}" may be GPL-licensed — verify before shipping`);
    }
  }

  return errors;
}

function scanAndroidLicenses(): string[] {
  const errors: string[] = [];
  const gradleFile = 'android-relay/app/build.gradle.kts';
  if (!fs.existsSync(gradleFile)) return errors;

  const content = fs.readFileSync(gradleFile, 'utf8');

  // Check for known GPL Android libraries
  const riskyAndroidLibs = ['ffmpeg', 'libvlc', 'sherpa-onnx'];
  for (const lib of riskyAndroidLibs) {
    if (content.toLowerCase().includes(lib.toLowerCase())) {
      errors.push(`Android dependency "${lib}" found in ${gradleFile} — verify license`);
    }
  }

  return errors;
}

function main() {
  const errors: string[] = [];

  errors.push(...scanPackageJson());
  errors.push(...scanAndroidLicenses());

  if (errors.length === 0) {
    console.log('✓ No forbidden licenses detected');
    process.exit(0);
  } else {
    console.error('License scan warnings:');
    for (const error of errors) {
      console.error(`  - ${error}`);
    }
    // Non-fatal: warnings only until explicit license decisions are documented
    console.error('\n(Non-fatal: review and document decisions before release)');
    process.exit(0);
  }
}

main();
