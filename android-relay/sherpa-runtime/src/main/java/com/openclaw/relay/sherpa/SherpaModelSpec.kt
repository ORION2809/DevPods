package com.openclaw.relay.sherpa

import java.net.URI

private val TRUSTED_MODEL_DOWNLOAD_HOSTS = setOf(
    "github.com",
    "huggingface.co",
)

enum class SherpaModelType {
    SILERO_VAD,
    STREAMING_STT,
}

data class SherpaModelSpec(
    val id: String,
    val type: SherpaModelType,
    val version: String,
    val requiredFiles: List<String>,
    val downloadBaseUrl: String = "",
    val downloadUrlByFile: Map<String, String> = emptyMap(),
    val expectedSha256ByFile: Map<String, String> = emptyMap(),
    val expectedCombinedSha256: String? = null,
    val totalBytes: Long = 0L,
    val license: String = "",
    val sourceUrl: String = "",
    val installRoot: String = "",
) {
    val modelRootPath: String
        get() = if (installRoot.isBlank()) "" else "$installRoot/$id"

    fun resolveDownloadUrl(fileName: String): String? {
        val directUrl = downloadUrlByFile[fileName]
        if (!directUrl.isNullOrBlank()) {
            return directUrl.takeIf(::isTrustedDownloadUrl)
        }

        if (downloadBaseUrl.isBlank()) {
            return null
        }

        return "${downloadBaseUrl.trimEnd('/')}/$fileName".takeIf(::isTrustedDownloadUrl)
    }

    private fun isTrustedDownloadUrl(candidate: String): Boolean {
        return runCatching {
            val uri = URI(candidate)
            val host = uri.host?.lowercase()
            uri.scheme.equals("https", ignoreCase = true) && host in TRUSTED_MODEL_DOWNLOAD_HOSTS
        }.getOrDefault(false)
    }

    companion object {
        val SILERO_VAD_V4: SherpaModelSpec = SherpaModelSpec(
            id = "silero_vad_v4",
            type = SherpaModelType.SILERO_VAD,
            version = "v4.0.0",
            requiredFiles = listOf("silero_vad.onnx"),
            downloadUrlByFile = mapOf(
                "silero_vad.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            ),
            expectedSha256ByFile = mapOf(
                "silero_vad.onnx" to "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
            expectedCombinedSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            totalBytes = 643_854L,
            license = "MIT",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models",
        )

        val SHERPA_STREAMING_EN: SherpaModelSpec = SherpaModelSpec(
            id = "sherpa_streaming_en_zipformer",
            type = SherpaModelType.STREAMING_STT,
            version = "2023-06-26",
            requiredFiles = listOf(
                "tokens.txt",
                "encoder.onnx",
                "decoder.onnx",
                "joiner.onnx",
            ),
            downloadUrlByFile = mapOf(
                "tokens.txt" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/tokens.txt",
                "encoder.onnx" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                "decoder.onnx" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
                "joiner.onnx" to "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
            ),
            expectedSha256ByFile = mapOf(
                "tokens.txt" to "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                "encoder.onnx" to "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1",
                "decoder.onnx" to "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef",
                "joiner.onnx" to "210591f72b3c56b8364f85f345dca240bc2b4c00632848f4aa923630d5639d3b",
            ),
            expectedCombinedSha256 = "5dc6038d5fca63ac6d4670d62fa713ba351b58ccfb4c65f088c22dfb1a1c0b94",
            totalBytes = 74_207_237L,
            license = "Apache-2.0",
            sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
        )
    }
}