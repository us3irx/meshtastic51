package com.mesh51.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mesh51.app.ble.BleDevice
import com.mesh51.app.ble.ConnectionState
import com.mesh51.app.databinding.FragmentScanBinding
import com.mesh51.app.databinding.ItemDeviceBinding
import com.mesh51.app.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val devices = mutableListOf<BleDevice>()
    private lateinit var adapter: DeviceAdapter
    private var observeJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceAdapter(devices) { device ->
            val service = (activity as? MainActivity)?.getMeshService() ?: return@DeviceAdapter
            lifecycleScope.launch {
                try { service.bleManager.connect(device.device) }
                catch (e: Exception) { Timber.e(e, "connect error") }
            }
        }
        binding.recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDevices.adapter = adapter

        binding.btnScan.setOnClickListener { startScanWithPermCheck() }

        lifecycleScope.launch { waitForServiceAndObserve() }
    }

    private fun startScanWithPermCheck() {
        val ctx = requireContext()

        // Проверяем разрешение геолокации — без него BLE scan молчит на Android 5.x
        val hasLocation = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            binding.statusText.text = "⚠ Включите геолокацию для BLE сканирования"
            // Запрашиваем разрешение
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ), 101
            )
            return
        }

        val service = (activity as? MainActivity)?.getMeshService()
        if (service == null) {
            binding.statusText.text = "Сервис не готов, подождите…"
            return
        }

        devices.clear()
        adapter.notifyDataSetChanged()
        binding.statusText.text = "Запуск сканирования…"
        service.startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startScanWithPermCheck()
        } else {
            _binding?.statusText?.text = "Нужно разрешение геолокации для BLE"
        }
    }

    private suspend fun waitForServiceAndObserve() {
        var attempts = 0
        while (attempts < 30) {
            val service = (activity as? MainActivity)?.getMeshService()
            if (service != null) {
                startObserving(service)
                return
            }
            delay(300)
            attempts++
        }
        _binding?.statusText?.text = "Сервис недоступен"
    }

    private fun startObserving(service: com.mesh51.app.ble.MeshService) {
        observeJob?.cancel()
        observeJob = lifecycleScope.launch {
            launch {
                service.bleManager.connectionState.collect { state ->
                    if (_binding == null) return@collect
                    updateUi(state)
                }
            }
            launch {
                service.bleManager.scanResults.collect { device ->
                    if (_binding == null) return@collect
                    val existing = devices.indexOfFirst { it.address == device.address }
                    if (existing >= 0) {
                        devices[existing] = device
                        adapter.notifyItemChanged(existing)
                    } else {
                        devices.add(device)
                        adapter.notifyItemInserted(devices.size - 1)
                    }
                }
            }
        }
    }

    private fun updateUi(state: ConnectionState) {
        val b = _binding ?: return
        when (state) {
            is ConnectionState.Scanning -> {
                b.btnScan.isEnabled = false
                b.btnScan.text = "Поиск…"
                b.statusText.text = "Сканирование BLE… (${devices.size} найдено)"
                b.progressBar.visibility = View.VISIBLE
            }
            is ConnectionState.Connecting -> {
                b.statusText.text = "Подключение к ${state.address}…"
                b.progressBar.visibility = View.VISIBLE
            }
            is ConnectionState.DiscoveringServices -> {
                b.statusText.text = "Инициализация…"
            }
            is ConnectionState.Connected -> {
                b.btnScan.isEnabled = true
                b.btnScan.text = "Сканировать"
                b.statusText.text = "✓ Подключено: ${state.name}"
                b.progressBar.visibility = View.GONE
            }
            is ConnectionState.Disconnected -> {
                b.btnScan.isEnabled = true
                b.btnScan.text = "Сканировать"
                b.statusText.text = "Не подключено. Найдено устройств: ${devices.size}"
                b.progressBar.visibility = View.GONE
            }
            is ConnectionState.Reconnecting -> {
                b.statusText.text = "Переподключение (${state.attempt})…"
                b.progressBar.visibility = View.VISIBLE
            }
            is ConnectionState.Error -> {
                b.btnScan.isEnabled = true
                b.btnScan.text = "Сканировать"
                b.statusText.text = "Ошибка: ${state.message}"
                b.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val service = (activity as? MainActivity)?.getMeshService()
        if (service != null) startObserving(service)
        else lifecycleScope.launch { waitForServiceAndObserve() }
    }

    override fun onDestroyView() {
        observeJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}

class DeviceAdapter(
    private val items: List<BleDevice>,
    private val onClick: (BleDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {
    inner class VH(val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.b.deviceName.text = d.name
        holder.b.deviceAddress.text = d.address
        holder.b.deviceRssi.text = d.rssiLabel
        holder.b.root.setOnClickListener { onClick(d) }
    }
}
