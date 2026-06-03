package com.openclaw.relay.sherpa

data class SherpaReadiness(
    val vadReadiness: VadReadiness,
    val sttReadiness: SttReadiness,
    val runtimeAvailability: SherpaRuntimeAvailability,
) {
    val vadReady: Boolean
        get() = runtimeAvailability.isAvailable && vadReadiness.isReady

    val sttReady: Boolean
        get() = runtimeAvailability.isAvailable && sttReadiness.isReady

    val failureReasons: List<String>
        get() = buildList {
            if (!runtimeAvailability.isAvailable) {
                add(runtimeAvailability.failureReason ?: "sherpa_runtime_unavailable")
            }
            addAll(vadReadiness.failureReasons)
            addAll(sttReadiness.failureReasons)
        }
}

data class VadReadiness(
    val isReady: Boolean,
    val modelPresent: Boolean,
    val modelVersion: String? = null,
    val modelFootprintBytes: Long = 0L,
    val missingFiles: List<String> = emptyList(),
    val checksumMatches: Boolean = true,
    val failureReasons: List<String> = emptyList(),
) {
    companion object {
        fun evaluate(
            runtimeAvailability: SherpaRuntimeAvailability,
            vadSpec: SherpaModelSpec?,
        ): VadReadiness {
            if (vadSpec == null) {
                return VadReadiness(
                    isReady = false,
                    modelPresent = false,
                    failureReasons = listOf("sherpa_vad_model_not_configured"),
                )
            }

            val status = SherpaModelInspector.inspect(vadSpec)
            val failures = buildList {
                if (!runtimeAvailability.isAvailable) add("sherpa_runtime_unavailable")
                if (!status.isComplete) add("sherpa_vad_model_incomplete")
                if (!status.checksumMatches) add("sherpa_vad_model_checksum_mismatch")
            }

            return VadReadiness(
                isReady = failures.isEmpty(),
                modelPresent = status.isComplete,
                modelVersion = vadSpec.version,
                modelFootprintBytes = status.totalBytes,
                missingFiles = status.missingFiles,
                checksumMatches = status.checksumMatches,
                failureReasons = failures,
            )
        }
    }
}

data class SttReadiness(
    val isReady: Boolean,
    val modelPresent: Boolean,
    val modelVersion: String? = null,
    val modelFootprintBytes: Long = 0L,
    val missingFiles: List<String> = emptyList(),
    val checksumMatches: Boolean = true,
    val failureReasons: List<String> = emptyList(),
) {
    companion object {
        fun evaluate(
            runtimeAvailability: SherpaRuntimeAvailability,
            sttSpec: SherpaModelSpec?,
        ): SttReadiness {
            if (sttSpec == null) {
                return SttReadiness(
                    isReady = false,
                    modelPresent = false,
                    failureReasons = listOf("sherpa_stt_model_not_configured"),
                )
            }

            val status = SherpaModelInspector.inspect(sttSpec)
            val failures = buildList {
                if (!runtimeAvailability.isAvailable) add("sherpa_runtime_unavailable")
                if (!status.isComplete) add("sherpa_stt_model_incomplete")
                if (!status.checksumMatches) add("sherpa_stt_model_checksum_mismatch")
            }

            return SttReadiness(
                isReady = failures.isEmpty(),
                modelPresent = status.isComplete,
                modelVersion = sttSpec.version,
                modelFootprintBytes = status.totalBytes,
                missingFiles = status.missingFiles,
                checksumMatches = status.checksumMatches,
                failureReasons = failures,
            )
        }
    }
}