package com.mesh51.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mesh51.app.R
import com.mesh51.app.ble.ConnectionState
import com.mesh51.app.ble.MeshService
import com.mesh51.app.databinding.ActivityMainBinding
import com.mesh51.app.ui.channels.ChannelsFragment
import com.mesh51.app.ui.contacts.ContactsFragment
import com.mesh51.app.ui.config.ConfigFragment
import com.mesh51.app.ui.map.MapFragment
import com.mesh51.app.ui.nodes.NodesFragment
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var meshService: MeshService? = null
    private var serviceBound = false

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                meshService = (binder as MeshService.MeshBinder).getService()
                serviceBound = true
                observeConnectionState()
            } catch (e: Exception) { Timber.e(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            meshService = null; serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) { Timber.e(e); return }

        if (savedInstanceState == null) {
            showFragment(ContactsFragment(), "contacts")
            binding.bottomNav.selectedItemId = R.id.nav_contacts
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_contacts -> ContactsFragment()
                R.id.nav_nodes    -> NodesFragment()
                R.id.nav_map      -> MapFragment()
                R.id.nav_channels -> ChannelsFragment()
                R.id.nav_settings -> ConfigFragment()
                else -> return@setOnItemSelectedListener false
            }
            try {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .commitAllowingStateLoss()
            } catch (e: Exception) { Timber.e(e) }
            true
        }

        checkPermissionsAndStartService()
    }

    fun showFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commitAllowingStateLoss()
    }

    fun setTitle(title: String) { _binding?.toolbarTitle?.text = title }
    fun setStatus(status: String) { _binding?.toolbarStatus?.text = status }

    private fun checkPermissionsAndStartService() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAndBindService()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun startAndBindService() {
        try {
            val intent = Intent(this, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Timber.e(e) }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            try {
                meshService?.bleManager?.connectionState?.collect { state ->
                    val status = when (state) {
                        is ConnectionState.Connected    -> "● ${state.name}"
                        is ConnectionState.Connecting   -> "Подключение…"
                        is ConnectionState.Scanning     -> "Поиск…"
                        is ConnectionState.Reconnecting -> "Переподключение…"
                        is ConnectionState.Error        -> "Ошибка"
                        else -> ""
                    }
                    setStatus(status)
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) startAndBindService()
    }

    fun getMeshService(): MeshService? = meshService

    override fun onDestroy() {
        try { if (serviceBound) unbindService(serviceConnection) } catch (e: Exception) {}
        _binding = null
        super.onDestroy()
    }
}
