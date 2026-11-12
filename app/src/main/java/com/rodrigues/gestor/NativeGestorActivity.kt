package com.rodrigues.gestor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.StatusGroups
import com.rodrigues.gestor.data.SupabaseOrdersApi
import com.rodrigues.gestor.data.money
import com.rodrigues.gestor.notifications.GestorConnectionService
import com.rodrigues.gestor.notifications.NotificationHelper
import com.rodrigues.gestor.notifications.OrderRingService
import java.util.Locale

/**
 * Experimento 100% Compose: interface sem WebView, PWA ou iframe.
 * Reutiliza o endpoint autenticado e a assinatura de pedidos do app existente.
 * Somente a branch feature/gestor20-compose-native usa este ponto de entrada.
 */
class NativeGestorActivity : ComponentActivity() {
    private var registered: ListenerRegistration? = null
    private val orders = mutableStateListOf<Order>()
    private var signedIn by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var connectionError by mutableStateOf("")
    private var pendingAction by mutableStateOf(false)
    private var chosenOrder by mutableStateOf<String?>(null)
    private var section by mutableStateOf(0)
    private var tab by mutableStateOf("Todos")

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* O estado é tratado pelo Android, sem bloquear o pedido. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        chosenOrder = intent?.getStringExtra(EXTRA_ORDER_ID)
        signedIn = GestorCredentials.load(this).length == 6
        setContent {
            BackHandler(enabled = chosenOrder != null) { chosenOrder = null }
            GestorTheme {
                if (signedIn) {
                    NativeDashboard(
                        orders = orders,
                        loading = loading,
                        error = connectionError,
                        busy = pendingAction,
                        selectedOrderId = chosenOrder,
                        section = section,
                        filter = tab,
                        onSection = { section = it; chosenOrder = null; tab = "Todos" },
                        onFilter = { tab = it },
                        onSelect = { chosenOrder = it },
                        onBack = { chosenOrder = null },
                        onRetry = { startListening() },
                        onAction = ::updateOrder,
                        onLogout = ::logout,
                        onNotify = ::requestNotifications
                    )
                } else {
                    OperatorLogin(error = connectionError, onSubmit = {
                        GestorCredentials.save(this, it)
                        signedIn = true
                        connectionError = ""
                        startListening()
                    })
                }
            }
        }
        if (signedIn) startListening()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startListening() {
        registered?.remove()
        loading = true
        registered = SupabaseOrdersApi.listenOrders(
            onData = {
                orders.clear()
                orders.addAll(it)
                loading = false
                connectionError = ""
                GestorConnectionService.start(this)
            },
            onError = { e ->
                loading = false
                connectionError = e.message ?: "Sem conexão com a central de pedidos."
                if (GestorCredentials.pin.length != 6 || connectionError.contains("PIN", true)) {
                    signedIn = false
                    registered?.remove()
                    registered = null
                }
            }
        )
    }

    private fun updateOrder(order: Order, action: String) {
        if (pendingAction) return
        pendingAction = true
        connectionError = ""
        val done = {
            pendingAction = false
            chosenOrder = null
            startListening()
        }
        val failed: (Throwable) -> Unit = {
            pendingAction = false
            connectionError = it.message ?: "Não foi possível atualizar o pedido."
        }
        when (action) {
            "CONFIRMADO", "EM_PREPARO", "PRONTO", "EM_ENTREGA", "CONCLUIDO" ->
                SupabaseOrdersApi.updateStatus(order.id, action, done, failed)
            "RETIRADO" -> SupabaseOrdersApi.finishPickup(order.id, done, failed)
            else -> {
                pendingAction = false
                connectionError = "Ação não suportada nesta versão."
            }
        }
    }

    private fun logout() {
        registered?.remove()
        registered = null
        GestorCredentials.clear()
        stopService(Intent(this, GestorConnectionService::class.java))
        OrderRingService.stop(this)
        signedIn = false
        orders.clear()
        chosenOrder = null
        connectionError = ""
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chosenOrder = intent.getStringExtra(EXTRA_ORDER_ID)
        section = 1
    }

    override fun onDestroy() {
        registered?.remove()
        registered = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ORDER_ID = "open_order_id"
    }
}

private val Purple = Color(0xFF6330A0)
private val Green = Color(0xFF087F62)
private val Canvas = Color(0xFFF6F6FA)

@Composable
private fun GestorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Purple,
            secondary = Green,
            background = Canvas,
            surface = Color.White,
            onSurface = Color(0xFF201B28),
            error = Color(0xFFC72F45)
        ),
        content = content
    )
}

