import type { BridgeRequest } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import { VoiceHabitStore, type IntentResolution } from '../personalization/voice-habit-store';
import { resolveIntent } from './router';

/**
 * Resolve intent from a bridge request using a layered approach:
 * 1. VoiceHabitStore (learned phrases)
 * 2. Fixed rule matching (from router.ts)
 * 3. Fallback to quick_status
 *
 * Returns confidence, source, and whether confirmation is needed.
 */
export function resolveIntentWithEngine(
  request: BridgeRequest,
  habitStore: VoiceHabitStore | null,
): IntentResolution {
  // Layer 1: learned habits
  if (habitStore && request.utterance) {
    const habit = habitStore.resolve(request.utterance);
    if (habit.intent) {
      return {
        intent: habit.intent,
        confidence: habit.confidence,
        source: 'habit',
        needsConfirmation: !habit.promoted,
      };
    }
  }

  // Layer 2: fixed rules
  const fixed = resolveIntent(request);
  if (fixed !== 'quick_status') {
    return {
      intent: fixed,
      confidence: 0.7,
      source: 'fixed_rule',
      needsConfirmation: false,
    };
  }

  // Layer 3: fallback for voice commands, fixed rule for explicit quick_status
  if (request.event === 'quick_status') {
    return {
      intent: 'quick_status',
      confidence: 1.0,
      source: 'fixed_rule',
      needsConfirmation: false,
    };
  }

  return {
    intent: 'quick_status',
    confidence: 0.3,
    source: 'fallback',
    needsConfirmation: false,
  };
}


