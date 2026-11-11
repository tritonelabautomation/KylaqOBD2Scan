package com.example.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * PackageInstaller status trampoline.
 *
 * [PackageInstaller.Session.commit] delivers its result through a PendingIntent.
 * On Android 14+ a BroadcastReceiver cannot reliably start the confirmation
 * Activity (background-start restriction), so this process-local Activity does:
 *  - STATUS_PENDING_USER_ACTION → launch the system confirm sheet
 *  - failure → Toast + optional [listener] so Settings can show the real reason
 *    instead of ColorOS's "App not installed"
 *  - success → the process is typically already being replaced
 */
class UpdateInstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = extraIntent(intent)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(confirm)
                    } catch (e: Exception) {
                        val msg = "Could not open the system installer: " +
                            (e.message ?: e.javaClass.simpleName)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        listener?.invoke(false, msg)
                    }
                } else {
                    val msg = "Android asked for install confirmation but sent no screen to open."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    listener?.invoke(false, msg)
                }
                finish()
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(
                    this,
                    "Update installed. Reopen Kylaq TSI Coach if it does not restart on its own.",
                    Toast.LENGTH_LONG
                ).show()
                listener?.invoke(true, null)
                finish()
            }
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val msg = UpdateInstallStatus.userMessage(status, raw)
                    ?: "Install did not finish (status $status)."
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    listener?.invoke(false, msg)
                }
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extraIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    companion object {
        /**
         * Set by [com.example.ui.viewmodel.MainViewModel] for the duration of one
         * install attempt so Settings can render the PackageInstaller status.
         */
        @Volatile
        var listener: ((success: Boolean, message: String?) -> Unit)? = null
    }
}
