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
        const val RADIO_MIN_M = 10.0        // ✅ Bajado de 50 a 10 para respetar geocercas pequeñas
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

    // ── referencia de debug (se setea en iniciar()) ──
    private var debugRef: DatabaseReference? = null
    private var currentCompanyId: String = ""
    private var currentAgenteName: String = ""

    // ═══════════════════════════════════════════════════════════
    // HELPER: Escribir log en Firebase  companies/{id}/debug_logs
    // ═══════════════════════════════════════════════════════════
    private fun fireLog(nivel: String, mensaje: String, extra: Map<String, Any> = emptyMap()) {
        Log.d(TAG, "[$nivel] $mensaje")
        try {
            val ref = debugRef ?: return
            val ts = System.currentTimeMillis()
            val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))
            val payload = hashMapOf<String, Any>(
                "ts"      to ts,
                "hora"    to hora,
                "nivel"   to nivel,
                "agente"  to currentAgenteName,
                "msg"     to mensaje
            )
            payload.putAll(extra)
            ref.push().setValue(payload)
        } catch (e: Exception) {
            Log.e(TAG, "fireLog error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // INICIAR
    // ═══════════════════════════════════════════════════════════
    fun iniciar(companyId: String) {
        currentCompanyId = companyId
        debugRef = FirebaseDatabase.getInstance()
            .getReference("companies/$companyId/debug_logs")

        fireLog("INFO", "VisitaManager iniciado", mapOf("companyId" to companyId))

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
                    // ✅ Ahora respeta geocercas desde 10m
                    val radioFinal = if (radio < RADIO_MIN_M) RADIO_DEFAULT_M else radio
                    lista.add(Cliente(
                        id = child.key ?: continue,
                        nombre    = data["nombre"]?.toString() ?: "",
                        codigo    = data["codigo"]?.toString() ?: "",
                        zona      = data["zona"]?.toString() ?: "",
                        direccion = data["direccion"]?.toString() ?: "",
                        lat = lat, lng = lng,
                        radioM = radioFinal
                    ))
                }
                clientes = lista
                fireLog("INFO", "${clientes.size} clientes cargados en memoria",
                    mapOf("total" to clientes.size))
            }
            override fun onCancelled(error: DatabaseError) {
                fireLog("ERROR", "Error cargando clientes: ${error.message}")
            }
        }
        clientesRef?.addValueEventListener(clientesListener!!)
        flushPendingVisitas()
    }

    // ═══════════════════════════════════════════════════════════
    // ON LOCATION UPDATE
    // ═══════════════════════════════════════════════════════════
    fun onLocationUpdate(lat: Double, lng: Double, companyId: String,
                         agenteName: String, agenteRol: String, webhookUrl: String) {

        currentAgenteName = agenteName

        // ── DIAGNÓSTICO: sin clientes cargados ──
        if (clientes.isEmpty()) {
            fireLog("WARN", "GPS update ignorado — 0 clientes en memoria",
                mapOf("lat" to lat, "lng" to lng))
            return
        }

        // ── Calcular distancia a cada cliente para diagnóstico ──
        val clienteActual = clientes.firstOrNull { distanciaMetros(lat, lng, it.lat, it.lng) <= it.radioM }

        // ── Log del cliente más cercano (cada update, útil para debug) ──
        val masCercano = clientes.minByOrNull { distanciaMetros(lat, lng, it.lat, it.lng) }
        if (masCercano != null) {
            val distancia = distanciaMetros(lat, lng, masCercano.lat, masCercano.lng)
            // Solo logueamos cuando estamos a menos de 200m de algún cliente (evita spam)
            if (distancia < 200) {
                fireLog("GPS", "Más cercano: ${masCercano.nombre} — ${distancia.toInt()}m (geocerca: ${masCercano.radioM.toInt()}m)",
                    mapOf(
                        "clienteNombre" to masCercano.nombre,
                        "distanciaM"    to distancia.toInt(),
                        "geocercaM"     to masCercano.radioM.toInt(),
                        "dentroGeocerca" to (clienteActual?.id == masCercano.id),
                        "agentelat"     to lat,
                        "agenteLng"     to lng
                    ))
            }
        }

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
                fireLog("ENTRADA", "Entró a geocerca: ${clienteActual.nombre}",
                    mapOf(
                        "clienteId"     to clienteActual.id,
                        "clienteNombre" to clienteActual.nombre,
                        "horaEntrada"   to visitaActiva!!.horaEntrada,
                        "geocercaM"     to clienteActual.radioM.toInt()
                    ))
            }
            clienteActual != null && visitaActiva != null &&
            clienteActual.id != visitaActiva!!.clienteId -> {
                fireLog("INFO", "Cambio de cliente: ${visitaActiva!!.clienteNombre} → ${clienteActual.nombre}")
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
            }
            clienteActual == null && visitaActiva != null -> {
                fireLog("SALIDA", "Salió de geocerca: ${visitaActiva!!.clienteNombre}",
                    mapOf("clienteNombre" to visitaActiva!!.clienteNombre))
                cerrarVisita(companyId, agenteName, agenteRol)
            }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CERRAR VISITA
    // ═══════════════════════════════════════════════════════════
    private fun cerrarVisita(companyId: String, agenteName: String, agenteRol: String) {
        val visita = visitaActiva ?: return
        visitaActiva = null
        val salidaTs    = System.currentTimeMillis()
        val duracionSeg = ((salidaTs - visita.entradaTs) / 1000).toInt()

        // ── DIAGNÓSTICO: visita muy corta ──
        if (duracionSeg < MIN_VISITA_SEG) {
            fireLog("IGNORADA", "Visita demasiado corta — NO registrada",
                mapOf(
                    "clienteNombre" to visita.clienteNombre,
                    "duracionSeg"   to duracionSeg,
                    "minimoSeg"     to MIN_VISITA_SEG,
                    "razon"         to "Duración ${duracionSeg}s < mínimo ${MIN_VISITA_SEG}s"
                ))
            return
        }

        val durMinRedon = Math.round(duracionSeg / 60.0 * 10.0) / 10.0
        val fecha       = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(visita.entradaTs))
        val horaSalida  = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(salidaTs))
        val idVisita    = "vis_${System.currentTimeMillis()}"

        fireLog("REGISTRADA", "✅ Visita válida — guardando en Firebase",
            mapOf(
                "visitaId"      to idVisita,
                "clienteNombre" to visita.clienteNombre,
                "duracionMin"   to durMinRedon,
                "horaEntrada"   to visita.horaEntrada,
                "horaSalida"    to horaSalida
            ))

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

        saveToPendingQueue(companyId, idVisita, data)
        sendToFirebaseWithRetry(companyId, idVisita, data)
    }

    // ═══════════════════════════════════════════════════════════
    // COLA LOCAL EN DISCO
    // ═══════════════════════════════════════════════════════════
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

    private fun removeFromPendingQueue(visitaId: String) {
        try {
            val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]")
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("_visitaId") != visitaId) newArr.put(obj)
            }
            prefs.edit().putString(PENDING_KEY, newArr.toString()).apply()
            Log.d(TAG, "🗑️ Visita eliminada de cola: $visitaId (quedan: ${newArr.length()})")
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando de cola: ${e.message}")
        }
    }

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
                    fireLog("ERROR", "❌ Firebase FAIL — visita queda en cola local",
                        mapOf("visitaId" to visitaId, "error" to (e.message ?: "desconocido")))
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando visita: ${e.message}")
        }
    }

    fun flushPendingVisitas() {
        try {
            val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(PENDING_KEY, "[]") ?: "[]")
            if (arr.length() == 0) return
            fireLog("INFO", "Flush: ${arr.length()} visitas pendientes en cola",
                mapOf("pendientes" to arr.length()))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cId = obj.optString("_companyId", "")
                val vId = obj.optString("_visitaId", "")
                if (cId.isEmpty() || vId.isEmpty()) continue
                val data = hashMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.startsWith("_")) continue
                    data[k] = obj.get(k)
                }
                sendToFirebaseWithRetry(cId, vId, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en flush: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DETENER
    // ═══════════════════════════════════════════════════════════
    fun detener(companyId: String, agenteName: String, agenteRol: String, webhookUrl: String) {
        if (visitaActiva != null) cerrarVisita(companyId, agenteName, agenteRol)
        clientesListener?.let { clientesRef?.removeEventListener(it) }
        clientesListener = null
        fireLog("INFO", "VisitaManager detenido")
        Log.d(TAG, "VisitaManager detenido")
    }

    // ═══════════════════════════════════════════════════════════
    // HAVERSINE
    // ═══════════════════════════════════════════════════════════
    private fun distanciaMetros(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
