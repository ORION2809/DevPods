package com.openclaw.relay.sherpa

import android.util.Log

/**
 * Safe native loader for Sherpa-ONNX.
 *
 * Tries to load `sherpa-onnx-jni` and reports whether it succeeded,
 * without crashing the app on ABI mismatch or missing library.
 */
object SherpaNativeLoader {
    private const val TAG = "SherpaNativeLoader"
    private const val LIB_NAME = "sherpa-onnx-jni"

    data class LoadResult(
        val success: Boolean,
        val version: String? = null,
        val error: String? = null,
    )

    private var cachedResult: LoadResult? = null

    @JvmStatic
    fun load(): LoadResult {
        cachedResult?.let { return it }

        val result = try {
            System.loadLibrary(LIB_NAME)
            val version = try {
                com.k2fsa.sherpa.onnx.VersionInfo.version
            } catch (e: Throwable) {
                safeWarn("Library loaded but version query failed: ${e.message}")
                throw IllegalStateException("version_query_failed: ${e.message}", e)
            }
            LoadResult(success = true, version = version)
        } catch (e: UnsatisfiedLinkError) {
            safeWarn("Native library not found: ${e.message}")
            LoadResult(success = false, error = "unsatisfied_link: ${e.message}")
        } catch (e: Exception) {
            safeError("Unexpected error loading native library: ${e.message}")
            LoadResult(success = false, error = "unexpected: ${e.message}")
        }

        cachedResult = result
        return result
    }

    @JvmStatic
    fun isLoaded(): Boolean = load().success

    @JvmStatic
    fun clearCache() {
        cachedResult = null
    }

    private fun safeWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun safeError(message: String) {
        runCatching { Log.e(TAG, message) }
    }
}
