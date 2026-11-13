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
import com.rodrigues.gestor.data.NativeOperationsApi
import com.rodrigues.gestor.data.StoreInfo
import com.rodrigues.gestor.data.CourierChoice
import com.rodrigues.gestor.data.CatalogItem
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.StatusGroups
import com.rodrigues.gestor.data.SupabaseOrdersApi
import com.rodrigues.gestor.data.money
import com.rodrigues.gestor.printing.OrderPrinter
import com.rodrigues.gestor.notifications.AlertPreferences
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
    private var storeInfo by mutableStateOf(StoreInfo())
    private var storeLoading by mutableStateOf(false)
    private var storeBusy by mutableStateOf(false)
    private var storeError by mutableStateOf("")
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
        storeInfo = NativeOperationsApi.cachedStore(this)
        OfflineOrderSyncWorker.cancelLegacy(this)
        queuedActions = offlineStore.waiting().size
        conflictedActions = offlineStore.conflicts().size
        chosenOrder = intent?.getStringExtra(EXTRA_ORDER_ID)
        signedIn = GestorCredentials.load(this).length == 6
        setContent {
            BackHandler(enabled = chosenOrder != null || section == 5) {
                if (chosenOrder != null) chosenOrder = null else section = 4
            }
            GestorTheme {
                if (signedIn) {
                    NativeDashboard(
                        orders = orders,
                        catalog = catalog,
                        catalogLoading = catalogLoading,
                        catalogError = catalogError,
                        catalogBusy = catalogBusy,
                        catalogOwner = catalogOwner,
                        storeInfo = storeInfo,
                        storeLoading = storeLoading,
                        storeBusy = storeBusy,
                        storeError = storeError,
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
                        onOwnDispatch = ::dispatchOwn,
                        onStore = { section = 5; chosenOrder = null; refreshStore() },
                        onStoreBack = { section = 4 },
                        onStoreRefresh = ::refreshStore,
                        onStoreOpen = ::setStoreOpen,
                        onStoreSave = ::saveStoreSettings,
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
                        refreshCatalog()
                        refreshStore()
                    })
                }
            }
        }
        if (signedIn) { startListening(); refreshCatalog(); refreshStore() }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startListening() {
        registered?.remove()
        loading = orders.isEmpty()
        registered = SupabaseOrdersApi.listenOrders(
            onData = {
                offlineStore.storeOrders(it)
                lastOrderSync = System.currentTimeMillis()
                queuedActions = offlineStore.waiting().size
                conflictedActions = offlineStore.conflicts().size
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


    private fun refreshStore() {
        if (storeLoading || storeBusy) return
        storeLoading = true
        storeError = ""
        NativeOperationsApi.loadStore(this, onSuccess = {
            storeInfo = it
            storeLoading = false
        }, onError = {
            storeLoading = false
            storeError = "Sem conexão: mostrando o último estado salvo. " + (it.message ?: "")
        })
    }

    private fun setStoreOpen(open: Boolean) {
        if (storeBusy) return
        storeBusy = true
        storeError = ""
        NativeOperationsApi.setOpen(this, open, onSuccess = {
            storeInfo = it
            storeBusy = false
        }, onError = {
            storeBusy = false
            storeError = "Não foi possível mudar o status da loja: " + (it.message ?: "")
        })
    }

    private fun saveStoreSettings(minutes: Int, message: String) {
        if (storeBusy) return
        storeBusy = true
        storeError = ""
        NativeOperationsApi.saveSettings(this, minutes, message, onSuccess = {
            storeInfo = it
            storeBusy = false
        }, onError = {
            storeBusy = false
            storeError = "Não foi possível salvar: " + (it.message ?: "")
        })
    }

    private fun dispatchOwn(order: Order) {
        if (pendingAction) return
        pendingAction = true
        connectionError = ""
        NativeOperationsApi.dispatchOwn(order.id, onSuccess = {
            pendingAction = false
            chosenOrder = null
            connectionError = "Despacho próprio confirmado pelo servidor."
            startListening()
        }, onError = {
            pendingAction = false
            connectionError = "Despacho NÃO confirmado: " + (it.message ?: "verifique conexão e status")
        })
    }

    private fun logout() {
        // Pendências de versões antigas não são mais enviadas automaticamente.
        registered?.remove()
        registered = null
        offlineStore.clearAll()
        NativeCatalogApi.clear(this)
        NativeOperationsApi.clear(this)
        storeInfo = StoreInfo()
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
    storeInfo: StoreInfo,
    storeLoading: Boolean,
    storeBusy: Boolean,
    storeError: String,
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
    onOwnDispatch: (Order) -> Unit,
    onStore: () -> Unit,
    onStoreBack: () -> Unit,
    onStoreRefresh: () -> Unit,
    onStoreOpen: (Boolean) -> Unit,
    onStoreSave: (Int, String) -> Unit,
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
                    if (selectedOrderId != null || section == 5) {
                        IconButton(onClick = if (section == 5 && selectedOrderId == null) onStoreBack else onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (selected != null) "Pedido #${selected.number}" else if (section == 5) "MINHA LOJA" else "RODRIGUES • GESTOR 20",
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = PurpleDeep)
                        Text(if (selected != null) "Detalhes e andamento" else if (lastSync > 0) "Pedidos salvos • atualização automática" else "Central de operações",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    if (selectedOrderId == null && section != 5) {
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
                        selected = section == index || (section == 5 && index == 4),
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
                    Text("$queuedActions ações legadas e $conflictedActions conflitos: envio automático desativado. Confira os pedidos no servidor.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF775115), style = MaterialTheme.typography.labelSmall)
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
                    onAction = { action -> onAction(selected, action) },
                    onOwnDispatch = { onOwnDispatch(selected) },
                    onDispatchDone = onRetry
                )
                selectedOrderId != null -> EmptyScreen("Pedido não encontrado ou indisponível.")
                section == 0 -> HomeScreen(orders, storeInfo, onStore, onSelect)
                section == 1 -> OrdersScreen(orders, filter, onFilter, onSelect, history = false)
                section == 2 -> CatalogScreen(catalog, catalogLoading, catalogError, catalogBusy,
                    catalogOwner, onCatalogRefresh, onCatalogAvailability, onCatalogPrice, onShareCatalog)
                section == 3 -> OrdersScreen(orders, filter, onFilter, onSelect, history = true)
                section == 5 -> StoreScreen(storeInfo, storeLoading, storeBusy, storeError,
                    catalogOwner, onStoreRefresh, onStoreOpen, onStoreSave)
                else -> MoreScreen(onNotify, onLogout, onStore)
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
private fun HomeScreen(
    orders: List<Order>, storeInfo: StoreInfo, onStore: () -> Unit, onSelect: (String) -> Unit
) {
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
            Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onStore),
                shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storefront, null,
                        tint = if (storeInfo.loaded && storeInfo.open) Green else Amber)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Minha loja", fontWeight = FontWeight.Bold)
                        Text(if (!storeInfo.loaded) "Verificar status"
                            else if (storeInfo.open) "Aberta • preparo " + storeInfo.prepMinutes + " min"
                            else "Fechada", style = MaterialTheme.typography.labelSmall,
                            color = if (storeInfo.loaded && storeInfo.open) Green else Amber)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Purple)
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
private fun OrderDetails(
    order: Order, busy: Boolean, onAction: (String) -> Unit,
    onOwnDispatch: () -> Unit, onDispatchDone: () -> Unit
) {
    val context = LocalContext.current
    var printingError by remember(order.id) { mutableStateOf("") }
    var showUp by remember(order.id) { mutableStateOf(false) }
    var upLoading by remember(order.id) { mutableStateOf(false) }
    var upBusy by remember(order.id) { mutableStateOf(false) }
    var upError by remember(order.id) { mutableStateOf("") }
    val upCouriers = remember(order.id) { mutableStateListOf<CourierChoice>() }
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
        "MANUAL_DISPATCH" to "Despachar (entrega própria)",
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
        item {
            Text("Cupom e compartilhamento", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    try {
                        OrderPrinter.print(context, order,
                            AlertPreferences.printCopies(context), AlertPreferences.paperWidth(context))
                    } catch (error: Throwable) {
                        printingError = error.message ?: "Impressão indisponível neste aparelho."
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Print, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Imprimir")
                }
                OutlinedButton(onClick = {
                    val text = buildString {
                        append("RODRIGUES AÇAÍ E CIA\nPEDIDO #" + order.number + "\n")
                        append("Cliente: " + order.clientName + "\n")
                        order.items.forEach { item ->
                            append("\n" + item.quantity + "× " + item.name)
                            item.details.forEach { append("\n- " + it) }
                        }
                        if (order.observation.isNotBlank()) append("\nObs.: " + order.observation)
                        append("\n\nTotal: " + money(order.total))
                        if (order.address.isNotBlank() && !order.pickup) append("\nEndereço: " + order.address)
                        append("\nPagamento: " + order.payment.form)
                    }
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(share, "Enviar resumo do pedido"))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Compartilhar")
                }
            }
            if (printingError.isNotBlank()) Text(printingError, color = RedAlert,
                style = MaterialTheme.typography.bodySmall)
            Text("O cupom funciona também com os dados de pedidos salvos no celular.",
                color = Color.Gray, style = MaterialTheme.typography.labelSmall)
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
                onClick = { confirmation = "MANUAL_DISPATCH" },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { Text(labels["MANUAL_DISPATCH"]!!) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    showUp = true
                    upLoading = true
                    upError = ""
                    NativeOperationsApi.couriers(onSuccess = {
                        upCouriers.clear()
                        upCouriers.addAll(it)
                        upLoading = false
                    }, onError = {
                        upLoading = false
                        upError = "Falha ao consultar entregadores: " + (it.message ?: "")
                    })
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Despachar com UP Entregas") }
        }
    }
    confirmation?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("Confirmar alteração?") },
            text = { Text("Pedido #${order.number}: ${labels[target] ?: target}. O pedido será atualizado no sistema real.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    if (target == "MANUAL_DISPATCH") onOwnDispatch() else onAction(target)
                }) { Text("Confirmar") }
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
    if (showUp) {
        AlertDialog(
            onDismissRequest = { if (!upBusy) showUp = false },
            title = { Text("Solicitar UP Entregas") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Escolha um entregador. A oferta só será enviada após confirmação no servidor; ele ainda precisará aceitar.",
                        style = MaterialTheme.typography.bodySmall)
                    if (upLoading || upBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (upError.isNotBlank()) Text(upError, color = RedAlert)
                    if (!upLoading && upCouriers.isEmpty()) Text("Nenhum entregador encontrado.")
                    upCouriers.forEach { courier ->
                        Card(colors = CardDefaults.cardColors(containerColor =
                            if (courier.available) Color(0xFFF1E8FF) else Color(0xFFF2F2F2))) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = courier.available && !upBusy) {
                                upBusy = true
                                upError = ""
                                NativeOperationsApi.offerUp(order.id, courier.id, onSuccess = {
                                    upBusy = false
                                    showUp = false
                                    onDispatchDone()
                                }, onError = {
                                    upBusy = false
                                    upError = "Oferta não enviada: " + (it.message ?: "")
                                })
                            }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DeliveryDining, null,
                                    tint = if (courier.available) Purple else Color.Gray)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(courier.name, fontWeight = FontWeight.Bold)
                                    Text(if (courier.available) "Disponível — tocar para enviar oferta"
                                         else "Indisponível — " + courier.status,
                                         style = MaterialTheme.typography.labelSmall,
                                         color = if (courier.available) Green else Color.Gray)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showUp = false }, enabled = !upBusy) { Text("Voltar") } }
        )
    }

}


@Composable
private fun StoreScreen(
    info: StoreInfo, loading: Boolean, busy: Boolean, error: String, owner: Boolean,
    onRefresh: () -> Unit, onSetOpen: (Boolean) -> Unit, onSave: (Int, String) -> Unit
) {
    var preparation by remember(info.prepMinutes, info.updatedAt) { mutableStateOf(info.prepMinutes.toString()) }
    var message by remember(info.message, info.updatedAt) { mutableStateOf(info.message) }
    var confirming by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Minha loja", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Configurações sincronizadas com o site do cliente.", color = Color.Gray)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storefront, null, tint = if (info.loaded && info.open) Green else Amber)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Funcionamento", fontWeight = FontWeight.Bold)
                        Text(when {
                            !info.loaded -> "Aguardando sincronização"
                            info.open -> "Loja aberta • recebendo pedidos"
                            else -> "Loja fechada"
                        }, color = if (info.loaded && info.open) Green else Amber)
                    }
                    Switch(checked = info.open, enabled = owner && info.loaded && !busy && !loading,
                        onCheckedChange = { confirming = it })
                }
                if (!owner) Text("Apenas o dono pode editar as configurações da loja.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("Status exibido após confirmação no servidor.",
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Prazo e comunicação", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = preparation, onValueChange = {
                    preparation = it.filter(Char::isDigit).take(3)
                }, label = { Text("Prazo estimado de preparo em minutos") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = owner && !busy, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = message, onValueChange = { message = it.take(500) },
                    label = { Text("Aviso exibido aos clientes") },
                    enabled = owner && !busy, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    val minutes = preparation.toIntOrNull()
                    if (minutes != null && minutes in 1..180) onSave(minutes, message)
                }, enabled = owner && !busy && !loading &&
                    (preparation.toIntOrNull()?.let { it in 1..180 } == true),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Salvando…" else "Salvar configurações")
                }
            }
        }
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = RedAlert)
        OutlinedButton(onClick = onRefresh, enabled = !loading && !busy,
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Atualizar dados da loja")
        }
        Text("Offline: a tela mostra o último status salvo. Mudanças só podem ser confirmadas online.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
    confirming?.let { next ->
        AlertDialog(onDismissRequest = { confirming = null },
            title = { Text(if (next) "Abrir a loja?" else "Fechar a loja?") },
            text = { Text("Isso muda a disponibilidade de pedidos no site Rodrigues Açaí e Cia.") },
            confirmButton = { TextButton(onClick = {
                confirming = null
                onSetOpen(next)
            }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun MoreScreen(onNotify: () -> Unit, onLogout: () -> Unit, onStore: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var ringEnabled by remember { mutableStateOf(AlertPreferences.enabled(context)) }
    var vibrationEnabled by remember { mutableStateOf(AlertPreferences.vibration(context)) }
    var interval by remember { mutableFloatStateOf(AlertPreferences.repeatSeconds(context).toFloat()) }
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
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            ListItem(
                headlineContent = { Text("Minha loja", fontWeight = FontWeight.Bold) },
                supportingContent = { Text("Abrir ou fechar, prazo e aviso aos clientes") },
                leadingContent = { Icon(Icons.Default.Storefront, null, tint = Purple) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable(onClick = onStore)
            )
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repetir alerta de novo pedido", modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold)
                    Switch(checked = ringEnabled, onCheckedChange = {
                        ringEnabled = it
                        AlertPreferences.setEnabled(context, it)
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vibrar ao receber pedido", modifier = Modifier.weight(1f))
                    Switch(checked = vibrationEnabled, onCheckedChange = {
                        vibrationEnabled = it
                        AlertPreferences.setVibration(context, it)
                    })
                }
                Text("Intervalo entre toques: " + interval.toInt() + " segundos",
                    style = MaterialTheme.typography.labelMedium)
                Slider(value = interval, onValueChange = {
                    interval = it
                    AlertPreferences.setRepeatSeconds(context, it.toInt())
                }, valueRange = 5f..60f, steps = 10)
            }
        }
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
