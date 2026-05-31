package com.mesh51.app.ble

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mesh51.app.R
import com.mesh51.app.ui.MainActivity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MeshService — Foreground Service для постоянного BLE соединения с Meshtastic устройством.
 *
 * Почему Foreground Service, а не просто фоновый?
 * - Flyme OS (Meizu) убивает фоновые процессы через 5-10 минут
 * - Foreground Service с уведомлением практически невозможно убить системе
 * - Пользователь видит что приложение активно работает
 *
 * Архитектура:
 * - UI (Activity/Fragment) привязываются через [MeshBinder]
 * - BleManager живёт внутри сервиса и переживает ротацию экрана
 * - Все данные передаются через Flow/StateFlow
 *
 * Важно для Flyme OS:
 * - Нужно добавить приложение в "белый список" в настройках батареи
 * - BOOT_COMPLETED receiver перезапускает сервис после перезагрузки
 */
class MeshService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mesh_service_channel"

        // Интенты для управления сервисом
        const val ACTION_CONNECT = "com.mesh51.app.CONNECT"
        const val ACTION_DISCONNECT = "com.mesh51.app.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    // ─────────────────────────────────────────────────────────────
    // BleManager — живёт всё время пока живёт сервис
    // ─────────────────────────────────────────────────────────────

    val bleManager: BleManager by lazy { BleManager(applicationContext) }

    // ─────────────────────────────────────────────────────────────
    // Binder для связи с UI
    // ─────────────────────────────────────────────────────────────

    inner class MeshBinder : Binder() {
        fun getService(): MeshService = this@MeshService
    }

    private val binder = MeshBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Timber.i("MeshService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Готов к подключению"))
        observeConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_STICKY
                connectToDevice(address)
            }
            ACTION_DISCONNECT -> bleManager.disconnect()
        }

        // START_STICKY: Android перезапустит сервис если его убьёт система
        // (но не Flyme — там нужен отдельный механизм через JobScheduler)
        return START_STICKY
    }

    override fun onDestroy() {
        bleManager.close()
        super.onDestroy()
        Timber.i("MeshService destroyed")
    }

    // ─────────────────────────────────────────────────────────────
    // Методы управления
    // ─────────────────────────────────────────────────────────────

    fun connectToDevice(address: String) {
        val device = bleManager.run {
            // Получаем BluetoothDevice по адресу из системы
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.getRemoteDevice(address)
        } ?: run {
            Timber.e("connectToDevice: device $address not found")
            return
        }
        lifecycleScope.launch {
            bleManager.connect(device)
        }
    }

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    suspend fun sendPacket(data: ByteArray) = bleManager.sendPacket(data)

    // ─────────────────────────────────────────────────────────────
    // Уведомление (обязательно для Foreground Service)
    // ─────────────────────────────────────────────────────────────

    private fun observeConnectionState() {
        lifecycleScope.launch {
            bleManager.connectionState.collect { state ->
                val text = when (state) {
                    is ConnectionState.Disconnected -> "Не подключено"
                    is ConnectionState.Scanning -> "Поиск устройств..."
                    is ConnectionState.Connecting -> "Подключение..."
                    is ConnectionState.DiscoveringServices -> "Инициализация..."
                    is ConnectionState.Connected -> "Подключено: ${state.name}"
                    is ConnectionState.Reconnecting -> "Переподключение (${state.attempt})..."
                    is ConnectionState.Error -> "Ошибка: ${state.message}"
                }
                updateNotification(text)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Meshtastic BLE сервис",
                NotificationManager.IMPORTANCE_LOW // LOW = без звука, без вибрации
            ).apply {
                description = "Поддержание BLE соединения с устройством"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meshtastic")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_mesh_notification) // нужно создать
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
