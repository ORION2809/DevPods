#!/usr/bin/env python3
"""
Host-side TTS/WAV fixture generator for T2 emulator host-audio proof runs.

Uses edge-tts (Microsoft Edge online TTS) to generate high-quality speech,
then converts to 16kHz mono WAV using ffmpeg for Android STT compatibility.

Requires:
    pip install edge-tts
    ffmpeg in PATH

Usage:
    python generate-tts-fixtures.py
"""
import asyncio
import os
import subprocess
import sys
from pathlib import Path

# Test phrases matching the T1 synthetic script phrases
PHRASES = [
    ("run_tests", "run tests"),
    ("what_branch", "what branch am I on"),
    ("open_file", "open file main dot kt"),
    ("check_status", "check status"),
    ("deploy_staging", "deploy staging"),
    ("stop", "stop"),
]

# Voice to use (see: edge-tts --list-voices)
VOICE = "en-US-AriaNeural"
RATE = "-0%"  # Normal speed
VOLUME = "+0%"

# Output format for Android STT compatibility
SAMPLE_RATE = 16000
CHANNELS = 1


def check_ffmpeg():
    """Verify ffmpeg is available."""
    try:
        result = subprocess.run(
            ["ffmpeg", "-version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        if result.returncode == 0:
            version = result.stdout.splitlines()[0]
            print(f"  ffmpeg: {version}")
            return True
    except FileNotFoundError:
        pass
    print("ERROR: ffmpeg not found in PATH. Please install ffmpeg.")
    return False


def check_edge_tts():
    """Verify edge-tts is available."""
    try:
        import edge_tts
        print(f"  edge-tts: available")
        return True
    except ImportError:
        print("ERROR: edge-tts not installed. Run: pip install edge-tts")
        return False


async def generate_fixture(name: str, text: str, output_dir: Path) -> Path:
    """Generate a WAV fixture for the given phrase."""
    mp3_path = output_dir / f"{name}.mp3"
    wav_path = output_dir / f"{name}.wav"

    if wav_path.exists():
        print(f"  {name}: already exists ({wav_path})")
        return wav_path

    print(f"  {name}: generating TTS for '{text}' ...")

    # Generate MP3 with edge-tts
    import edge_tts
    communicate = edge_tts.Communicate(text, VOICE, rate=RATE, volume=VOLUME)
    await communicate.save(str(mp3_path))

    # Convert to 16kHz mono WAV with ffmpeg
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-i", str(mp3_path),
            "-ar", str(SAMPLE_RATE),
            "-ac", str(CHANNELS),
            "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",  # Normalize loudness
            str(wav_path),
        ],
        capture_output=True,
        check=True,
    )

    # Clean up MP3
    mp3_path.unlink()

    # Get file info
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "format=duration", "-of",
         "default=noprint_wrappers=1:nokey=1", str(wav_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    duration = float(result.stdout.strip())
    size = wav_path.stat().st_size

    print(f"  {name}: {duration:.2f}s, {size} bytes -> {wav_path}")
    return wav_path


async def generate_silence_fixture(output_dir: Path) -> Path:
    """Generate a silent WAV fixture (for no-speech timeout testing)."""
    wav_path = output_dir / "silence_5s.wav"
    if wav_path.exists():
        print(f"  silence_5s: already exists")
        return wav_path

    print(f"  silence_5s: generating 5-second silence ...")
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f", "lavfi",
            "-i", "anullsrc=r=16000:cl=mono",
            "-t", "5",
            str(wav_path),
        ],
        capture_output=True,
        check=True,
    )
    print(f"  silence_5s: 5.00s -> {wav_path}")
    return wav_path


async def generate_noise_fixture(output_dir: Path) -> Path:
    """Generate a noise WAV fixture (for low-signal testing)."""
    wav_path = output_dir / "noise_5s.wav"
    if wav_path.exists():
        print(f"  noise_5s: already exists")
        return wav_path

    print(f"  noise_5s: generating 5-second low-level noise ...")
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f", "lavfi",
            "-i", "anoisesrc=a=0.001:c=pink",
            "-t", "5",
            "-ar", "16000",
            "-ac", "1",
            str(wav_path),
        ],
        capture_output=True,
        check=True,
    )
    print(f"  noise_5s: 5.00s -> {wav_path}")
    return wav_path


async def main():
    script_dir = Path(__file__).parent.resolve()
    output_dir = script_dir / "tts-fixtures"
    output_dir.mkdir(exist_ok=True)

    print("T2 TTS Fixture Generator")
    print("=" * 50)
    print(f"Output directory: {output_dir}")
    print(f"Voice: {VOICE}")
    print(f"Format: {SAMPLE_RATE}Hz, {CHANNELS} channel(s)")
    print()

    # Check dependencies
    print("Checking dependencies...")
    if not check_ffmpeg() or not check_edge_tts():
        sys.exit(1)
    print()

    # Generate speech fixtures
    print("Generating speech fixtures...")
    for name, text in PHRASES:
        await generate_fixture(name, text, output_dir)
    print()

    # Generate failure-mode fixtures
    print("Generating failure-mode fixtures...")
    await generate_silence_fixture(output_dir)
    await generate_noise_fixture(output_dir)
    print()

    print("Done. Fixtures ready for T2 host-audio injection.")
    print(f"Run: ffplay -autoexit -nodisp {output_dir}/run_tests.wav")


if __name__ == "__main__":
    asyncio.run(main())
