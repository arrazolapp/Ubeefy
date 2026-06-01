package com.arrazolapp.gpstracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver multi-propósito:
 * - BOOT_COMPLETED / QUICKBOOT_POWERON → reinicia tracking al encender el celular
 * - ACTION_WATCHDOG_ALARM → alarma periódica cada 15min que resucita el servicio si murió
 * - MY_PACKAGE_REPLACED → después de actualizar la app, reiniciar tracking
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootReceiver"
        const val ACTION_WATCHDOG_ALARM = "com.arrazolapp.gpstracker.WATCHDOG_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_WATCHDOG_ALARM -> {
                Log.d(TAG, "Received: $action")
                ensureServiceRunning(context, action)
            }
        }
    }

    private fun ensureServiceRunning(context: Context, trigger: String) {
        val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
        val userId = prefs.getString("userId", "") ?: ""
        if (userId.isEmpty()) {
            Log.d(TAG, "No userId configurado, ignorando $trigger")
            return
        }

        val wasTracking = prefs.getBoolean("trackingActive", false)

        // Si el servicio ya está corriendo en el modo correcto, no hacer nada
        if (TrackingService.isRunning && (!wasTracking || TrackingService.isTracking)) {
            Log.d(TAG, "Servicio ya activo en modo correcto ($trigger)")
            return
        }

        // Determinar el modo de inicio
        val serviceAction = if (wasTracking) {
            Log.d(TAG, "⚡ Resucitando tracking — trigger: $trigger")
            TrackingService.ACTION_START
        } else {
            Log.d(TAG, "📡 Iniciando standby — trigger: $trigger")
            TrackingService.ACTION_STANDBY
        }

        val serviceIntent = Intent(context, TrackingService::class.java).apply {
            this.action = serviceAction
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando servicio: ${e.message}")
        }
    }
}
