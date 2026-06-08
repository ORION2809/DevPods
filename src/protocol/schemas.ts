import { z } from 'zod';
import { intentNames } from './types';

export const earbudEventNameSchema = z.enum([
  'triple_tap_right',
  'tap_test_button',
  'left_long_press',
  'approve_right_double_tap',
  'reject_left_double_tap',
  'both_hold_cancel',
  'remove_one_bud_pause',
  'remove_both_buds_end_session',
  'put_both_in_resume',
  'headset_button_single',
  'android_push_to_talk',
  'android_status_shortcut',
  'android_approve',
  'android_reject',
  'android_cancel',
  'android_autonomy_continue',
  'android_autonomy_interrupt',
  'android_learning_confirm',
  'android_learning_reject',
  'android_agent_plan_confirm',
  'android_agent_plan_cancel',
  'android_agent_plan_redirect',
]);

export const requestEventSchema = z.enum([
  'wake_and_listen',
  'voice_command',
  'quick_status',
  'approval_action',
  'autonomy_continue',
  'autonomy_replan',
  'cancel',
  'pause',
  'resume',
  'learning_prompt_confirm',
  'learning_prompt_reject',
  'agent_plan_confirm',
  'agent_plan_cancel',
  'agent_plan_redirect',
]);

export const approvalActionSchema = z.enum(['approve', 'reject', 'cancel', 'expire']);
export const riskClassSchema = z.enum(['immediate', 'approval_required', 'hard_approval', 'denied']);
export const sessionStateSchema = z.enum([
  'idle',
  'listening',
  'thinking',
  'approval_pending',
  'awaiting_plan_confirmation',
  'queued',
  'running',
  'responding',
  'paused',
  'cancelled',
]);
export const responseStatusSchema = z.enum([
  'acknowledged',
  'running',
  'completed',
  'blocked',
  'cancelled',
  'error',
]);
export const wearStateSchema = z.enum(['in_ear', 'out_of_ear', 'unknown']);
export const profileSchema = z.enum([
  'coding_mode',
  'meeting_mode',
  'focus_mode',
  'debug_mode',
  'low_battery_mode',
  'default',
]);
export const intentNameSchema = z.enum(intentNames);

export const riskPolicySchema = z.object({
  profile: z.string().min(1),
  allowReadOnly: z.boolean(),
  allowSafeWithoutApproval: z.boolean(),
  requireApprovalFor: z.array(intentNameSchema),
  requireHardApprovalFor: z.array(intentNameSchema),
  approvalTimeoutMs: z.number().int().positive(),
});

export const hardwareContextSchema = z.object({
  provider: z.string().min(1),
  wakeSource: z.string().nullable().default(null),
  deviceConfidence: z.enum(['proven', 'observed', 'inferred', 'unproven']).default('unproven'),
  earState: z.enum(['in_ear', 'out_of_ear', 'both_in_ear', 'left_in_ear', 'right_in_ear', 'unknown']).nullable().default(null),
  batteryState: z.enum(['ok', 'low', 'critical', 'unknown']).nullable().default(null),
  deviceModel: z.string().nullable().default(null),
  connectionState: z.enum(['connected', 'disconnected', 'connecting', 'unknown']).nullable().default(null),
});

export const earbudEventSchema = z.object({
  source: z.string().min(1).default('developer_earbuds_simulator'),
  sessionId: z.string().min(1).default('sim-session'),
  workspace: z.string().min(1).default('current_repo'),
  device: z.enum(['left_bud', 'right_bud', 'both_buds']).default('right_bud'),
  event: earbudEventNameSchema,
  timestamp: z.number().int().nonnegative(),
  battery: z.number().int().min(0).max(100).optional(),
  wearState: wearStateSchema.optional(),
  profile: profileSchema.optional(),
  utterance: z.string().min(1).max(400).optional(),
  pendingActionId: z.string().min(1).optional(),
  hardwareContext: hardwareContextSchema.optional(),
  protocolVersion: z.string().min(1).optional(),
  idempotencyKey: z.string().min(1).optional(),
});

export const androidRelayEventSchema = earbudEventSchema.extend({
  protocolVersion: z.string().min(1, 'protocolVersion is required for Android relay events'),
  idempotencyKey: z.string().min(1, 'idempotencyKey is required for Android relay events'),
});

export const SUPPORTED_PROTOCOL_VERSIONS = ['1', '1.0'] as const;

