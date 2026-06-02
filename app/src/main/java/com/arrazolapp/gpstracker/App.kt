package com.arrazolapp.gpstracker

import android.app.Application
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

/**
 * Clase Application que habilita la persistencia en disco de Firebase.
 *
 * EFECTO: todas las escrituras a Firebase se guardan primero en disco local.
 * Si el celular pierde señal, los datos quedan en cola local y se envían
 * automáticamente cuando vuelve la conexión — incluso si la app se reinicia.
 *
 * IMPORTANTE: setPersistenceEnabled() DEBE llamarse antes de cualquier
 * otra operación de Firebase. Por eso va en Application.onCreate().
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            Log.d("Ubify", "✅ Firebase disk persistence ENABLED")
        } catch (e: Throwable) {
            // Capturar Throwable (no solo Exception) porque Firebase puede
            // lanzar DatabaseException que a veces no se atrapa con Exception
            Log.w("Ubify", "Firebase persistence skip: ${e.message}")
        }
    }
}
