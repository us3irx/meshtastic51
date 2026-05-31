package com.mesh51.app.ble

import android.bluetooth.BluetoothDevice

/**
 * Состояния BLE соединения.
 * Используем sealed class чтобы UI мог exhaustive-обрабатывать каждое состояние.
 */
sealed class ConnectionState {
    /** Не подключены, не сканируем */
    object Disconnected : ConnectionState()

    /** Идёт сканирование BLE */
    object Scanning : ConnectionState()

    /** Подключаемся к устройству */
    data class Connecting(val address: String) : ConnectionState()

    /** Ищем сервисы на устройстве (после подключения) */
    object DiscoveringServices : ConnectionState()

    /** Успешно подключены */
    data class Connected(val address: String, val name: String) : ConnectionState()

    /** Потеряли соединение, пытаемся переподключиться */
    data class Reconnecting(val attempt: Int) : ConnectionState()

    /** Ошибка (исчерпали попытки или критическая ошибка) */
    data class Error(val message: String) : ConnectionState()
}

/**
 * BLE устройство найденное при сканировании.
 */
data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val device: BluetoothDevice
) {
    /** Уровень сигнала как текст для UI */
    val rssiLabel: String get() = "$rssi dBm"

    /** Грубая оценка качества сигнала */
    val signalQuality: SignalQuality get() = when {
        rssi >= -60 -> SignalQuality.EXCELLENT
        rssi >= -70 -> SignalQuality.GOOD
        rssi >= -80 -> SignalQuality.FAIR
        else -> SignalQuality.POOR
    }

    enum class SignalQuality { EXCELLENT, GOOD, FAIR, POOR }
}