export const bridgeRequestSchema = z.object({
  source: z.string().min(1),
  sessionId: z.string().min(1),
  workspace: z.string().min(1),
  event: requestEventSchema,
  utterance: z.string().min(1).max(400).nullable(),
  gesture: earbudEventNameSchema.nullable(),
  riskPolicy: riskPolicySchema,
  pendingActionId: z.string().min(1).nullable(),
  approvalAction: approvalActionSchema.nullable(),
  deviceState: z
    .object({
      activeBud: z.enum(['left', 'right', 'both']).nullable().default(null),
      wearState: wearStateSchema.nullable().default(null),
      batteryPercent: z.number().int().min(0).max(100).nullable().default(null),
      profile: profileSchema.nullable().default(null),
    })
    .default({
      activeBud: null,
      wearState: null,
      batteryPercent: null,
      profile: null,
    }),
  hardwareContext: hardwareContextSchema.nullable().default(null),
});

export const approvalRequestSchema = z.object({
  actionType: intentNameSchema,
  summary: z.string().min(1),
  riskClass: z.enum(['approval_required', 'hard_approval']),
  expiresInMs: z.number().int().positive(),
});

export const autonomyPhaseSchema = z.enum(['report', 'plan', 'implementation']);
export const autonomyModeSchema = z.enum(['continue_on_silence', 'awaiting_user_input']);

export const pairingVerifyRequestSchema = z.object({
  pairingCode: z.string().min(1),
});

export const pairingVerifyResponseSchema = z.object({
  relayToken: z.string(),
});

export const autonomyInstructionSchema = z.object({
  phase: autonomyPhaseSchema,
  mode: autonomyModeSchema,
  summary: z.string().min(1),
  nextStep: z.string().nullable(),
  continueAfterMs: z.number().int().positive().nullable(),
  nextIntent: intentNameSchema.nullable(),
});

export const jarvisResponseSchema = z.object({
  speak: z.string().min(1),
  display: z.string().nullable(),
  requiresApproval: z.boolean(),
  approvalRequest: approvalRequestSchema.nullable(),
  actionId: z.string().nullable(),
  status: responseStatusSchema,
  nextState: sessionStateSchema,
  followUpHint: z.string().nullable(),
  autonomy: autonomyInstructionSchema.nullable().optional(),
});

export const workspaceCommandSchema = z.object({
  description: z.string().min(1),
  command: z.string().min(1),
  args: z.array(z.string()).default([]),
  timeoutMs: z.number().int().positive().default(120000),
});

export const workspaceConfigSchema = z.object({
  id: z.string().min(1),
  label: z.string().min(1),
  rootPath: z.string().min(1),
  allowedIntents: z.array(intentNameSchema),
  approvalRequiredIntents: z.array(intentNameSchema).default([]),
  hardApprovalIntents: z.array(intentNameSchema).default([]),
  commands: z.record(z.string(), workspaceCommandSchema).default({}),
});

export const workspaceRegistrySchema = z.object({
  defaultWorkspaceId: z.string().min(1),
  workspaces: z.array(workspaceConfigSchema).min(1),
});

export const auditRecordSchema = z.object({
  id: z.string().min(1),
  timestamp: z.string().min(1),
  sessionId: z.string().min(1),
  workspace: z.string().min(1),
  event: z.string().min(1),
  decision: z.enum([
    'received',
    'allowed',
    'blocked',
    'approval_requested',
    'approved',
    'queued',
    'rejected',
    'cancelled',
    'running',
    'completed',
    'failed',
  ]),
  status: responseStatusSchema,
  actionId: z.string().nullable(),
  detail: z.string().nullable(),
  hardwareContext: hardwareContextSchema.nullable().default(null),
});

export const outboxEventKindSchema = z.enum([
  'completion_soft_ping',
  'completion_full_report',
  'reminder_due',
  'workspace_nudge',
  'approval_pending',
  'learning_prompt',
  'badge_update',
]);

export const outboxEventSchema = z.object({
  id: z.string().min(1),
  sessionId: z.string().min(1),
  createdAtMs: z.number().int().nonnegative(),
  expiresAtMs: z.number().int().nonnegative(),
  priority: z.enum(['low', 'normal', 'high', 'critical']),
  kind: outboxEventKindSchema,
  summary: z.string().min(1),
  detail: z.string().optional(),
  actionId: z.string().optional(),
});

export const outboxPollResponseSchema = z.object({
  events: z.array(outboxEventSchema),
  cursor: z.string().optional(),
});

export const outboxAckSchema = z.object({
  eventId: z.string().min(1),
  sessionId: z.string().min(1),
});

export const notificationStyleSchema = z.enum([
  'aggressive',
  'soft',
  'silent_with_badge',
]);

