package com.mesh51.app.ui.channels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mesh51.app.databinding.FragmentChannelsBinding
import com.mesh51.app.databinding.ItemChannelBinding
import com.mesh51.app.ui.MainActivity
import com.mesh51.proto.MeshProtos.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!
    private val channels = mutableListOf<Channel>()
    private lateinit var adapter: ChannelAdapter
    private var observeJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ChannelAdapter(channels)
        binding.recyclerChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerChannels.adapter = adapter
        lifecycleScope.launch { waitAndObserve() }
    }

    private suspend fun waitAndObserve() {
        for (i in 0..30) {
            val repo = (activity as? MainActivity)?.getMeshService()?.repository
            if (repo != null) {
                observeJob?.cancel()
                observeJob = lifecycleScope.launch {
                    repo.channels.collect { list ->
                        if (_binding == null) return@collect
                        channels.clear()
                        channels.addAll(list.filter { it.role != Channel.Role.DISABLED })
                        adapter.notifyDataSetChanged()
                        binding.channelCount.text = "${channels.size} каналов"
                    }
                }
                return
            }
            delay(300)
        }
    }

    override fun onResume() { super.onResume(); lifecycleScope.launch { waitAndObserve() } }
    override fun onDestroyView() { observeJob?.cancel(); super.onDestroyView(); _binding = null }
}

class ChannelAdapter(private val items: List<Channel>) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    inner class VH(val b: ItemChannelBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemChannelBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val ch = items[pos]
        val name = ch.settings?.name?.ifEmpty { "Канал ${ch.index}" } ?: "Канал ${ch.index}"
        h.b.channelName.text = "${ch.index}: $name"
        h.b.channelRole.text = ch.role.name
        h.b.channelPsk.text = if ((ch.settings?.psk?.size() ?: 0) > 0) "🔒 Зашифрован" else "🔓 Открытый"
    }
}
