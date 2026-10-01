package com.greenfleet.app

import android.app.Application
import androidx.room.Room
import com.greenfleet.app.data.HistoryDatabase
import com.greenfleet.app.data.SecureSettings

class GreenFleetApplication : Application() {
    val settings by lazy { SecureSettings(this) }
    val database by lazy { Room.databaseBuilder(this, HistoryDatabase::class.java, "greenfleet_stats.db").build() }
}

