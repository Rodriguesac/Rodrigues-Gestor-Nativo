package com.rodrigues.gestor

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.rodrigues.gestor.cast.RodriguesCastBridge
import com.rodrigues.gestor.cast.RodriguesCastConfig
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.OrdersRepository
import com.rodrigues.gestor.data.StatusGroups
import com.rodrigues.gestor.data.money
import com.rodrigues.gestor.ui.theme.RodriguesGestorTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

class KitchenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (GestorCredentials.load(this).length != 6) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RodriguesGestorTheme {
                KitchenRemoteScreen()
            }
        }
    }
}

@Composable
private fun KitchenRemoteScreen() {
    val context = LocalContext.current
    val repository = remember { OrdersRepository() }

    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    var configuredId by remember { mutableStateOf(RodriguesCastConfig.receiverAppId(context)) }
    var appIdInput by remember { mutableStateOf(configuredId) }
    var castConnected by remember { mutableStateOf(false) }
    var castDevice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener = repository.listenOrders(
            onData = {
                orders = it
                loading = false
                errorText = null
            },
            onError = {
                loading = false
                errorText = it.message ?: "Erro ao carregar pedidos"
            }
        )
        onDispose { listener.remove() }
    }

    LaunchedEffect(configuredId) {
        if (configuredId.isBlank()) {
            castConnected = false
            castDevice = null
            return@LaunchedEffect
        }
        while (true) {
            castConnected = RodriguesCastBridge.isConnected(context)
            castDevice = RodriguesCastBridge.deviceName(context)
            delay(1_000)
        }
    }

    LaunchedEffect(orders, castConnected) {
        if (castConnected) RodriguesCastBridge.sendOrders(context, orders)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF7F4FA),
    ) {
        Column(Modifier.fillMaxSize()) {
            RemoteHeader(
                configured = configuredId.isNotBlank(),
                connected = castConnected,
                deviceName = castDevice,
            )

            if (configuredId.isBlank()) {
                CastSetupCard(
                    appId = appIdInput,
                    onAppId = { appIdInput = it.filter { ch -> ch.isLetterOrDigit() }.uppercase() },
                    onSave = {
                        val clean = appIdInput.trim().uppercase()
                        if (clean.length < 6) {
                            errorText = "Digite o ID do aplicativo Cast fornecido pelo Google."
                        } else {
                            RodriguesCastConfig.saveReceiverAppId(context, clean)
                            configuredId = clean
                            errorText = null
                        }
                    }
                )
                errorText?.let { ErrorBanner(it) }
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastPickerButton()
                Column(Modifier.weight(1f)) {
                    Text(
                        if (castConnected) "TV conectada" else "Toque no ícone para escolher a TV",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                    )
                    Text(
                        castDevice ?: if (castConnected) "Chromecast" else "Nenhuma TV conectada",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(onClick = {
                    appIdInput = configuredId
                    RodriguesCastConfig.saveReceiverAppId(context, "")
                    configuredId = ""
                }) {
                    Text("ID Cast")
                }
            }

            errorText?.let { ErrorBanner(it) }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val active = orders.filter { order ->
                order.status !in StatusGroups.DONE &&
                    order.status !in StatusGroups.CANCELED &&
                    (order.status in StatusGroups.NEW ||
                        order.status in StatusGroups.CONFIRMED ||
                        order.status in StatusGroups.PREPARING ||
                        order.status in StatusGroups.READY)
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Metric("Novos", active.count { it.status in StatusGroups.NEW }, Modifier.weight(1f))
                Metric("Preparo", active.count { it.status in StatusGroups.PREPARING }, Modifier.weight(1f))
                Metric("Prontos", active.count { it.status in StatusGroups.READY }, Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            if (active.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nenhum pedido na cozinha",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    items(active.sortedBy { if (it.createdMillis > 0L) it.createdMillis else Long.MAX_VALUE }, key = { it.id }) { order ->
                        RemoteOrderCard(
                            order = order,
                            busy = updatingId == order.id,
                            castConnected = castConnected,
                            onAdvance = { status ->
                                updatingId = order.id
                                repository.updateStatus(
                                    order,
                                    status,
                                    {
                                        updatingId = null
                                        errorText = null
                                    },
                                    {
                                        updatingId = null
                                        errorText = it.message ?: "Não foi possível atualizar o pedido"
                                    }
                                )
                            },
                            onAttention = {
                                if (!RodriguesCastBridge.sendAttention(context, order)) {
                                    errorText = "Conecte a TV antes de chamar atenção."
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteHeader(configured: Boolean, connected: Boolean, deviceName: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF64118B))
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            "Controle da Cozinha",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            when {
                !configured -> "Configure o Receiver do Chromecast"
                connected -> "Transmitindo painel para ${deviceName ?: "a TV"}"
                else -> "Chromecast pronto para conectar"
            },
            color = Color.White.copy(alpha = .80f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CastSetupCard(appId: String, onAppId: (String) -> Unit, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Ativar Chromecast nativo", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "Cole aqui o ID do aplicativo Receiver criado no Google Cast. Isso é feito uma única vez.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = appId,
                onValueChange = onAppId,
                label = { Text("ID Cast") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64118B)),
            ) {
                Text("SALVAR E ATIVAR TV", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun CastPickerButton() {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2DAE8)),
    ) {
        AndroidView(
            modifier = Modifier.size(54.dp),
            factory = {
                MediaRouteButton(it).apply {
                    CastButtonFactory.setUpMediaRouteButton(context, this)
                }
            }
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        color = Color(0xFFFFE5E5),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            color = Color(0xFF9E1D1D),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE5DFE9)),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), fontWeight = FontWeight.Black, fontSize = 21.sp, color = Color(0xFF64118B))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RemoteOrderCard(
    order: Order,
    busy: Boolean,
    castConnected: Boolean,
    onAdvance: (String) -> Unit,
    onAttention: () -> Unit,
) {
    val minutes = if (order.createdMillis > 0L) {
        max(0L, (System.currentTimeMillis() - order.createdMillis) / 60_000L)
    } else 0L

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#${order.number}", fontWeight = FontWeight.Black, fontSize = 23.sp, color = Color(0xFF64118B))
                    Text(
                        order.clientName,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${minutes} min", fontWeight = FontWeight.Black, fontSize = 14.sp)
                    Text(
                        StatusGroups.label(order.status),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            order.items.take(4).forEach { item ->
                Text(
                    "${item.quantity}x ${item.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            if (order.items.size > 4) {
                Text("+ ${order.items.size - 4} item(ns)", fontSize = 12.sp)
            }

            Spacer(Modifier.height(6.dp))
            Text(money(order.total), fontWeight = FontWeight.Black, fontSize = 16.sp)

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nextKitchenAction(order.status)?.let { action ->
                    Button(
                        onClick = { onAdvance(action.first) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64118B)),
                    ) {
                        Text(if (busy) "ATUALIZANDO..." else action.second, fontWeight = FontWeight.Black)
                    }
                }
                OutlinedButton(
                    onClick = onAttention,
                    enabled = castConnected,
                ) {
                    Text("TV", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

private fun nextKitchenAction(status: String): Pair<String, String>? {
    val s = status.uppercase(Locale.ROOT)
    return when {
        s in StatusGroups.NEW -> "CONFIRMADO" to "ACEITAR"
        s in StatusGroups.CONFIRMED -> "EM_PREPARO" to "INICIAR PREPARO"
        s in StatusGroups.PREPARING -> "PRONTO" to "MARCAR PRONTO"
        else -> null
    }
}
