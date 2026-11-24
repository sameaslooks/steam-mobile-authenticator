package io.github.sma.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import android.view.MotionEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.sma.core.steam.AuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val GEO_PREFS = "sma_geo"
private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
private const val HTTP_TIMEOUT_MS = 10_000
private const val MAP_INITIAL_ZOOM = 11.5
private const val MAP_MIN_ZOOM = 3.0
private const val MAP_MAX_ZOOM = 18.0

/** User-Agent required by OpenStreetMap services (Nominatim and tile servers). */
internal fun appUserAgent(ctx: Context): String {
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
        .getOrNull() ?: "unknown"
    return "SMA/$version (io.github.sameaslooks.sma)"
}

/**
 * Resolves approximate coordinates for a sign-in request: first from Steam's "lat,lon" geolocation,
 * otherwise by geocoding city/state/country with OpenStreetMap Nominatim (results cached per place).
 */
object GeoLookup {
    suspend fun locate(ctx: Context, info: AuthApi.AuthSessionInfo): Pair<Double, Double>? {
        parseCoords(info.geoloc)?.let { return it }

        val city = info.city.orEmpty().trim()
        val state = info.state.orEmpty().trim()
        val country = info.country.orEmpty().trim()
        if (city.isEmpty() && state.isEmpty() && country.isEmpty()) return null
        val key = "$city|$state|$country"

        return withContext(Dispatchers.IO) {
            try {
                val prefs = ctx.applicationContext.getSharedPreferences(GEO_PREFS, Context.MODE_PRIVATE)
                prefs.getString(key, null)?.let { parseCoords(it) }?.let { return@withContext it }

                val found = geocode(ctx, city, state, country) ?: return@withContext null
                prefs.edit().putString(key, "${found.first},${found.second}").apply()
                found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseCoords(raw: String?): Pair<Double, Double>? {
        val parts = raw?.split(",") ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun geocode(ctx: Context, city: String, state: String, country: String): Pair<Double, Double>? {
        val params = buildList {
            add("format" to "json")
            add("limit" to "1")
            if (city.isNotEmpty()) add("city" to city)
            if (state.isNotEmpty()) add("state" to state)
            if (country.isNotEmpty()) add("country" to country)
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val conn = URL("$NOMINATIM_URL?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = HTTP_TIMEOUT_MS
            conn.readTimeout = HTTP_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", appUserAgent(ctx))
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val first = Json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject ?: return null
            val lat = first["lat"]?.jsonPrimitive?.content ?: return null
            val lon = first["lon"]?.jsonPrimitive?.content ?: return null
            return parseCoords("$lat,$lon")
        } finally {
            conn.disconnect()
        }
    }
}

private fun createMapView(ctx: Context, lat: Double, lon: Double, dark: Boolean): MapView {
    Configuration.getInstance().apply {
        userAgentValue = appUserAgent(ctx)
        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
    }
    return MapView(ctx).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        isClickable = true
        setMinZoomLevel(MAP_MIN_ZOOM)
        setMaxZoomLevel(MAP_MAX_ZOOM)
        // The map lives in a scrolling list: keep the parent from intercepting pan/zoom gestures.
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        // Normal map colours; in dark theme only dim the tiles a little (full inversion looked orange/purple).
        if (dark) {
            val dim = 0.8f
            overlayManager.tilesOverlay.setColorFilter(
                android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setScale(dim, dim, dim, 1f) },
                ),
            )
        }
        val point = GeoPoint(lat, lon)
        controller.setZoom(MAP_INITIAL_ZOOM)
        controller.setCenter(point)
        val marker = Marker(this).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        overlays.add(marker)
    }
}

/** Static map preview of a sign-in location. Tiles are loaded from OpenStreetMap. */
@Composable
fun SignInMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context, lat, lon, dark) { createMapView(context, lat, lon, dark) }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                mapView.onPause()
            }
            mapView.onDetach()
        }
    }

    Column(modifier = modifier) {
        key(mapView) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                mapView.controller.setZoom(MAP_INITIAL_ZOOM)
                mapView.controller.setCenter(GeoPoint(lat, lon))
            }) { Text("Center") }
        }
    }
}
