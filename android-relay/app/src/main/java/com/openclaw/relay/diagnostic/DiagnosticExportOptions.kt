package com.openclaw.relay.diagnostic

/**
 * User-configurable options for what to include in a diagnostic export.
 * Respects privacy by defaulting to conservative inclusion.
 */
data class DiagnosticExportOptions(
    val includePhoneModel: Boolean = true,
    val includeCapabilityMatrix: Boolean = true,
    val includeErrorCategories: Boolean = true,
    val includeRawRoute: Boolean = false,
)
