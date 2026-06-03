package com.openclaw.relay.sherpa

data class SherpaFeatureFlags(
    val sherpaRuntimeEnabled: Boolean = false,
    val sherpaVadDiagnosticsEnabled: Boolean = false,
    val sherpaSttExperimentalEnabled: Boolean = false,
    val sherpaModelDownloadsEnabled: Boolean = false,
) {
    val anySherpaEnabled: Boolean
        get() = sherpaRuntimeEnabled || sherpaVadDiagnosticsEnabled || sherpaSttExperimentalEnabled || sherpaModelDownloadsEnabled

    companion object {
        val DEFAULT = SherpaFeatureFlags()

        fun fromConfig(
            runtimeEnabled: Boolean = false,
            vadEnabled: Boolean = false,
            sttEnabled: Boolean = false,
            downloadsEnabled: Boolean = false,
        ): SherpaFeatureFlags = SherpaFeatureFlags(
            sherpaRuntimeEnabled = runtimeEnabled,
            sherpaVadDiagnosticsEnabled = vadEnabled,
            sherpaSttExperimentalEnabled = sttEnabled,
            sherpaModelDownloadsEnabled = downloadsEnabled,
        )
    }
}