package com.mesh51.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mesh51.app.databinding.FragmentConfigBinding
import com.mesh51.app.ui.MainActivity
import com.mesh51.app.ui.scan.ScanFragment
import com.mesh51.proto.MeshProtos.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    private var observeJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnScan.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(com.mesh51.app.R.id.fragmentContainer, ScanFragment(), "scan")
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        lifecycleScope.launch { waitAndObserve() }
    }

    private suspend fun waitAndObserve() {
        for (i in 0..30) {
            val service = (activity as? MainActivity)?.getMeshService()
            if (service != null) {
                val repo = service.repository
                observeJob?.cancel()
                observeJob = lifecycleScope.launch {
                    launch {
                        service.bleManager.connectionState.collect { state ->
                            if (_binding == null) return@collect
                            val connected = state is com.mesh51.app.ble.ConnectionState.Connected
                            binding.btnScan.text =
                                if (connected) "Переподключиться" else "Подключиться к устройству"
                        }
                    }
                    launch {
                        repo.myNodeInfo.collect { info ->
                            if (_binding == null || info == null) return@collect
                            binding.myNodeNum.text = "ID: !${Integer.toHexString(info.myNodeNum)}"
                        }
                    }
                    launch {
                        repo.configComplete.collect { complete ->
                            if (_binding == null) return@collect
                            binding.configStatus.text =
                                if (complete) "✓ Конфигурация получена" else "Загрузка конфигурации…"
                        }
                    }
                    launch {
                        repo.nodes.collect { nodes ->
                            if (_binding == null) return@collect
                            binding.nodeCount.text = "Узлов в сети: ${nodes.size}"
                            val myNum = repo.myNodeInfo.value?.myNodeNum ?: 0
                            val myNode = nodes[myNum]
                            if (myNode?.user != null) {
                                val u = myNode.user
                                binding.myName.text = "Имя: ${u.longName}"
                                binding.myShortName.text = "Короткое: ${u.shortName}"
                                binding.myHwModel.text = "Устройство: ${u.hwModel.name}"
                                binding.myUserGroup.visibility = View.VISIBLE
                            }
                        }
                    }
                    launch {
                        // Показываем все типы конфигов из configMap
                        repo.config.collect { _ ->
                            if (_binding == null) return@collect
                            showAllConfigs(repo.configs)
                        }
                    }
                    launch {
                        repo.channels.collect { channels ->
                            if (_binding == null) return@collect
                            showChannels(channels)
                        }
                    }
                }
                return
            }
            delay(300)
        }
        _binding?.configStatus?.text = "Сервис недоступен"
    }

    private fun showAllConfigs(configMap: Map<Int, Config>) {
        configMap.values.forEach { showConfig(it) }
    }

    private fun showConfig(config: Config) {
        val b = _binding ?: return
        when (config.payloadVariantCase) {
            Config.PayloadVariantCase.LORA -> {
                val lora = config.lora
                b.loraRegion.text = "Регион: ${lora.region.name}"
                b.loraPreset.text = "Пресет: ${lora.modemPreset.name}"
                b.loraHopLimit.text = "Hop limit: ${lora.hopLimit}"
                b.loraTxPower.text = "TX: ${lora.txPower} dBm"
                b.loraFreq.text = if (lora.overrideFrequency > 0)
                    "Частота: ${"%.3f".format(lora.overrideFrequency)} МГц" else ""
                b.loraGroup.visibility = View.VISIBLE
            }
            Config.PayloadVariantCase.DEVICE -> {
                b.deviceRole.text = "Роль: ${config.device.role.name}"
                b.deviceGroup.visibility = View.VISIBLE
            }
            Config.PayloadVariantCase.BLUETOOTH -> {
                val bt = config.bluetooth
                b.btEnabled.text = "BT: ${if (bt.enabled) "Включён" else "Выключен"}"
                b.btMode.text = "Режим: ${bt.mode.name}"
                if (bt.mode == Config.BluetoothConfig.PairingMode.FIXED_PIN)
                    b.btPin.text = "PIN: ${bt.fixedPin}"
                else b.btPin.text = ""
                b.btGroup.visibility = View.VISIBLE
            }
            Config.PayloadVariantCase.POSITION -> {
                val pos = config.position
                b.posGpsEnabled.text = "GPS: ${pos.gpsMode.name}"
                b.posInterval.text = "Интервал: ${pos.positionBroadcastSecs} сек"
                b.posGroup.visibility = View.VISIBLE
            }
            Config.PayloadVariantCase.POWER -> {
                val pwr = config.power
                b.pwrSaving.text = "Энергосбережение: ${if (pwr.isPowerSaving) "Вкл" else "Выкл"}"
                b.pwrGroup.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private fun showChannels(channels: List<Channel>) {
        val b = _binding ?: return
        if (channels.isEmpty()) return
        val sb = StringBuilder()
        channels.forEach { ch ->
            if (ch.role != Channel.Role.DISABLED) {
                val name = ch.settings?.name?.ifEmpty { "Канал ${ch.index}" } ?: "Канал ${ch.index}"
                val enc = if ((ch.settings?.psk?.size() ?: 0) > 0) "🔒" else "🔓"
                sb.append("$enc ${ch.index}: $name [${ch.role.name}]\n")
            }
        }
        b.channelsText.text = sb.toString().trimEnd()
        b.channelsGroup.visibility = View.VISIBLE
    }

    override fun onResume() { super.onResume(); lifecycleScope.launch { waitAndObserve() } }
    override fun onDestroyView() { observeJob?.cancel(); super.onDestroyView(); _binding = null }
}
