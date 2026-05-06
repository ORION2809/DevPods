# Hardware Target Selection

## Purpose

This document selects the first hardware target for the developer-earbuds MVP.

The decision must be made from verifiable engineering evidence, not from aesthetics, optimism, or brand familiarity.

A candidate only advances if it passes the hard gates and then earns a competitive weighted score out of 100.

## Decision Rule

We will not choose hardware because it feels exciting, looks like the final product, or seems cheap in isolation.

We will choose the target that provides the strongest evidence for:

- firmware control
- safe recovery
- programmable BLE event flow
- fast iteration toward the Jarvis loop

Unknowns score as zero until proven.

## Candidate Classes

| Candidate class | Description | MVP role |
| --- | --- | --- |
| Open-firmware earbuds | Earbud platforms with known firmware projects, documented flashing paths, and some community support | Preferred primary target |
| Modifiable dev boards or headset prototypes | Audio-capable dev kits, reference designs, or headset rigs with exposed toolchains and debug paths | Best backup and bring-up platform |
| Cheap OEM earbuds | Low-cost retail TWS units with unclear firmware and recovery stories | Later validation track, not the first MVP target |

## Hard Gates

Hard gates are pass or fail. If a candidate fails any hard gate, it is not a valid primary MVP target regardless of its weighted score.

| Hard gate | Pass condition | Why it is non-negotiable |
| --- | --- | --- |
| Chipset identified | Exact SoC is known from teardown, tooling output, or vendor documentation | Unknown silicon creates blind risk in toolchain, flashing, and BLE support |
| Firmware access | There is a reproducible way to build, patch, or flash firmware or firmware-like configuration | No firmware path means no programmable control surface |
| Recovery mode | There is a tested unbrick path such as DFU, boot ROM, UART, test pads, rollback, or dual-bank recovery | An MVP cannot depend on disposable hardware |
| BLE support | The platform can expose or be extended with a usable BLE control and event path | The bridge depends on programmable device-to-host events |
| Gesture hardware | Touch, button, or equivalent gesture input exists and can be read or remapped reliably | No gesture surface means no earbud-first interaction loop |
| Legal and IP posture | No obvious requirement to bypass protected firmware, violate NDA-only SDK terms, or ship questionable derivative work | Legal ambiguity is a product risk, not a side issue |

If mic path or audio prompt support are weak, the device may still be used as an integration mule, but it should not be the primary voice-first target.

## Weighted Scoring Model

Only candidates that pass all hard gates are ranked.

Score each criterion from 0 to 10, then compute the weighted score using:

`weighted score = sum((rating / 10) * weight)`

Maximum score is 100.

Score meaning:

- `0` = blocked or unknown
- `5` = partially proven, major gaps remain
- `8` = proven and workable for MVP
- `10` = strongly proven and repeatable

## Criteria And Weights

| Criterion | Why it matters | Weight |
| --- | --- | --- |
| Chipset | Determines SDK, tooling, flashing, and reverse-engineering feasibility | 16 |
| Firmware access | Determines whether you can build, patch, or flash at all | 16 |
| Recovery mode | Determines whether you can unbrick after bad flashes or experiments | 12 |
| BLE support | Determines whether the buds can emit custom events and receive config | 12 |
| Gesture hardware | Determines whether tap, hold, and both-bud inputs are available and reliable | 10 |
| Mic path | Determines whether push-to-talk or gesture-to-talk can reach acceptable voice quality | 8 |
| Audio prompt support | Determines whether short Jarvis prompts can be played cleanly and quickly | 8 |
| Community docs | Reduces reverse-engineering cost and debugging time | 8 |
| Cost per unit | Matters because hardware iteration needs spare units and failure tolerance | 6 |
| Legal and IP risk | Avoids messy commercial exposure and prevents product dead ends | 4 |

## Candidate Scorecard Template

Use this table for every real device candidate.

| Candidate | Chipset | Firmware access | Recovery mode | BLE support | Gesture hardware | Mic path | Audio prompts | Community docs | Cost | Legal/IP | Weighted score | Decision |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Candidate A | 0-10 | 0-10 | 0-10 | 0-10 | 0-10 | 0-10 | 0-10 | 0-10 | 0-10 | 0-10 | 0-100 | Advance or reject |

## Evidence Pack Required Per Candidate

Every score must cite evidence.

Minimum evidence pack:

- confirmed chipset and source of identification
- flashing or patching steps with logs or screenshots
- recovery test result from rollback, recovery mode, or safe flash failure drill
- BLE scan, GATT dump, or packet capture
- gesture input proof
- mic path test
- audio prompt playback test
- community references
- real acquisition cost
- one-paragraph legal and IP note

## Decision Thresholds

| Score band | Decision |
| --- | --- |
| 75 to 100 | Valid primary MVP target |
| 60 to 74 | Secondary target or backup platform |
| Below 60 | Not worth MVP focus |
| Any hard-gate fail | Reject as primary MVP target |

## Working Shortlist

The shortlist below is a queue for evidence gathering, not a final ranking.

| Candidate track | Class | Current status | Notes |
| --- | --- | --- | --- |
| PineBuds Pro or an OpenPineBuds-style target | Open-firmware earbuds | Research first | Already aligned with the product vision, but still must pass the matrix |
| Audio dev kit or modifiable headset prototype | Dev board or prototype | Backup path | Useful if earbud firmware work slips but the bridge must keep moving |
| Cheap OEM TWS shortlist | Cheap OEM earbuds | Defer | Attractive on cost, but often fails firmware or recovery gates |

## Expected Ranking By Class

These are class-level expectations, not final device scores.

| Candidate class | Expected score range | Recommendation |
| --- | --- | --- |
| Open-firmware earbuds | 80 to 90 | Primary MVP path |
| Modifiable dev boards or headset prototypes | 70 to 85 | Strong backup or bring-up path |
| Cheap OEM earbuds | 25 to 55, or hard-gate fail | Later exploration only |

## MVP Recommendation

For MVP, select one open-firmware earbud platform as the primary target.

If that path slips on firmware access or recovery, use a modifiable dev board or headset prototype to complete the bridge, BLE event contract, and approval loop.

Do not make cheap OEM earbuds the primary MVP target. They are attractive on cost, but they usually convert product work into reverse-engineering work.

The MVP should optimize for controllability and iteration speed, not for final industrial design or lowest BOM.

## Final Decision Statement

The primary hardware target will be the candidate that:

- passes all hard gates
- scores highest on the weighted matrix
- provides the fastest credible path to a reliable gesture-to-bridge-to-response demo

The decision will be evidence-based, not emotional.