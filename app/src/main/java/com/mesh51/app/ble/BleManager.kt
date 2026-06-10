package com.mesh51.app.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BleManager — низкоуровневый менеджер BLE для Android 5.1+.
 *
 * Особенности реализации под Android 5.x / Meizu Flyme:
 *
 * 1. GATT операции строго последовательные — Android 5.x не поддерживает параллельные
 *    read/write. Используем [gattOperationChannel] как очередь + мьютекс.
 *
 * 2. После каждого write нужна задержка ~200ms иначе стек может зависнуть.
 *
 * 3. BluetoothGatt.close() нужно вызывать ТОЛЬКО из UI потока или Handler,
 *    иначе на MTK чипах возможен deadlock.
 *
 * 4. При потере соединения (status 133 = GATT_ERROR) — стандартная ситуация
 *    на Android 5.x — делаем переподключение с экспоненциальной задержкой.
 *
 * 5. На некоторых прошивках Flyme сканирование работает только если BT
 *    был явно включён пользователем (не программно).
 */
class BleManager(private val context: Context) {

    // ─────────────────────────────────────────────────────────────
    // Публичное состояние (Flow)
    // ─────────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableSharedFlow<BleDevice>(replay = 0, extraBufferCapacity = 64)
    val scanResults: SharedFlow<BleDevice> = _scanResults.asSharedFlow()

    /** Входящие пакеты от устройства (сырые байты protobuf) */
    private val _incomingPackets = MutableSharedFlow<ByteArray>(replay = 100, extraBufferCapacity = 256)
    val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    // ─────────────────────────────────────────────────────────────
    // Внутренние поля
    // ─────────────────────────────────────────────────────────────

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val isScanning = AtomicBoolean(false)
    private var scanJob: Job? = null

    /**
     * Канал для синхронизации GATT операций.
     * Каждая операция (read/write/descriptor write) отправляет результат сюда.
     * Следующая операция стартует только после получения результата.
     */
    private val gattResultChannel = Channel<GattResult>(Channel.UNLIMITED)
    private val gattMutex = kotlinx.coroutines.sync.Mutex()

    /** Текущий MTU (может быть изменён после negotiation) */
    private var currentMtu = BleConstants.MIN_MTU

    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    // ─────────────────────────────────────────────────────────────
    // Публичный API
    // ─────────────────────────────────────────────────────────────

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Запуск сканирования BLE устройств.
     * Результаты приходят через [scanResults].
     * Автоматически останавливается через [BleConstants.SCAN_PERIOD_MS].
     */
    fun startScan() {
        if (!isBluetoothEnabled()) {
            Timber.w("startScan: Bluetooth not enabled")
            return
        }
        if (isScanning.getAndSet(true)) {
            Timber.d("startScan: already scanning")
            return
        }

        Timber.i("BLE scan started")
        _connectionState.value = ConnectionState.Scanning

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.e("startScan: BluetoothLeScanner is null")
            isScanning.set(false)
            return
        }

