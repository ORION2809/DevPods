# Hermes Ticket Loop Template

Generated: 2026-05-18

Purpose: provide a reusable prompt-and-check loop for running one ticket at a time through Hermes when the active model is weaker and needs bounded tasks plus an external completion gate.

## Operating Rules

- One ticket per Hermes run.
- One validation command per ticket.
- The harness, not Hermes, decides whether the ticket is actually done.
- If required files are missing from the diff, send a continuation prompt instead of starting a new broad task.
- If validation fails, the next prompt must tell Hermes to repair only that same slice and rerun the same command.

## Initial Prompt Template

```text
Repository: c:\Users\ShreyasSuvarna\Desktop\its_mine\firmware_earphones
Working directory: c:\Users\ShreyasSuvarna\Desktop\its_mine\firmware_earphones\android-relay
Primary source of truth: docs/22-voice-audio-pipeline-external-source-blueprint.md

Ticket:
- ID: {TICKET_ID}
- Title: {TITLE}
- Why bounded: {WHY_BOUNDED}
- Anchor files:
  - {ANCHOR_1}
  - {ANCHOR_2}
  - {ANCHOR_3}
  - {ANCHOR_4}
- Excluded scope:
  - {EXCLUDED_SCOPE_1}
  - {EXCLUDED_SCOPE_2}
  - {EXCLUDED_SCOPE_3}
- Acceptance checks:
  - {CHECK_1}
  - {CHECK_2}
  - {CHECK_3}
- Validation command:
  {VALIDATION_COMMAND}

Execution rules:
- Read only the anchor files and one nearby test before editing.
- Make the smallest change that satisfies the acceptance checks.
- Do not broaden scope.
- Run the validation command immediately after the first substantive edit.
- If validation fails, repair only this slice and rerun the same command.
- Do not commit.
- Before stopping, verify that the git diff includes the required file changes for this ticket.

Return:
- Files changed
- Acceptance checks with pass or fail for each line
- Validation result
- Any blocker that should become the next ticket
```

## Continuation Prompt Template

Use this when Hermes stalls after a partial diff.

```text
Continue from the CURRENT BRANCH STATE.
Do not restart from scratch.

The following parts are already done:
- {DONE_1}
- {DONE_2}

Only finish the remaining work:
- {REMAINING_1}
- {REMAINING_2}
- {REMAINING_3}

Required final diff must include:
- {REQUIRED_FILE_1}
- {REQUIRED_FILE_2}
- {REQUIRED_FILE_3}
- {REQUIRED_TEST_FILE}

Before stopping:
- run {VALIDATION_COMMAND}
- verify the diff contains all required files
- summarize what changed and the validation result

Do not create temp files.
Do not broaden scope.
Do not commit.
```

## Harness Diff Gate

After each Hermes turn, check all of the following before accepting the ticket:

- The diff includes every required anchor file that should have changed.
- No excluded-scope file changed unless there was a compile-forcing adjacent edit.
- The validation command ran.
- The validation result is either passing or clearly blocked.
- Hermes did not replace unrelated contracts or invent temporary files.

If any gate fails, send a continuation prompt instead of marking the ticket done.

## Suggested First Ticket Prompt

Use `VAP-01` first because it is pure Kotlin and should strongly expose whether the model can finish a bounded ticket cleanly.

```text
Repository: c:\Users\ShreyasSuvarna\Desktop\its_mine\firmware_earphones
Working directory: c:\Users\ShreyasSuvarna\Desktop\its_mine\firmware_earphones\android-relay
Primary source of truth: docs/22-voice-audio-pipeline-external-source-blueprint.md

Ticket:
- ID: VAP-01
- Title: Flag missed barge-in target as a proof-run failure reason
- Why bounded: pure summary logic over existing interruption metrics with a nearby test already in place
- Anchor files:
  - android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt
  - android-relay/app/src/main/java/com/openclaw/relay/TtsInterruptionMetrics.kt
  - android-relay/app/src/test/java/com/openclaw/relay/TtsInterruptionMetricsTest.kt
- Excluded scope:
  - no TTS engine changes
  - no RelayService timing changes
  - no UI changes
- Acceptance checks:
  - VoiceProofRunSummary.failureReasons includes interruption_target_missed when any recorded interruption misses the 250 ms target
  - the failure flag is absent when all interruptions meet the target
  - interruptionTargetMetCount keeps its current counting behavior
- Validation command:
  .\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsInterruptionMetricsTest"

Execution rules:
- Read only the anchor files and one nearby test before editing.
- Make the smallest change that satisfies the acceptance checks.
- Do not broaden scope.
- Run the validation command immediately after the first substantive edit.
- If validation fails, repair only this slice and rerun the same command.
- Do not commit.
- Before stopping, verify that the git diff includes the required file changes for this ticket.

Return:
- Files changed
- Acceptance checks with pass or fail for each line
- Validation result
- Any blocker that should become the next ticket
```

## Secondary Ticket Prompt

Use `VAP-05` only after the loop proves itself on `VAP-01`.

Reason:

- It crosses request contracts, engine plumbing, Android recognizer wiring, and tests.
- It is still bounded, but it is less forgiving than `VAP-01` for a weak local model.