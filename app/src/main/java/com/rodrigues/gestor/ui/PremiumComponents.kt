package com.rodrigues.gestor.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rodrigues.gestor.data.DeliveryTracking
import com.rodrigues.gestor.ui.theme.AcaiPurple
import com.rodrigues.gestor.ui.theme.AcaiPurpleDark
import com.rodrigues.gestor.ui.theme.RodriguesLime
import com.rodrigues.gestor.ui.theme.RodriguesLimeDark
import com.rodrigues.gestor.ui.theme.WarningOrange
import java.util.Locale
import kotlin.math.roundToInt

private data class TrackingMetric(
    val label: String,
    val value: String,
    val icon: @Composable () -> Unit,
)

@Composable
internal fun DeliveryTrackingCard(
    tracking: DeliveryTracking,
    driverName: String,
    trackingEnabled: Boolean,
) {
    val eta = tracking.etaRange()
    val remaining = tracking.remainingKm()
    val stale = tracking.stale()
    val metrics = buildList {
        if (tracking.traveledMeters > 0.0) add(
            TrackingMetric("Percorrida", formatDistance(tracking.traveledMeters)) {
                Icon(Icons.Default.Route, null, modifier = Modifier.size(19.dp), tint = AcaiPurple)
            }
        )
        if (remaining != null) add(
            TrackingMetric("Restante", formatDistance(remaining * 1_000.0)) {
                Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(19.dp), tint = AcaiPurple)
            }
        )
        if (eta != null && !stale) add(
            TrackingMetric("Previsão", "${eta.first}–${eta.last} min") {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(19.dp), tint = AcaiPurple)
            }
        )
        if (tracking.stopsBefore > 0) add(
            TrackingMetric("Antes", "${tracking.stopsBefore} ${if (tracking.stopsBefore == 1) "parada" else "paradas"}") {
                Icon(Icons.Default.Route, null, modifier = Modifier.size(19.dp), tint = AcaiPurple)
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    color = RodriguesLime.copy(alpha = .18f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.TwoWheeler, null, tint = RodriguesLimeDark, modifier = Modifier.size(25.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("ENTREGA AO VIVO", color = RodriguesLimeDark, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .8.sp)
                    Text(driverName.ifBlank { "Entregador alocado" }, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(
                        if (trackingEnabled) "Mapa compartilhado com o cliente" else "GPS interno • mapa do cliente desligado",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Surface(
                    color = if (stale) WarningOrange.copy(alpha = .14f) else RodriguesLime.copy(alpha = .16f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (stale) "SINAL FRACO" else "AO VIVO",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        color = if (stale) Color(0xFF955B05) else RodriguesLimeDark,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            if (tracking.hasMap) {
                DeliveryMap(tracking)
                Spacer(Modifier.height(10.dp))
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = AcaiPurple)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Preparando o mapa", fontWeight = FontWeight.Bold)
                            Text("Aguardando as coordenadas do entregador e do cliente.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (stale) WarningOrange.copy(alpha = .10f) else RodriguesLime.copy(alpha = .10f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (stale) WarningOrange else RodriguesLime, RoundedCornerShape(50))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        tracking.freshness(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (stale) Color(0xFF955B05) else RodriguesLimeDark,
                    )
                }
            }

            if (metrics.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(metrics) { metric ->
                        Surface(
                            modifier = Modifier.width(120.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                metric.icon()
                                Spacer(Modifier.height(8.dp))
                                Text(metric.value, fontWeight = FontWeight.Black, fontSize = 16.sp, color = AcaiPurpleDark)
                                Text(metric.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (stale) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "O ETA foi ocultado porque a localização está desatualizada.",
                    color = Color(0xFF955B05),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DeliveryMap(tracking: DeliveryTracking) {
    val context = LocalContext.current
    val html = remember(
        tracking.driver.lat,
        tracking.driver.lng,
        tracking.customer.lat,
        tracking.customer.lng,
    ) { mapHtml(tracking) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(245.dp),
        factory = {
            WebView(context).apply {
                webViewHolder[0] = this
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(false)
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            if (view.tag != html.hashCode()) {
                view.tag = html.hashCode()
                view.loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.stopLoading()
            webViewHolder[0]?.destroy()
            webViewHolder[0] = null
        }
    }
}

private fun mapHtml(tracking: DeliveryTracking): String = """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
    <style>
      html,body,#map{width:100%;height:100%;margin:0;background:#eeeaf1}body{font-family:Arial,sans-serif}
      .leaflet-control-attribution{font-size:8px}.tag{padding:5px 8px;border-radius:99px;background:#fff;color:#3e006a;font-size:11px;font-weight:800;box-shadow:0 4px 14px #25003733;white-space:nowrap}
      .pin{width:25px;height:25px;border:3px solid #fff;border-radius:50% 50% 50% 7px;transform:rotate(-45deg);box-shadow:0 5px 12px #23003355}.pin.purple{background:#56008f}.pin.green{background:#7fd300}
    </style></head><body><div id="map"></div>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script><script>
      const driver=[${tracking.driver.lat},${tracking.driver.lng}], customer=[${tracking.customer.lat},${tracking.customer.lng}];
      const map=L.map('map',{zoomControl:true,attributionControl:true});
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
      const icon=(c)=>L.divIcon({className:'',html:'<div class="pin '+c+'"></div>',iconSize:[31,31],iconAnchor:[15,28]});
      L.marker(driver,{icon:icon('green')}).addTo(map).bindTooltip('<div class="tag">Entregador</div>',{permanent:true,direction:'top',offset:[0,-18],className:''});
      L.marker(customer,{icon:icon('purple')}).addTo(map).bindTooltip('<div class="tag">Cliente</div>',{permanent:true,direction:'top',offset:[0,-18],className:''});
      const fallback=()=>{const line=L.polyline([driver,customer],{color:'#56008f',weight:5,opacity:.82,dashArray:'8 8'}).addTo(map);map.fitBounds(line.getBounds(),{padding:[36,36]});};
      fetch('https://router.project-osrm.org/route/v1/driving/'+driver[1]+','+driver[0]+';'+customer[1]+','+customer[0]+'?overview=full&geometries=geojson')
        .then(r=>r.ok?r.json():Promise.reject()).then(j=>{const route=j&&j.routes&&j.routes[0];if(!route)return fallback();const line=L.geoJSON(route.geometry,{style:{color:'#56008f',weight:6,opacity:.88}}).addTo(map);map.fitBounds(line.getBounds(),{padding:[36,36]});}).catch(fallback);
    </script></body></html>
""".trimIndent()

private fun formatDistance(meters: Double): String = when {
    meters < 1_000.0 -> "${meters.roundToIntSafe()} m"
    else -> String.format(Locale("pt", "BR"), "%.1f km", meters / 1_000.0)
}

private fun Double.roundToIntSafe(): Int = roundToInt().coerceAtLeast(0)
