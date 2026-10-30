package com.example.components

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.DelhiPlace
import com.example.data.DelhiPlacesData
import com.example.lib.CommuteFormatters
import com.example.lib.Route
import com.example.lib.TravelMode
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBackground
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary
import com.example.ui.theme.RobotoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val DELHI_LAT = 28.6139
const val DELHI_LNG = 77.2090
const val DELHI_ZOOM = 12

private const val MAPBOX_TOKEN_PLACEHOLDER = "YOUR_MAPBOX_ACCESS_TOKEN"

private object SlippyTileCache {
    private val cache = LruCache<String, ImageBitmap>(160)

    @Synchronized
    fun get(key: String): ImageBitmap? = cache.get(key)

    @Synchronized
    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

// Geographic vector basemap corridors (rivers, parks, and highways) so the map always renders rich Google Maps
// cartography immediately even before remote raster tiles finish loading.
private val YAMUNA_RIVER_PATH = listOf(
    Pair(28.8200, 77.2150),
    Pair(28.7200, 77.2320),
    Pair(28.6650, 77.2430),
    Pair(28.6200, 77.2520),
    Pair(28.5700, 77.2680),
    Pair(28.5350, 77.3080),
    Pair(28.4200, 77.4800),
    Pair(27.9500, 77.6500),
    Pair(27.5050, 77.6850),
    Pair(27.1800, 78.0200)
)

private val HINDON_RIVER_PATH = listOf(
    Pair(28.7500, 77.3900),
    Pair(28.6650, 77.4050),
    Pair(28.6150, 77.4180),
    Pair(28.5400, 77.4450),
    Pair(28.4500, 77.4800)
)

private val MAJOR_HIGHWAYS = listOf(
    // NH-9 / Delhi-Meerut Expressway (Connaught Place -> Akshardham -> Indirapuram -> Siddharth Vihar -> Ghaziabad)
    listOf(
        Pair(28.6304, 77.2177),
        Pair(28.6127, 77.2773),
        Pair(28.6270, 77.3350),
        Pair(28.6380, 77.3720),
        Pair(28.6512, 77.4198),
        Pair(28.6750, 77.5000)
    ),
    // NH-19 / Delhi-Mathura-Agra Highway (Nizamuddin -> Ashram -> Badarpur -> Faridabad -> Palwal -> Mathura -> Agra)
    listOf(
        Pair(28.5880, 77.2530),
        Pair(28.5710, 77.2590),
        Pair(28.5020, 77.3010),
        Pair(28.4089, 77.3178),
        Pair(28.1480, 77.3320),
        Pair(27.4924, 77.6737),
        Pair(27.1767, 78.0081)
    ),
    // Yamuna Expressway (Noida -> Pari Chowk -> Jewar -> Vrindavan -> Mathura -> Agra)
    listOf(
        Pair(28.5672, 77.3210),
        Pair(28.5011, 77.3992),
        Pair(28.4670, 77.5138),
        Pair(28.1200, 77.5900),
        Pair(27.5650, 77.6950),
        Pair(27.2000, 77.9800)
    ),
    // NH-48 / Delhi-Gurugram Expressway (Connaught Place -> Dhaula Kuan -> IGI Airport -> Cyber Hub -> Gurugram)
    listOf(
        Pair(28.6304, 77.2177),
        Pair(28.5921, 77.1616),
        Pair(28.5562, 77.1000),
        Pair(28.4950, 77.0890),
        Pair(28.4595, 77.0266)
    ),
    // Delhi Ring Road Loop
    listOf(
        Pair(28.6692, 77.2315),
        Pair(28.6289, 77.2410),
        Pair(28.5690, 77.2580),
        Pair(28.5672, 77.2100),
        Pair(28.5880, 77.1620),
        Pair(28.6520, 77.1450),
        Pair(28.6980, 77.1920),
        Pair(28.6692, 77.2315)
    ),
    // Blue Line / Noida-Ghaziabad Arterial Link (Vaishali -> Kaushambi -> Sector 62 -> Sector 52 -> Botanical Garden)
    listOf(
        Pair(28.6512, 77.4198),
        Pair(28.6448, 77.3726),
        Pair(28.6499, 77.3397),
        Pair(28.6280, 77.3649),
        Pair(28.5969, 77.3752),
        Pair(28.5641, 77.3342)
    )
)

private val GREEN_PARKS = listOf(
    Triple(28.6150, 77.1850, 26f), // Central Ridge Reserve
    Triple(28.5933, 77.2197, 16f), // Lodhi Garden
    Triple(28.6129, 77.2295, 18f), // India Gate Lawns
    Triple(28.5350, 77.1800, 22f), // Sanjay Van
    Triple(28.5520, 77.3120, 18f), // Okhla Bird Sanctuary
    Triple(28.6420, 77.3680, 14f), // Swarn Jayanti Park Indirapuram
    Triple(27.5650, 77.6650, 18f)  // Vrindavan Forest Belt
)

private fun lngToTileX(lng: Double, zoom: Int): Double {
    val n = 2.0.pow(zoom.toDouble())
    return ((lng + 180.0) / 360.0) * n
}

private fun latToTileY(lat: Double, zoom: Int): Double {
    val clampedLat = lat.coerceIn(-85.0511, 85.0511)
    val latRad = clampedLat * PI / 180.0
    val n = 2.0.pow(zoom.toDouble())
    return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
}

private fun tileXToLng(tileX: Double, zoom: Int): Double {
    val n = 2.0.pow(zoom.toDouble())
    return tileX / n * 360.0 - 180.0
}

private fun tileYToLat(tileY: Double, zoom: Int): Double {
    val n = 2.0.pow(zoom.toDouble())
    val latRad = kotlin.math.atan(kotlin.math.sinh(PI * (1.0 - 2.0 * tileY / n)))
    return latRad * 180.0 / PI
}

private fun downloadTileBitmap(
    mapboxToken: String,
    zoom: Int,
    tx: Int,
    ty: Int
): ImageBitmap? {
    val subdomains = arrayOf("a", "b", "c", "d")
    val sub = subdomains[abs(tx + ty) % subdomains.size]
    val candidateUrls = buildList {
        if (mapboxToken.isNotEmpty()) {
            add("https://api.mapbox.com/styles/v1/mapbox/streets-v12/tiles/256/$zoom/$tx/$ty@2x?access_token=$mapboxToken")
        }
        add("https://$sub.basemaps.cartocdn.com/rastertiles/voyager/$zoom/$tx/$ty@2x.png")
        add("https://$sub.basemaps.cartocdn.com/rastertiles/voyager/$zoom/$tx/$ty.png")
        add("https://tile.openstreetmap.org/$zoom/$tx/$ty.png")
    }

    for (tileUrl in candidateUrls) {
        val decoded = runCatching {
            val conn = (URL(tileUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 CommuteBuddy/1.0"
                )
                setRequestProperty("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
            }
            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } else {
                null
            }
        }.getOrNull()

        if (decoded != null) {
            return decoded
        }
    }
    return null
}

// Native Jetpack Compose slippy-map canvas (no Chromium WebView required):
// - Density-scaled 256dp tiles so high-DPI mobile screens are completely covered with crisp cartography.
// - Parallel multi-CDN tile fetching (Mapbox Streets v12 @2x -> CARTO Voyager @2x -> OpenStreetMap).
// - Rich Google Maps-style vector basemap (rivers, parks, highways, locality labels) + interactive pan/zoom & +/- controls.
@Composable
fun LeafletMapView(
    centerLat: Double = DELHI_LAT,
    centerLng: Double = DELHI_LNG,
    zoom: Int = DELHI_ZOOM,
    startPlace: DelhiPlace? = null,
    endPlace: DelhiPlace? = null,
    selectedRoute: Route? = null,
    allRoutes: List<Route> = emptyList(),
    isRoutesSheetOpen: Boolean = false,
    isLiveRideActive: Boolean = false,
    onToggleLiveRide: () -> Unit = {},
    recenterTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val tileSizePx = with(density) { 256.dp.toPx().toDouble() }

    val mapboxToken = remember {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN.trim()
        if (token.isEmpty() || token == MAPBOX_TOKEN_PLACEHOLDER) {
            Log.i("LeafletMapView", "Zero-key OpenStreetMap & CARTO Voyager mode active.")
            ""
        } else {
            token
        }
    }

    var viewportLat by remember { mutableDoubleStateOf(centerLat) }
    var viewportLng by remember { mutableDoubleStateOf(centerLng) }
    var viewportZoom by remember { mutableFloatStateOf(zoom.toFloat()) }
    var canvasWidthPx by remember { mutableIntStateOf(1080) }
    var canvasHeightPx by remember { mutableIntStateOf(1920) }
    var tileLoadTick by remember { mutableIntStateOf(0) }

    // Live Car on Road simulation state (0f = trip origin, 1f = destination)
    var carProgress by remember(selectedRoute?.id) { mutableFloatStateOf(0.06f) }
    var isCarPaused by remember(selectedRoute?.id) { mutableStateOf(false) }
    var followCarCamera by remember(isLiveRideActive) { mutableStateOf(isLiveRideActive) }

    LaunchedEffect(isLiveRideActive) {
        if (isLiveRideActive) {
            followCarCamera = true
            isCarPaused = false
        }
    }

    // Continuous smooth car movement along the active road polyline
    LaunchedEffect(selectedRoute?.id, isLiveRideActive, isCarPaused) {
        val pathPts = selectedRoute?.path
        if (pathPts == null || pathPts.size < 2) {
            carProgress = 0f
            return@LaunchedEffect
        }
        while (!isCarPaused) {
            delay(50L) // 20 FPS smooth road interpolation
            val stepIncrement = if (isLiveRideActive) 0.0038f else 0.0022f
            val next = carProgress + stepIncrement
            carProgress = if (next >= 1f) 0.02f else next
        }
    }

    // Compute exact interpolated (lat, lng, segmentIndex) of the Live Car on the road
    val carRoadPosition = remember(selectedRoute?.path, carProgress) {
        val pts = selectedRoute?.path
        if (pts == null || pts.size < 2) {
            null
        } else {
            val segLengths = DoubleArray(pts.size - 1)
            var totalLen = 0.0
            for (i in 0 until pts.size - 1) {
                val d = DelhiPlacesData.haversineMeters(
                    pts[i].first,
                    pts[i].second,
                    pts[i + 1].first,
                    pts[i + 1].second
                ).coerceAtLeast(1.0)
                segLengths[i] = d
                totalLen += d
            }
            val targetDist = (carProgress.coerceIn(0f, 1f) * totalLen)
            var accum = 0.0
            var chosenIdx = 0
            var localT = 0.0
            for (i in segLengths.indices) {
                val seg = segLengths[i]
                if (accum + seg >= targetDist || i == segLengths.lastIndex) {
                    chosenIdx = i
                    localT = ((targetDist - accum) / seg).coerceIn(0.0, 1.0)
                    break
                }
                accum += seg
            }
            val p0 = pts[chosenIdx]
            val p1 = pts[(chosenIdx + 1).coerceAtMost(pts.lastIndex)]
            val lat = p0.first + (p1.first - p0.first) * localT
            val lng = p0.second + (p1.second - p0.second) * localT
            Triple(lat, lng, chosenIdx)
        }
    }

    // Follow the moving car on the road when Live Road View camera tracking is active
    LaunchedEffect(carRoadPosition, isLiveRideActive, followCarCamera, isRoutesSheetOpen) {
        if (isLiveRideActive && followCarCamera && carRoadPosition != null) {
            val (cLat, cLng, _) = carRoadPosition
            val distKm = selectedRoute?.distanceKm ?: 12.0
            val roadZoom = when {
                distKm > 120.0 -> 11f
                distKm > 45.0 -> 12.5f
                distKm > 15.0 -> 13.5f
                else -> 14.5f
            }
            viewportZoom = roadZoom
            val latOffsetForSheet = if (isRoutesSheetOpen) -0.0035 * (15f / roadZoom) else 0.0
            viewportLat = (cLat + latOffsetForSheet).coerceIn(-80.0, 80.0)
            viewportLng = cLng.coerceIn(-179.0, 179.0)
        }
    }

    // Recenter when recenter button is pressed
    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0) {
            followCarCamera = false
            viewportLat = centerLat
            viewportLng = centerLng
            viewportZoom = zoom.toFloat()
        }
    }

    // Automatically fit bounds when startPlace, endPlace, or selectedRoute changes (when not locked to car camera)
    LaunchedEffect(startPlace, endPlace, selectedRoute, isRoutesSheetOpen, isLiveRideActive) {
        if (isLiveRideActive && followCarCamera) return@LaunchedEffect
        val points = buildList {
            if (selectedRoute != null && selectedRoute.path.size >= 2) {
                addAll(selectedRoute.path)
            } else {
                if (startPlace != null) add(Pair(startPlace.lat, startPlace.lng))
                if (endPlace != null) add(Pair(endPlace.lat, endPlace.lng))
            }
        }
        if (points.size >= 2) {
            val minLat = points.minOf { it.first }
            val maxLat = points.maxOf { it.first }
            val minLng = points.minOf { it.second }
            val maxLng = points.maxOf { it.second }

            val latSpan = max(0.008, (maxLat - minLat) * 1.45)
            val lngSpan = max(0.008, (maxLng - minLng) * 1.45)

            // Offset center slightly south when bottom sheet is open so route sits in upper visible area
            val sheetOffsetLat = if (isRoutesSheetOpen) -latSpan * 0.16 else 0.0
            viewportLat = ((minLat + maxLat) / 2.0 + sheetOffsetLat).coerceIn(-80.0, 80.0)
            viewportLng = ((minLng + maxLng) / 2.0).coerceIn(-179.0, 179.0)

            val maxSpan = max(latSpan, lngSpan)
            viewportZoom = when {
                maxSpan > 12.0 -> 5f
                maxSpan > 6.0 -> 6f
                maxSpan > 3.0 -> 7f
                maxSpan > 1.5 -> 8f
                maxSpan > 0.7 -> 9f
                maxSpan > 0.35 -> 10f
                maxSpan > 0.16 -> 11f
                maxSpan > 0.08 -> 12f
                maxSpan > 0.04 -> 13f
                else -> 14f
            }
        } else if (startPlace != null) {
            viewportLat = startPlace.lat
            viewportLng = startPlace.lng
            viewportZoom = 13f
        } else if (endPlace != null) {
            viewportLat = endPlace.lat
            viewportLng = endPlace.lng
            viewportZoom = 13f
        }
    }

    val discreteZoom = viewportZoom.roundToInt().coerceIn(4, 17)

    // Parallel prefetch of visible 5x5 slippy tiles ordered from center outward
    LaunchedEffect(viewportLat, viewportLng, discreteZoom, mapboxToken) {
        val centerTileX = floor(lngToTileX(viewportLng, discreteZoom)).toInt()
        val centerTileY = floor(latToTileY(viewportLat, discreteZoom)).toInt()
        val maxTileIndex = (1 shl discreteZoom) - 1
        val prefix = if (mapboxToken.isNotEmpty()) "mb" else "osm"

        val tileOffsets = buildList {
            for (dx in -2..2) {
                for (dy in -2..2) {
                    add(Pair(dx, dy))
                }
            }
        }.sortedBy { (dx, dy) -> dx * dx + dy * dy }

        withContext(Dispatchers.IO) {
            coroutineScope {
                for ((dx, dy) in tileOffsets) {
                    val tx = (centerTileX + dx).coerceIn(0, maxTileIndex)
                    val ty = (centerTileY + dy).coerceIn(0, maxTileIndex)
                    val cacheKey = "${prefix}_${discreteZoom}_${tx}_$ty"
                    if (SlippyTileCache.get(cacheKey) != null) continue

                    launch {
                        val bmp = downloadTileBitmap(mapboxToken, discreteZoom, tx, ty)
                        if (bmp != null) {
                            SlippyTileCache.put(cacheKey, bmp)
                            withContext(Dispatchers.Main) {
                                tileLoadTick += 1
                            }
                        }
                    }
                }
            }
        }
    }

    fun latLngToScreenOffset(lat: Double, lng: Double, widthPx: Float, heightPx: Float): Offset {
        val centerTx = lngToTileX(viewportLng, discreteZoom)
        val centerTy = latToTileY(viewportLat, discreteZoom)
        val ptTx = lngToTileX(lng, discreteZoom)
        val ptTy = latToTileY(lat, discreteZoom)
        val sx = (widthPx / 2f) + ((ptTx - centerTx) * tileSizePx).toFloat()
        val sy = (heightPx / 2f) + ((ptTy - centerTy) * tileSizePx).toFloat()
        return Offset(sx, sy)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapsBackground)
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    canvasWidthPx = size.width
                    canvasHeightPx = size.height
                }
            }
            .pointerInput(tileSizePx) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val z = viewportZoom.roundToInt().coerceIn(4, 17)
                    val curTx = lngToTileX(viewportLng, z) - (pan.x / tileSizePx)
                    val curTy = latToTileY(viewportLat, z) - (pan.y / tileSizePx)
                    viewportLng = tileXToLng(curTx, z).coerceIn(68.0, 97.5)
                    viewportLat = tileYToLat(curTy, z).coerceIn(6.5, 37.2)
                    if (abs(gestureZoom - 1f) > 0.01f) {
                        val zoomDelta = (ln(gestureZoom.toDouble()) / ln(2.0)).toFloat()
                        viewportZoom = (viewportZoom + zoomDelta).coerceIn(4f, 17f)
                    }
                }
            }
            .testTag("leaflet_map_container")
    ) {
        // Keep testTag("leaflet_webview") on the map drawing surface for test compatibility
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("leaflet_webview")
        ) {
            // Read tileLoadTick so Canvas recomposes when background tiles finish loading
            val currentTick = tileLoadTick
            if (currentTick < 0) return@Canvas

            val w = size.width
            val h = size.height

            // 1. Base Google Maps land canvas (#f1f3f4) and urban sector grid
            drawRect(color = Color(0xFFF1F3F4))

            val gridSpacing = 64.dp.toPx()
            val centerScreen = latLngToScreenOffset(DELHI_LAT, DELHI_LNG, w, h)
            var gx = ((centerScreen.x % gridSpacing) + gridSpacing) % gridSpacing
            while (gx < w) {
                drawLine(
                    color = Color(0xFFE4E7EB),
                    start = Offset(gx, 0f),
                    end = Offset(gx, h),
                    strokeWidth = 1.dp.toPx()
                )
                gx += gridSpacing
            }
            var gy = ((centerScreen.y % gridSpacing) + gridSpacing) % gridSpacing
            while (gy < h) {
                drawLine(
                    color = Color(0xFFE4E7EB),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1.dp.toPx()
                )
                gy += gridSpacing
            }

            // 2. Google Maps vector basemap layer (parks, rivers, and major expressways)
            val zoomScale = (discreteZoom / 12f).coerceIn(0.6f, 1.8f)
            for ((pLat, pLng, radiusDp) in GREEN_PARKS) {
                val pt = latLngToScreenOffset(pLat, pLng, w, h)
                if (pt.x in -120f..(w + 120f) && pt.y in -120f..(h + 120f)) {
                    drawCircle(
                        color = Color(0xFFCEEAD6),
                        radius = radiusDp.dp.toPx() * zoomScale,
                        center = pt
                    )
                }
            }

            // Yamuna & Hindon rivers in Google Maps water blue (#aadaff)
            for (riverCoords in listOf(YAMUNA_RIVER_PATH, HINDON_RIVER_PATH)) {
                val riverPath = Path()
                riverCoords.forEachIndexed { idx, (lat, lng) ->
                    val pt = latLngToScreenOffset(lat, lng, w, h)
                    if (idx == 0) riverPath.moveTo(pt.x, pt.y) else riverPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = riverPath,
                    color = Color(0xFFAADAFF),
                    style = Stroke(
                        width = 8.dp.toPx() * zoomScale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // National Highways & Expressways in Google Maps highway yellow (#fde293 with #e8ab00 casing)
            for (hwyCoords in MAJOR_HIGHWAYS) {
                val hwyPath = Path()
                hwyCoords.forEachIndexed { idx, (lat, lng) ->
                    val pt = latLngToScreenOffset(lat, lng, w, h)
                    if (idx == 0) hwyPath.moveTo(pt.x, pt.y) else hwyPath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = hwyPath,
                    color = Color(0xFFE5C158),
                    style = Stroke(
                        width = 5.5.dp.toPx() * zoomScale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                drawPath(
                    path = hwyPath,
                    color = Color(0xFFFDE293),
                    style = Stroke(
                        width = 3.8.dp.toPx() * zoomScale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // 3. Draw cached high-DPI slippy map tiles (scaled to 256dp per tile)
            val centerTx = lngToTileX(viewportLng, discreteZoom)
            val centerTy = latToTileY(viewportLat, discreteZoom)
            val baseTileX = floor(centerTx).toInt()
            val baseTileY = floor(centerTy).toInt()
            val maxTileIndex = (1 shl discreteZoom) - 1
            val prefix = if (mapboxToken.isNotEmpty()) "mb" else "osm"
            val tileDstSizePx = tileSizePx.roundToInt() + 2

            for (dx in -2..2) {
                for (dy in -2..2) {
                    val tx = (baseTileX + dx).coerceIn(0, maxTileIndex)
                    val ty = (baseTileY + dy).coerceIn(0, maxTileIndex)
                    val bmp = SlippyTileCache.get("${prefix}_${discreteZoom}_${tx}_$ty") ?: continue
                    val leftPx = (w / 2f) + ((tx - centerTx) * tileSizePx).toFloat()
                    val topPx = (h / 2f) + ((ty - centerTy) * tileSizePx).toFloat()
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(leftPx.roundToInt(), topPx.roundToInt()),
                        dstSize = IntSize(tileDstSizePx, tileDstSizePx)
                    )
                }
            }

            // 4. Draw transit hub and sub-location markers visible in viewport
            for (hub in DelhiPlacesData.places) {
                val pt = latLngToScreenOffset(hub.lat, hub.lng, w, h)
                if (pt.x in -20f..(w + 20f) && pt.y in -20f..(h + 20f)) {
                    drawCircle(
                        color = Color(0xFFFFFFFF),
                        radius = 4.5.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = if (hub.stationId != null) Color(0xFF1A73E8) else Color(0xFF5F6368),
                        radius = 2.8.dp.toPx(),
                        center = pt
                    )
                }
            }

            // 5. Draw alternate routes (#8ab4f8 with #ffffff casing)
            val altRoutes = allRoutes.filter { it.id != selectedRoute?.id }
            for (alt in altRoutes) {
                if (alt.path.size >= 2) {
                    val altPath = Path()
                    alt.path.forEachIndexed { idx, (lat, lng) ->
                        val p = latLngToScreenOffset(lat, lng, w, h)
                        if (idx == 0) altPath.moveTo(p.x, p.y) else altPath.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path = altPath,
                        color = Color(0xFFFFFFFF),
                        style = Stroke(
                            width = 8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    drawPath(
                        path = altPath,
                        color = Color(0xFF8AB4F8),
                        style = Stroke(
                            width = 5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }

            // 6. Draw selected route road corridor + lane markings + Live Car on Road
            val selPts = selectedRoute?.path
            if (selPts != null && selPts.size >= 2) {
                val screenPts = selPts.map { (lat, lng) -> latLngToScreenOffset(lat, lng, w, h) }
                val fullRoadPath = Path()
                screenPts.forEachIndexed { idx, p ->
                    if (idx == 0) fullRoadPath.moveTo(p.x, p.y) else fullRoadPath.lineTo(p.x, p.y)
                }

                // Outer road shoulder casing (#ffffff)
                drawPath(
                    path = fullRoadPath,
                    color = Color(0xFFFFFFFF),
                    style = Stroke(
                        width = 14.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Base road asphalt surface (#5f6368)
                drawPath(
                    path = fullRoadPath,
                    color = Color(0xFF5F6368),
                    style = Stroke(
                        width = 10.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Active Google Maps route corridor (#1a73e8)
                drawPath(
                    path = fullRoadPath,
                    color = Color(0xFF1A73E8),
                    style = Stroke(
                        width = 7.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Center road lane dashed markings (#ffffff) so road lanes are clearly visible
                drawPath(
                    path = fullRoadPath,
                    color = Color(0xFFE8F0FE),
                    style = Stroke(
                        width = 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(10.dp.toPx(), 10.dp.toPx()),
                            0f
                        )
                    )
                )

                // Draw Live Moving Car on the Road with real-time road heading rotation
                if (carRoadPosition != null) {
                    val (cLat, cLng, segIdx) = carRoadPosition
                    val carPt = latLngToScreenOffset(cLat, cLng, w, h)
                    val pA = screenPts[segIdx.coerceIn(0, screenPts.lastIndex)]
                    val pB = screenPts[(segIdx + 1).coerceIn(0, screenPts.lastIndex)]
                    val dx = pB.x - pA.x
                    val dy = pB.y - pA.y
                    val bearingDeg = if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
                        (atan2(dx.toDouble(), (-dy).toDouble()) * 180.0 / PI).toFloat()
                    } else {
                        0f
                    }

                    // Draw traversed road behind the car in muted grey (#dadce0)
                    if (segIdx > 0) {
                        val passedPath = Path()
                        for (i in 0..segIdx) {
                            val pt = screenPts[i]
                            if (i == 0) passedPath.moveTo(pt.x, pt.y) else passedPath.lineTo(pt.x, pt.y)
                        }
                        passedPath.lineTo(carPt.x, carPt.y)
                        drawPath(
                            path = passedPath,
                            color = Color(0xFF8AB4F8),
                            style = Stroke(
                                width = 5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // Outer navigation puck pulse ring
                    drawCircle(
                        color = Color(0xFFE8F0FE),
                        radius = 18.dp.toPx(),
                        center = carPt
                    )
                    drawCircle(
                        color = Color(0xFF1A73E8),
                        radius = 18.dp.toPx(),
                        center = carPt,
                        style = Stroke(width = 1.8.dp.toPx())
                    )

                    // Top-down Car icon rotated along the road direction
                    withTransform({
                        translate(left = carPt.x, top = carPt.y)
                        rotate(degrees = bearingDeg, pivot = Offset.Zero)
                    }) {
                        val carW = 12.dp.toPx()
                        val carH = 22.dp.toPx()

                        // Forward directional road beam
                        val beamPath = Path().apply {
                            moveTo(0f, -carH * 0.5f)
                            lineTo(-carW * 0.9f, -carH * 1.15f)
                            lineTo(carW * 0.9f, -carH * 1.15f)
                            close()
                        }
                        drawPath(
                            path = beamPath,
                            color = Color(0xFF8AB4F8)
                        )

                        // Four wheels on the road
                        val wheelW = 2.6.dp.toPx()
                        val wheelH = 5.dp.toPx()
                        drawRoundRect(
                            color = Color(0xFF202124),
                            topLeft = Offset(-carW / 2f - wheelW * 0.6f, -carH * 0.34f),
                            size = Size(wheelW, wheelH),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                        drawRoundRect(
                            color = Color(0xFF202124),
                            topLeft = Offset(carW / 2f - wheelW * 0.4f, -carH * 0.34f),
                            size = Size(wheelW, wheelH),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                        drawRoundRect(
                            color = Color(0xFF202124),
                            topLeft = Offset(-carW / 2f - wheelW * 0.6f, carH * 0.14f),
                            size = Size(wheelW, wheelH),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                        drawRoundRect(
                            color = Color(0xFF202124),
                            topLeft = Offset(carW / 2f - wheelW * 0.4f, carH * 0.14f),
                            size = Size(wheelW, wheelH),
                            cornerRadius = CornerRadius(2f, 2f)
                        )

                        // Car white outer shell
                        drawRoundRect(
                            color = Color(0xFFFFFFFF),
                            topLeft = Offset(-carW / 2f - 1.5.dp.toPx(), -carH / 2f - 1.5.dp.toPx()),
                            size = Size(carW + 3.dp.toPx(), carH + 3.dp.toPx()),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )

                        // Car main body (#1a73e8)
                        drawRoundRect(
                            color = Color(0xFF1A73E8),
                            topLeft = Offset(-carW / 2f, -carH / 2f),
                            size = Size(carW, carH),
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                        )

                        // Front windshield & roof cabin (#ffffff / #e8f0fe)
                        drawRoundRect(
                            color = Color(0xFFE8F0FE),
                            topLeft = Offset(-carW * 0.36f, -carH * 0.24f),
                            size = Size(carW * 0.72f, carH * 0.48f),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )

                        // Front windshield dark glass bar
                        drawRoundRect(
                            color = Color(0xFF202124),
                            topLeft = Offset(-carW * 0.32f, -carH * 0.26f),
                            size = Size(carW * 0.64f, carH * 0.15f),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    }
                }
            }

            // 7. Draw Start Marker (#1a73e8 with white border)
            if (startPlace != null) {
                val sPt = latLngToScreenOffset(startPlace.lat, startPlace.lng, w, h)
                drawCircle(
                    color = Color(0xFFFFFFFF),
                    radius = 9.dp.toPx(),
                    center = sPt
                )
                drawCircle(
                    color = Color(0xFF1A73E8),
                    radius = 7.dp.toPx(),
                    center = sPt
                )
            }

            // 8. Draw End Marker (#d93025 with white border)
            if (endPlace != null) {
                val ePt = latLngToScreenOffset(endPlace.lat, endPlace.lng, w, h)
                drawCircle(
                    color = Color(0xFFFFFFFF),
                    radius = 9.dp.toPx(),
                    center = ePt
                )
                drawCircle(
                    color = Color(0xFFD93025),
                    radius = 7.dp.toPx(),
                    center = ePt
                )
            }
        }

        // Floating Live Car speed & road status label right above the moving car on the map
        if (selectedRoute != null && carRoadPosition != null) {
            val (cLat, cLng, _) = carRoadPosition
            val carPt = latLngToScreenOffset(cLat, cLng, canvasWidthPx.toFloat(), canvasHeightPx.toFloat())
            val baseSpeedKmh = if (selectedRoute.distanceKm >= 38.0) 68 else 42
            val liveSpeedKmh = if (isCarPaused) 0 else (baseSpeedKmh + ((carProgress * 100).roundToInt() % 7) - 3)
            if (carPt.x in 36f..(canvasWidthPx - 80f) && carPt.y in 80f..(canvasHeightPx - 80f)) {
                val xDp = with(density) { (carPt.x - 42f).coerceAtLeast(8f).toDp() }
                val yDp = with(density) { (carPt.y - 42f).coerceAtLeast(80f).toDp() }
                Text(
                    text = if (isCarPaused) "Car Paused" else "Live Car • $liveSpeedKmh km/h",
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MapsSurface,
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .background(MapsAccentBlue, CircleShape)
                        .border(1.dp, MapsSurface, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("live_car_road_badge")
                )
            }
        }

        // Visible nearby locality & station labels so the map always has clear place labels
        val visibleLandmarks = remember(viewportLat, viewportLng, discreteZoom, canvasWidthPx, canvasHeightPx) {
            val selectedIds = setOfNotNull(startPlace?.id, endPlace?.id)
            val chosen = mutableListOf<Pair<DelhiPlace, Offset>>()
            for (place in DelhiPlacesData.places) {
                if (place.id in selectedIds) continue
                val pt = latLngToScreenOffset(
                    place.lat,
                    place.lng,
                    canvasWidthPx.toFloat(),
                    canvasHeightPx.toFloat()
                )
                if (pt.x in 48f..(canvasWidthPx - 140f) && pt.y in 120f..(canvasHeightPx - 140f)) {
                    val overlaps = chosen.any { (_, otherPt) ->
                        abs(otherPt.x - pt.x) < 150f && abs(otherPt.y - pt.y) < 48f
                    }
                    if (!overlaps) {
                        chosen.add(place to pt)
                        if (chosen.size >= 8) break
                    }
                }
            }
            chosen
        }

        for ((landmark, pt) in visibleLandmarks) {
            val xDp = with(density) { (pt.x + 8f).toDp() }
            val yDp = with(density) { (pt.y - 10f).toDp() }
            Text(
                text = landmark.name,
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = MapsTextSecondary,
                modifier = Modifier
                    .offset(x = xDp, y = yDp)
                    .background(MapsSurface.copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // Start & End place floating map labels
        if (startPlace != null) {
            val sPt = latLngToScreenOffset(startPlace.lat, startPlace.lng, canvasWidthPx.toFloat(), canvasHeightPx.toFloat())
            if (sPt.x in 24f..(canvasWidthPx - 24f) && sPt.y in 48f..(canvasHeightPx - 48f)) {
                val xDp = with(density) { sPt.x.toDp() }
                val yDp = with(density) { (sPt.y - 34f).toDp() }
                Text(
                    text = startPlace.name,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MapsTextPrimary,
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .background(MapsSurface, RoundedCornerShape(6.dp))
                        .border(1.dp, MapsAccentBlue, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        if (endPlace != null) {
            val ePt = latLngToScreenOffset(endPlace.lat, endPlace.lng, canvasWidthPx.toFloat(), canvasHeightPx.toFloat())
            if (ePt.x in 24f..(canvasWidthPx - 24f) && ePt.y in 48f..(canvasHeightPx - 48f)) {
                val xDp = with(density) { ePt.x.toDp() }
                val yDp = with(density) { (ePt.y + 12f).toDp() }
                Text(
                    text = endPlace.name,
                    fontFamily = RobotoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MapsTextPrimary,
                    modifier = Modifier
                        .offset(x = xDp, y = yDp)
                        .background(MapsSurface, RoundedCornerShape(6.dp))
                        .border(1.dp, MapsBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Floating "Live Car on Road" toggle pill & active road navigation HUD
        if (selectedRoute != null) {
            val remFraction = (1f - carProgress).coerceIn(0f, 1f)
            val remKm = ((selectedRoute.distanceKm * remFraction) * 10.0).roundToInt() / 10.0
            val remSec = (selectedRoute.durationSeconds * remFraction).roundToInt().coerceAtLeast(60)
            val remEtaStr = CommuteFormatters.formatDuration(remSec)
            val stepCount = selectedRoute.steps.size.coerceAtLeast(1)
            val currentStepIdx = (carProgress * stepCount).toInt().coerceIn(0, stepCount - 1)
            val currentStepText = selectedRoute.steps.getOrNull(currentStepIdx)?.text
                ?: "Driving along active road corridor towards ${endPlace?.name ?: "destination"}"

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 16.dp,
                        end = 76.dp,
                        bottom = if (isRoutesSheetOpen) 116.dp else 28.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLiveRideActive) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MapsSurface,
                        border = BorderStroke(1.dp, MapsAccentBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp), clip = false)
                            .testTag("live_car_road_hud")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "LIVE CAR ON ROAD • ${(carProgress * 100).roundToInt()}%",
                                    fontFamily = RobotoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MapsAccentBlue
                                )
                                Text(
                                    text = "$remKm km • $remEtaStr left",
                                    fontFamily = RobotoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = MapsTextPrimary
                                )
                            }
                            Text(
                                text = currentStepText,
                                fontFamily = RobotoFontFamily,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = MapsTextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    onClick = { isCarPaused = !isCarPaused },
                                    shape = CircleShape,
                                    color = MapsSelectedTint,
                                    border = BorderStroke(1.dp, MapsBorder),
                                    modifier = Modifier
                                        .heightIn(min = 36.dp)
                                        .testTag("live_car_pause_resume_button")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isCarPaused) "Resume Car" else "Pause Car",
                                            fontFamily = RobotoFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = MapsAccentBlue
                                        )
                                    }
                                }

                                Surface(
                                    onClick = { followCarCamera = !followCarCamera },
                                    shape = CircleShape,
                                    color = if (followCarCamera) MapsAccentBlue else MapsSurface,
                                    border = BorderStroke(1.dp, MapsAccentBlue),
                                    modifier = Modifier
                                        .heightIn(min = 36.dp)
                                        .testTag("live_car_follow_camera_button")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (followCarCamera) "Following Car" else "Follow Car",
                                            fontFamily = RobotoFontFamily,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = if (followCarCamera) MapsSurface else MapsAccentBlue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 48dp pill button to toggle Live Car Road View at any time
                Surface(
                    onClick = onToggleLiveRide,
                    shape = CircleShape,
                    color = if (isLiveRideActive) MapsAccentBlue else MapsSurface,
                    border = BorderStroke(1.dp, MapsAccentBlue),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                        .testTag("toggle_live_car_view_button")
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TravelModeIcon(
                            mode = TravelMode.CAR,
                            tint = if (isLiveRideActive) MapsSurface else MapsAccentBlue,
                            iconSize = 20.dp
                        )
                        Text(
                            text = if (isLiveRideActive) "Exit Live Road View" else "Live Car Road View",
                            fontFamily = RobotoFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = if (isLiveRideActive) MapsSurface else MapsAccentBlue
                        )
                    }
                }
            }
        }

        // Google Maps style + / - zoom controls on the right edge (48dp touch targets)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MapsSurface,
            border = BorderStroke(1.dp, MapsBorder),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = if (isRoutesSheetOpen) 168.dp else 80.dp
                )
                .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp), clip = false)
                .testTag("map_zoom_controls")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable {
                            followCarCamera = false
                            viewportZoom = (viewportZoom + 1f).coerceIn(4f, 17f)
                        }
                        .testTag("map_zoom_in_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        color = MapsTextPrimary
                    )
                }
                HorizontalDivider(
                    color = MapsBorder,
                    thickness = 1.dp,
                    modifier = Modifier.size(width = 32.dp, height = 1.dp)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable {
                            followCarCamera = false
                            viewportZoom = (viewportZoom - 1f).coerceIn(4f, 17f)
                        }
                        .testTag("map_zoom_out_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "−",
                        fontFamily = RobotoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        color = MapsTextPrimary
                    )
                }
            }
        }

        // Single clean attribution at bottom-right (8px grid padding)
        Text(
            text = if (mapboxToken.isNotEmpty()) "© Mapbox © OpenStreetMap" else "© OpenStreetMap • OSRM Live Road",
            fontFamily = RobotoFontFamily,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MapsTextSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(MapsSurface)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("osm_attribution")
        )
    }
}