@Composable
private fun OperatorLogin(error: String, onSubmit: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("RODRIGUES", color = Purple, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Gestor 20", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Central de pedidos nativa", color = Color.Gray)
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                label = { Text("PIN de 6 números") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            if (error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSubmit(pin) }, enabled = pin.length == 6, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Entrar no Gestor")
            }
            Spacer(Modifier.height(12.dp))
            Text("Ações são sincronizadas com a central de pedidos existente.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun NativeDashboard(
    orders: List<Order>,
    loading: Boolean,
    error: String,
    busy: Boolean,
    selectedOrderId: String?,
    section: Int,
    filter: String,
    onSection: (Int) -> Unit,
    onFilter: (String) -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAction: (Order, String) -> Unit,
    onLogout: () -> Unit,
    onNotify: () -> Unit
) {
    val selected = orders.find { it.id == selectedOrderId }
    Scaffold(
        topBar = {
            Surface(color = Color.White, shadowElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedOrderId != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (selected != null) "Pedido #${selected.number}" else "Rodrigues Gestor 20",
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(if (selected != null) "Acompanhamento do pedido" else "Operação em tempo real",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    if (selectedOrderId == null) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "Atualizar pedidos", tint = Purple)
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selectedOrderId == null) NavigationBar(containerColor = Color.White) {
                listOf(
                    Triple("Início", Icons.Default.Home, 0),
                    Triple("Pedidos", Icons.Default.List, 1),
                    Triple("Histórico", Icons.Default.History, 2),
                    Triple("Mais", Icons.Default.MoreHoriz, 3)
                ).forEach { (title, icon, index) ->
                    NavigationBarItem(
                        selected = section == index,
                        onClick = { onSection(index) },
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (error.isNotBlank()) {
                Surface(color = Color(0xFFFFF1F0), modifier = Modifier.fillMaxWidth()) {
                    Text(error, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Color(0xFF9E2634), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                selected != null -> OrderDetails(
                    order = selected,
                    busy = busy,
                    onAction = { action -> onAction(selected, action) }
                )
                selectedOrderId != null -> EmptyScreen("Pedido não encontrado ou indisponível.")
                section == 0 -> HomeScreen(orders, onSelect)
                section == 1 -> OrdersScreen(orders, filter, onFilter, onSelect, history = false)
                section == 2 -> OrdersScreen(orders, filter, onFilter, onSelect, history = true)
                else -> MoreScreen(onNotify, onLogout)
            }
        }
    }
}

private fun bucket(order: Order): String = when (order.status.uppercase(Locale.ROOT)) {
    in StatusGroups.NEW -> "Novos"
    in StatusGroups.CONFIRMED -> "Aceitos"
    in StatusGroups.PREPARING -> "Preparo"
    in StatusGroups.READY -> "Prontos"
    in StatusGroups.DELIVERY -> "Entrega"
    in StatusGroups.DONE -> "Concluídos"
    in StatusGroups.CANCELED -> "Cancelados"
    else -> "Outros"
}

@Composable
private fun HomeScreen(orders: List<Order>, onSelect: (String) -> Unit) {
    val active = orders.filter { bucket(it) !in setOf("Concluídos", "Cancelados") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Sua operação", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Tudo o que precisa de atenção agora.", color = Color.Gray)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Novos", orders.count { bucket(it) == "Novos" }, Purple, Modifier.weight(1f))
                MetricCard("Preparo", orders.count { bucket(it) == "Preparo" }, Green, Modifier.weight(1f))
                MetricCard("Prontos", orders.count { bucket(it) == "Prontos" }, Color(0xFFB26612), Modifier.weight(1f))
            }
        }
        item { Text("Pedidos em andamento", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        if (active.isEmpty()) item { EmptyScreen("Nenhum pedido em andamento.") }
        items(active.take(35), key = { it.id }) { order -> OrderCard(order, onSelect) }
    }
}

@Composable
private fun MetricCard(name: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("$value", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
            Text(name, color = Color.DarkGray, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun OrdersScreen(
    orders: List<Order>,
    filter: String,
    onFilter: (String) -> Unit,
    onSelect: (String) -> Unit,
    history: Boolean
) {
    val tabs = if (history) listOf("Todos", "Concluídos", "Cancelados")
        else listOf("Todos", "Novos", "Aceitos", "Preparo", "Prontos", "Entrega")
    val visible = orders.filter {
        val group = bucket(it)
        if (history) group in setOf("Concluídos", "Cancelados") && (filter == "Todos" || group == filter)
        else group !in setOf("Concluídos", "Cancelados") && (filter == "Todos" || group == filter)
    }
    Column {
        Text(if (history) "Histórico" else "Pedidos", Modifier.padding(16.dp),
            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs) { name ->
                FilterChip(selected = filter == name, onClick = { onFilter(name) },
                    label = { Text(name) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (visible.isEmpty()) item { EmptyScreen("Nenhum pedido nesta categoria.") }
            items(visible, key = { it.id }) { order -> OrderCard(order, onSelect) }
        }
    }
}

@Composable
private fun OrderCard(order: Order, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(order.id) },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${order.number}", fontWeight = FontWeight.Bold, color = Purple)
                Spacer(Modifier.weight(1f))
                Text(bucket(order), color = if (bucket(order) == "Novos") Purple else Green,
                    style = MaterialTheme.typography.labelMedium)
            }
            Text(order.clientName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(order.items.firstOrNull()?.let { "${it.quantity}× ${it.name}" } ?: "Ver itens do pedido",
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray,
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = Color(0xFFF0EDF3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (order.pickup) "Retirada" else "Entrega", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(money(order.total), fontWeight = FontWeight.Bold, color = Purple)
            }
        }
    }
}

@Composable
private fun OrderDetails(order: Order, busy: Boolean, onAction: (String) -> Unit) {
    val group = bucket(order)
    var confirmation by remember(order.id) { mutableStateOf<String?>(null) }
    val action: String? = when (group) {
        "Novos" -> "CONFIRMADO"
        "Aceitos" -> "EM_PREPARO"
        "Preparo" -> "PRONTO"
        "Prontos" -> if (order.pickup) "RETIRADO" else null
        "Entrega" -> "CONCLUIDO"
        else -> null
    }
    val labels = mapOf(
        "CONFIRMADO" to "Aceitar pedido",
        "EM_PREPARO" to "Iniciar preparo",
        "PRONTO" to "Marcar como pronto",
        "RETIRADO" to "Confirmar retirada",
        "EM_ENTREGA" to "Despachar (entrega própria)",
        "CONCLUIDO" to "Finalizar entrega"
    )
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PEDIDO #${order.number}", color = Purple, fontWeight = FontWeight.Bold)
                    Text(order.clientName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    Text(group, color = Green)
                    Text(if (order.pickup) "Retirada no local" else "Entrega", color = Color.Gray)
                    if (order.phone.isNotBlank()) Text("Telefone: ${order.phone}")
                    if (!order.pickup) Text("Endereço: ${order.address}")
                    Text("Pagamento: ${order.payment.form}")
                }
            }
        }
        item { Text("Itens do pedido", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        items(order.items) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("${item.quantity}× ${item.name}", fontWeight = FontWeight.SemiBold)
                    item.details.forEach { Text("• $it", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (order.observation.isNotBlank()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E8))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Observações", fontWeight = FontWeight.Bold)
                    Text(order.observation)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("TOTAL", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(money(order.total), color = Purple, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (action != null) item {
            Button(
                onClick = { confirmation = action },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(if (busy) "Atualizando…" else labels[action] ?: "Confirmar") }
        }
        if (group == "Prontos" && !order.pickup) item {
            Button(
                onClick = { confirmation = "EM_ENTREGA" },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(labels["EM_ENTREGA"]!!) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Despachar com UP • integração pendente") }
        }
    }
    confirmation?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Confirmar alteração?") },
            text = { Text("Pedido #${order.number}: ${labels[target] ?: target}. O pedido será atualizado no sistema real.") },
            confirmButton = {
                TextButton(onClick = { confirmation = null; onAction(target) }) { Text("Confirmar") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Voltar") } }
        )
    }
}

@Composable
private fun MoreScreen(onNotify: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mais opções", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            ListItem(
                headlineContent = { Text("Notificações Android") },
                supportingContent = { Text("Permitir os alertas de novos pedidos") },
                leadingContent = { Icon(Icons.Default.Notifications, null) },
                modifier = Modifier.clickable(onClick = onNotify)
            )
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            ListItem(
                headlineContent = { Text("Sair da conta") },
                supportingContent = { Text("Remover o PIN salvo neste dispositivo") },
                leadingContent = { Icon(Icons.Default.ExitToApp, null) },
                modifier = Modifier.clickable(onClick = onLogout)
            )
        }
        Text("Prévia nativa: pedidos e operações essenciais. Cardápio, chat, impressão e UP terão migração gradual.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun EmptyScreen(message: String) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
    }
}
