package com.mesh51.app.ui.map

import android.graphics.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.mesh51.app.databinding.FragmentMapBinding
import com.mesh51.app.mesh.MeshRepository
import com.mesh51.app.ui.MainActivity
import com.mesh51.proto.MeshProtos.NodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import timber.log.Timber

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private lateinit var map: MapView
    private val markers = mutableMapOf<Int, Marker>()
    private var observeJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            Configuration.getInstance().userAgentValue = requireContext().packageName
            map = binding.mapView
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.setMultiTouchControls(true)
            map.controller.setZoom(12.0)
            map.controller.setCenter(GeoPoint(55.75, 37.62))
            binding.btnMyLocation.setOnClickListener { centerOnNodes() }
            lifecycleScope.launch { waitForServiceAndObserve() }
        } catch (e: Exception) {
            Timber.e(e, "MapFragment init error")
        }
    }

    private suspend fun waitForServiceAndObserve() {
        var attempts = 0
        while (attempts < 20) {
            val service = (activity as? MainActivity)?.getMeshService()
            if (service != null) {
                val repo = service.repository
                observeJob?.cancel()
                observeJob = lifecycleScope.launch {
                    repo.nodes.collect { nodesMap -> updateMarkers(nodesMap) }
                }
                return
            }
            delay(300); attempts++
        }
    }

    private fun updateMarkers(nodesMap: Map<Int, NodeInfo>) {
        if (_binding == null) return
        val toRemove = markers.keys - nodesMap.keys
        toRemove.forEach { num -> markers[num]?.let { map.overlays.remove(it) }; markers.remove(num) }
        nodesMap.values.forEach { node ->
            val pos = node.position ?: return@forEach
            if (pos.latitudeI == 0 && pos.longitudeI == 0) return@forEach
            val geoPoint = GeoPoint(pos.latitudeI / 1e7, pos.longitudeI / 1e7)
            val marker = markers.getOrPut(node.num) { Marker(map).also { map.overlays.add(it) } }
            marker.position = geoPoint
            marker.title = node.user?.longName ?: "!${Integer.toHexString(node.num)}"
            marker.snippet = "SNR: ${"%.1f".format(node.snr)}"
            marker.icon = createNodeIcon(node.user?.shortName ?: "?", node.snr)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.invalidate()
    }

    private fun centerOnNodes() {
        val points = markers.values.map { it.position }
        if (points.isEmpty()) return
        val center = GeoPoint(points.map { it.latitude }.average(), points.map { it.longitude }.average())
        map.controller.animateTo(center)
        if (points.size == 1) map.controller.setZoom(14.0)
    }

    private fun createNodeIcon(shortName: String, snr: Float): android.graphics.drawable.BitmapDrawable {
        val size = 80
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val color = when { snr >= 5f -> Color.parseColor("#4CAF50"); snr >= 0f -> Color.parseColor("#FFC107"); else -> Color.parseColor("#F44336") }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, paint)
        paint.apply { this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, paint)
        paint.apply { this.color = Color.WHITE; style = Paint.Style.FILL; textSize = if (shortName.length <= 2) 28f else 20f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText(shortName.take(4), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    override fun onResume() {
        super.onResume()
        try { map.onResume() } catch (e: Exception) {}
        lifecycleScope.launch { waitForServiceAndObserve() }
    }

    override fun onPause() {
        super.onPause()
        try { map.onPause() } catch (e: Exception) {}
    }

    override fun onDestroyView() {
        observeJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
