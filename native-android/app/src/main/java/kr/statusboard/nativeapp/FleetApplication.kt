package kr.statusboard.nativeapp

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

class FleetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Prepared from the existing public Firebase client configuration at build time.
        val config = JSONObject(assets.open("firebase-client.json").bufferedReader().use { it.readText() })
        FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
            .setApiKey(config.getString("apiKey"))
            .setApplicationId(config.getString("appId"))
            .setProjectId(config.getString("projectId"))
            .setDatabaseUrl(config.getString("databaseURL"))
            .build())
    }
}
