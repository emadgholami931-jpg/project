package com.vazheyar.app

import android.app.Application
import com.vazheyar.app.ai.EnrichmentScheduler
import com.vazheyar.app.data.AppDatabase

class VazheYarApp : Application() {
    val database by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        EnrichmentScheduler.enqueue(this)
    }
}