export const notificationPreferenceSchema = z.object({
  sessionId: z.string().min(1),
  style: notificationStyleSchema.default('soft'),
  badgeEnabled: z.boolean().default(true),
  mutedKinds: z.array(outboxEventKindSchema).default([]),
  softPingTtsEnabled: z.boolean().default(true),
  nudgeTtsEnabled: z.boolean().default(true),
  reminderTtsEnabled: z.boolean().default(true),
  showSensitiveInNotifications: z.boolean().default(false),
  wearApprovalEnabled: z.boolean().default(true),
  updatedAtMs: z.number().int().nonnegative().default(() => Date.now()),
});

export const reminderSchema = z.object({
  id: z.string().min(1),
  sessionId: z.string().min(1),
  summary: z.string().min(1),
  createdAtMs: z.number().int().nonnegative(),
  dueAtMs: z.number().int().nonnegative(),
  ackedAtMs: z.number().int().nonnegative().optional(),
  recurring: z.enum(['none', 'daily', 'weekly']).default('none'),
});

export const reminderListResponseSchema = z.object({
  reminders: z.array(reminderSchema),
});

export const reminderCreateRequestSchema = z.object({
  sessionId: z.string().min(1),
  summary: z.string().min(1),
  dueAtMs: z.number().int().nonnegative(),
  recurring: z.enum(['none', 'daily', 'weekly']).default('none'),
});

export const learnedPhraseSchema = z.object({
  phrase: z.string().min(1),
  intent: z.string().min(1),
  confirmationCount: z.number().int().nonnegative().default(0),
  createdAtMs: z.number().int().nonnegative().default(() => Date.now()),
  lastConfirmedAtMs: z.number().int().nonnegative().optional(),
});

export const voiceHabitSnapshotSchema = z.object({
  phrases: z.array(learnedPhraseSchema),
});

export const confirmLearningRequestSchema = z.object({
  sessionId: z.string().min(1),
  phrase: z.string().min(1),
  intent: z.string().min(1),
});

export const workspaceSnapshotSchema = z.object({
  workspaceId: z.string().min(1),
  repoDetected: z.boolean(),
  branch: z.string().nullable(),
  changedFiles: z.number().int().nonnegative(),
  testsRunning: z.boolean(),
  lastCommitAtMs: z.number().int().nonnegative().nullable(),
  ciStatus: z.enum(['unknown', 'green', 'red', 'no_ci']).default('unknown'),
  lastCiFailureAtMs: z.number().int().nonnegative().nullable(),
  polledAtMs: z.number().int().nonnegative().default(() => Date.now()),
});

export const prefetchRequestSchema = z.object({
  kinds: z.array(z.enum(['workspace_status', 'diff_stat', 'ci_snapshot'])),
  idempotencyKey: z.string().min(1),
});

export const devPodsInstalledTierSchema = z.enum(['core', 'agent', 'intelligence']);
export const agentRuntimeKindSchema = z.enum(['openclaw', 'hermes', 'none']);
export const agentRuntimeHealthSchema = z.enum(['healthy', 'degraded', 'unavailable']);
export const agentRuntimePhaseSchema = z.enum([
  'idle',
  'thinking',
  'planning',
  'awaiting_confirmation',
  'running',
  'done',
  'blocked',
  'error',
  'cancelled',
]);
export const intelligenceIndexStateSchema = z.enum([
  'not_installed',
  'disabled',
  'not_indexed',
  'indexing',
  'ready',
  'stale',
  'failed',
  'paused',
]);

export const intelligenceWorkspaceCapabilitySchema = z.object({
  workspaceId: z.string().min(1),
  indexState: intelligenceIndexStateSchema,
  indexedCommit: z.string().nullable(),
  lastIndexedAtMs: z.number().int().nullable(),
  stalenessReason: z.string().nullable(),
});

export const agentCapabilitySnapshotSchema = z.object({
  runtime: agentRuntimeKindSchema,
  health: agentRuntimeHealthSchema,
  state: agentRuntimePhaseSchema,
  supportsPlanConfirmation: z.boolean(),
  supportsProgressEvents: z.boolean(),
  intelligence: z.object({
    available: z.boolean(),
    indexState: intelligenceIndexStateSchema,
    workspaces: z.array(z.string()),
  }),
});

export const bridgeCapabilitySnapshotSchema = z.object({
  tier: devPodsInstalledTierSchema,
  capabilities: z.object({
    core: z.object({
      available: z.literal(true),
      voiceLoop: z.literal(true),
      approvals: z.literal(true),
      gitBasics: z.literal(true),
    }),
    agent: z.object({
      available: z.boolean(),
      runtime: agentRuntimeKindSchema,
      healthy: z.boolean(),
      state: agentRuntimePhaseSchema,
      degradedReason: z.string().nullable(),
    }),
    intelligence: z.object({
      available: z.boolean(),
      indexState: intelligenceIndexStateSchema,
      workspaces: z.array(z.string()),
      currentWorkspace: intelligenceWorkspaceCapabilitySchema.nullable(),
    }),
  }),
});

