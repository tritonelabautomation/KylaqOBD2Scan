package com.example.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * PackageInstaller status handler.
 *
 * ColorOS treats a translucent / noHistory trampoline as a blocked overlay popup, so
 * the confirm sheet never appears and Settings just sits on "Install now". The
 * PendingIntent now targets [com.example.MainActivity] (already on screen). This
 * Activity stays as a fallback if the process was not in the foreground.
 *
 * Do not finish() on PENDING_USER_ACTION — ColorOS dismisses the confirm sheet
 * when the calling activity dies.
 */
class UpdateInstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Broker.consume(this, intent, finishAfterConfirm = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Broker.consume(this, intent, finishAfterConfirm = false)
    }

    object Broker {
        const val ACTION = "com.example.update.ACTION_INSTALL_RESULT"

        /**
         * Set by [com.example.ui.viewmodel.MainViewModel] for the duration of one
         * install attempt so Settings can render the PackageInstaller status.
         */
        @Volatile
        var listener: ((success: Boolean, message: String?) -> Unit)? = null

        fun consume(activity: Activity, intent: Intent?, finishAfterConfirm: Boolean = false): Boolean {
            if (intent == null) return false
            if (intent.action != ACTION) return false
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                Int.MIN_VALUE
            )
            if (status == Int.MIN_VALUE) return false
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = extraIntent(intent)
                    if (confirm == null) {
                        val msg = "Android asked for install confirmation but sent no screen to open."
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        listener?.invoke(false, msg)
                    } else {
                        try {
                            activity.startActivity(confirm)
                        } catch (_: Exception) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                activity.startActivity(confirm)
                            } catch (e: Exception) {
                                val msg = "Could not open the system installer: " +
                                    (e.message ?: e.javaClass.simpleName)
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                                listener?.invoke(false, msg)
                            }
                        }
                    }
                    if (finishAfterConfirm) activity.finish()
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Toast.makeText(
                        activity,
                        "Update installed. Reopen Kylaq TSI Coach if it does not restart on its own.",
                        Toast.LENGTH_LONG
                    ).show()
                    listener?.invoke(true, null)
                    if (finishAfterConfirm) activity.finish()
                }
                else -> {
                    val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    val msg = UpdateInstallStatus.userMessage(status, raw)
                    if (status != PackageInstaller.STATUS_FAILURE_ABORTED && msg != null) {
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        listener?.invoke(false, msg)
                    }
                    if (finishAfterConfirm) activity.finish()
                }
            }
            return true
        }

        @Suppress("DEPRECATION")
        private fun extraIntent(intent: Intent): Intent? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
        }
    }

    companion object {
        var listener: ((success: Boolean, message: String?) -> Unit)?
            get() = Broker.listener
            set(value) {
                Broker.listener = value
            }
    }
}
