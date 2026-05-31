package com.mesh51.app

import android.app.Application
import androidx.multidex.MultiDex
import android.content.Context
import timber.log.Timber

class MeshApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Multidex нужен для API < 21, но на 21+ ставим для надёжности
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Timber: в debug-сборке логируем всё, в release — только ошибки
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("MeshApplication started, SDK=${android.os.Build.VERSION.SDK_INT}")
    }
}
