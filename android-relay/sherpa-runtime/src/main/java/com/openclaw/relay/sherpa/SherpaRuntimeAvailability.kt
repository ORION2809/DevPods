package com.openclaw.relay.sherpa

import android.os.Build

/**
 * Probes whether the Sherpa-ONNX native runtime is available on this device.
 *
 * Never crashes. All native load failures are reported as structured data.
 */
data class SherpaRuntimeAvailability(
    val isNativeLibraryLoadable: Boolean = false,
    val availableAbis: List<String> = emptyList(),
    val runtimeVersion: String? = null,
    val failureReason: String? = null,
) {
    val isAvailable: Boolean
        get() = isNativeLibraryLoadable && !runtimeVersion.isNullOrBlank()

    companion object {
        fun probe(): SherpaRuntimeAvailability {
            val abis = try {
                Build.SUPPORTED_ABIS?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            val loadResult = SherpaNativeLoader.load()

            return if (loadResult.success) {
                SherpaRuntimeAvailability(
                    isNativeLibraryLoadable = true,
                    availableAbis = abis,
                    runtimeVersion = loadResult.version,
                    failureReason = null,
                )
            } else {
                val reasons = buildList {
                    if (abis.isEmpty()) add("no_supported_abis")
                    loadResult.error?.let { add(it) }
                }
                SherpaRuntimeAvailability(
                    isNativeLibraryLoadable = false,
                    availableAbis = abis,
                    runtimeVersion = null,
                    failureReason = reasons.joinToString(", "),
                )
            }
        }
    }
}