export const streamFrameSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('started') }),
  z.object({ type: z.literal('speak_delta'), delta: z.string() }),
  z.object({ type: z.literal('display_delta'), delta: z.string() }),
  z.object({ type: z.literal('approval_request'), approvalRequest: approvalRequestSchema }),
  z.object({ type: z.literal('final_response'), response: jarvisResponseSchema }),
  z.object({ type: z.literal('error'), error: z.string(), category: z.string().optional() }),
  z.object({ type: z.literal('done') }),
]);

export const nudgeTypeSchema = z.enum([
  'uncommitted_files',
  'stale_branch',
  'ci_red',
  'tests_failing',
  'ready_to_push',
]);

export const nudgeThresholdSchema = z.object({
  type: nudgeTypeSchema,
  changedFilesMin: z.number().int().nonnegative().default(1),
  staleBranchHours: z.number().int().nonnegative().default(24),
  consecutiveTestFailures: z.number().int().nonnegative().default(2),
  ciRedHours: z.number().int().nonnegative().default(1),
});

export const nudgePolicySchema = z.object({
  sessionId: z.string().min(1),
  enabled: z.boolean().default(true),
  mutedTypes: z.array(nudgeTypeSchema).default([]),
  thresholds: z.array(nudgeThresholdSchema).default([]),
  updatedAtMs: z.number().int().nonnegative().default(() => Date.now()),
});

export type HardwareContext = z.infer<typeof hardwareContextSchema>;
export type EarbudEvent = z.infer<typeof earbudEventSchema>;
export type BridgeRequest = z.infer<typeof bridgeRequestSchema>;
export type JarvisResponse = z.infer<typeof jarvisResponseSchema>;
export type WorkspaceConfig = z.infer<typeof workspaceConfigSchema>;
export type WorkspaceRegistry = z.infer<typeof workspaceRegistrySchema>;
export type RiskPolicy = z.infer<typeof riskPolicySchema>;
export type AuditRecord = z.infer<typeof auditRecordSchema>;
export type ApprovalRequest = z.infer<typeof approvalRequestSchema>;
export type AutonomyInstruction = z.infer<typeof autonomyInstructionSchema>;
export type OutboxEvent = z.infer<typeof outboxEventSchema>;
export type OutboxEventKind = z.infer<typeof outboxEventKindSchema>;
export type OutboxPollResponse = z.infer<typeof outboxPollResponseSchema>;
export type OutboxAck = z.infer<typeof outboxAckSchema>;
export type NotificationStyle = z.infer<typeof notificationStyleSchema>;
export type NotificationPreference = z.infer<typeof notificationPreferenceSchema>;
export type Reminder = z.infer<typeof reminderSchema>;
export type ReminderListResponse = z.infer<typeof reminderListResponseSchema>;
export type ReminderCreateRequest = z.infer<typeof reminderCreateRequestSchema>;
export type LearnedPhrase = z.infer<typeof learnedPhraseSchema>;
export type VoiceHabitSnapshot = z.infer<typeof voiceHabitSnapshotSchema>;
export type ConfirmLearningRequest = z.infer<typeof confirmLearningRequestSchema>;
export type NudgeType = z.infer<typeof nudgeTypeSchema>;
export type NudgeThreshold = z.infer<typeof nudgeThresholdSchema>;
export type NudgePolicy = z.infer<typeof nudgePolicySchema>;
export type PrefetchRequest = z.infer<typeof prefetchRequestSchema>;
export type StreamFrame = z.infer<typeof streamFrameSchema>;
export type DevPodsInstalledTier = z.infer<typeof devPodsInstalledTierSchema>;
export type AgentRuntimeKind = z.infer<typeof agentRuntimeKindSchema>;
export type AgentRuntimeHealth = z.infer<typeof agentRuntimeHealthSchema>;
export type AgentRuntimePhase = z.infer<typeof agentRuntimePhaseSchema>;
export type IntelligenceIndexState = z.infer<typeof intelligenceIndexStateSchema>;
export type IntelligenceWorkspaceCapability = z.infer<typeof intelligenceWorkspaceCapabilitySchema>;
export type AgentCapabilitySnapshot = z.infer<typeof agentCapabilitySnapshotSchema>;
export type BridgeCapabilitySnapshot = z.infer<typeof bridgeCapabilitySnapshotSchema>;
