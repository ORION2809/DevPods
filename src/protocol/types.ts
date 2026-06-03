export const intentNames = [
  'quick_status',
  'summarize_diff',
  'latest_ci_failure',
  'run_tests',
  'create_commit_message',
  'commit_staged',
  'open_file',
  'push',
  'deploy',
  'delete',
  'revert',
  'create_reminder',
] as const;

export type IntentName = (typeof intentNames)[number];

export const immediateIntents = new Set<IntentName>([
  'quick_status',
  'summarize_diff',
  'latest_ci_failure',
  'create_commit_message',
  'create_reminder',
]);

export const approvalAliases: Record<string, string> = {
  approve: 'approve_right_double_tap',
  reject: 'reject_left_double_tap',
  cancel: 'both_hold_cancel',
};