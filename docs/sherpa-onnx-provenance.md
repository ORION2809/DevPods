# Sherpa-ONNX Provenance

Generated: 2026-05-20

## Purpose

This document records the provenance, licensing, and integrity of Sherpa-ONNX native libraries and model files bundled with or referenced by the DevPods Android relay.

## Sherpa-ONNX Runtime

- **Version:** v1.13.2
- **Source URL:** https://github.com/k2-fsa/sherpa-onnx
- **License:** Apache-2.0
- **Release artifact checksum:** `sherpa-onnx-v1.13.2-android.tar.bz2` SHA-256 `fc4d17941152941a883b0cfabfc9acac118682324e9f97df6c1ae1360bc7bc8e`
- **Included ABIs:** arm64-v8a (primary), x86_64 (emulator/dev only, if needed)
- **Included JNI libraries:**
	- `arm64-v8a/libonnxruntime.so` SHA-256 `4d2318b3849abb8862133d3068fc7e807ed8b2671cc6d83657fff2fcb9e1caad`
	- `arm64-v8a/libsherpa-onnx-c-api.so` SHA-256 `ec09f16e2e5043898d4dd18ec5c316310a39b7ca74ce4db0a33a3b945790e2ab`
	- `arm64-v8a/libsherpa-onnx-cxx-api.so` SHA-256 `e330684b081ded3d3d694df44abce6de313ccee82e119121d2ea342e5ce0e3d2`
	- `arm64-v8a/libsherpa-onnx-jni.so` SHA-256 `fc072f201dc1923ee98b594eb61c796b538ef087f7f18d08dcfdf0565167a8bd`
	- `x86_64/libonnxruntime.so` SHA-256 `2328d9481be5869dd8c4be96eb57b38a0105db17f1fe696a903c02a0988f0bd9`
	- `x86_64/libsherpa-onnx-c-api.so` SHA-256 `3fea6fe8f81a01e621a885ebcffdb312fe9612e74c6e0352917cfdc7d909e199`
	- `x86_64/libsherpa-onnx-cxx-api.so` SHA-256 `36aab84e1ffa80ceae741b97b8fc759f194707accf5ed3711e19368b23de8300`
	- `x86_64/libsherpa-onnx-jni.so` SHA-256 `4070645480f59bbc4990117f3587d48c891947671f5f0d979a771fd8a4c5d73c`
- **Included Kotlin API files:** `Vad.kt`, `OnlineRecognizer.kt`, `OnlineStream.kt`, `FeatureConfig.kt`, `VersionInfo.kt`, and supporting config/result types under `android-relay/sherpa-runtime/src/main/java/com/k2fsa/sherpa/onnx/`
- **Local modifications:** None
- **Reviewer:** Maintainer review pending

## Silero VAD Model

- **Model:** silero_vad.onnx (Silero VAD v4)
- **Source URL:** https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- **License:** MIT
- **Version:** v4.0.0
- **Expected SHA-256:** `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`
- **Total size:** 643,854 bytes

## Streaming STT Model

- **Model:** Zipformer streaming English (tokens.txt, encoder.onnx, decoder.onnx, joiner.onnx)
- **Source URL:** https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26
- **License:** Apache-2.0 (verify per-model)
- **Version:** 2023-06-26
- **Expected SHA-256 per file:**
	- `tokens.txt`: `49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb`
	- `encoder.onnx`: `563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1`
	- `decoder.onnx`: `7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef`
	- `joiner.onnx`: `210591f72b3c56b8364f85f345dca240bc2b4c00632848f4aa923630d5639d3b`
- **Expected combined SHA-256:** `5dc6038d5fca63ac6d4670d62fa713ba351b58ccfb4c65f088c22dfb1a1c0b94`
- **Total size:** 74,207,237 bytes

## Build Variants

| Variant | Sherpa Runtime | VAD Model | STT Model | Notes |
| --- | --- | --- | --- | --- |
| Default (release) | Linked but feature-flagged off | Not included | Not included | Platform STT remains the default path |
| Debug | Linked but feature-flagged off | Downloadable | Downloadable | Synthetic engines available; Sherpa still opt-in |
| Sherpa-enabled (internal) | Linked | Downloadable | Downloadable | Feature flags required |

## Integrity Verification

All model files are verified by SHA-256 checksum before use. The checksum is stored in the model spec and compared after download. Checksum mismatches block model readiness and produce diagnostic failure reasons:

- `sherpa_vad_model_checksum_mismatch`
- `sherpa_stt_model_checksum_mismatch`

## Native Library Failures

If the Sherpa native library fails to load (missing ABI, link error, or `UnsatisfiedLinkError`), the runtime reports:

- `sherpa_native_not_linked` (runtime availability check)
- `sherpa_native_dependency_missing` (legacy readiness check)

Neither failure blocks platform STT. The app must fall back to `PlatformSpeechRecognizerEngine` when Sherpa is unavailable.