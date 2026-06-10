package com.mesh51.app.ui.nodes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mesh51.app.databinding.FragmentNodesBinding
import com.mesh51.app.databinding.ItemNodeBinding
import com.mesh51.app.ui.MainActivity
import com.mesh51.proto.MeshProtos.NodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NodesFragment : Fragment() {

    private var _binding: FragmentNodesBinding? = null
    private val binding get() = _binding!!
    private val nodeList = mutableListOf<NodeInfo>()
    private lateinit var adapter: NodeAdapter
    private var observeJob: Job? = null
    private var myNodeNum: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentNodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = NodeAdapter(nodeList, onNodeClick = { node -> openDirectMessage(node) })
        binding.recyclerNodes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerNodes.adapter = adapter
        lifecycleScope.launch { waitAndObserve() }
    }

    private suspend fun waitAndObserve() {
        for (i in 0..30) {
            val repo = (activity as? MainActivity)?.getMeshService()?.repository
            if (repo != null) {
                observeJob?.cancel()
                observeJob = lifecycleScope.launch {
                    launch {
                        repo.myNodeInfo.collect { info ->
                            myNodeNum = info?.myNodeNum ?: 0
                        }
                    }
                    launch {
                        repo.nodes.collect { nodesMap ->
                            if (_binding == null) return@collect
                            nodeList.clear()
                            // Сортируем: сначала наш узел, потом по времени последнего контакта
                            nodeList.addAll(nodesMap.values.sortedWith(
                                compareByDescending<NodeInfo> { it.num == myNodeNum }
                                    .thenByDescending { it.lastHeard }
                            ))
                            adapter.notifyDataSetChanged()
                            binding.nodeCount.text = "${nodesMap.size} узлов в сети"
                        }
                    }
                }
                return
            }
            delay(300)
        }
    }

    private fun openDirectMessage(node: NodeInfo) {
        // Открываем детальную карточку ноды (как в оригинале)
        val fragment = NodeDetailFragment.newInstance(nodeNum = node.num)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(com.mesh51.app.R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    override fun onResume() { super.onResume(); lifecycleScope.launch { waitAndObserve() } }
    override fun onDestroyView() { observeJob?.cancel(); super.onDestroyView(); _binding = null }
}

class NodeAdapter(
    private val items: List<NodeInfo>,
    private val onNodeClick: (NodeInfo) -> Unit
) : RecyclerView.Adapter<NodeAdapter.VH>() {
    inner class VH(val b: ItemNodeBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = items[position]; val user = node.user
        holder.b.nodeName.text = user?.longName ?: "!${Integer.toHexString(node.num)}"
        holder.b.nodeShortName.text = user?.shortName ?: "?"
        holder.b.nodeId.text = user?.id ?: "!${Integer.toHexString(node.num)}"
        if (node.lastHeard > 0) {
            val diff = System.currentTimeMillis() / 1000 - node.lastHeard
            holder.b.nodeLastHeard.text = when {
                diff < 60 -> "${diff}с"
                diff < 3600 -> "${diff/60}м"
                diff < 86400 -> "${diff/3600}ч"
                else -> "${diff/86400}д"
            }
        } else holder.b.nodeLastHeard.text = "?"
        holder.b.nodeSnr.text = "SNR: ${"%.1f".format(node.snr)}"
        val battery = node.deviceMetrics?.batteryLevel ?: 0
        holder.b.nodeBattery.visibility = if (battery > 0) View.VISIBLE else View.GONE
        if (battery > 0) holder.b.nodeBattery.text = "🔋$battery%"
        val pos = node.position
        if (pos != null && (pos.latitudeI != 0 || pos.longitudeI != 0)) {
            holder.b.nodePosition.visibility = View.VISIBLE
            holder.b.nodePosition.text = "📍${"%.4f".format(pos.latitudeI/1e7)}, ${"%.4f".format(pos.longitudeI/1e7)}"
        } else holder.b.nodePosition.visibility = View.GONE
        holder.b.nodeHwModel.text = user?.hwModel?.name ?: ""
        holder.b.root.setOnClickListener { onNodeClick(node) }
    }
}
