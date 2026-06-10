package com.mesh51.app.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mesh51.app.databinding.FragmentDirectChatBinding
import com.mesh51.app.databinding.ItemMessageInBinding
import com.mesh51.app.databinding.ItemMessageOutBinding
import com.mesh51.app.mesh.MeshMessage
import com.mesh51.app.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

class DirectChatFragment : Fragment() {

    companion object {
        private const val ARG_KEY = "contact_key"
        private const val ARG_NAME = "contact_name"
        fun newInstance(contactKey: String, name: String) = DirectChatFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_KEY, contactKey)
                putString(ARG_NAME, name)
            }
        }
    }

    private var _binding: FragmentDirectChatBinding? = null
    private val binding get() = _binding!!
    private var contactKey = ""
    private var contactName = ""
    private val messages = mutableListOf<MeshMessage>()
    private lateinit var adapter: MsgAdapter
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactKey = arguments?.getString(ARG_KEY) ?: ""
        contactName = arguments?.getString(ARG_NAME) ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentDirectChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.setTitle(contactName)

        adapter = MsgAdapter(messages, fmt)
        binding.recyclerMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.recyclerMessages.adapter = adapter
        binding.chatTitle.text = contactName

        binding.btnBack.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
            (activity as? MainActivity)?.setTitle("Meshtastic")
        }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.editMessage.setOnEditorActionListener { _, _, _ -> sendMessage(); true }

        lifecycleScope.launch { waitAndObserve() }
    }

    private fun getChannelIndex(): Int {
        return contactKey.substringBefore("^").toIntOrNull() ?: 0
    }

    private suspend fun waitAndObserve() {
        for (i in 0..30) {
            val repo = (activity as? MainActivity)?.getMeshService()?.repository
            if (repo != null) {
                observeJob?.cancel()
                observeJob = lifecycleScope.launch {
                    repo.messages.collect { msg ->
                        if (_binding == null) return@collect
                        if (msg.channel == getChannelIndex()) {
                            messages.add(msg)
                            adapter.notifyItemInserted(messages.size - 1)
                            binding.recyclerMessages.scrollToPosition(messages.size - 1)
                        }
                    }
                }
                return
            }
            delay(300)
        }
    }

    private fun sendMessage() {
        val text = _binding?.editMessage?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        val repo = (activity as? MainActivity)?.getMeshService()?.repository ?: return
        binding.editMessage.setText("")
        binding.btnSend.isEnabled = false
        lifecycleScope.launch {
            try { repo.sendTextMessage(text, channel = getChannelIndex()) }
            catch (e: Exception) { Timber.e(e) }
            _binding?.btnSend?.isEnabled = true
        }
    }

    override fun onResume() { super.onResume(); lifecycleScope.launch { waitAndObserve() } }
    override fun onDestroyView() {
        observeJob?.cancel()
        (activity as? MainActivity)?.setTitle("Meshtastic")
        super.onDestroyView(); _binding = null
    }
}

private const val TYPE_IN = 0; private const val TYPE_OUT = 1
class MsgAdapter(private val items: List<MeshMessage>, private val fmt: SimpleDateFormat)
    : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    inner class InVH(val b: ItemMessageInBinding) : RecyclerView.ViewHolder(b.root)
    inner class OutVH(val b: ItemMessageOutBinding) : RecyclerView.ViewHolder(b.root)
    override fun getItemViewType(p: Int) = if (items[p].isOutgoing) TYPE_OUT else TYPE_IN
    override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder =
        if (t == TYPE_OUT) OutVH(ItemMessageOutBinding.inflate(LayoutInflater.from(p.context), p, false))
        else InVH(ItemMessageInBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val m = items[pos]; val t = fmt.format(Date(m.time))
        when (h) {
            is InVH -> { h.b.msgSender.text = m.fromName; h.b.msgText.text = m.text; h.b.msgTime.text = t; h.b.msgSnr.text = "SNR: ${"%.1f".format(m.snr)}" }
            is OutVH -> { h.b.msgText.text = m.text; h.b.msgTime.text = t }
        }
    }
}
