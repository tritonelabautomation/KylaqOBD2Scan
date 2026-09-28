package com.example.update

import java.io.File

/**
 * Everything the update UI renders, as one flat state (2026-09-16).
 *
 * Deliberately no sealed-class branches: the Settings card and the launch dialog both
 * need several of these facts at once (e.g. "downloading, 42 %, 18 MB of 43 MB"), and a
 * flat state keeps them from silently dropping a field when a new phase is added.
 */
data class UpdateUiState(
    /** A check is in flight. */
    val checking: Boolean = false,
    /** The APK is being fetched. */
    val downloading: Boolean = false,
    /** 0f..1f, or null while the total size is still unknown (indeterminate bar). */
    val progress: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** A newer build exists on the rolling release. */
    val available: AppUpdateInfo? = null,
    /** The check succeeded and this install is already the newest build. */
    val upToDate: Boolean = false,
    /** Download finished AND its SHA-256 matched; safe to hand to the installer. */
    val stagedFile: File? = null,
    /**
     * True when the installed build was signed with a different key than the staged APK,
     * so Android will refuse an in-place update. Only ever true for installs predating
     * 2026-09-16 (each CI build then used a throwaway runner-local debug keystore); it
     * needs one manual reinstall, after which updates install in place forever.
     */
    val signatureMismatch: Boolean = false,
    /** Human-readable failure (offline, HTTP error, truncated download, checksum mismatch). */
    val error: String? = null,
    val lastCheckedAtMs: Long = 0L,
    /** Owner dismissed the launch dialog for this app session; Settings still offers it. */
    val dismissed: Boolean = false
) {
    /** Show the launch dialog only for a verified, actionable, undismissed update. */
    val shouldPrompt: Boolean
        get() = available != null && !dismissed && !downloading && error == null
}
