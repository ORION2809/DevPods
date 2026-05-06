import type { JarvisResponse } from '../protocol/schemas';
import { runCommandCapture } from '../adapters/process';

export interface Notifier {
  notify(response: JarvisResponse): Promise<void>;
}

class ConsoleNotifier implements Notifier {
  async notify(response: JarvisResponse): Promise<void> {
    if (response.speak) {
      process.stdout.write(`[jarvis] ${response.speak}\n`);
    }
  }
}

class WindowsSpeechNotifier implements Notifier {
  private readonly enabled =
    process.platform === 'win32' &&
    process.env.JARVIS_DISABLE_TTS !== '1' &&
    !process.env.VITEST;

  async notify(response: JarvisResponse): Promise<void> {
    if (!this.enabled || !response.speak) {
      return;
    }

    try {
      // The spoken text is passed through an environment variable rather than interpolated into the PowerShell command.
      await runCommandCapture(
        'powershell.exe',
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          'Add-Type -AssemblyName System.Speech; ' +
            '$text = [Environment]::GetEnvironmentVariable("JARVIS_SPEAK_TEXT"); ' +
            'if ($text) { ' +
            '$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer; ' +
            '$speaker.Speak($text); ' +
            '$speaker.Dispose(); ' +
            '}',
        ],
        {
          cwd: process.cwd(),
          timeoutMs: 15000,
          env: {
            ...process.env,
            JARVIS_SPEAK_TEXT: response.speak,
          },
        },
      );
    } catch (error) {
      if (!process.env.NODE_ENV || process.env.NODE_ENV !== 'production') {
        process.stderr.write(`[jarvis-tts] Failed to synthesize speech: ${error instanceof Error ? error.message : String(error)}\n`);
      }
      // Fail closed to console-only output.
    }
  }
}

class CompositeNotifier implements Notifier {
  constructor(private readonly notifiers: Notifier[]) {}

  async notify(response: JarvisResponse): Promise<void> {
    for (const notifier of this.notifiers) {
      await notifier.notify(response);
    }
  }
}

export function createDefaultNotifier(): Notifier {
  return new CompositeNotifier([new ConsoleNotifier(), new WindowsSpeechNotifier()]);
}