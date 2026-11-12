package com.example.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.scheduler.ObdQuickConnect

/**
 * BroadcastReceiver for Bluetooth device connection events (ACL_CONNECTED / ACL_DISCONNECTED).
 *
 * Enables true zero-touch always-on trip logging:
 * When the phone connects to the car's Bluetooth or the OBD2 adapter powers on and connects,
 * this receiver immediately triggers the background keep-alive service to connect the RFCOMM
 * socket and start PID polling without requiring the driver to unlock or open the phone.
 */
class BluetoothStateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_CONNECT = "com.example.service.action.TRIGGER_CONNECT"
        const val ACTION_BT_DISCONNECTED = "com.example.service.action.BT_DISCONNECTED"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext ?: return

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                val shouldConnect = runCatching {
                    com.example.di.AppContainer.init(app)
                    val settings = com.example.di.AppContainer.settingsRepository
                    val isAutoConnect = settings.autoConnect.value || settings.alwaysOnService.value
                    if (!isAutoConnect) return@runCatching false

                    val devName = try { device?.name } catch (_: SecurityException) { null }
                    val devAddr = device?.address
                    val defaultAddr = settings.defaultBtAddress.value

                    // Connect if matches starred address, or looks like OBD adapter, or any ACL connected if auto-connect on
                    defaultAddr == devAddr ||
                        ObdQuickConnect.looksLikeObdAdapter(devName) ||
                        (devName != null && devName.contains("Kylaq", ignoreCase = true)) ||
                        (devName != null && devName.contains("Skoda", ignoreCase = true)) ||
                        defaultAddr == null
                }.getOrDefault(false)

                if (shouldConnect) {
                    startServiceWithAction(app, ACTION_TRIGGER_CONNECT)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                startServiceWithAction(app, ACTION_BT_DISCONNECTED)
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    startServiceWithAction(app, ACTION_TRIGGER_CONNECT)
                }
            }
        }
    }

    private fun startServiceWithAction(context: Context, actionStr: String) {
        runCatching {
            val svc = Intent(context, ObdKeepAliveService::class.java).apply {
                action = actionStr
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }.onFailure {
            android.util.Log.e("BluetoothStateReceiver", "Failed to forward action $actionStr to service", it)
        }
    }
}
