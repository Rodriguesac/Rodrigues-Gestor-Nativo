package com.rodrigues.gestor

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.OrdersRepository
import com.rodrigues.gestor.data.StatusGroups
import com.rodrigues.gestor.data.money
import com.rodrigues.gestor.ui.theme.RodriguesGestorTheme
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
                KitchenBoard()
            }
        }
    }
}

private enum class KitchenStage(val title: String) {
    NEW("NOVOS"),
    CONFIRMED("ACEITOS"),
    PREPARING("PREPARANDO"),
    READY("PRONTOS"),
}

@Composable
private fun KitchenBoard() {
    val repository = remember { OrdersRepository() }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var updatingId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener = repository.listenOrders(
            onData = {
                orders = it
                errorText = null
            },
            onError = { errorText = it.message ?: "Falha ao carregar pedidos" }
        )
        onDispose { listener.remove() }
    }

    val active = orders.filter {
        it.status !in StatusGroups.DONE &&
            it.status !in StatusGroups.CANCELED &&
            (it.status in StatusGroups.NEW ||
                it.status in StatusGroups.CONFIRMED ||
                it.status in StatusGroups.PREPARING ||
                it.status in StatusGroups.READY)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101014),
    ) {
        Column(Modifier.fillMaxSize()) {
            KitchenHeader(active.size, errorText)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                KitchenStage.entries.forEach { stage ->
                    KitchenColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        stage = stage,
                        orders = active.filter { it.matchesKitchenStage(stage) },
                        updatingId = updatingId,
                        onAdvance = { order, status ->
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
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun KitchenHeader(activeCount: Int, errorText: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF64118B))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "RODRIGUES • COZINHA",
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Painel ao vivo • ${activeCount} pedido(s) na produção",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (errorText != null) {
            Text(
                errorText,
                color = Color(0xFFFFD1D1),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.35f),
            )
        }
    }
}

@Composable
private fun KitchenColumn(
    modifier: Modifier,
    stage: KitchenStage,
    orders: List<Order>,
    updatingId: String?,
    onAdvance: (Order, String) -> Unit,
) {
    Column(
        modifier = modifier
            .background(Color(0xFF1A1A20), RoundedCornerShape(18.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stage.title,
                color = stage.stageColor(),
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                orders.size.toString(),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Spacer(Modifier.height(7.dp))

        if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Sem pedidos",
                    color = Color.White.copy(alpha = 0.35f),
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(orders, key = { it.id }) { order ->
                    KitchenOrderCard(
                        order = order,
                        stage = stage,
                        busy = updatingId == order.id,
                        onAdvance = onAdvance,
                    )
                }
            }
        }
    }
}

@Composable
private fun KitchenOrderCard(
    order: Order,
    stage: KitchenStage,
    busy: Boolean,
    onAdvance: (Order, String) -> Unit,
) {
    val minutes = if (order.createdMillis > 0L) {
        max(0L, (System.currentTimeMillis() - order.createdMillis) / 60_000L)
    } else 0L

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF26262E)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${order.number}",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${minutes} min",
                    color = when {
                        minutes >= 30 -> Color(0xFFFF6B6B)
                        minutes >= 20 -> Color(0xFFFFC857)
                        else -> Color(0xFF8BE28B)
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                )
            }

            Text(
                order.clientName.uppercase(Locale("pt", "BR")),
                color = Color.White.copy(alpha = 0.82f),
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (order.pickup) "RETIRADA" else "ENTREGA",
                color = Color.White.copy(alpha = 0.48f),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(7.dp))
            order.items.take(8).forEach { item ->
                Text(
                    "${item.quantity}x ${item.name}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                )
                item.details.take(6).forEach { detail ->
                    Text(
                        "• ${detail}",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            if (order.items.size > 8) {
                Text(
                    "+ ${order.items.size - 8} item(ns)",
                    color = Color.White.copy(alpha = 0.60f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }

            if (order.observation.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "OBS: ${order.observation}",
                    color = Color(0xFFFFDC73),
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                money(order.total),
                color = Color.White.copy(alpha = 0.80f),
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
            )

            stage.nextStatus()?.let { next ->
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onAdvance(order, next.first) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = stage.stageColor()),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Text(
                        if (busy) "ATUALIZANDO..." else next.second,
                        color = Color(0xFF121212),
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

private fun Order.matchesKitchenStage(stage: KitchenStage): Boolean = when (stage) {
    KitchenStage.NEW -> status in StatusGroups.NEW
    KitchenStage.CONFIRMED -> status in StatusGroups.CONFIRMED
    KitchenStage.PREPARING -> status in StatusGroups.PREPARING
    KitchenStage.READY -> status in StatusGroups.READY
}

private fun KitchenStage.nextStatus(): Pair<String, String>? = when (this) {
    KitchenStage.NEW -> "CONFIRMADO" to "ACEITAR"
    KitchenStage.CONFIRMED -> "EM_PREPARO" to "INICIAR PREPARO"
    KitchenStage.PREPARING -> "PRONTO" to "MARCAR PRONTO"
    KitchenStage.READY -> null
}

private fun KitchenStage.stageColor(): Color = when (this) {
    KitchenStage.NEW -> Color(0xFFFFC857)
    KitchenStage.CONFIRMED -> Color(0xFF72D6FF)
    KitchenStage.PREPARING -> Color(0xFFFF9F5A)
    KitchenStage.READY -> Color(0xFF72E18F)
}
