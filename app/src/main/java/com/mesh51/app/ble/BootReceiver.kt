package com.mesh51.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * BootReceiver — перезапускает MeshService после перезагрузки телефона.
 *
 * Особенность Flyme OS (Meizu):
 * Flyme иногда задерживает или вообще не отправляет BOOT_COMPLETED другим приложениям.
 * Поэтому также слушаем MY_PACKAGE_REPLACED (обновление APK) — это надёжнее.
 *
 * Для полного решения проблемы с Flyme нужно:
 * 1. Добавить приложение в "Автозапуск" в настройках → Управление приложениями
 * 2. Добавить в "Белый список" батареи
 * 3. Показать пользователю инструкцию при первом запуске
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("BootReceiver: ${intent.action} — starting MeshService")
                startMeshService(context)
            }
        }
    }

    private fun startMeshService(context: Context) {
        val serviceIntent = Intent(context, MeshService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start MeshService from BootReceiver")
        }
    }
}
