import type { BridgeRequest } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';

export function resolveIntent(request: BridgeRequest): IntentName {
  if (request.event === 'quick_status') {
    return 'quick_status';
  }

  if (request.event !== 'voice_command') {
    return 'quick_status';
  }

  const utterance = (request.utterance ?? '').toLowerCase();

  if (utterance.includes('push')) {
    return 'push';
  }

  if (utterance.includes('deploy')) {
    return 'deploy';
  }

  if (utterance.includes('delete file') || utterance.startsWith('delete ')) {
    return 'delete';
  }

  if (utterance.includes('revert file') || utterance.startsWith('revert ')) {
    return 'revert';
  }

  if (utterance.includes('diff') || utterance.includes('changes')) {
    return 'summarize_diff';
  }

  if (utterance.includes('ci') || utterance.includes('build failed') || utterance.includes('github actions')) {
    return 'latest_ci_failure';
  }

  if (utterance.includes('test')) {
    return 'run_tests';
  }

  if (utterance.includes('commit message')) {
    return 'create_commit_message';
  }

  if (utterance.includes('commit staged') || utterance.includes('commit the staged')) {
    return 'commit_staged';
  }

  if (
    utterance.includes('open the main file')
    || utterance.includes('open file')
    || (utterance.startsWith('open ') && /\.[a-z0-9]+\b/i.test(utterance))
  ) {
    return 'open_file';
  }

  if (utterance.includes('status') || utterance.includes('branch')) {
    return 'quick_status';
  }

  return 'quick_status';
}

export function describeIntent(intent: IntentName): string {
  switch (intent) {
    case 'quick_status':
      return 'Read current developer status';
    case 'summarize_diff':
      return 'Summarize current diff';
    case 'latest_ci_failure':
      return 'Read latest CI failure';
    case 'run_tests':
      return 'Run workspace tests';
    case 'create_commit_message':
      return 'Generate a commit message';
    case 'commit_staged':
      return 'Commit staged files';
    case 'open_file':
      return 'Open the target file';
    case 'push':
      return 'Push the current branch';
    case 'deploy':
      return 'Deploy the current build';
    case 'delete':
      return 'Delete the selected files';
    case 'revert':
      return 'Revert local changes';
  }
}