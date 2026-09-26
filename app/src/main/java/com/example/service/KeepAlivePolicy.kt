package com.example.service

import com.example.data.SessionJournal
import com.example.data.SessionRecoveryPolicy

/**
 * The decisions behind restarting the keep-alive service from a system broadcast, extracted so
 * they are testable on the JVM (KILL-AUDIT FIX D, owner 2026-09-19: "At any cost kylaq TSI coach
 * app shouldn't be killed by android ... find hidden mechanism which could kill app during trip
 * and lose a trip data which I don't want").
 *
 * Two broadcasts are rescue opportunities the app never listened to:
 *  - `ACTION_BOOT_COMPLETED`: a phone that reboots mid-drive (battery, OTA, kernel) leaves its
 *    half-written journal on disk with NOTHING scheduled to recover it, and no supervisor left to
 *    reconnect the adapter for the rest of the drive - both waited for the owner to open the app
 *    by hand, which is exactly what he cannot do while driving.
 *  - `ACTION_MY_PACKAGE_REPLACED`: the in-app updater installs over the running app, and the
 *    installer kills the process as part of a NORMAL update. Same orphaned journal, same dead
 *    supervisors, and it happens precisely when a drive may be in progress.
 *
 * Starting a foreground service from these two broadcasts is an explicit exemption from the
 * Android 12+ background-start restriction, so this is a sanctioned restart path, not a hack.
 */
object KeepAlivePolicy {

    // String literals, not Intent constants: the rules stay pure JVM and unit-testable.
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    /** Only these two broadcasts may revive the service. Everything else - including null - may not. */
    fun isStartBroadcast(action: String?): Boolean =
        action == ACTION_BOOT_COMPLETED || action == ACTION_MY_PACKAGE_REPLACED

    /**
     * True when the journal directory holds a session that was cut off: a transactions journal
     * bigger than a bare header with no `.finished` marker beside it. This is the coarse pre-check
     * for the receiver; [RecordingManager]'s recovery pass then applies the strict line-count
     * rule, so a false positive here costs one service start and nothing else.
     */
    fun hasPendingJournal(
        fileNames: List<String>,
        sizeOfBytes: (String) -> Long,
        isFinished: (String) -> Boolean
    ): Boolean =
        fileNames.mapNotNull { SessionJournal.sessionIdOf(it) }
            .distinct()
            .any { id ->
                !isFinished(id) &&
                    sizeOfBytes(id + SessionJournal.TX_SUFFIX) > SessionRecoveryPolicy.MIN_JOURNAL_BYTES
            }

    /**
     * Start the service when there is a cut-off journal to rescue, OR when the owner's
     * auto-connect is on - the second case is what makes the NEXT drive work after a reboot
     * without anyone opening the app. With neither, starting a foreground service would only
     * hang a permanent notification on a phone that has nothing to log.
     */
    fun shouldStartKeepAlive(pendingJournal: Boolean, autoConnectEnabled: Boolean): Boolean =
        pendingJournal || autoConnectEnabled
}
