package dev.herdr.mobile.feature.settings

import dev.herdr.mobile.core.model.ConnectionTestResult
import dev.herdr.mobile.core.model.FailureKind
import dev.herdr.mobile.core.model.ThemeMode

internal fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.MIDNIGHT -> "Midnight"
}

internal fun testSummary(result: ConnectionTestResult?): String = when (result) {
    null -> "DNS, auth, host key, daemon and protocol failures are distinguished"
    is ConnectionTestResult.Success ->
        "OK in ${result.latencyMs} ms · herdr ${result.herdr.version ?: "?"} · ${result.workspaceCount} workspaces"

    is ConnectionTestResult.Failure -> when (result.kind) {
        FailureKind.DNS -> "DNS failure: ${result.detail}"
        FailureKind.AUTH -> "Auth failure: ${result.detail}"
        FailureKind.HOST_KEY_MISMATCH -> "Host key mismatch: ${result.detail}"
        FailureKind.HOST_KEY_UNKNOWN -> "Unknown host key: ${result.detail}"
        FailureKind.UNREACHABLE -> "Unreachable: ${result.detail}"
        FailureKind.DAEMON_UNAVAILABLE -> "Daemon unavailable: ${result.detail}"
        FailureKind.PROTOCOL_MISMATCH -> "Protocol mismatch: ${result.detail}"
        FailureKind.UNKNOWN -> "Failed: ${result.detail}"
    }

    is ConnectionTestResult.UnknownHostKey -> "Unknown host key: ${result.detail}"
}
