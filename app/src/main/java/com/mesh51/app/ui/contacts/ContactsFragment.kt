package com.mesh51.app.ui.contacts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mesh51.app.databinding.FragmentContactsBinding
import com.mesh51.app.databinding.ItemContactBinding
import com.mesh51.app.ui.MainActivity
import com.mesh51.app.ui.chat.DirectChatFragment
import com.mesh51.proto.MeshProtos.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ContactItem(
    val contactKey: String,
    val name: String,
    val lastMessage: String = "",
    val channelIndex: Int = 0,
    val nodeNum: Int = 0
)

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val contacts = mutableListOf<ContactItem>()
    private lateinit var adapter: ContactAdapter
    private var observeJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ContactAdapter(contacts) { contact ->
            val frag = DirectChatFragment.newInstance(contact.contactKey, contact.name)
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(com.mesh51.app.R.id.fragmentContainer, frag)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        binding.recyclerContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerContacts.adapter = adapter

        binding.btnConnect.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(com.mesh51.app.R.id.fragmentContainer,
                    com.mesh51.app.ui.scan.ScanFragment(), "scan")
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
                            binding.btnConnect.visibility = if (connected) View.GONE else View.VISIBLE
                            binding.statusText.visibility = if (connected) View.GONE else View.VISIBLE
                        }
                    }
                    launch {
                        repo.channels.collect { channels ->
                            if (_binding == null) return@collect
                            val msgs = contacts.associate { it.contactKey to it.lastMessage }
                            contacts.clear()
                            channels.filter { it.role != Channel.Role.DISABLED }.forEach { ch ->
                                val name = ch.settings?.name?.ifEmpty {
                                    if (ch.index == 0) "Primary" else "Channel ${ch.index}"
                                } ?: if (ch.index == 0) "Primary" else "Channel ${ch.index}"
                                val key = "${ch.index}^all"
                                contacts.add(ContactItem(
                                    contactKey = key,
                                    name = name,
                                    lastMessage = msgs[key] ?: "",
                                    channelIndex = ch.index,
                                    nodeNum = 0
                                ))
                            }
                            adapter.notifyDataSetChanged()
                        }
                    }
                    launch {
                        repo.messages.collect { msg ->
                            if (_binding == null) return@collect
                            val key = "${msg.channel}^all"
                            val idx = contacts.indexOfFirst { it.contactKey == key }
                            if (idx >= 0) {
                                contacts[idx] = contacts[idx].copy(
                                    lastMessage = "${msg.fromName}: ${msg.text}"
                                )
                                adapter.notifyItemChanged(idx)
                            }
                        }
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

class ContactAdapter(
    private val items: List<ContactItem>,
    private val onClick: (ContactItem) -> Unit
) : RecyclerView.Adapter<ContactAdapter.VH>() {
    inner class VH(val b: ItemContactBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(ItemContactBinding.inflate(LayoutInflater.from(p.context), p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.b.contactName.text = c.name
        h.b.contactLastMsg.text = c.lastMessage.ifEmpty { "Нет сообщений" }
        h.b.contactChannel.text = "Канал ${c.channelIndex}"
        h.b.unreadBadge.visibility = View.GONE
        h.b.root.setOnClickListener { onClick(c) }
    }
}
