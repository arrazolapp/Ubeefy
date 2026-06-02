package com.arrazolapp.gpstracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var btnMap: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvUpdates: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvAgentName: TextView
    private lateinit var tvAgentRole: TextView
    private lateinit var liveContainer: LinearLayout
    private lateinit var liveDot: View
    private lateinit var lockMessage: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnim: Animation? = null

    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "GPS_UPDATE" -> {
                    tvCoords.text = "${String.format("%.5f", intent.getDoubleExtra("lat", 0.0))} , ${String.format("%.5f", intent.getDoubleExtra("lng", 0.0))}"
                    tvSpeed.text = "${intent.getIntExtra("speed", 0)}"
                    tvBattery.text = "${intent.getIntExtra("battery", 0)}%"
                    tvUpdates.text = "${intent.getIntExtra("updates", 0)}"
                    tvAccuracy.text = "${intent.getIntExtra("accuracy", 0)}"
                }
                "GPS_STOPPED" -> updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle  = findViewById(R.id.btnToggle)
        btnMap     = findViewById(R.id.btnMap)
        tvStatus   = findViewById(R.id.tvStatus)
        tvCoords   = findViewById(R.id.tvCoords)
        tvSpeed    = findViewById(R.id.tvSpeed)
        tvBattery  = findViewById(R.id.tvBattery)
        tvUpdates  = findViewById(R.id.tvUpdates)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvAgentName = findViewById(R.id.tvAgentName)
        tvAgentRole = findViewById(R.id.tvAgentRole)
        liveContainer = findViewById(R.id.liveContainer)
        liveDot    = findViewById(R.id.liveDot)
        lockMessage = findViewById(R.id.lockMessage)

        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        val userId = prefs.getString("userId", "") ?: ""

        if (userId.isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        tvAgentName.text = prefs.getString("nombre", "Agente")
        val placa = prefs.getString("placa", "") ?: ""
        tvAgentRole.text = "${prefs.getString("rol", "")?.uppercase()} ${if (placa.isNotEmpty()) "• $placa" else ""}"

        pulseAnim = AlphaAnimation(1f, 0.3f).apply {
            duration = 800
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }

        btnToggle.setOnClickListener { toggleTracking() }
        btnMap.setOnClickListener { startActivity(Intent(this, MapActivity::class.java)) }

        checkPermissions()
        requestBatteryOptimization()
        requestAutoStart()

        // ── AUTO-ARRANQUE: si el tracking estaba activo antes de que Android
        //    matara el servicio, lo reiniciamos automáticamente ──────────────
        val wasTracking = prefs.getBoolean("trackingActive", false)

        if (!TrackingService.isRunning) {
            val action = if (wasTracking) TrackingService.ACTION_START else TrackingService.ACTION_STANDBY
            val intent = Intent(this, TrackingService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            if (wasTracking) {
                android.util.Log.d("MainActivity", "Auto-reiniciando tracking que estaba activo")
            }
        } else if (wasTracking && !TrackingService.isTracking) {
            // El servicio está corriendo en STANDBY pero debería estar trackeando
            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("GPS_UPDATE")
            addAction("GPS_STOPPED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gpsReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(gpsReceiver, filter)
        }
        updateUI()
        tvBattery.text = "${getBatteryLevel()}%"

        val uiUpdater = object : Runnable {
            override fun run() {
                if (!isFinishing) {
                    updateUI()
                    handler.postDelayed(this, 2000)
                }
            }
        }
        handler.postDelayed(uiUpdater, 2000)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(gpsReceiver) } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
    }

    private fun toggleTracking() {
        if (TrackingService.isTracking) {
            // El usuario NUNCA puede detener el tracking desde el celular
            AlertDialog.Builder(this)
                .setTitle("🔒 Modo Supervisado")
                .setMessage("El tracking está en modo supervisado.\n\nSolo tu administrador puede detenerlo.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        } else {
            if (!hasLocationPermission()) { checkPermissions(); return }
            // ── Guardar en prefs que el tracking fue iniciado ──
            getSharedPreferences("agent_config", MODE_PRIVATE)
                .edit().putBoolean("trackingActive", true).apply()

            val intent = Intent(this, TrackingService::class.java).apply {
                action = TrackingService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        handler.postDelayed({ updateUI() }, 500)
    }

    private fun updateUI() {
        if (TrackingService.isTracking) {
            liveContainer.visibility = View.VISIBLE
            liveDot.startAnimation(pulseAnim)
            tvStatus.text = "GPS TRANSMITIENDO (SUPERVISADO)"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            btnToggle.text = "🔒  MODO SUPERVISADO"
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.gris_oscuro))
            lockMessage.visibility = View.VISIBLE
        } else {
            liveContainer.visibility = View.GONE
            liveDot.clearAnimation()
            lockMessage.visibility = View.GONE
            btnToggle.text = "▶  INICIAR TRACKING"
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.naranja))
            tvStatus.text = if (TrackingService.isRunning) "CONECTADO — ESPERANDO" else "GPS DESACTIVADO"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            tvCoords.text = "--.----- , ---.-----"
            tvSpeed.text = "0"
            tvUpdates.text = "0"
            tvAccuracy.text = "--"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════
    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            requestBackgroundLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestBackgroundLocation()
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this)
                    .setTitle("Ubicación en segundo plano")
                    .setMessage("Para que el tracking funcione con la app cerrada, seleccioná \"Permitir siempre\" en el siguiente diálogo.")
                    .setPositiveButton("Entendido") { _, _ ->
                        ActivityCompat.requestPermissions(this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 200)
                    }
                    .show()
            }
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Optimización de batería")
                    .setMessage("Para que el GPS funcione siempre, desactivá la optimización de batería para esta app.")
                    .setPositiveButton("Configurar") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("Después", null)
                    .show()
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Detecta la marca del celular y guía al usuario a desactivar
     * las restricciones de batería propietarias que matan apps en segundo plano.
     * Solo se muestra una vez.
     */
    private fun requestAutoStart() {
        val prefs = getSharedPreferences("agent_config", MODE_PRIVATE)
        if (prefs.getBoolean("autostart_guide_shown_v2", false)) return

        val manufacturer = Build.MANUFACTURER.lowercase()
        val (title, message, intentAction) = when {
            manufacturer.contains("samsung") -> Triple(
                "⚡ Configuración importante",
                "Para que el GPS funcione siempre en tu Samsung:\n\n" +
                "1. Ir a Ajustes → Cuidado del dispositivo → Batería\n" +
                "2. Límites de uso en segundo plano\n" +
                "3. Quitar Ubify de \"Apps en suspensión\"\n" +
                "4. Agregar Ubify a \"Apps que nunca se suspenden\"\n\n" +
                "¿Abrir configuración de batería?",
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> Triple(
                "⚡ Configuración importante",
                "Para que el GPS funcione siempre en tu Xiaomi:\n\n" +
                "1. Ir a Ajustes → Aplicaciones → Ubify\n" +
                "2. Ahorro de batería → Sin restricciones\n" +
                "3. Inicio automático → Activar\n\n" +
                "¿Abrir ajustes de la aplicación?",
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Triple(
                "⚡ Configuración importante",
                "Para que el GPS funcione siempre en tu Huawei:\n\n" +
                "1. Ir a Ajustes → Batería → Inicio de aplicaciones\n" +
                "2. Buscar Ubify → Desactivar administración automática\n" +
                "3. Activar: Inicio automático + Actividad en segundo plano\n\n" +
                "¿Abrir configuración de batería?",
                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> Triple(
                "⚡ Configuración importante",
                "Para que el GPS funcione siempre:\n\n" +
                "1. Ir a Ajustes → Administración de batería\n" +
                "2. Buscar Ubify → Permitir actividad en segundo plano\n" +
                "3. Desactivar optimización de batería para esta app\n\n" +
                "¿Abrir ajustes de la aplicación?",
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            else -> return
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Abrir ajustes") { _, _ ->
                try {
                    startActivity(intentAction)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("Ya lo hice", null)
            .show()

        prefs.edit().putBoolean("autostart_guide_shown_v2", true).apply()
    }
}
