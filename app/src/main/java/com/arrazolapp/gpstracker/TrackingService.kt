package com.arrazolapp.gpstracker

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class TrackingService : Service() {

    companion object {
        const val TAG = "TrackingService"
        const val CHANNEL_ID = "gps_tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STANDBY = "ACTION_STANDBY"

        var isRunning = false
            private set
        var isTracking = false
            private set
    }

    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var controlListener: ValueEventListener? = null
    private var controlRef: DatabaseReference? = null

    // ── WakeLock: evita que el CPU duerma y mate el servicio ──
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Watchdog: reinicia el GPS si deja de recibir updates ──
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastLocationTime = 0L
    private val WATCHDOG_INTERVAL = 60_000L   // revisar cada 60s
    private val WATCHDOG_TIMEOUT  = 90_000L   // si no hay update en 90s → reiniciar GPS

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isTracking) {
                val now = System.currentTimeMillis()
                // ── Watchdog GPS: reiniciar si no hay updates ──
                if (lastLocationTime > 0 && (now - lastLocationTime) > WATCHDOG_TIMEOUT) {
                    Log.w(TAG, "⚠️ Watchdog: sin updates por ${(now - lastLocationTime) / 1000}s — reiniciando GPS")
                    restartGPS()
                }
                // ── Watchdog Firebase: reconectar si está desconectado ──
                if (!isFirebaseConnected) {
                    Log.w(TAG, "⚠️ Watchdog: Firebase desconectado — forzando reconexión")
                    try {
                        val db = FirebaseDatabase.getInstance()
                        db.goOffline()
                        Thread.sleep(300)
                        db.goOnline()
                    } catch (e: Exception) {
                        Log.e(TAG, "Watchdog reconnect error: ${e.message}")
                    }
                }
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL)
        }
    }

    // ── Monitor de conexión Firebase ──
    private var isFirebaseConnected = true
    private var firebaseFailCount = 0
    private val MAX_FAIL_BEFORE_RECONNECT = 3

    // ── NetworkCallback: detecta cuando vuelve la red ──
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastRecoveryTime = 0L
    private val RECOVERY_DEBOUNCE = 10_000L  // máx 1 recovery cada 10s

    // ── Receiver: detecta modo avión on/off ──
    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                val isAirplane = Settings.Global.getInt(
                    contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
                ) != 0
                Log.d(TAG, if (isAirplane) "✈️ Modo avión ACTIVADO" else "✈️ Modo avión DESACTIVADO")
                if (!isAirplane) {
                    // Esperar 5s para que los radios (GPS + celular) se inicialicen
                    Handler(Looper.getMainLooper()).postDelayed({
                        onNetworkRecovered("airplane_off")
                    }, 5000)
                }
            }
        }
    }

    // ── Gestor de visitas a clientes ──
    private lateinit var visitaManager: VisitaManager

    private var updateCount = 0
    private var totalDistance = 0.0
    private var stopCount = 0
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var lastSpeed = 0
    private var lastWasMoving = false

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        visitaManager = VisitaManager(this)
        createNotificationChannel()

        // ── Adquirir WakeLock para que el CPU no duerma ──
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Ubify::TrackingWakeLock"
        ).apply { acquire(12 * 60 * 60 * 1000L) } // máx 12 horas

        // ── Iniciar watchdog ──
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL)

        // ── Monitorear conexión Firebase ──
        startConnectionMonitor()

        // ── Detectar cambios de red (WiFi/celular vuelve) ──
        registerNetworkCallback()

        // ── Detectar modo avión on/off ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(airplaneModeReceiver,
                IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(airplaneModeReceiver,
                IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        }

        Log.d(TAG, "✅ WakeLock + Watchdog + Firebase monitor + Network monitor + Airplane listener iniciados")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Verificar si el agente tiene permiso para detener el tracking
                val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
                val allowStop = prefs.getBoolean("allowStop", true)
                if (allowStop) {
                    stopGPS()
                } else {
                    // Ignorar — el tracking solo puede ser detenido por el admin
                    Log.d(TAG, "ACTION_STOP ignorado: allowStop=false (solo el admin puede detener)")
                }
                return START_STICKY
            }
            ACTION_START -> {
                startGPS()
                return START_STICKY
            }
            ACTION_STANDBY -> {
                startStandby()
                return START_STICKY
            }
            else -> {
                if (!isRunning) startStandby()
                return START_STICKY
            }
        }
    }

    private fun startStandby() {
        if (isRunning && !isTracking) return

        isRunning = true
        isTracking = false

        val notification = buildNotification("Conectado — esperando instrucciones", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startCommandListener()
        Log.d(TAG, "STANDBY activo")
    }

    private fun startGPS() {
        isRunning = true
        isTracking = true

        // Persistir estado para auto-inicio al reiniciar
        getSharedPreferences("agent_config", MODE_PRIVATE)
            .edit().putBoolean("trackingActive", true).apply()

        val notification = buildNotification("Iniciando GPS...", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startCommandListener()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .setMinUpdateDistanceMeters(5f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocationTime = System.currentTimeMillis() // ── Watchdog timestamp ──
                val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toInt() else 0

                if (lastLat != 0.0 && lastLng != 0.0) {
                    val dist = haversine(lastLat, lastLng, location.latitude, location.longitude)
                    if (dist > 0.01) totalDistance += dist
                    if (lastWasMoving && speedKmh < 3) { stopCount++; lastWasMoving = false }
                    else if (speedKmh >= 3) lastWasMoving = true
                }

                lastLat = location.latitude
                lastLng = location.longitude
                lastSpeed = speedKmh
                updateCount++

                sendToFirebase(location.latitude, location.longitude, speedKmh)

                // ── Detectar visitas a clientes ──
                val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
                visitaManager.onLocationUpdate(
                    lat        = location.latitude,
                    lng        = location.longitude,
                    companyId  = prefs.getString("company", "demo_corp") ?: "demo_corp",
                    agenteName = prefs.getString("nombre", "Agente") ?: "Agente",
                    agenteRol  = prefs.getString("rol", "vendedor") ?: "vendedor",
                    webhookUrl = prefs.getString("webhookUrl", "") ?: ""
                )

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(
                    "${location.latitude.f(4)}, ${location.longitude.f(4)} · ${speedKmh} km/h · #$updateCount", true
                ))

                sendBroadcast(Intent("GPS_UPDATE").apply {
                    putExtra("lat", location.latitude)
                    putExtra("lng", location.longitude)
                    putExtra("speed", speedKmh)
                    putExtra("accuracy", location.accuracy.toInt())
                    putExtra("heading", if (location.hasBearing()) location.bearing else 0f)
                    putExtra("updates", updateCount)
                    putExtra("distance", totalDistance)
                    putExtra("stops", stopCount)
                    putExtra("battery", getBatteryLevel())
                })
            }
        }

        try {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
            Log.d(TAG, "GPS tracking iniciado")
            markOnline()

            // ── Iniciar detección de visitas ──
            val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
            val companyId = prefs.getString("company", "demo_corp") ?: "demo_corp"
            visitaManager.iniciar(companyId)

        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permisos de GPS", e)
        }
    }

    private fun stopGPS() {
        isTracking = false
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null

        // Persistir estado: el tracking fue detenido intencionalmente
        getSharedPreferences("agent_config", MODE_PRIVATE)
            .edit().putBoolean("trackingActive", false).apply()

        // ── Cerrar visita activa si hay una ──
        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        visitaManager.detener(
            companyId  = prefs.getString("company", "demo_corp") ?: "demo_corp",
            agenteName = prefs.getString("nombre", "Agente") ?: "Agente",
            agenteRol  = prefs.getString("rol", "vendedor") ?: "vendedor",
            webhookUrl = prefs.getString("webhookUrl", "") ?: ""
        )

        markOffline()
        sendBroadcast(Intent("GPS_STOPPED"))

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("Conectado — esperando instrucciones", false))
        Log.d(TAG, "GPS detenido, standby activo")
    }

    private fun startCommandListener() {
        if (controlListener != null) return

        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        val companyId = prefs.getString("company", "demo_corp") ?: "demo_corp"
        val userId = prefs.getString("userId", "") ?: ""
        if (userId.isEmpty()) return

        controlRef = FirebaseDatabase.getInstance()
            .getReference("companies/$companyId/controls/$userId")

        controlListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.value as? Map<*, *> ?: return

                val forceStart = data["forceStart"] as? Boolean ?: false
                val forceStop = data["forceStop"] as? Boolean ?: false
                val allowStop = data["allowStop"] as? Boolean ?: true
                val schedEnabled = data["scheduleEnabled"] as? Boolean ?: false
                val allowedStart = data["allowedStart"] as? String
                val allowedEnd = data["allowedEnd"] as? String

                Log.d(TAG, "Comando: start=$forceStart stop=$forceStop allow=$allowStop")

                if (forceStart && !isTracking) {
                    Log.d(TAG, "Admin: INICIAR")
                    controlRef?.child("forceStart")?.setValue(false)
                    if (schedEnabled && allowedStart != null && allowedEnd != null) {
                        if (!isWithinSchedule(allowedStart, allowedEnd)) return
                    }
                    startGPS()
                }

                if (forceStop && isTracking) {
                    Log.d(TAG, "Admin: DETENER")
                    controlRef?.child("forceStop")?.setValue(false)
                    stopGPS()
                }

                getSharedPreferences("agent_config", MODE_PRIVATE).edit()
                    .putBoolean("allowStop", allowStop).apply()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error listener: ${error.message}")
            }
        }

        controlRef?.addValueEventListener(controlListener!!)
        Log.d(TAG, "Firebase listener activo para $userId")
    }

    private fun sendToFirebase(lat: Double, lng: Double, speed: Int) {
        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        val companyId = prefs.getString("company", "demo_corp") ?: "demo_corp"
        val userId = prefs.getString("userId", "") ?: ""
        if (userId.isEmpty()) return

        val data = hashMapOf<String, Any>(
            "nombre" to (prefs.getString("nombre", "Agente") ?: "Agente"),
            "iniciales" to (prefs.getString("iniciales", "AG") ?: "AG"),
            "rol" to (prefs.getString("rol", "vendedor") ?: "vendedor"),
            "placa" to (prefs.getString("placa", "") ?: ""),
            "whatsapp" to (prefs.getString("whatsapp", "") ?: ""),
            "lat" to lat, "lng" to lng, "speed" to speed,
            "battery" to getBatteryLevel(),
            "distancia" to (Math.round(totalDistance * 10.0) / 10.0),
            "paradas" to stopCount,
            "status" to if (speed > 3) "moving" else "online",
            "ubicacion" to "${lat.f(4)}, ${lng.f(4)}",
            "lastUpdate" to ServerValue.TIMESTAMP
        )

        val db = FirebaseDatabase.getInstance()

        // ── Escritura tracking con listeners de éxito/fallo ──
        db.getReference("companies/$companyId/tracking/$userId")
            .updateChildren(data)
            .addOnSuccessListener {
                if (firebaseFailCount > 0) {
                    Log.d(TAG, "✅ Firebase recuperado después de $firebaseFailCount fallos")
                }
                firebaseFailCount = 0
            }
            .addOnFailureListener { e ->
                firebaseFailCount++
                Log.e(TAG, "❌ Firebase tracking FAIL #$firebaseFailCount: ${e.message}")
                // Si acumula fallos, forzar reconexión del socket
                if (firebaseFailCount >= MAX_FAIL_BEFORE_RECONNECT) {
                    Log.w(TAG, "⚠️ Demasiados fallos — forzando reconexión Firebase")
                    try {
                        db.goOffline()
                        Thread.sleep(500)
                        db.goOnline()
                        firebaseFailCount = 0
                        Log.d(TAG, "🔄 Reconexión Firebase forzada")
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error en reconexión: ${ex.message}")
                    }
                }
            }

        // ── Escritura historial con listener ──
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        db.getReference("companies/$companyId/history/$userId/$today").push().setValue(
            hashMapOf("lat" to lat, "lng" to lng, "speed" to speed,
                "battery" to getBatteryLevel(), "timestamp" to System.currentTimeMillis())
        ).addOnFailureListener { e ->
            Log.e(TAG, "❌ Firebase history FAIL: ${e.message}")
        }
    }

    private fun markOnline() {
        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        val c = prefs.getString("company", "demo_corp") ?: "demo_corp"
        val u = prefs.getString("userId", "") ?: ""
        if (u.isEmpty()) return
        val ref = FirebaseDatabase.getInstance().getReference("companies/$c/tracking/$u")
        ref.child("status").setValue("online")
        ref.child("lastUpdate").setValue(ServerValue.TIMESTAMP)
        ref.child("status").onDisconnect().setValue("offline")
        ref.child("speed").onDisconnect().setValue(0)
        ref.child("lastUpdate").onDisconnect().setValue(ServerValue.TIMESTAMP)
    }

    private fun markOffline() {
        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        val c = prefs.getString("company", "demo_corp") ?: "demo_corp"
        val u = prefs.getString("userId", "") ?: ""
        if (u.isEmpty()) return
        val ref = FirebaseDatabase.getInstance().getReference("companies/$c/tracking/$u")
        ref.child("status").setValue("offline")
        ref.child("speed").setValue(0)
        ref.child("lastUpdate").setValue(ServerValue.TIMESTAMP)
    }

    private fun isWithinSchedule(start: String, end: String): Boolean {
        return try {
            val now = Calendar.getInstance()
            val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val s = start.split(":"); val e = end.split(":")
            cur in (s[0].toInt() * 60 + s[1].toInt())..(e[0].toInt() * 60 + e[1].toInt())
        } catch (ex: Exception) { true }
    }

    private fun buildNotification(text: String, showStop: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📡 Ubify")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openPending)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (showStop) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

        if (showStop) {
            val stopIntent = Intent(this, TrackingService::class.java).apply { action = ACTION_STOP }
            val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_notification, "Detener", stopPending)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Ubify", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tracking GPS y control remoto"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Monitorea .info/connected de Firebase en tiempo real.
     * Cuando la conexión vuelve después de una caída, re-marca online
     * y envía el último punto conocido para cerrar el "hueco" de datos.
     */
    private fun startConnectionMonitor() {
        val connRef = FirebaseDatabase.getInstance().getReference(".info/connected")
        connRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                isFirebaseConnected = connected
                Log.d(TAG, if (connected) "🟢 Firebase CONECTADO" else "🔴 Firebase DESCONECTADO")
                if (connected) {
                    firebaseFailCount = 0
                    if (isTracking) {
                        markOnline()
                        if (lastLat != 0.0 && lastLng != 0.0) {
                            sendToFirebase(lastLat, lastLng, lastSpeed)
                        }
                        visitaManager.flushPendingVisitas()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Connection monitor error: ${error.message}")
            }
        })
    }

    /**
     * Registra un NetworkCallback para detectar cuando WiFi/celular vuelve.
     * Esto complementa al airplane receiver — cubre pérdidas de red sin modo avión.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "🌐 Red disponible")
                // Esperar 2s para que la red se estabilice
                Handler(Looper.getMainLooper()).postDelayed({
                    onNetworkRecovered("network_available")
                }, 2000)
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "📵 Red perdida — Firebase seguirá encolando en disco")
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)
        Log.d(TAG, "NetworkCallback registrado")
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NetworkCallback: ${e.message}")
            }
        }
        networkCallback = null
    }

    /**
     * Punto central de recuperación. Llamado cuando:
     * - El modo avión se desactiva
     * - Una red WiFi/celular se vuelve disponible
     * Tiene debounce de 10s para evitar llamadas repetidas.
     */
    private fun onNetworkRecovered(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryTime < RECOVERY_DEBOUNCE) {
            Log.d(TAG, "Recovery ignorado (debounce) — fuente: $source")
            return
        }
        lastRecoveryTime = now
        Log.d(TAG, "🔄 Recovery activado por: $source")

        // 1. Forzar reconexión Firebase
        try {
            val db = FirebaseDatabase.getInstance()
            db.goOffline()
            Thread.sleep(500)
            db.goOnline()
            Log.d(TAG, "✅ Firebase reconectado")
        } catch (e: Exception) {
            Log.e(TAG, "Error reconectando Firebase: ${e.message}")
        }

        // 2. Si estaba trackeando, reiniciar GPS + re-marcar online
        if (isTracking) {
            restartGPS()
            markOnline()
            if (lastLat != 0.0 && lastLng != 0.0) {
                sendToFirebase(lastLat, lastLng, lastSpeed)
                Log.d(TAG, "✅ Último punto re-enviado: $lastLat, $lastLng")
            }
            // 3. Enviar visitas que quedaron pendientes durante el corte de señal
            visitaManager.flushPendingVisitas()
        }
    }

    /**
     * Reinicia el GPS sin perder el estado de tracking.
     * Llamado por el watchdog cuando no hay updates por >90 segundos.
     */
    private fun restartGPS() {
        Log.d(TAG, "🔄 Reiniciando GPS...")
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        lastLocationTime = System.currentTimeMillis()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .setMinUpdateDistanceMeters(5f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocationTime = System.currentTimeMillis()
                val speedKmh = if (location.hasSpeed()) (location.speed * 3.6).toInt() else 0

                if (lastLat != 0.0 && lastLng != 0.0) {
                    val dist = haversine(lastLat, lastLng, location.latitude, location.longitude)
                    if (dist > 0.01) totalDistance += dist
                    if (lastWasMoving && speedKmh < 3) { stopCount++; lastWasMoving = false }
                    else if (speedKmh >= 3) lastWasMoving = true
                }
                lastLat = location.latitude
                lastLng = location.longitude
                lastSpeed = speedKmh
                updateCount++

                sendToFirebase(location.latitude, location.longitude, speedKmh)

                val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
                visitaManager.onLocationUpdate(
                    lat        = location.latitude,
                    lng        = location.longitude,
                    companyId  = prefs.getString("company", "demo_corp") ?: "demo_corp",
                    agenteName = prefs.getString("nombre", "Agente") ?: "Agente",
                    agenteRol  = prefs.getString("rol", "vendedor") ?: "vendedor",
                    webhookUrl = prefs.getString("webhookUrl", "") ?: ""
                )

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(
                    "${location.latitude.f(4)}, ${location.longitude.f(4)} · ${speedKmh} km/h · #$updateCount", true
                ))

                sendBroadcast(Intent("GPS_UPDATE").apply {
                    putExtra("lat", location.latitude)
                    putExtra("lng", location.longitude)
                    putExtra("speed", speedKmh)
                    putExtra("accuracy", location.accuracy.toInt())
                    putExtra("heading", if (location.hasBearing()) location.bearing else 0f)
                    putExtra("updates", updateCount)
                    putExtra("distance", totalDistance)
                    putExtra("stops", stopCount)
                    putExtra("battery", getBatteryLevel())
                })
            }
        }

        try {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
            Log.d(TAG, "✅ GPS reiniciado exitosamente")
        } catch (e: SecurityException) {
            Log.e(TAG, "Error reiniciando GPS: ${e.message}")
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0; val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun Double.f(n: Int) = String.format("%.${n}f", this)

    override fun onDestroy() {
        super.onDestroy()
        controlListener?.let { controlRef?.removeEventListener(it) }
        controlListener = null

        // ── Liberar WakeLock y detener Watchdog ──
        try { wakeLock?.release() } catch (e: Exception) {}
        watchdogHandler.removeCallbacks(watchdogRunnable)

        // ── Desregistrar listeners de red y modo avión ──
        unregisterNetworkCallback()
        try { unregisterReceiver(airplaneModeReceiver) } catch (e: Exception) {}

        if (isRunning) {
            Log.w(TAG, "⚠️ Servicio destruido por Android — reiniciando automáticamente...")
            val wasTracking = isTracking
            isRunning = false; isTracking = false
            val i = Intent(this, TrackingService::class.java).apply {
                action = if (wasTracking) ACTION_START else ACTION_STANDBY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        }
    }
}
// ╔══════════════════════════════════════════════════════════════════════════╗
// ║  ADICIONES A TrackingService.kt — Servicio "inmortal"                   ║
// ║                                                                          ║
// ║  3 bloques para agregar al TrackingService existente:                    ║
// ║  BLOQUE 1: Import nuevo (agregar arriba con los imports)                 ║
// ║  BLOQUE 2: onTaskRemoved() (agregar como función nueva en la clase)      ║
// ║  BLOQUE 3: scheduleAlarmWatchdog() + cancelAlarmWatchdog()               ║
// ║  BLOQUE 4: Llamar scheduleAlarmWatchdog() en 3 lugares                   ║
// ╚══════════════════════════════════════════════════════════════════════════╝


// ══════════════════════════════════════════════════════════════
// BLOQUE 1: IMPORT NUEVO
// Agregar junto a los otros imports al inicio del archivo.
// (PendingIntent puede ya estar importado por android.app.*)
// Si usás import android.app.* → no necesitás agregar nada.
// ══════════════════════════════════════════════════════════════


// ══════════════════════════════════════════════════════════════
// BLOQUE 2: onTaskRemoved()
// Agregar ANTES de onDestroy().
// Se ejecuta cuando el usuario desliza la app de "recientes".
// En Samsung/Xiaomi esto MATA el servicio si no se maneja.
// ══════════════════════════════════════════════════════════════

    /**
     * Llamado cuando el usuario desliza la app de la lista de "recientes".
     * Sin este override, en Samsung/Xiaomi/Huawei el servicio muere silenciosamente.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "⚠️ App removida de recientes — protegiendo servicio")

        // Programar alarma de respaldo por si Android mata el servicio
        scheduleAlarmWatchdog()

        // Re-lanzar el servicio inmediatamente
        if (isTracking || isRunning) {
            val action = if (isTracking) ACTION_START else ACTION_STANDBY
            val restartIntent = Intent(this, TrackingService::class.java).apply {
                this.action = action
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
                Log.d(TAG, "✅ Servicio re-lanzado después de swipe")
            } catch (e: Exception) {
                Log.e(TAG, "Error re-lanzando: ${e.message}")
            }
        }
    }


// ══════════════════════════════════════════════════════════════
// BLOQUE 3: scheduleAlarmWatchdog() + cancelAlarmWatchdog()
// Agregar como funciones nuevas en la clase.
// La alarma se dispara cada 15 minutos y si el servicio está
// muerto, el BootReceiver lo resucita.
// Funciona incluso en Doze mode (setExactAndAllowWhileIdle).
// ══════════════════════════════════════════════════════════════

    /**
     * Programa una alarma periódica cada 15 minutos que verifica
     * si el servicio sigue vivo. Si Android lo mató, el BootReceiver
     * lo resucita. Funciona incluso en Doze mode.
     */
    private fun scheduleAlarmWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_WATCHDOG_ALARM
        }
        val pi = PendingIntent.getBroadcast(
            this, 9999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val interval = 15 * 60 * 1000L  // 15 minutos

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + interval,
                pi
            )
        } else {
            am.setExact(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + interval,
                pi
            )
        }
        Log.d(TAG, "⏰ Alarm watchdog programada en 15 min")
    }

    private fun cancelAlarmWatchdog() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, BootReceiver::class.java).apply {
            action = BootReceiver.ACTION_WATCHDOG_ALARM
        }
        val pi = PendingIntent.getBroadcast(
            this, 9999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }


// ══════════════════════════════════════════════════════════════
// BLOQUE 4: LLAMAR scheduleAlarmWatchdog() EN 3 LUGARES
// ══════════════════════════════════════════════════════════════
//
// 4a. En onCreate(), al final, después del watchdog handler:
//     watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL)
//     scheduleAlarmWatchdog()    // ← AGREGAR
//     Log.d(TAG, "WakeLock + Watchdog + AlarmWatchdog iniciados")
//
// 4b. En startGPS(), después de markOnline():
//     markOnline()
//     scheduleAlarmWatchdog()    // ← AGREGAR (reprograma la alarma)
//
// 4c. En onDestroy(), ANTES de intentar reiniciar:
//     scheduleAlarmWatchdog()    // ← AGREGAR (última línea de defensa)
//     if (isRunning) { ...
//
// ══════════════════════════════════════════════════════════════
// NOTA SOBRE stopGPS():
// Cuando el admin detiene el tracking intencionalmente,
// CANCELAR la alarma para que no lo reviva:
//
//   En stopGPS(), agregar:
//     cancelAlarmWatchdog()    // ← AGREGAR
//     markOffline()
// ══════════════════════════════════════════════════════════════
