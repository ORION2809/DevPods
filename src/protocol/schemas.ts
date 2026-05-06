import { z } from 'zod';
import { intentNames } from './types';

export const earbudEventNameSchema = z.enum([
  'triple_tap_right',
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
]);

export const requestEventSchema = z.enum([
  'wake_and_listen',
  'voice_command',
  'quick_status',
  'approval_action',
  'cancel',
  'pause',
  'resume',
]);

export const approvalActionSchema = z.enum(['approve', 'reject', 'cancel', 'expire']);
export const riskClassSchema = z.enum(['immediate', 'approval_required', 'hard_approval', 'denied']);
export const sessionStateSchema = z.enum([
  'idle',
  'listening',
  'thinking',
  'approval_pending',
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
});

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
});

export const approvalRequestSchema = z.object({
  actionType: intentNameSchema,
  summary: z.string().min(1),
  riskClass: z.enum(['approval_required', 'hard_approval']),
  expiresInMs: z.number().int().positive(),
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
});

export type EarbudEvent = z.infer<typeof earbudEventSchema>;
export type BridgeRequest = z.infer<typeof bridgeRequestSchema>;
export type JarvisResponse = z.infer<typeof jarvisResponseSchema>;
export type WorkspaceConfig = z.infer<typeof workspaceConfigSchema>;
export type WorkspaceRegistry = z.infer<typeof workspaceRegistrySchema>;
export type RiskPolicy = z.infer<typeof riskPolicySchema>;
export type AuditRecord = z.infer<typeof auditRecordSchema>;
export type ApprovalRequest = z.infer<typeof approvalRequestSchema>;