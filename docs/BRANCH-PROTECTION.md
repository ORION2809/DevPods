# Branch Protection and Required Status Checks

This document describes the required CI checks that must pass before merging to `main` or release branches.

## Required Status Checks

The following GitHub Actions workflow jobs are defined in `.github/workflows/ci.yml` and **must be configured as required status checks** in the repository branch protection settings:

| Job | Purpose |
|-----|---------|
| `bridge` | TypeScript typecheck, build, Vitest tests, npm audit, allowlist enforcement, secret scan, matrix schema validation, proof-run validation, license scan |
| `android` | Android debug assembly, 166 unit tests, lint |
| `windows-package` | Windows bridge packaging (depends on `bridge`) |

## How to Configure

Repository administrators should enable these settings in GitHub:

1. Go to **Settings > Branches**
2. Add/edit branch protection rule for `main` (and `master` if used)
3. Enable **Require a pull request before merging**
4. Enable **Require status checks to pass before merging**
5. Search for and select these status checks:
   - `Bridge (TypeScript)`
   - `Android Relay`
   - `Windows Bridge Package`
6. Enable **Require branches to be up to date before merging**
7. Optionally enable **Require conversation resolution before merging**

## Why These Checks Matter

- **Bridge typecheck/tests**: Prevents runtime errors from TypeScript regressions
- **Android tests**: Ensures the relay app logic remains correct across changes
- **Secret scan**: Blocks accidental commits of API keys or tokens
- **Matrix validation**: Ensures the supported-devices JSON remains schema-compliant
- **Proof-run validation**: Enforces the physical proof contract for any checked-in artifacts
- **License scan**: Flags GPL/AGPL dependencies before release

## Merge Blocking

No release branch should merge with:
- Failing TypeScript compilation
- Failing Vitest tests
- Failing Android unit tests
- Failing Android lint
- Secret scan matches
- Invalid matrix schema
- Invalid proof-run artifacts (if any are checked in)

## Known Pre-Existing Test Failures

The following tests are known to fail in the current environment due to optional dependencies and are **not** blockers:

- `test/openclaw-mode.test.ts` — Requires `@mariozechner/pi-agent-core` (optional)
- `test/openclaw-managed-mode.test.ts` — Requires `@mariozechner/pi-agent-core` (optional)
- `test/cli-openclaw-http.test.ts` — OpenClaw transport metadata mismatch (pre-existing)

These are tracked as environment-specific issues, not code regressions.
