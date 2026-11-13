package com.rodrigues.gestor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.MediaPlayer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.rodrigues.gestor.R
import com.rodrigues.gestor.data.GestorCredentials
import com.rodrigues.gestor.data.OfflineOrderStore
import com.rodrigues.gestor.data.OfflineOrderSyncWorker
import com.rodrigues.gestor.data.NativeCatalogApi
import com.rodrigues.gestor.data.CatalogItem
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
    private val catalog = mutableStateListOf<CatalogItem>()
    private var catalogLoading by mutableStateOf(false)
    private var catalogError by mutableStateOf("")
    private var catalogBusy by mutableStateOf(false)
    private var catalogOwner by mutableStateOf(false)
    private var lastOrderSync by mutableLongStateOf(0L)
    private lateinit var offlineStore: OfflineOrderStore
    private var queuedActions by mutableIntStateOf(0)
    private var conflictedActions by mutableIntStateOf(0)
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
        offlineStore = OfflineOrderStore(this)
        orders.addAll(offlineStore.cachedOrders())
        catalog.addAll(NativeCatalogApi.cached(this))
        queuedActions = offlineStore.waiting().size
        conflictedActions = offlineStore.conflicts().size
        chosenOrder = intent?.getStringExtra(EXTRA_ORDER_ID)
        signedIn = GestorCredentials.load(this).length == 6
        setContent {
            BackHandler(enabled = chosenOrder != null) { chosenOrder = null }
            GestorTheme {
                if (signedIn) {
                    NativeDashboard(
                        orders = orders,
                        catalog = catalog,
                        catalogLoading = catalogLoading,
                        catalogError = catalogError,
                        catalogBusy = catalogBusy,
                        catalogOwner = catalogOwner,
                        lastSync = lastOrderSync,
                        loading = loading,
                        error = connectionError,
                        busy = pendingAction,
                        queuedActions = queuedActions,
                        conflictedActions = conflictedActions,
                        selectedOrderId = chosenOrder,
                        section = section,
                        filter = tab,
                        onSection = {
                            section = it
                            chosenOrder = null
                            tab = "Todos"
                            if (it == 2) refreshCatalog()
                        },
                        onFilter = { tab = it },
                        onSelect = { chosenOrder = it },
                        onBack = { chosenOrder = null },
                        onRetry = { startListening() },
                        onAction = ::updateOrder,
                        onCatalogRefresh = ::refreshCatalog,
                        onCatalogAvailability = ::updateCatalogAvailability,
                        onCatalogPrice = ::updateCatalogPrice,
                        onShareCatalog = ::shareCatalog,
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
        loading = orders.isEmpty()
        if (queuedActions > 0) OfflineOrderSyncWorker.schedule(this)
        registered = SupabaseOrdersApi.listenOrders(
            onData = {
                offlineStore.storeOrders(it)
                lastOrderSync = System.currentTimeMillis()
                queuedActions = offlineStore.waiting().size
                conflictedActions = offlineStore.conflicts().size
                if (queuedActions > 0) OfflineOrderSyncWorker.schedule(this)
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

    /** Mudanças de pedido exigem confirmação online; o cache local é somente leitura. */
    private fun updateOrder(order: Order, action: String) {
        if (pendingAction) return
        pendingAction = true
        connectionError = ""
        SupabaseOrdersApi.updateStatus(order.id, action,
            onDone = {
                pendingAction = false
                chosenOrder = null
                section = 1
                OrderRingService.stop(this)
                connectionError = "Pedido atualizado e confirmado pelo servidor."
                startListening()
            },
            onError = { error ->
                pendingAction = false
                connectionError = "Não foi possível confirmar a ação. O pedido continua salvo no celular. Confira a conexão e tente novamente: " +
                    (error.message ?: "falha de rede")
            }
        )
    }

    private fun refreshCatalog() {
        if (catalogLoading || catalogBusy) return
        catalogLoading = true
        catalogError = ""
        NativeCatalogApi.load(this, onSuccess = { snapshot ->
            catalog.clear()
            catalog.addAll(snapshot.items)
            catalogOwner = snapshot.canEdit
            catalogLoading = false
        }, onError = {
            catalogLoading = false
            catalogError = "Sem conexão: mostrando o último cardápio salvo. " + (it.message ?: "")
        })
    }

    private fun updateCatalogAvailability(item: CatalogItem, available: Boolean) {
        if (catalogBusy) return
        catalogBusy = true
        catalogError = ""
        NativeCatalogApi.setAvailability(item, available, onSuccess = {
            catalogBusy = false
            refreshCatalog()
        }, onError = {
            catalogBusy = false
            catalogError = "Alteração não confirmada pelo servidor: " + (it.message ?: "verifique a conexão")
        })
    }

    private fun updateCatalogPrice(item: CatalogItem, newPrice: Double) {
        if (catalogBusy) return
        catalogBusy = true
        catalogError = ""
        NativeCatalogApi.setProductPrice(item, newPrice, onSuccess = {
            catalogBusy = false
            refreshCatalog()
        }, onError = {
            catalogBusy = false
            catalogError = "Preço não foi alterado: " + (it.message ?: "verifique a conexão")
        })
    }

    private fun shareCatalog() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,
                "Confira nosso cardápio e faça seu pedido na Rodrigues Açaí e Cia: https://rodriguesacaiecia.web.app")
        }
        startActivity(Intent.createChooser(share, "Enviar cardápio ao cliente"))
    }

    private fun logout() {
        if (offlineStore.waiting().isNotEmpty() || offlineStore.conflicts().isNotEmpty()) {
            connectionError = "Existem alterações pendentes ou conflitos. Sincronize e resolva antes de sair."
            return
        }
        registered?.remove()
        registered = null
        offlineStore.clearAll()
        NativeCatalogApi.clear(this)
        catalog.clear()
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
        offlineStore.close()
        registered = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ORDER_ID = "open_order_id"
    }
}

private val Purple = Color(0xFF632CA6)
private val PurpleDeep = Color(0xFF42146F)
private val Amber = Color(0xFFBE731C)
private val RedAlert = Color(0xFFCC3C4A)
private val Blue = Color(0xFF257BAA)
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
    catalog: List<CatalogItem>,
    catalogLoading: Boolean,
    catalogError: String,
    catalogBusy: Boolean,
    catalogOwner: Boolean,
    lastSync: Long,
    loading: Boolean,
    error: String,
    busy: Boolean,
    queuedActions: Int,
    conflictedActions: Int,
    selectedOrderId: String?,
    section: Int,
    filter: String,
    onSection: (Int) -> Unit,
    onFilter: (String) -> Unit,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAction: (Order, String) -> Unit,
    onCatalogRefresh: () -> Unit,
    onCatalogAvailability: (CatalogItem, Boolean) -> Unit,
    onCatalogPrice: (CatalogItem, Double) -> Unit,
    onShareCatalog: () -> Unit,
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
                        Text(if (selected != null) "Pedido #${selected.number}" else "RODRIGUES • GESTOR 20",
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = PurpleDeep)
                        Text(if (selected != null) "Detalhes e andamento" else if (lastSync > 0) "Pedidos salvos • atualização automática" else "Central de operações",
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
                    Triple("Cardápio", Icons.Default.RestaurantMenu, 2),
                    Triple("Histórico", Icons.Default.History, 3),
                    Triple("Mais", Icons.Default.MoreHoriz, 4)
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
            if (queuedActions > 0 || conflictedActions > 0) {
                Surface(color = Color(0xFFFFF3E3), modifier = Modifier.fillMaxWidth()) {
                    Text("No aparelho: $queuedActions aguardando sincronização • $conflictedActions conflitos", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF775115), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (error.isNotBlank()) {
                Surface(color = Color(0xFFFFF1F0), modifier = Modifier.fillMaxWidth()) {
                    Text(error, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Color(0xFF9E2634), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (orders.isNotEmpty() && error.contains("conexão", true)) {
                Surface(color = Color(0xFFF1EBFA), modifier = Modifier.fillMaxWidth()) {
                    Text("Pedidos já recebidos disponíveis no aparelho, mesmo sem internet.",
                        Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = PurpleDeep,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            when {
                selected != null -> OrderDetails(
                    order = selected,
                    busy = busy,
                    onAction = { action -> onAction(selected, action) }
                )
                selectedOrderId != null -> EmptyScreen("Pedido não encontrado ou indisponível.")
                section == 0 -> HomeScreen(orders, onSelect)
                section == 1 -> OrdersScreen(orders, filter, onFilter, onSelect, history = false)
                section == 2 -> CatalogScreen(catalog, catalogLoading, catalogError, catalogBusy,
                    catalogOwner, onCatalogRefresh, onCatalogAvailability, onCatalogPrice, onShareCatalog)
                section == 3 -> OrdersScreen(orders, filter, onFilter, onSelect, history = true)
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
            Surface(color = PurpleDeep, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    Text("CENTRAL DE OPERAÇÕES", color = Color(0xFFE5D0FA),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Tudo sob controle.", color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(5.dp))
                    Text("Acompanhe cada etapa dos seus pedidos.", color = Color(0xFFE8DCF2),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Novos", orders.count { bucket(it) == "Novos" }, Amber, Modifier.weight(1f))
                MetricCard("Preparo", orders.count { bucket(it) == "Preparo" }, Blue, Modifier.weight(1f))
                MetricCard("Prontos", orders.count { bucket(it) == "Prontos" }, Green, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("Aceitos", orders.count { bucket(it) == "Aceitos" }, Purple, Modifier.weight(1f))
                MetricCard("Entrega", orders.count { bucket(it) == "Entrega" }, Blue, Modifier.weight(1f))
                MetricCard("Atenção", orders.count {
                    bucket(it) == "Novos" && System.currentTimeMillis() - it.createdMillis > 180_000L
                }, RedAlert, Modifier.weight(1f))
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
                val (statusColor, statusBg) = when (bucket(order)) {
                    "Novos" -> Amber to Color(0xFFFFF1D9)
                    "Aceitos" -> Purple to Color(0xFFF1E8FF)
                    "Preparo" -> Blue to Color(0xFFE6F3FC)
                    "Prontos" -> Green to Color(0xFFE5F6EE)
                    "Cancelados" -> RedAlert to Color(0xFFFFE8E9)
                    else -> Green to Color(0xFFE5F6EE)
                }
                Surface(color = statusBg, shape = RoundedCornerShape(100.dp)) {
                    Text(bucket(order), modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = statusColor, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall)
                }
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
private fun CatalogScreen(
    catalog: List<CatalogItem>,
    loading: Boolean,
    error: String,
    busy: Boolean,
    canEdit: Boolean,
    onRefresh: () -> Unit,
    onAvailability: (CatalogItem, Boolean) -> Unit,
    onPrice: (CatalogItem, Double) -> Unit,
    onShare: () -> Unit
) {
    val groups = listOf(
        "products" to "Produtos", "bases" to "Bases", "cardapio_acai" to "Tamanhos",
        "acompanhamentos_gratis" to "Grátis", "adicionais" to "Adicionais",
        "coberturas" to "Coberturas", "utensilios" to "Utensílios"
    )
    var group by remember { mutableStateOf("products") }
    var search by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<Pair<CatalogItem, Boolean>?>(null) }
    var editing by remember { mutableStateOf<CatalogItem?>(null) }
    var newPrice by remember { mutableStateOf("") }
    val visible = catalog.filter {
        it.collection == group && it.name.contains(search.trim(), ignoreCase = true)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Cardápio", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                Text("Controle produtos e ingredientes", color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh, enabled = !loading && !busy) {
                Icon(Icons.Default.Refresh, contentDescription = "Atualizar cardápio", tint = Purple)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Enviar cardápio ao cliente")
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { (key, title) ->
                FilterChip(selected = key == group, onClick = { group = key },
                    label = { Text(title) })
            }
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Buscar produto ou ingrediente") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true, shape = RoundedCornerShape(16.dp)
        )
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, Modifier.padding(16.dp), color = RedAlert,
                style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty()) item {
                EmptyScreen(if (loading) "Carregando cardápio…" else
                    "Nenhum item nesta categoria. Se estiver offline, os itens já recebidos continuam disponíveis.")
            }
            items(visible, key = { it.collection + ":" + it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = if (item.forSale) Color(0xFFE6F7EE) else Color(0xFFFFE8E9),
                                shape = RoundedCornerShape(12.dp)) {
                                Icon(if (item.forSale) Icons.Default.CheckCircle else Icons.Default.PauseCircle,
                                    null, Modifier.padding(10.dp), tint = if (item.forSale) Green else RedAlert)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall)
                                Text(if (item.forSale) "Disponível" else "Pausado",
                                    color = if (item.forSale) Green else RedAlert,
                                    style = MaterialTheme.typography.labelSmall)
                                if (item.price > 0) Text(money(item.price), color = Purple,
                                    fontWeight = FontWeight.Bold)
                            }
                            if (canEdit) Switch(
                                checked = item.forSale,
                                enabled = !busy && !loading,
                                onCheckedChange = { confirming = item to it }
                            )
                        }
                        if (item.description.isNotBlank()) Text(item.description,
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (canEdit && item.collection == "products") {
                            TextButton(onClick = { editing = item; newPrice = String.format(Locale.US, "%.2f", item.price) },
                                enabled = !busy) { Text("Alterar preço") }
                        }
                    }
                }
            }
            item {
                Text("Mudanças só aparecem como concluídas após a confirmação do servidor.",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
    confirming?.let { (item, available) ->
        AlertDialog(onDismissRequest = { confirming = null },
            title = { Text(if (available) "Ativar item?" else "Pausar item?") },
            text = { Text(item.name + " — essa mudança será refletida no cardápio da loja.") },
            confirmButton = { TextButton(onClick = {
                confirming = null
                onAvailability(item, available)
            }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Voltar") } })
    }
    editing?.let { item ->
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Preço de " + item.name) },
            text = {
                OutlinedTextField(value = newPrice, onValueChange = { newPrice = it },
                    label = { Text("Preço em reais") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = { TextButton(onClick = {
                val value = newPrice.trim().replace(',', '.').toDoubleOrNull()
                if (value != null && value.isFinite() && value in 0.0..10000.0) {
                    editing = null
                    onPrice(item, value)
                }
            }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun MoreScreen(onNotify: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            try { player?.stop(); player?.release() } catch (_: Throwable) {}
            player = null
        }
    }
    val sounds = listOf(
        Triple("Novo pedido", "Nokia clássico (seu áudio)", R.raw.nokia_pedido),
        Triple("Aguardando aceite", "Nokia clássico (seu áudio)", R.raw.nokia_pedido),
        Triple("Pedido atrasado", "Alerta forte", R.raw.alert_strong),
        Triple("Mensagem do cliente", "Campainha leve", R.raw.chime),
        Triple("Negociação / alteração", "Telefone de atenção", R.raw.old_phone),
        Triple("Pedido cancelado", "Bipe curto", R.raw.beep_short),
        Triple("Pagamento confirmado", "Campainha suave", R.raw.chime),
        Triple("UP Entregas", "Aviso curto", R.raw.beep_short)
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ferramentas e ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Personalize os avisos, permissões e recursos do aplicativo.", color = Color.Gray)
        Text("Sons próprios do Gestor 20", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Cada situação usa um som adequado. Toque para ouvir a prévia.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        sounds.forEach { (title, subtitle, sound) ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                ListItem(
                    headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(subtitle) },
                    trailingContent = {
                        IconButton(onClick = {
                            try {
                                player?.stop()
                                player?.release()
                                player = MediaPlayer.create(context, sound)?.apply {
                                    setOnCompletionListener { completed ->
                                        completed.release()
                                        player = null
                                    }
                                    start()
                                }
                            } catch (_: Throwable) { player = null }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Ouvir $title", tint = Purple)
                        }
                    }
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            ListItem(
                headlineContent = { Text("Permissões de notificações") },
                supportingContent = { Text("Autorizar alertas no Android") },
                leadingContent = { Icon(Icons.Default.Notifications, null) },
                modifier = Modifier.clickable(onClick = onNotify)
            )
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            ListItem(
                headlineContent = { Text("Sair do aparelho") },
                supportingContent = { Text("Desconecta após sincronizar todas as alterações") },
                leadingContent = { Icon(Icons.Default.ExitToApp, null) },
                modifier = Modifier.clickable(onClick = onLogout)
            )
        }
        Text("Toque Nokia aplicado aos novos pedidos. Volume e permissões dependem das configurações do Android.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun EmptyScreen(message: String) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
    }
}