        // На Android 5.1 фильтр по ServiceUUID ломает сканирование.
        // Сканируем без фильтров, все BLE устройства.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            Timber.i("BLE scan started (no filters)")
        } catch (e: Exception) {
            Timber.e(e, "startScan failed, trying legacy")
            try {
                scanner.startScan(scanCallback)
            } catch (e2: Exception) {
                Timber.e(e2, "legacy scan failed")
                isScanning.set(false)
                _connectionState.value = ConnectionState.Error("Сканирование недоступно")
                return
            }
        }

        // Автоостановка через SCAN_PERIOD_MS
        scanJob = scope.launch {
            delay(BleConstants.SCAN_PERIOD_MS)
            stopScan()
        }
    }

    fun stopScan() {
        if (!isScanning.getAndSet(false)) return
        scanJob?.cancel()
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Timber.e(e, "stopScan error")
        }
        Timber.i("BLE scan stopped")
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /**
     * Подключение к устройству по MAC-адресу.
     * Используем [autoConnect = false] для быстрого прямого подключения.
     * autoConnect = true медленнее, но надёжнее при слабом сигнале —
     * можно сделать опцией в настройках.
     */
    suspend fun connect(device: BluetoothDevice) {
        if (_connectionState.value is ConnectionState.Connected) {
            Timber.w("connect: already connected")
            return
        }
        stopScan()
        connectedDevice = device
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Connecting(device.address)

        withContext(Dispatchers.Main) {
            // connectGatt ОБЯЗАТЕЛЬНО вызывать из UI потока на Android 5.x!
            // Иначе callback может никогда не прийти.
            bluetoothGatt = device.connectGatt(
                context,
                false, // autoConnect = false = быстрое прямое подключение
                gattCallback
            )
        }
    }

    /**
     * Отправка сырых байт на устройство (в TORADIO характеристику).
     * Данные должны быть protobuf-сериализованным объектом ToRadio.
     */
    suspend fun sendPacket(data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: run {
            Timber.e("sendPacket: not connected")
            return false
        }
        val service = gatt.getService(BleConstants.MESH_SERVICE_UUID) ?: gatt.getService(BleConstants.MESH_SERVICE_UUID_OLD) ?: run {
            Timber.e("sendPacket: MESH_SERVICE not found")
            return false
        }
        val characteristic = service.getCharacteristic(BleConstants.TORADIO_UUID) ?: run {
            Timber.e("sendPacket: TORADIO characteristic not found")
            return false
        }

        // BLE 4.0 MTU = 23 байта (20 полезных). Если пакет больше — фрагментируем.
        val chunks = data.chunked(currentMtu - 3)

        for (chunk in chunks) {
            val success = writeCharacteristicSync(gatt, characteristic, chunk)
            if (!success) {
                Timber.e("sendPacket: write failed")
                return false
            }
            // Задержка между чанками — критично для Android 5.x
            if (chunks.size > 1) delay(BleConstants.GATT_WRITE_DELAY_MS)
        }
        return true
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectAttempts = Int.MAX_VALUE // Запрещаем переподключение
        mainHandler.post {
            try {
                bluetoothGatt?.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "disconnect error")
            }
        }
    }

    fun close() {
        disconnect()
        scope.cancel()
        mainHandler.postDelayed({
            try {
                bluetoothGatt?.close()
                bluetoothGatt = null
            } catch (e: Exception) {
                Timber.e(e, "close error")
            }
        }, 500)
    }

    // ─────────────────────────────────────────────────────────────
    // Приватные методы
    // ─────────────────────────────────────────────────────────────

    /**
     * Синхронная запись характеристики с ожиданием callback.
     * На Android 5.x нельзя делать следующий write до получения onCharacteristicWrite.
     */
    private suspend fun writeCharacteristicSync(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return gattMutex.withLock {
            withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                // Дренируем старые результаты
                while (!gattResultChannel.isEmpty) gattResultChannel.tryReceive()
                characteristic.value = data
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                withContext(Dispatchers.Main) { gatt.writeCharacteristic(characteristic) }
                val result = gattResultChannel.receive()
                result.success
            }
        }
    }

    /**
     * Читаем все доступные пакеты из FROMRADIO (пока не придёт пустой ответ).
     * Вызывается когда FROMNUM изменился (notify).
     */
    private fun readAllFromRadio() {
        scope.launch {
            val gatt = bluetoothGatt ?: return@launch
            val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                ?: gatt.getService(BleConstants.MESH_SERVICE_UUID_OLD)
                ?: return@launch
            val characteristic = service.getCharacteristic(BleConstants.FROMRADIO_UUID)
                ?: return@launch

            Timber.i("Reading FromRadio...")
            var emptyCount = 0
            var totalPackets = 0

            // Читаем до 50 пакетов или пока не придёт пустой ответ
            repeat(50) {
                // Дренируем канал перед чтением
                while (!gattResultChannel.isEmpty) gattResultChannel.tryReceive()

                withContext(Dispatchers.Main) { gatt.readCharacteristic(characteristic) }

                val result = withTimeoutOrNull(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                    gattResultChannel.receive()
                }

                if (result == null || !result.success || result.data == null || result.data.isEmpty()) {
                    emptyCount++
                    if (emptyCount >= 2) return@repeat // Два пустых подряд — буфер пуст
                    delay(200)
                    return@repeat
                }

                emptyCount = 0
                totalPackets++
                Timber.i("FromRadio packet #$totalPackets: ${result.data.size} bytes")
                _incomingPackets.emit(result.data)
                delay(100)
            }
            Timber.i("ReadAllFromRadio done: $totalPackets packets")
        }
    }

    /**
     * Включение уведомлений на FROMNUM характеристике.
     * Стандартная процедура: записываем CCCD дескриптор со значением ENABLE_NOTIFICATION_VALUE.
     */
    private fun enableFromNumNotifications(gatt: BluetoothGatt) {
        scope.launch {
            val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                ?: gatt.getService(BleConstants.MESH_SERVICE_UUID_OLD)
                ?: run { Timber.e("No Meshtastic service"); return@launch }

            // Шаг 1: включаем notify на FROMNUM без ожидания результата
            val fromNumChar = service.getCharacteristic(BleConstants.FROMNUM_UUID)
            if (fromNumChar != null) {
                withContext(Dispatchers.Main) {
                    gatt.setCharacteristicNotification(fromNumChar, true)
                }
                delay(300)
                val descriptor = fromNumChar.getDescriptor(BleConstants.CCCD_UUID)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    withContext(Dispatchers.Main) { gatt.writeDescriptor(descriptor) }
                    delay(1000) // Ждём пока onDescriptorWrite отработает
                    // Дренируем все результаты от writeDescriptor
                    while (!gattResultChannel.isEmpty) gattResultChannel.tryReceive()
                    Timber.i("FROMNUM notify sent")
                }
            }

            // Шаг 2: UI — подключены
            _connectionState.value = ConnectionState.Connected(
                connectedDevice!!.address,
                connectedDevice!!.name ?: "Unknown"
            )

            // Шаг 3: отправляем want_config через WRITE_NO_RESPONSE
            // Не требует onCharacteristicWrite callback — просто пишем
            delay(500)
            val toRadioChar = service.getCharacteristic(BleConstants.TORADIO_UUID) ?: run {
                Timber.e("TORADIO not found"); return@launch
            }

            val configId = (System.currentTimeMillis() / 1000).toInt()
            val buf = mutableListOf(0x18.toByte())
            var v = configId
            while (v and 0x7F.inv() != 0) {
                buf.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
            buf.add((v and 0x7F).toByte())

            // Отправляем want_config и ждём ответа
            while (!gattResultChannel.isEmpty) gattResultChannel.tryReceive()
            toRadioChar.value = buf.toByteArray()
            toRadioChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            var writeOk = false
            withContext(Dispatchers.Main) {
                writeOk = gatt.writeCharacteristic(toRadioChar)
            }
            Timber.i("want_config id=$configId writeOk=$writeOk")

            if (writeOk) {
                val ack = withTimeoutOrNull(5000) { gattResultChannel.receive() }
                Timber.i("want_config ack: ${ack?.success}")
                // Ждём FROMNUM notify от ноды (нода скажет когда данные готовы)
                // Но также делаем принудительное чтение через 2 секунды
                delay(2000)
                readAllFromRadio()
            } else {
                Timber.e("want_config: writeCharacteristic returned false")
                // Fallback: NO_RESPONSE
                toRadioChar.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                withContext(Dispatchers.Main) { gatt.writeCharacteristic(toRadioChar) }
                Timber.i("want_config fallback NO_RESPONSE sent")
                delay(2000)
                readAllFromRadio()
            }
        }
    }


    private fun scheduleReconnect() {
        if (reconnectAttempts >= BleConstants.MAX_RECONNECT_ATTEMPTS) {
            Timber.e("Max reconnect attempts reached")
            _connectionState.value = ConnectionState.Error("Connection failed after ${BleConstants.MAX_RECONNECT_ATTEMPTS} attempts")
            return
        }

        val delay = BleConstants.RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempts) // Экспоненциальная задержка
        reconnectAttempts++
        Timber.i("Reconnect attempt $reconnectAttempts in ${delay}ms")

        reconnectJob = scope.launch {
            delay(delay)
            connectedDevice?.let { device ->
                _connectionState.value = ConnectionState.Connecting(device.address)
                withContext(Dispatchers.Main) {
                    bluetoothGatt?.close()
                    bluetoothGatt = device.connectGatt(context, false, gattCallback)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // BLE Scan Callback
    // ─────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"
            // Показываем все BLE устройства (фильтр по UUID ломает Android 5.1)
            // Meshtastic устройства обычно называются "Meshtastic_XXXX"
            scope.launch {
                _scanResults.emit(
                    BleDevice(
                        address = device.address,
                        name = name,
                        rssi = result.rssi,
                        device = device
                    )
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed: errorCode=$errorCode")
            isScanning.set(false)
            _connectionState.value = ConnectionState.Error("Scan failed: code $errorCode")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // GATT Callback — все события от BLE стека
    // ─────────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.d("onConnectionStateChange: status=$status, newState=$newState")

            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    Timber.i("GATT connected, checking bond state...")
                    _connectionState.value = ConnectionState.DiscoveringServices
                    val bondState = gatt.device.bondState
                    Timber.i("Bond state: $bondState (NONE=10, BONDING=11, BONDED=12)")
                    if (bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
                        // Инициируем сопряжение — появится запрос PIN на телефоне
                        Timber.i("Starting bonding...")
                        gatt.device.createBond()
                        // discoverServices после bonding — ждём дольше
                        mainHandler.postDelayed({
                            gatt.discoverServices()
                        }, 4000)
                    } else {
                        mainHandler.postDelayed({
                            gatt.discoverServices()
                        }, 1500)
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("GATT disconnected, status=$status")
                    // status 133 = GATT_ERROR — самая частая ошибка на Android 5.x
                    // Означает разрыв соединения или ошибку стека
                    val isExpected = reconnectAttempts >= BleConstants.MAX_RECONNECT_ATTEMPTS
                    if (isExpected) {
                        _connectionState.value = ConnectionState.Disconnected
                    } else {
                        _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts + 1)
                        scheduleReconnect()
                    }
                    // Закрываем старый GATT объект — важно для освобождения ресурсов
                    mainHandler.postDelayed({ gatt.close() }, 500)
                }

                status != BluetoothGatt.GATT_SUCCESS -> {
                    Timber.e("GATT error: status=$status")
                    _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts + 1)
                    scheduleReconnect()
                    mainHandler.postDelayed({ gatt.close() }, 500)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Service discovery failed: status=$status")
                scheduleReconnect()
                return
            }

            // Логируем ВСЕ найденные сервисы для диагностики
            Timber.i("=== Services discovered (${gatt.services.size} total) ===")
            gatt.services.forEach { svc ->
                Timber.i("  Service: ${svc.uuid}")
                svc.characteristics.forEach { chr ->
                    Timber.d("    Char: ${chr.uuid} props=${chr.properties}")
                }
            }

            // Пробуем оба UUID — новый и старый формат прошивок
            val meshService = gatt.getService(BleConstants.MESH_SERVICE_UUID)
                ?: gatt.getService(BleConstants.MESH_SERVICE_UUID_OLD)
            if (meshService == null) {
                Timber.w("Meshtastic service not found! Trying rediscovery...")
                // Повторный discoverServices через 3 секунды
                mainHandler.postDelayed({
                    Timber.i("Re-running discoverServices...")
                    gatt.discoverServices()
                }, 3000)
                return
            }

            Timber.i("Meshtastic service FOUND! Chars: ${meshService.characteristics.map { it.uuid }}")
            gatt.requestMtu(BleConstants.REQUESTED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("MTU negotiated: $mtu bytes")
                mtu
            } else {
                Timber.w("MTU negotiation failed, using default ${BleConstants.MIN_MTU}")
                BleConstants.MIN_MTU
            }
            // После MTU negotiation включаем уведомления
            enableFromNumNotifications(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                BleConstants.FROMNUM_UUID -> {
                    val num = characteristic.value?.let {
                        if (it.size >= 4) java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).int else 0
                    } ?: 0
                    Timber.i("FROMNUM notify: value=$num — reading FromRadio")
                    readAllFromRadio()
                }
                BleConstants.LOGRADIO_UUID -> {
                    // Логи с устройства
                    val log = characteristic.value?.decodeToString() ?: return
                    Timber.d("Device log: $log")
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            scope.launch {
                gattResultChannel.send(
                    GattResult(
                        success = status == BluetoothGatt.GATT_SUCCESS,
                        data = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                    )
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            scope.launch {
                gattResultChannel.send(GattResult(success = status == BluetoothGatt.GATT_SUCCESS))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            scope.launch {
                gattResultChannel.send(GattResult(success = status == BluetoothGatt.GATT_SUCCESS))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────

    private data class GattResult(val success: Boolean, val data: ByteArray? = null)
}

// Расширение для удобного фрагментирования байтового массива
private fun ByteArray.chunked(size: Int): List<ByteArray> {
    if (size <= 0) return listOf(this)
    val result = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < this.size) {
        result.add(copyOfRange(offset, minOf(offset + size, this.size)))
        offset += size
    }
    return result
}
