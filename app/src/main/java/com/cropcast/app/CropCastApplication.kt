package com.cropcast.app

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class CropCastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { FirebaseDatabase.getInstance().setPersistenceEnabled(true) }
    }
}
