package com.arrazolapp.gpstracker

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class VisitaManager(private val context: Context) {

    companion object {
        const val TAG = "VisitaManager"
        const val MIN_VISITA_SEG = 60
        const val RADIO_DEFAULT_M = 50.0
        const val RADIO_MIN_M = 50.0
        const val PENDING_PREFS = "pending_visitas"
        const val PENDING_KEY = "queue"
    }

    data class Cliente(
        val id: String, val nombre: String, val codigo: String,
        val zona: String, val direccion: String,
        val lat: Double, val lng: Double, val radioM: Double
    )

    data class VisitaActiva(
        val clienteId: String, val clienteNombre: String,
        val clienteCodigo: String, val clienteZona: String,
        val clienteDireccion: String,
        val lat: Double, val lng: Double,
        val entradaTs: Long, val horaEntrada: String
    )

    private var clientes: List<Cliente> = emptyList()
    private var visitaActiva: VisitaActiva? = null
    private var clientesRef: DatabaseReference? = null
    private var clientesListener: ValueEventListener? = null

    fun iniciar(companyId: String) {
        Log.d(TAG, "Iniciando VisitaManager para company=$companyId")
        clientesRef = FirebaseDatabase.getInstance().getReference("companies/$companyId/clientes")
        clientesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lista = mutableListOf<Cliente>()
                for (child in snapshot.children) {
                    val data = child.value as? Map<*, *> ?: continue
                    val lat = when (val v = data["lat"]) {
                        is Double -> v; is Long -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: continue; else -> continue
                    }
                    val lng = when (val v = data["lng"]) {
                        is Double -> v; is Long -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: continue; else -> continue
                    }
                    if (lat == 0.0 && lng == 0.0) continue
                    val radio = when (val v = data["radio_m"]) {
                        is Double -> v; is Long -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: RADIO_DEFAULT_M; else -> RADIO_DEFAULT_M
                    }
                    lista.add(Cliente(
                        id = child.key ?: continue,
                        nombre    = data["nombre"]?.toString() ?: "",
                        codigo    = data["codigo"]?.toString() ?: "",
                        zona      = data["zona"]?.toString() ?: "",
                        direccion = data["direccion"]?.toString() ?: "",
                        lat = lat, lng = lng,
                        radioM = if (radio < RADIO_MIN_M) RADIO_DEFAULT_M else radio
                    ))
                }
                clientes = lista
                Log.d(TAG, "${clientes.size} clientes cargados")
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Error cargando clientes: ${error.message}")
            }
        }
        clientesRef?.addValueEventListener(clientesListener!!)

        // Al iniciar, intentar enviar visitas pendientes de sesiones anteriores
        flushPendingVisitas()
    }

    fun onLocationUpdate(lat: Double, lng: Double, companyId: String,
                         agenteName: String, agenteRol: String, webhookUrl: String) {
        if (clientes.isEmpty()) return
        val clienteActual = clientes.firstOrNull { distanciaMetros(lat, lng, it.lat, it.lng) <= it.radioM }
        when {
            clienteActual != null && visitaActiva == null -> {
                val ahora = System.currentTimeMillis()
                visitaActiva = VisitaActiva(
                    clienteId        = clienteActual.id,
                    clienteNombre    = clienteActual.nombre,
                    clienteCodigo    = clienteActual.codigo,
                    clienteZona      = clienteActual.zona,
                    clienteDireccion = clienteActual.direccion,
                    lat = clienteActual.lat, lng = clienteActual.lng,
                    entradaTs   = ahora,
                    horaEntrada = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ahora))
                )
                Log.d(TAG, "ENTRADA: ${clienteActual.nombre} a las ${visitaActiva!!.horaEntrada}")
            }
            clienteActual != null && visitaActiva != null &&
            clienteActual.id != visitaActiva!!.clienteId -> {
                cerrarVisita(companyId, agenteName, agenteRol)
                val ahora = System.currentTimeMillis()
                visitaActiva = VisitaActiva(
                    clienteId        = clienteActual.id,
                    clienteNombre    = clienteActual.nombre,
                    clienteCodigo    = clienteActual.codigo,
                    clienteZona      = clienteActual.zona,
                    clienteDireccion = clienteActual.direccion,
                    lat = clienteActual.lat, lng = clienteActual.lng,
                    entradaTs   = ahora,
                    horaEntrada = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ahora))
                )
                Log.d(TAG, "CAMBIO DE CLIENTE: ${clienteActual.nombre}")
            }
            clienteActual == null && visitaActiva != null -> cerrarVisita(companyId, agenteName, agenteRol)
            else -> {}
        }
    }

    private fun cerrarVisita(companyId: String, agenteName: String, agenteRol: String) {
        val visita = visitaActiva ?: return
        visitaActiva = null
        val salidaTs    = System.currentTimeMillis()
        val duracionSeg = ((salidaTs - visita.entradaTs) / 1000).toInt()
        if (duracionSeg < MIN_VISITA_SEG) {
            Log.d(TAG, "Visita ignorada (${duracionSeg}s): ${visita.clienteNombre}"); return
        }
        val durMinRedon = Math.round(duracionSeg / 60.0 * 10.0) / 10.0
        val fecha       = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(visita.entradaTs))
        val horaSalida  = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(salidaTs))
        val idVisita    = "vis_${System.currentTimeMillis()}"
        Log.d(TAG, "SALIDA: ${visita.clienteNombre} — ${durMinRedon} min")

        val data = hashMapOf<String, Any>(
            "id"               to idVisita,
            "fecha"            to fecha,
            "horaEntrada"      to visita.horaEntrada,
            "horaSalida"       to horaSalida,
            "entradaTs"        to visita.entradaTs,
            "salidaTs"         to salidaTs,
            "duracionMin"      to durMinRedon,
            "agente"           to agenteName,
            "agenteId"         to agenteName,
            "agenteName"       to agenteName,
            "rol"              to agenteRol,
            "cliente"          to visita.clienteNombre,
            "clienteNombre"    to visita.clienteNombre,
            "clienteId"        to visita.clienteId,
            "codigoCliente"    to visita.clienteCodigo,
            "zona"             to visita.clienteZona,
            "clienteZona"      to visita.clienteZona,
            "direccion"        to visita.clienteDireccion,
            "clienteDireccion" to visita.clienteDireccion,
            "lat"              to visita.lat,
            "lng"              to visita.lng,
            "fechaStr"         to fecha
        )

        // ══════════════════════════════════════════════════════════
        // PASO 1: Guardar en disco local PRIMERO (nunca se pierde)
        // ══════════════════════════════════════════════════════════
        saveToPendingQueue(companyId, idVisita, data)

        // ══════════════════════════════════════════════════════════
        // PASO 2: Intentar enviar a Firebase
        // Si tiene éxito → se elimina de la cola local
        // Si falla → queda en cola para retry automático
        // ══════════════════════════════════════════════════════════
        sendToFirebaseWithRetry(companyId, idVisita, data)
    }

    // ═══════════════════════════════════════════════════════════
    // COLA LOCAL EN DISCO (SharedPreferences + JSON)
    // ═══════════════════════════════════════════════════════════

    /**
     * Guarda una visita en la cola local de SharedPreferences.
     * Esta cola sobrevive reinicios de app y pérdidas de señal.
     */
    private fun saveToPendingQueue(companyId: String, visitaId: String, data: Map<String, Any>) {
        try {
            val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]")

            val obj = JSONObject()
            obj.put("_companyId", companyId)
            obj.put("_visitaId", visitaId)
            for ((k, v) in data) {
                when (v) {
                    is Double -> obj.put(k, v)
                    is Long   -> obj.put(k, v)
                    is Int    -> obj.put(k, v)
                    else      -> obj.put(k, v.toString())
                }
            }
            arr.put(obj)
            prefs.edit().putString(PENDING_KEY, arr.toString()).apply()
            Log.d(TAG, "💾 Visita guardada en disco: $visitaId (cola: ${arr.length()} pendientes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando en cola local: ${e.message}")
        }
    }

    /**
     * Elimina una visita de la cola local (llamado al confirmar Firebase OK).
     */
    private fun removeFromPendingQueue(visitaId: String) {
        try {
            val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]")
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("_visitaId") != visitaId) {
                    newArr.put(obj)
                }
            }
            prefs.edit().putString(PENDING_KEY, newArr.toString()).apply()
            Log.d(TAG, "🗑️ Visita eliminada de cola: $visitaId (quedan: ${newArr.length()})")
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando de cola: ${e.message}")
        }
    }

    /**
     * Intenta enviar una visita a Firebase.
     * Si tiene éxito, la elimina de la cola local.
     * Si falla, queda en cola para el próximo flush.
     */
    private fun sendToFirebaseWithRetry(companyId: String, visitaId: String, data: Map<String, Any>) {
        try {
            FirebaseDatabase.getInstance()
                .getReference("companies/$companyId/visitas/$visitaId")
                .setValue(data)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Visita en Firebase: $visitaId")
                    removeFromPendingQueue(visitaId)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Firebase FAIL (queda en cola): $visitaId — ${e.message}")
                    // No hacer nada: ya está en la cola, flushPendingVisitas lo reintentará
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando visita: ${e.message}")
        }
    }

    /**
     * Reintenta enviar TODAS las visitas pendientes en la cola local.
     * Llamar desde:
     * - VisitaManager.iniciar() → al arrancar el tracking
     * - TrackingService.onNetworkRecovered() → cuando vuelve la señal
     */
    fun flushPendingVisitas() {
        try {
            val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]")
            if (arr.length() == 0) return

            Log.d(TAG, "🔄 Flush: ${arr.length()} visitas pendientes en cola")

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val companyId = obj.optString("_companyId", "")
                val visitaId = obj.optString("_visitaId", "")
                if (companyId.isEmpty() || visitaId.isEmpty()) continue

                // Reconstruir data map sin los campos internos _companyId, _visitaId
                val data = hashMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.startsWith("_")) continue
                    val v = obj.get(k)
                    data[k] = v
                }

                sendToFirebaseWithRetry(companyId, visitaId, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en flush: ${e.message}")
        }
    }

    fun detener(companyId: String, agenteName: String, agenteRol: String, webhookUrl: String) {
        if (visitaActiva != null) cerrarVisita(companyId, agenteName, agenteRol)
        clientesListener?.let { clientesRef?.removeEventListener(it) }
        clientesListener = null
        Log.d(TAG, "VisitaManager detenido")
    }

    private fun distanciaMetros(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
