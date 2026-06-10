package com.mesh51.app.mesh

import com.mesh51.app.ble.BleManager
import com.mesh51.app.ble.ConnectionState
import com.mesh51.proto.MeshProtos.*
import com.mesh51.proto.Portnums.PortNum
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.charset.Charset

/**
 * MeshRepository — центральный слой бизнес-логики.
 *
 * Отвечает за:
 * - Парсинг входящих FromRadio protobuf пакетов
 * - Хранение актуального состояния сети (узлы, сообщения, конфиг)
 * - Отправку ToRadio пакетов через BleManager
 * - Инициализацию сессии (want_config после подключения)
 *
 * UI получает данные через StateFlow/SharedFlow — реактивно.
 */
class MeshRepository(private val bleManager: BleManager) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─────────────────────────────────────────────────────────────
    // Публичные потоки данных для UI
    // ─────────────────────────────────────────────────────────────

    /** Информация о нашем собственном узле */
    private val _myNodeInfo = MutableStateFlow<MyNodeInfo?>(null)
    val myNodeInfo: StateFlow<MyNodeInfo?> = _myNodeInfo.asStateFlow()

    /** Карта узлов сети: nodeNum -> NodeInfo */
    // Используем LinkedHashMap чтобы гарантировать эмит при каждом изменении
    private val _nodesMap = mutableMapOf<Int, NodeInfo>()
    private val _nodes = MutableStateFlow<Map<Int, NodeInfo>>(emptyMap())
    val nodes: StateFlow<Map<Int, NodeInfo>> = _nodes.asStateFlow()

    /** Входящие текстовые сообщения */
    private val _messages = MutableSharedFlow<MeshMessage>(replay = 50, extraBufferCapacity = 100)
    val messages: SharedFlow<MeshMessage> = _messages.asSharedFlow()

    /** Каналы устройства */
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** Все конфиги устройства (нода присылает несколько: LoRa, Device, BT и т.д.) */
    private val _configMap = mutableMapOf<Int, Config>()
    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config.asStateFlow()
    val configs: MutableMap<Int, Config> get() = _configMap

    /** Конфигурация модулей */
    private val _moduleConfig = MutableStateFlow<ModuleConfig?>(null)
    val moduleConfig: StateFlow<ModuleConfig?> = _moduleConfig.asStateFlow()

    /** Статус инициализации конфига (true = вся конфигурация получена) */
    private val _configComplete = MutableStateFlow(false)
    val configComplete: StateFlow<Boolean> = _configComplete.asStateFlow()

    /** Прокси к состоянию BLE */
    val connectionState = bleManager.connectionState

    // ─────────────────────────────────────────────────────────────
    // Сессия
    // ─────────────────────────────────────────────────────────────

    private var configRequestId: Int = 0

    init {
        observeIncomingPackets()
        observeConnectionState()
    }

    private fun observeConnectionState() {
        scope.launch {
            bleManager.connectionState.collect { state ->
                if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
                    _configComplete.value = false
                }
            }
        }
    }

    private fun observeIncomingPackets() {
        scope.launch {
            Timber.i("MeshRepository: starting to observe incoming packets")
            bleManager.incomingPackets.collect { bytes ->
                try {
                    Timber.d("MeshRepository: parsing ${bytes.size} bytes")
                    val fromRadio = FromRadio.parseFrom(bytes)
                    Timber.i("MeshRepository: parsed payload=${fromRadio.payloadVariantCase}")
                    processFromRadio(fromRadio)
                } catch (e: Exception) {
                    Timber.e(e, "MeshRepository: Failed to parse ${bytes.size} bytes: ${bytes.take(8).map { it.toInt() and 0xFF }}")
                }
            }
        }
    }

    private fun processFromRadio(fromRadio: FromRadio) {
        when (fromRadio.payloadVariantCase.number) {

            3 -> {  // MY_INFO
                Timber.d("MyNodeInfo received: nodeNum=${fromRadio.myInfo.myNodeNum}")
                _myNodeInfo.value = fromRadio.myInfo
            }

            4 -> {  // NODE_INFO
                val node = fromRadio.nodeInfo
                Timber.d("NodeInfo: ${node.user?.longName} (${node.num})")
                _nodesMap[node.num] = node
                _nodes.value = _nodesMap.toMap() // toMap() создаёт новый объект — гарантирует эмит
            }

            2 -> {  // PACKET
                processPacket(fromRadio.packet)
            }

            10 -> {  // CHANNEL
                val ch = fromRadio.channel
                Timber.d("Channel ${ch.index}: ${ch.settings?.name} role=${ch.role}")
                val updated = _channels.value.toMutableList()
                // Заменяем или добавляем канал по индексу
                val idx = updated.indexOfFirst { it.index == ch.index }
                if (idx >= 0) updated[idx] = ch else updated.add(ch)
                _channels.value = updated.sortedBy { it.index }
            }

            11 -> {  // CONFIG
                val cfg = fromRadio.config
                Timber.d("Config received: ${cfg.payloadVariantCase} num=${cfg.payloadVariantCase.number}")
                _configMap[cfg.payloadVariantCase.number] = cfg
                _config.value = cfg // эмитим каждый конфиг
            }

            12 -> {  // MODULE_CONFIG
                Timber.d("ModuleConfig received")
                _moduleConfig.value = fromRadio.moduleConfig
            }

            8 -> {  // CONFIG_COMPLETE_ID
                val id = fromRadio.configCompleteId
                Timber.i("Config complete! id=$id (expected=$configRequestId)")
                if (id == configRequestId) {
                    _configComplete.value = true
                }
            }

            7 -> {  // LOG_RECORD
                Timber.d("Device log [${fromRadio.logRecord.level}]: ${fromRadio.logRecord.message}")
            }

            else -> {  // UNKNOWN
                Timber.v("Unhandled FromRadio: ${fromRadio.payloadVariantCase}")
            }
        }
    }

    private fun processPacket(packet: MeshPacket) {
        if (packet.payloadVariantCase != MeshPacket.PayloadVariantCase.DECODED) {
            Timber.v("Encrypted packet from ${packet.from}, skipping")
            return
        }

        val data = packet.decoded
        val fromNode = _nodes.value[packet.from]
        val fromName = fromNode?.user?.longName ?: "!${Integer.toHexString(packet.from)}"

        Timber.d("Packet portnum=${data.portnum} from=$fromName")

        when (data.portnum) {
            PortNum.TEXT_MESSAGE_APP -> {
                val text = data.payload.toString(Charset.forName("UTF-8"))
                Timber.i("Message from $fromName: $text")
                scope.launch {
                    _messages.emit(
                        MeshMessage(
                            id = packet.id,
                            from = packet.from,
                            fromName = fromName,
                            to = packet.to,
                            text = text,
                            time = if (packet.rxTime > 0) packet.rxTime.toLong() * 1000
                                   else System.currentTimeMillis(),
                            channel = packet.channel,
                            snr = packet.rxSnr,
                            rssi = packet.rxRssi,
                            isOutgoing = false
                        )
                    )
                }
            }

            PortNum.POSITION_APP -> {
                try {
                    val position = Position.parseFrom(data.payload)
                    Timber.d("Position from $fromName: ${position.latitudeI / 1e7}, ${position.longitudeI / 1e7}")
                    // Обновляем позицию в NodeInfo
                    val node = _nodes.value[packet.from]
                    if (node != null) {
                        val updated = node.toBuilder().setPosition(position).build()
                        _nodesMap[packet.from] = updated
                        _nodes.value = _nodesMap.toMap()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse Position")
                }
            }

            PortNum.NODEINFO_APP -> {
                try {
                    val user = User.parseFrom(data.payload)
                    Timber.d("NodeInfo update: ${user.longName}")
                    val existing = _nodes.value[packet.from]
                    val updated = (existing?.toBuilder() ?: NodeInfo.newBuilder().setNum(packet.from))
                        .setUser(user)
                        .setLastHeard((System.currentTimeMillis() / 1000).toInt())
                        .build()
                    _nodesMap[packet.from] = updated
                    _nodes.value = _nodesMap.toMap()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse User")
                }
            }

            PortNum.ROUTING_APP -> {
                try {
                    val routing = Routing.parseFrom(data.payload)
                    if (routing.variantCase == Routing.VariantCase.ERROR_REASON &&
                        routing.errorReason != Routing.Error.NONE) {
                        Timber.w("Routing error: ${routing.errorReason} for packet ${data.requestId}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse Routing")
                }
            }

            PortNum.TELEMETRY_APP -> {
                try {
                    val telemetry = Telemetry.parseFrom(data.payload)
                    if (telemetry.variantCase == Telemetry.VariantCase.DEVICE_METRICS) {
                        val node = _nodes.value[packet.from]
                        if (node != null) {
                            val updated = node.toBuilder()
                                .setDeviceMetrics(telemetry.deviceMetrics)
                                .build()
                            _nodesMap[packet.from] = updated
                            _nodes.value = _nodesMap.toMap()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse Telemetry")
                }
            }

            else -> Timber.v("Unhandled portnum: ${data.portnum}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Исходящие команды
    // ─────────────────────────────────────────────────────────────

    /**
     * Запрос конфигурации устройства.
     * Отправляем ToRadio.want_config_id с уникальным ID.
     * Устройство ответит всеми NodeInfo, Channel, Config, ModuleConfig
     * и завершит сессию пакетом CONFIG_COMPLETE_ID с тем же ID.
     */
    suspend fun requestConfig() {
        configRequestId = (System.currentTimeMillis() / 1000).toInt()
        val toRadio = ToRadio.newBuilder()
            .setWantConfigId(configRequestId)
            .build()
        val success = bleManager.sendPacket(toRadio.toByteArray())
        Timber.i("Config requested id=$configRequestId success=$success")
    }

    /**
     * Отправка текстового сообщения.
     * @param text текст сообщения
     * @param channel номер канала (0 = primary)
     * @param dest адрес получателя (0xFFFFFFFF = broadcast)
     */
    suspend fun sendTextMessage(text: String, channel: Int = 0, dest: Int = 0xFFFFFFFF.toInt()): Boolean {
        val myNum = _myNodeInfo.value?.myNodeNum ?: run {
            Timber.e("sendTextMessage: myNodeNum not known")
            return false
        }

        val packetId = (System.currentTimeMillis() / 1000).toInt() or (Math.random() * 0x7FFF).toInt()

        val data = Data.newBuilder()
            .setPortnum(PortNum.TEXT_MESSAGE_APP)
            .setPayload(com.google.protobuf.ByteString.copyFromUtf8(text))
            .setWantResponse(false)
            .build()

        val packet = MeshPacket.newBuilder()
            .setTo(dest)
            .setChannel(channel)
            .setDecoded(data)
            .setId(packetId)
            .setWantAck(true)
            .setHopLimit(3)
            .build()

        val toRadio = ToRadio.newBuilder().setPacket(packet).build()
        val success = bleManager.sendPacket(toRadio.toByteArray())

        if (success) {
            // Добавляем в список как исходящее
            val myNode = _nodes.value[myNum]
            _messages.emit(
                MeshMessage(
                    id = packetId,
                    from = myNum,
                    fromName = myNode?.user?.longName ?: "Я",
                    to = dest,
                    text = text,
                    time = System.currentTimeMillis(),
                    channel = channel,
                    snr = 0f,
                    rssi = 0,
                    isOutgoing = true
                )
            )
        }
        return success
    }

    fun close() {
        scope.cancel()
    }
}

/**
 * Модель текстового сообщения для UI.
 */
data class MeshMessage(
    val id: Int,
    val from: Int,
    val fromName: String,
    val to: Int,
    val text: String,
    val time: Long,
    val channel: Int,
    val snr: Float,
    val rssi: Int,
    val isOutgoing: Boolean
) {
    val fromHex: String get() = "!${Integer.toHexString(from)}"
    val isBroadcast: Boolean get() = to == 0xFFFFFFFF.toInt() || to == 0
}
