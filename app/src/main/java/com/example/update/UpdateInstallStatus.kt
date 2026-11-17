package com.example.update

/**
 * Pure mapping of [android.content.pm.PackageInstaller] status codes so the updater
 * can explain a failed install instead of leaving ColorOS's cryptic "App not installed".
 *
 * Constants are duplicated from the framework so this file stays a pure-JVM unit-test
 * target (no android.jar on the CI test classpath).
 */
object UpdateInstallStatus {

    const val PENDING_USER_ACTION = -1
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7

    /**
     * Human-readable error, or null when the UI should stay quiet (success, user
     * cancelled, or still waiting on the system confirmation sheet).
     */
    fun userMessage(status: Int, statusMessage: String? = null): String? {
        val detail = statusMessage?.trim().orEmpty()
        return when (status) {
            PENDING_USER_ACTION, SUCCESS -> null
            FAILURE_ABORTED -> null
            FAILURE_BLOCKED ->
                "Android blocked this install" + detailSuffix(detail) +
                    ". Check Play Protect / the phone's install restrictions and try again."
            FAILURE_INVALID ->
                "Android could not read the downloaded APK" + detailSuffix(detail) +
                    ". Delete the staged file and tap Download & install again."
            FAILURE_CONFLICT ->
                "This update conflicts with the copy already installed" +
                    detailSuffix(detail) +
                    ". Export a backup, uninstall once, then install the new APK."
            FAILURE_STORAGE ->
                "Not enough storage to install the update" + detailSuffix(detail) + "."
            FAILURE_INCOMPATIBLE ->
                "Android refused to replace the installed app with this APK" +
                    detailSuffix(detail) +
                    ". Usually a signing-key mismatch or a lower versionCode — " +
                    "export a backup, uninstall once, then install the new APK so later " +
                    "updates can land in place."
            FAILURE ->
                "Install failed" + detailSuffix(detail) + "."
            else ->
                "Install failed (status $status)" + detailSuffix(detail) + "."
        }
    }

    private fun detailSuffix(detail: String): String =
        if (detail.isBlank()) "" else " ($detail)"
}
