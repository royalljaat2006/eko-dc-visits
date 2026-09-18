package com.eko.dcvisits.app

import android.app.Application
import com.eko.dcvisits.app.data.sync.SyncWorker
import com.eko.dcvisits.app.di.ServiceLocator

class DcVisitsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Backstop drain so a multi-day offline backlog clears on its own.
        SyncWorker.schedulePeriodic(this)
    }
}
