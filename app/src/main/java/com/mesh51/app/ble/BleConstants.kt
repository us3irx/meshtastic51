package com.mesh51.app.ble

import java.util.UUID

/**
 * UUID сервисов и характеристик Meshtastic BLE протокола.
 *
 * Официальный источник:
 * https://github.com/meshtastic/Meshtastic-Android/blob/main/core/ble/
 *
 * Протокол работает так:
 * 1. Подключаемся к устройству по BLE
 * 2. Находим MESH_SERVICE
 * 3. Читаем FROMRADIO_CHARACTERISTIC — получаем пакеты от устройства
 * 4. Пишем в TORADIO_CHARACTERISTIC — отправляем пакеты на устройство
 * 5. Подписываемся на FROMNUM_CHARACTERISTIC (notify) — уведомления о новых данных
 *
 * Данные в характеристиках — это protobuf-сериализованные объекты ToRadio/FromRadio.
 *
 * MTU: по умолчанию 23 байта (BLE 4.0). Запрашиваем 512 для больших пакетов.
 * Если устройство не поддерживает — продолжаем с 23 байтами (фрагментация).
 */
object BleConstants {

    // ─────────────────────────────────────────────────────────────
    // Основной сервис Meshtastic
    // ─────────────────────────────────────────────────────────────

    /** UUID основного BLE сервиса Meshtastic */
    // Поддерживаем оба UUID — старый и новый формат прошивок Meshtastic
    val MESH_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    val MESH_SERVICE_UUID_OLD: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5d6571045a21")

    // ─────────────────────────────────────────────────────────────
    // Характеристики сервиса
    // ─────────────────────────────────────────────────────────────

    /**
     * FROMRADIO — читаем данные ОТ устройства.
     * Каждое чтение возвращает один protobuf пакет (FromRadio).
     * Читать нужно в цикле пока не вернёт пустой массив — значит буфер пуст.
     * Properties: READ
     */
    val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")

    /**
     * TORADIO — пишем данные НА устройство.
     * Каждая запись — один protobuf пакет (ToRadio).
     * Properties: WRITE
     */
    val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /**
     * FROMNUM — счётчик непрочитанных пакетов.
     * Когда устройство хочет отправить данные — оно инкрементирует этот счётчик.
     * Мы подписываемся на notify и при изменении идём читать FROMRADIO.
     * Properties: READ, NOTIFY
     */
    val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    /**
     * LOGRADIO — логи отладки с устройства (опционально).
     * Properties: READ, NOTIFY
     */
    val LOGRADIO_UUID: UUID = UUID.fromString("6c6fd238-78fa-436b-aacf-15c5be1ef2e2")

    // ─────────────────────────────────────────────────────────────
    // Стандартный UUID для Client Characteristic Configuration Descriptor
    // Нужен чтобы включить уведомления (notify) на характеристике
    // ─────────────────────────────────────────────────────────────

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ─────────────────────────────────────────────────────────────
    // MTU и тайминги
    // ─────────────────────────────────────────────────────────────

    /** Запрашиваемый MTU. Устройство может вернуть меньше — это нормально */
    const val REQUESTED_MTU = 512

    /** Минимальный гарантированный MTU по BLE 4.0 */
    const val MIN_MTU = 23

    /** Таймаут подключения к устройству, мс */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /** Таймаут одной GATT операции (read/write), мс */
    const val GATT_OPERATION_TIMEOUT_MS = 5_000L

    /** Задержка между GATT операциями на Android 5.x (нужна из-за багов стека) */
    const val GATT_WRITE_DELAY_MS = 200L

    /** Максимум попыток переподключения перед тем как сдаться */
    const val MAX_RECONNECT_ATTEMPTS = 5

    /** Базовая задержка перед переподключением (удваивается с каждой попыткой) */
    const val RECONNECT_BASE_DELAY_MS = 1_000L

    // ─────────────────────────────────────────────────────────────
    // Scan settings
    // ─────────────────────────────────────────────────────────────

    /** Время сканирования BLE устройств, мс */
    const val SCAN_PERIOD_MS = 15_000L

    /** Имя устройства в BLE рекламе (обычно начинается с "Meshtastic") */
    const val MESHTASTIC_DEVICE_NAME_PREFIX = "Meshtastic"
}
