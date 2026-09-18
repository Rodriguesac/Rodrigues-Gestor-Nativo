from pathlib import Path

models_path = Path("app/src/main/java/com/rodrigues/gestor/data/Models.kt")
models = models_path.read_text(encoding="utf-8")
old_points = '''        address["coords"],
        deliveryAddress["coords"],
        address,
        deliveryAddress,
'''
new_points = '''        address["coords"],
        address["latlng"],
        address["latLng"],
        deliveryAddress["coords"],
        deliveryAddress["latlng"],
        deliveryAddress["latLng"],
        address,
        deliveryAddress,
'''
if new_points not in models:
    if old_points not in models:
        raise SystemExit("Customer tracking coordinates anchor not found")
    models = models.replace(old_points, new_points, 1)
models_path.write_text(models, encoding="utf-8")

ui_path = Path("app/src/main/java/com/rodrigues/gestor/ui/GestorApp.kt")
ui = ui_path.read_text(encoding="utf-8")

imports = [
    "import android.webkit.WebView\n",
    "import android.webkit.WebViewClient\n",
    "import androidx.compose.ui.viewinterop.AndroidView\n",
]
import_anchor = "import android.app.Activity\n"
for line in imports:
    if line not in ui:
        if import_anchor not in ui:
            raise SystemExit("UI import anchor not found")
        ui = ui.replace(import_anchor, import_anchor + line, 1)
        import_anchor = line

payment_anchor = '''            DetailCard("Pagamento") {
'''
map_call = '''            if (!order.pickup && deliveryTracking.customer.valid) {
                CustomerAddressMapCard(
                    lat = deliveryTracking.customer.lat,
                    lng = deliveryTracking.customer.lng,
                    address = order.address,
                )
            }

'''
if map_call not in ui:
    if payment_anchor not in ui:
        raise SystemExit("Payment card anchor not found")
    ui = ui.replace(payment_anchor, map_call + payment_anchor, 1)

function_anchor = '''@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
'''
map_function = '''@Composable
private fun CustomerAddressMapCard(
    lat: Double,
    lng: Double,
    address: String,
) {
    val context = LocalContext.current
    val delta = 0.006
    val left = lng - delta
    val bottom = lat - delta
    val right = lng + delta
    val top = lat + delta
    val mapUrl = remember(lat, lng) {
        "https://www.openstreetmap.org/export/embed.html?bbox=${left}%2C${bottom}%2C${right}%2C${top}&layer=mapnik&marker=${lat}%2C${lng}"
    }

    DetailCard("Mapa do endereço") {
        Card(
            modifier = Modifier.fillMaxWidth().height(190.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        loadUrl(mapUrl)
                    }
                },
                update = { webView ->
                    if (webView.url != mapUrl) webView.loadUrl(mapUrl)
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            address,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val label = Uri.encode(address.ifBlank { "Cliente" })
                val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng($label)")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, geo))
                } catch (_: Throwable) {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=17/$lat/$lng")
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.LocalShipping, null)
            Spacer(Modifier.width(7.dp))
            Text("ABRIR NO MAPA")
        }
    }
}

'''
if map_function not in ui:
    if function_anchor not in ui:
        raise SystemExit("DetailCard function anchor not found")
    ui = ui.replace(function_anchor, map_function + function_anchor, 1)

ui_path.write_text(ui, encoding="utf-8")
print("Customer address map patch applied")
