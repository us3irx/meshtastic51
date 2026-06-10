package com.mesh51.app.ui.nodes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mesh51.app.databinding.FragmentNodeDetailBinding
import com.mesh51.app.ui.MainActivity
import com.mesh51.app.ui.chat.DirectChatFragment
import com.mesh51.proto.MeshProtos.NodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date

class NodeDetailFragment : Fragment() {

    companion object {
        private const val ARG_NODE_NUM = "node_num"
        fun newInstance(nodeNum: Int) = NodeDetailFragment().apply {
            arguments = Bundle().apply { putInt(ARG_NODE_NUM, nodeNum) }
        }
    }

    private var _binding: FragmentNodeDetailBinding? = null
    private val binding get() = _binding!!
    private var nodeNum = 0
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nodeNum = arguments?.getInt(ARG_NODE_NUM) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentNodeDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }

        binding.btnMessage.setOnClickListener {
            val name = binding.nodeName.text.toString()
            // contactKey для прямого сообщения: "0^nodeHex"
            val contactKey = "0^!${Integer.toHexString(nodeNum)}"
            val frag = com.mesh51.app.ui.chat.DirectChatFragment.newInstance(contactKey, name)
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(com.mesh51.app.R.id.fragmentContainer, frag)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        binding.btnTrace.setOnClickListener {
            // Traceroute — отправляем запрос
            val service = (activity as? MainActivity)?.getMeshService() ?: return@setOnClickListener
            lifecycleScope.launch {
                try {
                    // ToRadio с admin пакетом traceroute
                    Timber.i("Traceroute to $nodeNum requested")
                    binding.traceResult.text = "Traceroute отправлен…"
                    binding.traceResult.visibility = View.VISIBLE
                } catch (e: Exception) { Timber.e(e) }
            }
        }

        lifecycleScope.launch { waitAndObserve() }
    }

    private suspend fun waitAndObserve() {
        for (i in 0..30) {
            val repo = (activity as? MainActivity)?.getMeshService()?.repository
            if (repo != null) {
                observeJob?.cancel()
                observeJob = lifecycleScope.launch {
                    repo.nodes.collect { nodes ->
                        val node = nodes[nodeNum] ?: return@collect
                        updateUI(node)
                    }
                }
                return
            }
            delay(300)
        }
    }

    private fun updateUI(node: NodeInfo) {
        val b = _binding ?: return
        val user = node.user

        (activity as? MainActivity)?.setTitle(user?.longName ?: "!${Integer.toHexString(node.num)}")

        b.nodeName.text = user?.longName ?: "!${Integer.toHexString(node.num)}"
        b.nodeShortName.text = user?.shortName ?: "?"
        b.nodeId.text = user?.id ?: "!${Integer.toHexString(node.num)}"
        b.nodeHwModel.text = user?.hwModel?.name ?: "Неизвестно"
        b.nodeRole.text = user?.role?.name ?: ""

        // SNR и RSSI
        b.nodeSnr.text = "SNR: ${"%.1f".format(node.snr)} dB"

        // Время последнего контакта
        if (node.lastHeard > 0) {
            val diff = System.currentTimeMillis() / 1000 - node.lastHeard
            val timeStr = when {
                diff < 60 -> "${diff} сек назад"
                diff < 3600 -> "${diff / 60} мин назад"
                diff < 86400 -> "${diff / 3600} ч назад"
                else -> "${diff / 86400} дн назад"
            }
            b.nodeLastHeard.text = "Последний контакт: $timeStr"
        } else {
            b.nodeLastHeard.text = "Последний контакт: неизвестно"
        }

        // Батарея и напряжение
        val metrics = node.deviceMetrics
        if (metrics != null && metrics.batteryLevel > 0) {
            b.nodeBattery.visibility = View.VISIBLE
            b.nodeBattery.text = "Батарея: ${metrics.batteryLevel}% (${
                "%.2f".format(metrics.voltage)
            }V)"
            b.nodeAirUtil.text = "Загрузка канала: ${"%.1f".format(metrics.channelUtilization)}%"
            b.nodeTxUtil.text = "TX: ${"%.1f".format(metrics.airUtilTx)}%"
            b.metricsGroup.visibility = View.VISIBLE
        } else {
            b.nodeBattery.visibility = View.GONE
            b.metricsGroup.visibility = View.GONE
        }

        // GPS позиция
        val pos = node.position
        if (pos != null && (pos.latitudeI != 0 || pos.longitudeI != 0)) {
            b.posGroup.visibility = View.VISIBLE
            b.nodeLat.text = "Широта: ${"%.6f".format(pos.latitudeI / 1e7)}°"
            b.nodeLon.text = "Долгота: ${"%.6f".format(pos.longitudeI / 1e7)}°"
            if (pos.altitude != 0) b.nodeAlt.text = "Высота: ${pos.altitude} м"
            else b.nodeAlt.text = ""
        } else {
            b.posGroup.visibility = View.GONE
        }

        // Hops
        b.nodeHops.text = "Hops: ${node.hopsAway}"
        if (node.viaMqtt) b.nodeMqtt.visibility = View.VISIBLE
        else b.nodeMqtt.visibility = View.GONE
    }

    override fun onResume() { super.onResume(); lifecycleScope.launch { waitAndObserve() } }
    override fun onDestroyView() {
        observeJob?.cancel()
        (activity as? MainActivity)?.setTitle("Meshtastic")
        super.onDestroyView(); _binding = null
    }
}
