package com.rodrigues.gestor.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.firestore.ListenerRegistration
import com.rodrigues.gestor.data.ChatMessage
import com.rodrigues.gestor.data.ChatSummary
import com.rodrigues.gestor.data.CatalogProduct
import com.rodrigues.gestor.data.Driver
import com.rodrigues.gestor.data.Order
import com.rodrigues.gestor.data.OrderAlteration
import com.rodrigues.gestor.data.OrderChat
import com.rodrigues.gestor.data.OrdersRepository
import com.rodrigues.gestor.data.PresenceSummary
import com.rodrigues.gestor.data.StatusGroups
import com.rodrigues.gestor.data.StoreOperation
import com.rodrigues.gestor.data.money
import com.rodrigues.gestor.data.timeText
import com.rodrigues.gestor.notifications.AlertPreferences
import com.rodrigues.gestor.notifications.NotificationHelper
import com.rodrigues.gestor.notifications.OrderRingService
import com.rodrigues.gestor.printing.OrderPrinter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

private enum class MainSection(val label: String) {
    ORDERS("Pedidos"),
    HISTORY("Histórico"),
    PRODUCTS("Produtos"),
    STORE("Loja"),
    MORE("Mais"),
}

private enum class Stage(val label: String) {
    NEW("Novos"),
    PREPARING("Em preparo"),
    READY("Prontos"),
    DELIVERY("Entrega"),
    HISTORY("Histórico"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestorApp(
    requestedOrderId: String?,
    onRequestedOrderConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { OrdersRepository() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var drivers by remember { mutableStateOf<List<Driver>>(emptyList()) }
    var operation by remember { mutableStateOf(StoreOperation()) }
    var presence by remember { mutableStateOf(PresenceSummary()) }
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var alterations by remember { mutableStateOf<List<OrderAlteration>>(emptyList()) }
    var products by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var stage by remember { mutableStateOf(Stage.NEW) }
    var section by remember { mutableStateOf(MainSection.ORDERS) }
    var search by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var urgentOrderId by remember { mutableStateOf<String?>(null) }
    var alertsEnabled by remember { mutableStateOf(AlertPreferences.enabled(context)) }
    var compactCards by remember { mutableStateOf(AlertPreferences.compactCards(context)) }
    var firstSnapshot by remember { mutableStateOf(true) }
    var firstChatSnapshot by remember { mutableStateOf(true) }
    var firstAlterationSnapshot by remember { mutableStateOf(true) }
    val knownIds = remember { mutableSetOf<String>() }
    val knownStatuses = remember { mutableMapOf<String, String>() }
    val knownPayments = remember { mutableMapOf<String, String>() }
    val knownChatTimes = remember { mutableMapOf<String, Long>() }
    val knownAlterationIds = remember { mutableSetOf<String>() }
    val knownAlterationStatuses = remember { mutableMapOf<String, String>() }
    val alertedUnanswered = remember { mutableSetOf<String>() }

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    DisposableEffect(Unit) {
        val orderListener = repository.listenOrders(
            onData = { list ->
                val newOrders = list.filter { it.status in StatusGroups.NEW }
                val fresh = newOrders.filter { it.id !in knownIds }
                if (!firstSnapshot) {
                    list.forEach { current ->
                        val previousStatus = knownStatuses[current.id]
                        if (previousStatus != null && previousStatus != current.status && AlertPreferences.driverAlerts(context) && current.status in StatusGroups.DELIVERY) {
                            NotificationHelper.showMessage(context, "Entrega atualizada", "Pedido #${current.number} • ${StatusGroups.label(current.status)}", current.id)
                        }
                        val paymentNow = current.payment.status.uppercase(Locale.ROOT)
                        val paymentBefore = knownPayments[current.id]
                        if (paymentBefore != null && paymentBefore != paymentNow && AlertPreferences.paymentAlerts(context) && paymentNow in setOf("PAGO", "PAID", "APROVADO", "APPROVED")) {
                            NotificationHelper.showMessage(context, "Pagamento confirmado", "Pedido #${current.number} • ${current.clientName}", current.id)
                        }
                    }
                }
                orders = list
                loading = false
                if (urgentOrderId != null && newOrders.none { it.id == urgentOrderId }) urgentOrderId = null
                if (!firstSnapshot && fresh.isNotEmpty() && selectedId == null && urgentOrderId == null) {
                    urgentOrderId = fresh.minByOrNull { if (it.createdMillis > 0) it.createdMillis else Long.MAX_VALUE }?.id
                    section = MainSection.ORDERS
                    stage = Stage.NEW
                }

                if (alertsEnabled) {
                    val target = when {
                        firstSnapshot -> newOrders.minByOrNull { if (it.createdMillis > 0) it.createdMillis else Long.MAX_VALUE }
                        fresh.isNotEmpty() -> fresh.minByOrNull { if (it.createdMillis > 0) it.createdMillis else Long.MAX_VALUE }
                        else -> null
                    }
                    if (target != null) OrderRingService.start(context, target.id, target.number, target.clientName)
                }
                if (newOrders.isEmpty()) OrderRingService.stop(context)

                knownIds.clear()
                knownIds.addAll(list.map { it.id })
                knownStatuses.clear()
                knownStatuses.putAll(list.associate { it.id to it.status })
                knownPayments.clear()
                knownPayments.putAll(list.associate { it.id to it.payment.status.uppercase(Locale.ROOT) })
                firstSnapshot = false
            },
            onError = { error ->
                loading = false
                showMessage("Erro ao ler pedidos: ${error.message ?: "desconhecido"}")
            }
        )
        val driverListener = repository.listenDrivers(onData = { drivers = it }, onError = { })
        val operationListener = repository.listenOperation(onData = { operation = it }, onError = { })
        val productListener = repository.listenCatalogProducts(onData = { products = it }, onError = { })
        val presenceListener = repository.listenPresence(onData = { presence = it }, onError = { presence = PresenceSummary() })
        val chatListener = repository.listenChats(onData = { list ->
            if (!firstChatSnapshot && AlertPreferences.messageAlerts(context)) {
                list.firstOrNull { chat -> chat.unreadForStore && chat.timestamp > (knownChatTimes[chat.id] ?: 0L) }?.let { fresh ->
                    NotificationHelper.showMessage(context, "Nova mensagem do cliente", fresh.lastText.ifBlank { "Abra o Gestor para responder." }, fresh.orderId)
                }
            }
            chats = list
            knownChatTimes.clear()
            knownChatTimes.putAll(list.associate { it.id to it.timestamp })
            firstChatSnapshot = false
        }, onError = { })
        val alterationListener = repository.listenAlterations(onData = { list ->
            if (!firstAlterationSnapshot && AlertPreferences.changeAlerts(context)) {
                list.firstOrNull { it.waitingStore && it.id !in knownAlterationIds }?.let { fresh ->
                    NotificationHelper.showMessage(context, "Alteração solicitada", "Pedido #${fresh.orderNumber.ifBlank { fresh.orderId.takeLast(6) }} • ${fresh.type}", fresh.orderId)
                }
                list.firstOrNull { alt ->
                    alt.origin.uppercase(Locale.ROOT) == "GESTOR" &&
                        knownAlterationStatuses[alt.id] == "AGUARDANDO_CLIENTE" &&
                        alt.status.uppercase(Locale.ROOT) in setOf("APROVADO_CLIENTE", "RECUSADO_CLIENTE")
                }?.let { response ->
                    NotificationHelper.showMessage(
                        context,
                        if (response.status.uppercase(Locale.ROOT) == "APROVADO_CLIENTE") "Cliente aprovou a alteração" else "Cliente recusou a alteração",
                        "Pedido #${response.orderNumber.ifBlank { response.orderId.takeLast(6) }} • ${response.newItem.ifBlank { response.type }}",
                        response.orderId
                    )
                }
            }
            alterations = list
            knownAlterationIds.clear()
            knownAlterationIds.addAll(list.map { it.id })
            knownAlterationStatuses.clear()
            knownAlterationStatuses.putAll(list.associate { it.id to it.status.uppercase(Locale.ROOT) })
            firstAlterationSnapshot = false
        }, onError = { })
        onDispose {
            orderListener.remove()
            driverListener.remove()
            operationListener.remove()
            productListener.remove()
            presenceListener.remove()
            chatListener.remove()
            alterationListener.remove()
        }
    }

    LaunchedEffect(orders, alertsEnabled) {
        while (true) {
            if (alertsEnabled) {
                val limitMs = AlertPreferences.unansweredMinutes(context) * 60_000L
                orders.filter { it.status in StatusGroups.NEW && it.createdMillis > 0 && System.currentTimeMillis() - it.createdMillis >= limitMs }
                    .firstOrNull { it.id !in alertedUnanswered }
                    ?.let { overdue ->
                        alertedUnanswered += overdue.id
                        NotificationHelper.showMessage(context, "Pedido aguardando atendimento", "Pedido #${overdue.number} ainda não foi aceito.", overdue.id)
                    }
                alertedUnanswered.retainAll(orders.filter { it.status in StatusGroups.NEW }.map { it.id }.toSet())
            }
            delay(30_000L)
        }
    }

    LaunchedEffect(requestedOrderId, orders) {
        if (!requestedOrderId.isNullOrBlank() && orders.any { it.id == requestedOrderId }) {
            selectedId = requestedOrderId
            section = MainSection.ORDERS
            onRequestedOrderConsumed()
        }
    }

    val selected = selectedId?.let { id -> orders.firstOrNull { it.id == id || it.number == id } }
    if (selected != null) {
        OrderDetailScreen(
            order = selected,
            drivers = drivers,
            repository = repository,
            onBack = { selectedId = null },
            onChanged = { message -> showMessage(message) },
        )
        return
    }

    val urgentOrder = urgentOrderId?.let { id -> orders.firstOrNull { it.id == id && it.status in StatusGroups.NEW } }
    if (urgentOrder != null) {
        NewOrderAlertScreen(
            order = urgentOrder,
            onAccept = {
                repository.updateStatus(urgentOrder, "CONFIRMADO", {
                    urgentOrderId = null
                    OrderRingService.stop(context)
                    if (AlertPreferences.autoPrintOnAccept(context)) {
                        OrderPrinter.print(context, urgentOrder, AlertPreferences.printCopies(context), AlertPreferences.paperWidth(context))
                    }
                    showMessage("Pedido #${urgentOrder.number} aceito")
                }, { showMessage(it.message ?: "Não foi possível aceitar o pedido") })
            },
            onView = {
                urgentOrderId = null
                selectedId = urgentOrder.id
            },
            onSilence = {
                OrderRingService.stop(context)
                showMessage("Toque silenciado; o pedido continua aguardando aceite")
            }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greetingText(), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            when (section) {
                                MainSection.ORDERS -> "Pedidos em tempo real"
                                MainSection.HISTORY -> "Histórico"
                                MainSection.PRODUCTS -> "Produtos"
                                MainSection.STORE -> "Loja"
                                MainSection.MORE -> "Mais"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        alertsEnabled = !alertsEnabled
                        AlertPreferences.setEnabled(context, alertsEnabled)
                        if (alertsEnabled) {
                            orders.firstOrNull { it.status in StatusGroups.NEW }?.let {
                                OrderRingService.start(context, it.id, it.number, it.clientName)
                            }
                            showMessage("Alertas de novos pedidos ativados")
                        } else showMessage("Alertas de novos pedidos silenciados")
                    }) {
                        Icon(
                            if (alertsEnabled) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                            contentDescription = "Alertas",
                            tint = if (alertsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MainSection.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = {
                            Icon(
                                when (item) {
                                    MainSection.ORDERS -> Icons.Default.Restaurant
                                    MainSection.HISTORY -> Icons.Default.History
                                    MainSection.PRODUCTS -> Icons.Default.Restaurant
                                    MainSection.STORE -> Icons.Default.Store
                                    MainSection.MORE -> Icons.Default.MoreHoriz
                                },
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label, fontSize = 10.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        when (section) {
            MainSection.ORDERS -> OrdersHomeScreen(
                modifier = Modifier.padding(padding),
                orders = orders,
                loading = loading,
                stage = stage,
                search = search,
                presence = presence,
                operation = operation,
                compactCards = compactCards,
                onStage = { stage = it },
                onSearch = { search = it },
                onOpenOrder = { selectedId = it },
            )
            MainSection.HISTORY -> HistoryScreen(
                modifier = Modifier.padding(padding),
                orders = orders,
                onOpenOrder = { selectedId = it },
            )
            MainSection.PRODUCTS -> ProductsScreen(
                modifier = Modifier.padding(padding),
                products = products,
                repository = repository,
                onMessage = ::showMessage,
            )
            MainSection.STORE -> StoreScreen(
                modifier = Modifier.padding(padding),
                operation = operation,
                repository = repository,
                onMessage = ::showMessage,
            )
            MainSection.MORE -> MoreScreen(
                modifier = Modifier.padding(padding),
                orders = orders,
                drivers = drivers,
                chats = chats,
                alterations = alterations,
                repository = repository,
                alertsEnabled = alertsEnabled,
                compactCards = compactCards,
                onAlertsChanged = { alertsEnabled = it },
                onCompactChanged = { compactCards = it },
                onOpenOrder = { selectedId = it },
                onMessage = ::showMessage,
            )
        }
    }
}


@Composable
private fun NewOrderAlertScreen(
    order: Order,
    onAccept: () -> Unit,
    onView: () -> Unit,
    onSilence: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(82.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("NOVO PEDIDO!", fontWeight = FontWeight.Black, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
            Text("#${order.number}", fontWeight = FontWeight.Black, fontSize = 40.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(order.clientName, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(if (order.pickup) "Retirada" else "Entrega", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${order.items.sumOf { it.quantity }} item(ns)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(money(order.total), fontWeight = FontWeight.Black, fontSize = 32.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text("ACEITAR PEDIDO", fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onView, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Text("VER DETALHES", fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(7.dp))
            TextButton(onClick = onSilence) {
                Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Silenciar toque")
            }
        }
    }
}

@Composable
private fun OrdersHomeScreen(
    modifier: Modifier,
    orders: List<Order>,
    loading: Boolean,
    stage: Stage,
    search: String,
    presence: PresenceSummary,
    operation: StoreOperation,
    compactCards: Boolean,
    onStage: (Stage) -> Unit,
    onSearch: (String) -> Unit,
    onOpenOrder: (String) -> Unit,
) {
    val context = LocalContext.current
    var quickFilter by remember { mutableStateOf("TODOS") }
    val yellow = AlertPreferences.lateYellowMinutes(context)
    val red = AlertPreferences.lateRedMinutes(context)
    val filtered = remember(orders, stage, search, quickFilter, yellow) {
        val base = when (stage) {
            Stage.NEW -> orders.filter { it.status in StatusGroups.NEW }
            Stage.PREPARING -> orders.filter { it.status in StatusGroups.PREPARATION_BUCKET }
            Stage.READY -> orders.filter { it.status in StatusGroups.READY }
            Stage.DELIVERY -> orders.filter { it.status in StatusGroups.DELIVERY }
            Stage.HISTORY -> orders.filter { it.status in StatusGroups.DONE || it.status in StatusGroups.CANCELED }
        }
        val q = search.trim().lowercase(Locale.ROOT)
        val searched = if (q.isBlank()) base else base.filter { order ->
            buildString {
                append(order.number); append(' ')
                append(order.clientName); append(' ')
                append(order.phone); append(' ')
                order.items.forEach { append(it.name); append(' ') }
            }.lowercase(Locale.ROOT).contains(q)
        }
        searched.filter { order ->
            when (quickFilter) {
                "ENTREGA" -> !order.pickup
                "RETIRADA" -> order.pickup
                "ATRASADOS" -> order.createdMillis > 0 && (System.currentTimeMillis() - order.createdMillis) / 60_000L >= yellow
                "PAGAMENTO" -> order.payment.status.uppercase(Locale.ROOT) !in setOf("PAGO", "PAID", "APROVADO", "APPROVED")
                else -> true
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp)
    ) {
        StoreAndPresenceStrip(operation, presence)
        Spacer(Modifier.height(9.dp))
        MetricsRow(orders = orders, selectedStage = stage, onStage = onStage)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Pedido, cliente ou item") },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf("TODOS", "ATRASADOS", "ENTREGA", "RETIRADA", "PAGAMENTO")) { option ->
                FilterChip(
                    selected = quickFilter == option,
                    onClick = { quickFilter = option },
                    label = { Text(if (option == "PAGAMENTO") "Pagamento pendente" else option.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (filtered.isEmpty()) {
            EmptyStage(stage)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (compactCards) 7.dp else 10.dp),
                contentPadding = PaddingValues(bottom = 22.dp)
            ) {
                items(filtered, key = { it.id }) { order ->
                    OrderCard(
                        order = order,
                        onClick = { onOpenOrder(order.id) },
                        yellowMinutes = yellow,
                        redMinutes = red,
                        compact = compactCards,
                    )
                }
            }
        }
    }
}

@Composable
private fun StoreAndPresenceStrip(operation: StoreOperation, presence: PresenceSummary) {
    val open = operation.acceptingOrders
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (open) "●  Loja aberta" else "●  Pedidos pausados",
                fontWeight = FontWeight.Bold,
                color = if (open) Color(0xFF239A3B) else MaterialTheme.colorScheme.primary,
                fontSize = 13.sp
            )
            Text("  •  preparo ~${operation.prepMinutes} min", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.People, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                "${presence.online} ${if (presence.online == 1) "pessoa online agora" else "pessoas online agora"}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
        if (presence.online > 0 && (presence.cart > 0 || presence.checkout > 0)) {
            Spacer(Modifier.height(3.dp))
            Text(
                "${presence.cart} na sacola  •  ${presence.checkout} no checkout",
                modifier = Modifier.padding(start = 23.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun greetingText(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..11 -> "Bom dia 👋"
    in 12..17 -> "Boa tarde 👋"
    else -> "Boa noite 👋"
}


@Composable
private fun MetricsRow(orders: List<Order>, selectedStage: Stage, onStage: (Stage) -> Unit) {
    val data = listOf(
        Triple(Stage.NEW, orders.count { it.status in StatusGroups.NEW }, Color(0xFFE9151D)),
        Triple(Stage.PREPARING, orders.count { it.status in StatusGroups.PREPARATION_BUCKET }, Color(0xFFF28C18)),
        Triple(Stage.READY, orders.count { it.status in StatusGroups.READY }, Color(0xFF249B3B)),
        Triple(Stage.DELIVERY, orders.count { it.status in StatusGroups.DELIVERY }, Color(0xFF2878D0)),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        data.forEach { (stage, count, color) ->
            MetricBox(
                label = stage.label,
                value = count.toString(),
                color = color,
                selected = selectedStage == stage,
                onClick = { onStage(stage) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = if (selected) color.copy(alpha = .10f) else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 1.dp else 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, color = color, fontSize = 22.sp)
            Spacer(Modifier.height(2.dp))
            Text(label, fontWeight = FontWeight.Bold, color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun StageTabs(stage: Stage, orders: List<Order>, onStage: (Stage) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(listOf(Stage.NEW, Stage.PREPARING, Stage.READY, Stage.DELIVERY)) { item ->
            val count = when (item) {
                Stage.NEW -> orders.count { it.status in StatusGroups.NEW }
                Stage.PREPARING -> orders.count { it.status in StatusGroups.PREPARATION_BUCKET }
                Stage.READY -> orders.count { it.status in StatusGroups.READY }
                Stage.DELIVERY -> orders.count { it.status in StatusGroups.DELIVERY }
                Stage.HISTORY -> orders.count { it.status in StatusGroups.DONE || it.status in StatusGroups.CANCELED }
            }
            FilterChip(
                selected = stage == item,
                onClick = { onStage(item) },
                label = { Text("${item.label} ($count)", fontWeight = FontWeight.Bold) }
            )
        }
    }
}

@Composable
private fun EmptyStage(stage: Stage) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                when (stage) {
                    Stage.NEW -> Icons.Default.Notifications
                    Stage.PREPARING -> Icons.Default.Restaurant
                    Stage.READY -> Icons.Default.CheckCircle
                    Stage.DELIVERY -> Icons.Default.DeliveryDining
                    Stage.HISTORY -> Icons.Default.History
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text("Nenhum pedido aqui", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("A lista atualiza automaticamente pelo Firestore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OrderCard(
    order: Order,
    onClick: () -> Unit,
    yellowMinutes: Int = 20,
    redMinutes: Int = 35,
    compact: Boolean = false,
) {
    val statusColor = statusColor(order.status)
    val issueMap = order.raw["problemaOperacional"] as? Map<*, *>
    val issueActive = issueMap?.get("ativo") == true
    val issueType = issueMap?.get("tipo")?.toString().orEmpty()
    val ageMinutes = if (order.createdMillis > 0) ((System.currentTimeMillis() - order.createdMillis) / 60_000L).coerceAtLeast(0) else 0
    val urgency = when {
        issueActive -> Color(0xFFFFF0F0)
        order.status in StatusGroups.DONE || order.status in StatusGroups.CANCELED -> MaterialTheme.colorScheme.surface
        ageMinutes >= redMinutes -> Color(0xFFFFF0F0)
        ageMinutes >= yellowMinutes -> Color(0xFFFFF7E8)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = urgency)
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PEDIDO #${order.number}", fontWeight = FontWeight.Black, fontSize = if (compact) 17.sp else 19.sp)
                    Text(
                        "${timeText(order.createdMillis)} • ${order.clientName}",
                        fontSize = if (compact) 13.sp else 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill(order.status)
            }
            Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = statusColor.copy(alpha = .10f), shape = RoundedCornerShape(50)) {
                    Text(
                        if (ageMinutes == 0L) "agora" else "há ${ageMinutes} min",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    if (order.pickup) "RETIRADA" else "ENTREGA${order.neighborhood.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(if (compact) 6.dp else 9.dp))
            order.items.take(if (compact) 2 else 3).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    Text("${item.quantity}x", modifier = Modifier.width(30.dp), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text(
                        item.name,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 14.sp else 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.price > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(money(item.price), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            if (order.items.size > (if (compact) 2 else 3)) {
                Text(
                    "+ ${order.items.size - (if (compact) 2 else 3)} item(ns)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            if (issueActive) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ PROBLEMA • ${issueType.ifBlank { "Atenção necessária" }}", color = Color(0xFFE9151D), fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (order.observation.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("⚠ ${order.observation}", color = Color(0xFFF28C18), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(if (compact) 7.dp else 9.dp))
            HorizontalDivider()
            Spacer(Modifier.height(if (compact) 7.dp else 9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(order.payment.form.ifBlank { "Pagamento não informado" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(money(order.total), fontWeight = FontWeight.Black, fontSize = if (compact) 18.sp else 20.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val color = statusColor(status)
    Box(
        Modifier
            .background(color.copy(alpha = .12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(StatusGroups.label(status), color = color, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

private fun statusColor(status: String): Color = when {
    status in StatusGroups.NEW -> Color(0xFFE9151D)
    status in StatusGroups.PREPARATION_BUCKET -> Color(0xFFF28C18)
    status in StatusGroups.READY || status in StatusGroups.DONE -> Color(0xFF249B3B)
    status in StatusGroups.DELIVERY -> Color(0xFF2878D0)
    status in StatusGroups.CANCELED -> Color(0xFF6B7280)
    else -> Color(0xFF6B7280)
}


@Composable
private fun HistoryScreen(
    modifier: Modifier,
    orders: List<Order>,
    onOpenOrder: (String) -> Unit,
) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("HOJE") }
    val yellow = AlertPreferences.lateYellowMinutes(context)
    val red = AlertPreferences.lateRedMinutes(context)
    val startToday = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val filtered = remember(orders, search, range, startToday) {
        val q = search.trim().lowercase(Locale.ROOT)
        val minTime = when (range) {
            "HOJE" -> startToday
            "7_DIAS" -> System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            else -> 0L
        }
        orders.filter { it.status in StatusGroups.DONE || it.status in StatusGroups.CANCELED }
            .filter { minTime == 0L || it.createdMillis == 0L || it.createdMillis >= minTime }
            .filter {
                q.isBlank() || listOf(it.number, it.clientName, it.phone, it.payment.form)
                    .joinToString(" ").lowercase(Locale.ROOT).contains(q)
            }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Buscar por número ou cliente") },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = range == "HOJE", onClick = { range = "HOJE" }, label = { Text("Hoje") })
            FilterChip(selected = range == "7_DIAS", onClick = { range = "7_DIAS" }, label = { Text("7 dias") })
            FilterChip(selected = range == "TODOS", onClick = { range = "TODOS" }, label = { Text("Todos") })
        }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum pedido no histórico.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.id }) { order ->
                    OrderCard(
                        order = order,
                        onClick = { onOpenOrder(order.id) },
                        yellowMinutes = yellow,
                        redMinutes = red,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductsScreen(
    modifier: Modifier,
    products: List<CatalogProduct>,
    repository: OrdersRepository,
    onMessage: (String) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("TODOS") }
    val shown = remember(products, search, filter) {
        products.filter { product ->
            (search.isBlank() || product.name.contains(search, ignoreCase = true) || product.category.contains(search, ignoreCase = true)) &&
                when (filter) {
                    "ATIVOS" -> product.available
                    "PAUSADOS" -> !product.available
                    else -> true
                }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Buscar produto") },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(selected = filter == "TODOS", onClick = { filter = "TODOS" }, label = { Text("Todos") })
            FilterChip(selected = filter == "ATIVOS", onClick = { filter = "ATIVOS" }, label = { Text("Ativos") })
            FilterChip(selected = filter == "PAUSADOS", onClick = { filter = "PAUSADOS" }, label = { Text("Pausados") })
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "Aqui é operação rápida: pausar ou reativar. Cadastro completo continua no GADM.",
                modifier = Modifier.padding(12.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum produto encontrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(shown, key = { it.id }) { product ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.Black, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (product.category.isNotBlank()) Text(product.category, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (product.available) "ATIVO" else "PAUSADO",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    color = if (product.available) Color(0xFF65D66E) else Color(0xFFFF6B65)
                                )
                            }
                            Switch(
                                checked = product.available,
                                onCheckedChange = { value ->
                                    repository.setCatalogProductAvailable(
                                        product,
                                        value,
                                        { onMessage(if (value) "${product.name} reativado" else "${product.name} pausado") },
                                        { onMessage(it.message ?: "Erro ao alterar produto") }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreScreen(
    modifier: Modifier,
    operation: StoreOperation,
    repository: OrdersRepository,
    onMessage: (String) -> Unit,
) {
    var prep by remember { mutableStateOf(operation.prepMinutes) }
    var closedMessage by remember { mutableStateOf(operation.closedMessage) }
    var demandMessage by remember { mutableStateOf(operation.demandMessage) }
    LaunchedEffect(operation.prepMinutes, operation.closedMessage, operation.demandMessage) {
        prep = operation.prepMinutes
        closedMessage = operation.closedMessage
        demandMessage = operation.demandMessage
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = if (operation.acceptingOrders) Color(0xFF182814) else Color(0xFF2B1717))
            ) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Store, null, modifier = Modifier.size(44.dp), tint = if (operation.acceptingOrders) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(if (operation.acceptingOrders) "LOJA ABERTA" else "LOJA PAUSADA/FECHADA", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text(
                        if (operation.acceptingOrders) "Recebendo pedidos normalmente" else "O Cliente não deve concluir novos pedidos",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { repository.setStoreOpen(true, { onMessage("Loja aberta para pedidos") }, { onMessage(it.message ?: "Erro ao abrir loja") }) },
                            modifier = Modifier.weight(1f)
                        ) { Text("ABRIR") }
                        OutlinedButton(
                            onClick = { repository.setStoreOpen(false, { onMessage("Loja fechada para pedidos") }, { onMessage(it.message ?: "Erro ao fechar loja") }) },
                            modifier = Modifier.weight(1f)
                        ) { Text("FECHAR") }
                    }
                }
            }
        }
        item {
            DetailCard("Pausa temporária") {
                if (operation.paused) {
                    Text("Pausa ativa agora.", fontWeight = FontWeight.Bold, color = Color(0xFFF28C18))
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { repository.clearStorePause({ onMessage("Pedidos retomados") }, { onMessage(it.message ?: "Erro ao retomar") }) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RETOMAR PEDIDOS") }
                } else {
                    Text("A loja continua visível, mas o checkout fica temporariamente bloqueado.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf(15, 30, 45, 60)) { minutes ->
                            AssistChip(
                                onClick = { repository.pauseStore(minutes, { onMessage("Loja pausada por $minutes min") }, { onMessage(it.message ?: "Erro ao pausar") }) },
                                label = { Text("$minutes min") }
                            )
                        }
                    }
                }
            }
        }
        item {
            DetailCard("Tempo de preparo") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf(15, 20, 25, 30, 40, 50)) { minutes ->
                        FilterChip(selected = prep == minutes, onClick = { prep = minutes }, label = { Text("$minutes min") })
                    }
                }
            }
        }
        item {
            DetailCard("Mensagens da loja") {
                OutlinedTextField(
                    value = demandMessage,
                    onValueChange = { demandMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Aviso quando aberta") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = closedMessage,
                    onValueChange = { closedMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mensagem quando fechada") }
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        repository.updateOperationSettings(
                            prepMinutes = prep,
                            closedMessage = closedMessage,
                            demandMessage = demandMessage,
                            onDone = { onMessage("Configurações da loja salvas") },
                            onError = { onMessage(it.message ?: "Erro ao salvar") }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("SALVAR") }
            }
        }
    }
}


@Composable
private fun DeliveriesScreen(
    modifier: Modifier,
    orders: List<Order>,
    drivers: List<Driver>,
    onOpenOrder: (String) -> Unit,
) {
    val deliveryOrders = orders.filter { it.status in StatusGroups.DELIVERY || it.status in StatusGroups.READY }
    val available = drivers.count { it.available }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF182814))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeliveryDining, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("UP ENTREGAS", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text("Escolha do entregador continua manual", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$available", fontSize = 24.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text("livres agora", fontSize = 11.sp)
                    }
                }
            }
        }
        item {
            Text("Entregadores", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 7.dp)) {
                if (drivers.isEmpty()) {
                    item { Text("Nenhum entregador cadastrado.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(drivers, key = { it.id }) { driver ->
                        Surface(
                            color = if (driver.available) Color(0xFF182814) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text(driver.name, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(if (driver.available) "● disponível" else driver.status, fontSize = 11.sp, color = if (driver.available) Color(0xFF65D66E) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Expedição", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (deliveryOrders.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Text("Nenhum pedido aguardando ou em entrega.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(deliveryOrders, key = { it.id }) { order ->
                Card(onClick = { onOpenOrder(order.id) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("PEDIDO #${order.number}", fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Text(order.clientName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(order.status)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (order.status in StatusGroups.READY) Icons.Default.Store else Icons.Default.LocalShipping, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                if (order.status in StatusGroups.READY) "Pronto para chamar entregador" else StatusGroups.label(order.status),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(money(order.total), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesScreen(
    modifier: Modifier,
    orders: List<Order>,
    chats: List<ChatSummary>,
    alterations: List<OrderAlteration>,
    repository: OrdersRepository,
    onOpenOrder: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val pending = alterations.filter { it.waitingStore }
    val waitingClient = alterations.filter { it.waitingClient }
    val clientResponses = alterations.filter {
        it.origin.uppercase(Locale.ROOT) == "GESTOR" && it.status.uppercase(Locale.ROOT) in setOf("APROVADO_CLIENTE", "RECUSADO_CLIENTE")
    }.take(6)
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = if (pending.isNotEmpty()) Color(0xFF33260F) else MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Chat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ATENDIMENTO", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        Text("Chat e alterações de itens", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${pending.size + waitingClient.size}", fontWeight = FontWeight.Black, fontSize = 24.sp, color = if (pending.isNotEmpty()) Color(0xFFFFB454) else MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (pending.isNotEmpty()) {
            item { Text("Aguardando sua decisão", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color(0xFFF28C18)) }
            items(pending, key = { "alt:${it.id}" }) { alt ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF28231A))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${alt.type.replace('_', ' ')} • Pedido #${alt.orderNumber.ifBlank { alt.orderId.takeLast(6) }}", fontWeight = FontWeight.Black)
                        Text(alt.clientName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(7.dp))
                        if (alt.currentItem.isNotBlank()) Text("Atual: ${alt.currentItem}", fontWeight = FontWeight.SemiBold)
                        if (alt.newItem.isNotBlank()) Text("Pedido pelo cliente: ${alt.newItem}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (alt.observation.isNotBlank()) Text(alt.observation, fontSize = 13.sp)
                        Spacer(Modifier.height(9.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    repository.resolveAlteration(alt, true, { onMessage("Alteração aprovada") }, { onMessage(it.message ?: "Erro ao aprovar") })
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("APROVAR") }
                            OutlinedButton(
                                onClick = {
                                    repository.resolveAlteration(alt, false, { onMessage("Alteração recusada") }, { onMessage(it.message ?: "Erro ao recusar") })
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("RECUSAR") }
                        }
                        val target = orders.firstOrNull { it.id == alt.orderId || it.number == alt.orderNumber }
                        if (target != null) {
                            TextButton(onClick = { onOpenOrder(target.id) }) { Text("ABRIR PEDIDO") }
                        }
                    }
                }
            }
        }
        if (waitingClient.isNotEmpty()) {
            item { Text("Aguardando o cliente", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
            items(waitingClient, key = { "wait:${it.id}" }) { alt ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(13.dp)) {
                        Text("Pedido #${alt.orderNumber.ifBlank { alt.orderId.takeLast(6) }} • ${alt.type}", fontWeight = FontWeight.Black)
                        Text(alt.newItem.ifBlank { alt.observation.ifBlank { "Proposta enviada" } }, fontSize = 13.sp)
                        Text("Aguardando aprovação/recusa do cliente", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (clientResponses.isNotEmpty()) {
            item { Text("Respostas do cliente", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
            items(clientResponses, key = { "resp:${it.id}" }) { alt ->
                val approved = alt.status.uppercase(Locale.ROOT) == "APROVADO_CLIENTE"
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (approved) Color(0xFF182814) else Color(0xFF2B1717))) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (approved) Icons.Default.CheckCircle else Icons.Default.Cancel, null, tint = if (approved) Color(0xFF65D66E) else Color(0xFFFF6B65))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pedido #${alt.orderNumber.ifBlank { alt.orderId.takeLast(6) }}", fontWeight = FontWeight.Black)
                            Text(if (approved) "Cliente aprovou: ${alt.newItem.ifBlank { alt.type }}" else "Cliente recusou a proposta", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item { Text("Conversas", fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
        if (chats.isEmpty()) {
            item { Text("Nenhuma conversa ativa.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp)) }
        } else {
            items(chats.filter { it.lastText.isNotBlank() }, key = { "chat:${it.id}" }) { chat ->
                val order = orders.firstOrNull { it.id == chat.orderId || it.number == chat.orderId }
                Card(
                    onClick = { order?.let { onOpenOrder(it.id) } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(19.dp),
                    colors = CardDefaults.cardColors(containerColor = if (chat.unreadForStore) Color(0xFF24192E) else MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Default.Chat, null, modifier = Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(order?.let { "Pedido #${it.number} • ${it.clientName}" } ?: "Conversa do cliente", fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(chat.lastText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (chat.unreadForStore) Text("NOVA", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationScreen(
    modifier: Modifier,
    operation: StoreOperation,
    products: List<CatalogProduct>,
    repository: OrdersRepository,
    onMessage: (String) -> Unit,
) {
    var prep by remember { mutableStateOf(operation.prepMinutes.toString()) }
    var closedMessage by remember { mutableStateOf(operation.closedMessage) }
    var demandMessage by remember { mutableStateOf(operation.demandMessage) }
    var productSearch by remember { mutableStateOf("") }
    LaunchedEffect(operation.prepMinutes, operation.closedMessage, operation.demandMessage) {
        prep = operation.prepMinutes.toString()
        closedMessage = operation.closedMessage
        demandMessage = operation.demandMessage
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = if (operation.acceptingOrders) Color(0xFF182814) else Color(0xFF2B1717))
            ) {
                Column(Modifier.padding(17.dp)) {
                    Text(if (operation.acceptingOrders) "● LOJA ABERTA" else "● LOJA PAUSADA/FECHADA", fontWeight = FontWeight.Black, fontSize = 20.sp, color = if (operation.acceptingOrders) Color(0xFF65D66E) else Color(0xFFFF6B65))
                    Text("Essa configuração é a mesma que o site Cliente lê em tempo real.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { repository.setStoreOpen(true, { onMessage("Loja aberta para pedidos") }, { onMessage(it.message ?: "Erro ao abrir loja") }) },
                            modifier = Modifier.weight(1f)
                        ) { Text("ABRIR") }
                        OutlinedButton(
                            onClick = { repository.setStoreOpen(false, { onMessage("Loja fechada para pedidos") }, { onMessage(it.message ?: "Erro ao fechar loja") }) },
                            modifier = Modifier.weight(1f)
                        ) { Text("FECHAR") }
                    }
                }
            }
        }
        item {
            DetailCard("Pausa temporária") {
                if (operation.paused) {
                    Text("Pausa ativa agora.", fontWeight = FontWeight.Bold, color = Color(0xFFF28C18))
                    Spacer(Modifier.height(7.dp))
                    Button(onClick = { repository.clearStorePause({ onMessage("Pedidos retomados") }, { onMessage(it.message ?: "Erro ao retomar") }) }, modifier = Modifier.fillMaxWidth()) {
                        Text("RETOMAR PEDIDOS AGORA")
                    }
                } else {
                    Text("Pause o checkout sem precisar fechar a loja definitivamente.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(7.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(listOf(15, 30, 45, 60)) { minutes ->
                            AssistChip(
                                onClick = { repository.pauseStore(minutes, { onMessage("Loja pausada por $minutes min") }, { onMessage(it.message ?: "Erro ao pausar") }) },
                                label = { Text("$minutes min") }
                            )
                        }
                    }
                }
            }
        }
        item {
            DetailCard("Tempo de preparo") {
                Text("Previsão padrão mostrada pela operação.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(7.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf(15, 20, 25, 30, 40, 50)) { minutes ->
                        FilterChip(selected = prep == minutes.toString(), onClick = { prep = minutes.toString() }, label = { Text("$minutes min") })
                    }
                }
            }
        }
        item {
            DetailCard("Pausar produto rapidamente") {
                Text("Use para item que acabou durante a operação. O cadastro completo continua no GADM.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = productSearch,
                    onValueChange = { productSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Buscar produto") }
                )
                Spacer(Modifier.height(7.dp))
                val shown = products.filter { productSearch.isBlank() || it.name.contains(productSearch, ignoreCase = true) }.take(8)
                if (shown.isEmpty()) {
                    Text("Nenhum produto encontrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    shown.forEach { product ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (product.category.isNotBlank()) Text(product.category, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = product.available,
                                onCheckedChange = { value ->
                                    repository.setCatalogProductAvailable(product, value, { onMessage(if (value) "${product.name} reativado" else "${product.name} pausado") }, { onMessage(it.message ?: "Erro ao alterar produto") })
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            DetailCard("Mensagens da operação") {
                OutlinedTextField(
                    value = demandMessage,
                    onValueChange = { demandMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Aviso quando aberta") },
                    placeholder = { Text("🟢 Loja aberta • Faça seu pedido") }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = closedMessage,
                    onValueChange = { closedMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mensagem quando fechada") },
                    placeholder = { Text("Voltamos em breve") }
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        repository.updateOperationSettings(
                            prepMinutes = prep.toIntOrNull() ?: 25,
                            closedMessage = closedMessage,
                            demandMessage = demandMessage,
                            onDone = { onMessage("Configurações da loja salvas") },
                            onError = { onMessage(it.message ?: "Erro ao salvar") }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("SALVAR OPERAÇÃO") }
            }
        }
    }
}

@Composable
private fun MoreScreen(
    modifier: Modifier,
    orders: List<Order>,
    drivers: List<Driver>,
    chats: List<ChatSummary>,
    alterations: List<OrderAlteration>,
    repository: OrdersRepository,
    alertsEnabled: Boolean,
    compactCards: Boolean,
    onAlertsChanged: (Boolean) -> Unit,
    onCompactChanged: (Boolean) -> Unit,
    onOpenOrder: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var morePage by remember { mutableStateOf("MENU") }
    if (morePage == "ENTREGAS") {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { morePage = "MENU" }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.ArrowBack, null)
                Spacer(Modifier.width(6.dp))
                Text("Voltar para Mais")
            }
            DeliveriesScreen(
                modifier = Modifier.weight(1f),
                orders = orders,
                drivers = drivers,
                onOpenOrder = onOpenOrder,
            )
        }
        return
    }
    if (morePage == "MENSAGENS") {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { morePage = "MENU" }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Default.ArrowBack, null)
                Spacer(Modifier.width(6.dp))
                Text("Voltar para Mais")
            }
            MessagesScreen(
                modifier = Modifier.weight(1f),
                orders = orders,
                chats = chats,
                alterations = alterations,
                repository = repository,
                onOpenOrder = onOpenOrder,
                onMessage = onMessage,
            )
        }
        return
    }
    var vibration by remember { mutableStateOf(AlertPreferences.vibration(context)) }
    var repeat by remember { mutableStateOf(AlertPreferences.repeatSeconds(context)) }
    var ringMinutes by remember { mutableStateOf(AlertPreferences.maxRingMinutes(context)) }
    var unanswered by remember { mutableStateOf(AlertPreferences.unansweredMinutes(context)) }
    var yellow by remember { mutableStateOf(AlertPreferences.lateYellowMinutes(context)) }
    var red by remember { mutableStateOf(AlertPreferences.lateRedMinutes(context)) }
    var msgAlerts by remember { mutableStateOf(AlertPreferences.messageAlerts(context)) }
    var changeAlerts by remember { mutableStateOf(AlertPreferences.changeAlerts(context)) }
    var driverAlerts by remember { mutableStateOf(AlertPreferences.driverAlerts(context)) }
    var paymentAlerts by remember { mutableStateOf(AlertPreferences.paymentAlerts(context)) }
    var autoPrint by remember { mutableStateOf(AlertPreferences.autoPrintOnAccept(context)) }
    var copies by remember { mutableStateOf(AlertPreferences.printCopies(context)) }
    var paper by remember { mutableStateOf(AlertPreferences.paperWidth(context)) }

    val network = isNetworkAvailable(context)
    val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val battery = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    val doneToday = orders.count { it.status in StatusGroups.DONE }
    val active = orders.count { it.status !in StatusGroups.DONE && it.status !in StatusGroups.CANCELED }
    val sales = orders.filter { it.status in StatusGroups.DONE }.sumOf { it.total }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            DetailCard("Central operacional") {
                val activeDeliveries = orders.count { it.status in StatusGroups.DELIVERY }
                val unreadChats = chats.count { it.unreadForStore }
                val pendingChanges = alterations.count { it.waitingStore }
                Button(onClick = { morePage = "ENTREGAS" }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DeliveryDining, null)
                    Spacer(Modifier.width(8.dp))
                    Text("UP ENTREGAS • $activeDeliveries em andamento", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { morePage = "MENSAGENS" }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Chat, null)
                    Spacer(Modifier.width(8.dp))
                    Text("MENSAGENS • $unreadChats nova(s)", fontWeight = FontWeight.Black)
                }
                if (pendingChanges > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠ $pendingChanges alteração(ões) aguardando decisão da loja", color = Color(0xFFF28C18), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text("Entregadores disponíveis: ${drivers.count { it.available }}", modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            DetailCard("Resumo rápido") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric("Ativos", active.toString(), Modifier.weight(1f))
                    MiniMetric("Finalizados", doneToday.toString(), Modifier.weight(1f))
                    MiniMetric("Alertas", if (alertsEnabled) "ON" else "OFF", Modifier.weight(1f))
                }
            }
        }
        item {
            DetailCard("Financeiro rápido") {
                LabelValue("Vendas finalizadas", money(sales))
                LabelValue("Pedidos finalizados", doneToday.toString())
                val ticket = if (doneToday > 0) sales / doneToday else 0.0
                LabelValue("Ticket médio", money(ticket))
                Text("Resumo operacional; relatórios completos podem permanecer no GADM.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            DetailCard("Configurações de alertas") {
                SettingSwitchRow("Novo pedido", "Som forte e aviso de novo pedido", alertsEnabled) {
                    AlertPreferences.setEnabled(context, it); onAlertsChanged(it)
                }
                SettingSwitchRow("Vibração", "Vibrar junto com o toque", vibration) {
                    vibration = it; AlertPreferences.setVibration(context, it)
                }
                SettingSwitchRow("Mensagens", "Avisos de conversa do cliente", msgAlerts) {
                    msgAlerts = it; AlertPreferences.setMessageAlerts(context, it)
                }
                SettingSwitchRow("Alterações de item", "Adicionar, excluir ou substituir", changeAlerts) {
                    changeAlerts = it; AlertPreferences.setChangeAlerts(context, it)
                }
                SettingSwitchRow("UP Entregas", "Aceite, recusa e andamento do entregador", driverAlerts) {
                    driverAlerts = it; AlertPreferences.setDriverAlerts(context, it)
                }
                SettingSwitchRow("Pagamento", "Pagamento aprovado, recusado ou pendente", paymentAlerts) {
                    paymentAlerts = it; AlertPreferences.setPaymentAlerts(context, it)
                }
                Spacer(Modifier.height(8.dp))
                Text("Intervalo do toque", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(listOf(10, 15, 20, 30)) { seconds ->
                        FilterChip(selected = repeat == seconds, onClick = { repeat = seconds; AlertPreferences.setRepeatSeconds(context, seconds) }, label = { Text("${seconds}s") })
                    }
                }
                Text("Tocar por no máximo", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(listOf(1, 3, 5, 10)) { minutes ->
                        FilterChip(selected = ringMinutes == minutes, onClick = { ringMinutes = minutes; AlertPreferences.setMaxRingMinutes(context, minutes) }, label = { Text("$minutes min") })
                    }
                }
                Text("Avisar se ninguém aceitar em", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(listOf(1, 2, 3, 5)) { minutes ->
                        FilterChip(selected = unanswered == minutes, onClick = { unanswered = minutes; AlertPreferences.setUnansweredMinutes(context, minutes) }, label = { Text("$minutes min") })
                    }
                }
                Spacer(Modifier.height(5.dp))
                OutlinedButton(
                    onClick = {
                        OrderRingService.start(context, "TESTE", "TESTE", "Teste de alerta")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ OrderRingService.stop(context) }, 3_500L)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Notifications, null)
                    Spacer(Modifier.width(7.dp))
                    Text("TESTAR SOM E VIBRAÇÃO")
                }
            }
        }
        item {
            DetailCard("Tempos e atrasos") {
                Text("Amarelo depois de", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(listOf(10, 15, 20, 25, 30)) { minutes ->
                        FilterChip(selected = yellow == minutes, onClick = { yellow = minutes; AlertPreferences.setLateYellowMinutes(context, minutes) }, label = { Text("$minutes min") })
                    }
                }
                Text("Vermelho depois de", fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                    items(listOf(20, 30, 35, 45, 60)) { minutes ->
                        FilterChip(selected = red == minutes, onClick = { red = minutes; AlertPreferences.setLateRedMinutes(context, minutes) }, label = { Text("$minutes min") })
                    }
                }
            }
        }
        item {
            DetailCard("Impressão de comanda") {
                SettingSwitchRow("Imprimir ao aceitar", "Abre a impressão automaticamente ao aceitar pedido", autoPrint) {
                    autoPrint = it; AlertPreferences.setAutoPrintOnAccept(context, it)
                }
                Text("Largura", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected = paper == 58, onClick = { paper = 58; AlertPreferences.setPaperWidth(context, 58) }, label = { Text("58 mm") })
                    FilterChip(selected = paper == 80, onClick = { paper = 80; AlertPreferences.setPaperWidth(context, 80) }, label = { Text("80 mm") })
                }
                Spacer(Modifier.height(6.dp))
                Text("Vias", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    (1..3).forEach { n ->
                        FilterChip(selected = copies == n, onClick = { copies = n; AlertPreferences.setPrintCopies(context, n) }, label = { Text("$n") })
                    }
                }
            }
        }
        item {
            DetailCard("Tela e aparência") {
                SettingSwitchRow("Cards compactos", "Mostra mais pedidos por tela sem cortar nomes", compactCards) {
                    AlertPreferences.setCompactCards(context, it); onCompactChanged(it)
                }
                Text("Tema escuro operacional • alto contraste para balcão e celular", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            DetailCard("Saúde do aparelho") {
                HealthRow("Internet", if (network) "Conectado" else "Sem conexão", network)
                HealthRow("Notificações", if (notifications) "Permitidas" else "Desativadas no Android", notifications)
                HealthRow("Bateria", if (battery >= 0) "$battery%" else "Não disponível", battery < 0 || battery >= 20)
                Text("O Gestor continua exibindo o estado da conexão e não perde o pedido que já está aberto.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text("Segurança operacional", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text("Cancelamento usa motivo + confirmação simples. Sem PIN e sem histórico de ações de funcionários.", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 19.sp, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "●", color = if (ok) Color(0xFF65D66E) else Color(0xFFFF6B65), fontSize = 16.sp)
        Spacer(Modifier.width(7.dp))
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderDetailScreen(
    order: Order,
    drivers: List<Driver>,
    repository: OrdersRepository,
    onBack: () -> Unit,
    onChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var showCancel by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showDrivers by remember { mutableStateOf(false) }
    var showAlteration by remember { mutableStateOf(false) }
    var showIssue by remember { mutableStateOf(false) }
    val issueActive = (order.raw["problemaOperacional"] as? Map<*, *>)?.get("ativo") == true

    fun update(status: String, success: String) {
        if (busy) return
        busy = true
        repository.updateStatus(
            order,
            status,
            onDone = {
                busy = false
                if (status == "CONFIRMADO") {
                    OrderRingService.stop(context)
                    if (AlertPreferences.autoPrintOnAccept(context)) {
                        OrderPrinter.print(
                            context,
                            order,
                            AlertPreferences.printCopies(context),
                            AlertPreferences.paperWidth(context)
                        )
                    }
                }
                onChanged(success)
            },
            onError = {
                busy = false
                onChanged(it.message ?: "Não foi possível atualizar o pedido")
            }
        )
    }

    if (showCancel) {
        CancelOrderScreen(
            order = order,
            busy = busy,
            onBack = { if (!busy) showCancel = false },
            onConfirm = { reason ->
                busy = true
                repository.cancelOrder(order, reason, {
                    busy = false
                    showCancel = false
                    OrderRingService.stop(context)
                    onChanged("Pedido cancelado")
                }, {
                    busy = false
                    onChanged(it.message ?: "Não foi possível cancelar")
                })
            }
        )
        return
    }

    if (showDrivers) {
        DriverSelectionScreen(
            order = order,
            drivers = drivers,
            repository = repository,
            onBack = { showDrivers = false },
            onMessage = onChanged,
            onSent = { showDrivers = false },
        )
        return
    }

    if (showChat) {
        CustomerChatScreen(
            order = order,
            repository = repository,
            onBack = { showChat = false },
            onMessage = onChanged,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pedido #${order.number}", fontWeight = FontWeight.Black)
                        Text(order.clientName, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } },
                actions = {
                    IconButton(
                        onClick = {
                            OrderPrinter.print(
                                context,
                                order,
                                AlertPreferences.printCopies(context),
                                AlertPreferences.paperWidth(context)
                            )
                        }
                    ) { Icon(Icons.Default.Print, "Imprimir comanda") }
                    StatusPill(order.status)
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrderProgress(order)

            DetailCard("Cliente e entrega") {
                LabelValue("Nome", order.clientName)
                if (order.phone.isNotBlank()) LabelValue("WhatsApp", order.phone)
                LabelValue("Tipo", if (order.pickup) "Retirada no balcão" else "Entrega")
                if (!order.pickup) LabelValue("Endereço", order.address)
                val age = if (order.createdMillis > 0) ((System.currentTimeMillis() - order.createdMillis) / 60_000L).coerceAtLeast(0) else 0
                LabelValue("Recebido", if (age == 0L) "Agora" else "Há $age min • ${timeText(order.createdMillis)}")
            }

            DetailCard("Pagamento") {
                LabelValue("Forma", order.payment.form)
                LabelValue("Status", order.payment.status.uppercase().ifBlank { "PENDENTE" })
                if (order.payment.needsMachine) LabelValue("Maquininha", "Levar na entrega")
                if (order.payment.needsChange) LabelValue("Troco para", money(order.payment.changeFor))
                Spacer(Modifier.height(7.dp))
                val paid = order.payment.status.uppercase(Locale.ROOT) in setOf("PAGO", "PAID", "APROVADO", "APPROVED")
                OutlinedButton(
                    onClick = {
                        repository.setPaymentPaid(order, !paid, { onChanged(if (paid) "Pagamento marcado como pendente" else "Pagamento confirmado") }, { onChanged(it.message ?: "Erro no pagamento") })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (paid) "MARCAR COMO PENDENTE" else "CONFIRMAR PAGAMENTO") }
            }

            val pickupCode = (order.raw["codigoRetirada"] ?: order.raw["codigoLiberacao"] ?: order.raw["codigoParaRetirada"])?.toString().orEmpty()
            val deliveryCode = (order.raw["codigoEntrega"] ?: order.raw["codigoCurto"])?.toString().orEmpty()
            val driverName = (order.raw["entregadorNome"] ?: order.raw["driverName"] ?: (order.raw["entrega"] as? Map<*, *>)?.get("entregadorNome"))?.toString().orEmpty()
            if (pickupCode.isNotBlank() || deliveryCode.isNotBlank() || driverName.isNotBlank()) {
                DetailCard("Entrega e códigos") {
                    if (driverName.isNotBlank()) LabelValue("Entregador", driverName)
                    if (pickupCode.isNotBlank()) LabelValue("Código de retirada", pickupCode)
                    if (deliveryCode.isNotBlank()) LabelValue("Código de entrega", deliveryCode)
                }
            }

            DetailCard("Itens do pedido") {
                order.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 9.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("${item.quantity}x", modifier = Modifier.width(34.dp), fontWeight = FontWeight.Black, fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        }
                        if (item.price > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(money(item.price), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                if (order.observation.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(color = Color(0xFFFFF7E8), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(11.dp)) {
                            Text("⚠ OBSERVAÇÃO", fontWeight = FontWeight.Black, color = Color(0xFFF28C18))
                            Text(order.observation, fontSize = 15.sp)
                        }
                    }
                }
            }

            DetailCard("Resumo") {
                if (order.subtotal > 0) LabelValue("Subtotal", money(order.subtotal))
                if (order.freight > 0) LabelValue("Entrega", money(order.freight))
                if (order.discount > 0) LabelValue("Desconto", "- ${money(order.discount)}")
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOTAL", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    Text(money(order.total), fontWeight = FontWeight.Black, fontSize = 25.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            ActionBlock(
                order = order,
                busy = busy,
                onAccept = { update("CONFIRMADO", "Pedido aceito") },
                onPrepare = { update("EM_PREPARO", "Preparo iniciado") },
                onReady = { update("PRONTO", "Pedido marcado como pronto") },
                onCallDriver = { showDrivers = true },
                onFinish = {
                    if (order.pickup) {
                        busy = true
                        repository.finishPickup(order, {
                            busy = false
                            onChanged("Retirada finalizada")
                        }, {
                            busy = false
                            onChanged(it.message ?: "Erro ao finalizar retirada")
                        })
                    } else update("ENTREGUE", "Pedido finalizado")
                }
            )

            DetailCard("Atendimento e ações") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showChat = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Chat, null)
                        Spacer(Modifier.width(5.dp))
                        Text("CHAT")
                    }
                    OutlinedButton(onClick = { showAlteration = true }, modifier = Modifier.weight(1f)) {
                        Text("ALTERAR ITEM")
                    }
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(
                    onClick = {
                        if (issueActive) {
                            repository.setOperationalIssue(order, "", "", false, { onChanged("Problema resolvido") }, { onChanged(it.message ?: "Erro ao resolver") })
                        } else showIssue = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(if (issueActive) Icons.Default.CheckCircle else Icons.Default.Warning, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (issueActive) "RESOLVER PROBLEMA" else "MARCAR PROBLEMA")
                }
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            OrderPrinter.print(
                                context,
                                order,
                                AlertPreferences.printCopies(context),
                                AlertPreferences.paperWidth(context)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Print, null)
                        Spacer(Modifier.width(5.dp))
                        Text("COMANDA")
                    }
                    if (order.phone.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                val digits = order.phone.filter(Char::isDigit)
                                val phone = if (digits.startsWith("55")) digits else "55$digits"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone")))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Phone, null)
                            Spacer(Modifier.width(5.dp))
                            Text("WHATSAPP")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showCancel = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = order.status !in StatusGroups.DONE && order.status !in StatusGroups.CANCELED
            ) {
                Icon(Icons.Default.Cancel, null)
                Spacer(Modifier.width(6.dp))
                Text("CANCELAR PEDIDO")
            }
        }
    }

    if (showCancel) {
        CancelDialog(
            onDismiss = { showCancel = false },
            onConfirm = { reason ->
                busy = true
                repository.cancelOrder(order, reason, {
                    busy = false
                    showCancel = false
                    OrderRingService.stop(context)
                    onChanged("Pedido cancelado")
                }, {
                    busy = false
                    onChanged(it.message ?: "Não foi possível cancelar")
                })
            }
        )
    }
    if (showDrivers) {
        DriverDialog(
            order = order,
            drivers = drivers,
            repository = repository,
            onDismiss = { showDrivers = false },
            onMessage = onChanged,
            onSent = { showDrivers = false }
        )
    }
    if (showChat) ChatDialog(order, repository, onDismiss = { showChat = false }, onMessage = onChanged)
    if (showAlteration) {
        AlterationProposalDialog(
            order = order,
            repository = repository,
            onDismiss = { showAlteration = false },
            onMessage = onChanged,
        )
    }
    if (showIssue) {
        IssueDialog(
            onDismiss = { showIssue = false },
            onConfirm = { type, note ->
                repository.setOperationalIssue(order, type, note, true, {
                    showIssue = false
                    onChanged("Problema destacado no pedido")
                }, { onChanged(it.message ?: "Erro ao marcar problema") })
            }
        )
    }
}

@Composable
private fun OrderProgress(order: Order) {
    val labels = if (order.pickup) {
        listOf("Novo", "Confirmado", "Preparo", "Pronto", "Retirado")
    } else {
        listOf("Novo", "Confirmado", "Preparo", "Pronto", "Entrega", "Entregue")
    }
    val current = when {
        order.status in StatusGroups.NEW -> 0
        order.status in StatusGroups.CONFIRMED -> 1
        order.status in StatusGroups.PREPARING -> 2
        order.status in StatusGroups.READY -> 3
        order.status in StatusGroups.DELIVERY -> if (order.pickup) 3 else 4
        order.status in StatusGroups.DONE -> labels.lastIndex
        else -> 0
    }
    val active = statusColor(order.status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = active.copy(alpha = .07f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, active.copy(alpha = .22f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(StatusGroups.label(order.status), fontWeight = FontWeight.Black, color = active, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { index, label ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            color = if (index <= current) active else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                if (index < current) "✓" else "${index + 1}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = if (index <= current) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(label, fontSize = 9.sp, fontWeight = if (index == current) FontWeight.Black else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label.uppercase(Locale.ROOT), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun ActionBlock(
    order: Order,
    busy: Boolean,
    onAccept: () -> Unit,
    onPrepare: () -> Unit,
    onReady: () -> Unit,
    onCallDriver: () -> Unit,
    onFinish: () -> Unit,
) {
    DetailCard("Próxima ação") {
        when {
            order.status in StatusGroups.NEW -> {
                Button(
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ACEITAR PEDIDO", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
            order.status in StatusGroups.CONFIRMED -> {
                Button(
                    onClick = onPrepare,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Restaurant, null)
                    Spacer(Modifier.width(8.dp))
                    Text("INICIAR PREPARO", fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(8.dp))
                HoldActionButton(
                    label = "SEGURE PARA MARCAR COMO PRONTO",
                    color = Color(0xFFF28C18),
                    enabled = !busy,
                    onConfirmed = onReady
                )
            }
            order.status in StatusGroups.PREPARING -> {
                HoldActionButton(
                    label = "SEGURE PARA MARCAR COMO PRONTO",
                    color = Color(0xFFF28C18),
                    enabled = !busy,
                    onConfirmed = onReady
                )
            }
            order.status in StatusGroups.READY -> {
                if (order.pickup) {
                    HoldActionButton(
                        label = "SEGURE PARA CONFIRMAR RETIRADA",
                        color = Color(0xFF249B3B),
                        enabled = !busy,
                        onConfirmed = onFinish
                    )
                } else {
                    Button(
                        onClick = onCallDriver,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF249B3B))
                    ) {
                        Icon(Icons.Default.DeliveryDining, null)
                        Spacer(Modifier.width(8.dp))
                        Text("CHAMAR ENTREGADOR", fontWeight = FontWeight.Black)
                    }
                }
            }
            order.status in StatusGroups.DELIVERY -> {
                HoldActionButton(
                    label = "SEGURE PARA FINALIZAR ENTREGA",
                    color = Color(0xFF2878D0),
                    enabled = !busy,
                    onConfirmed = onFinish
                )
            }
            else -> Text("Pedido encerrado.", fontWeight = FontWeight.Bold)
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Atualizando…")
            }
        }
    }
}

@Composable
private fun HoldActionButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 1000),
        label = "holdProgress"
    )

    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled) {
            delay(1050)
            if (pressed) {
                pressed = false
                onConfirmed()
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        }
                    )
                }
            },
        color = if (enabled) color else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(Color.Black.copy(alpha = .12f))
                    .align(Alignment.CenterStart)
            )
            Text(
                if (pressed) "CONTINUE SEGURANDO…" else label,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CancelOrderScreen(
    order: Order,
    busy: Boolean,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var customReason by remember { mutableStateOf("") }
    val reasons = listOf(
        "Cliente desistiu",
        "Pagamento recusado",
        "Endereço inválido",
        "Item fora de estoque",
        "Outro motivo",
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cancelar pedido #${order.number}", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.Default.ArrowBack, "Voltar") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Motivo do cancelamento", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text("Escolha um motivo. Ele ficará registrado no pedido.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            reasons.forEach { option ->
                Card(
                    onClick = { if (!busy) reason = option },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (reason == option) Color(0xFF2B1717) else MaterialTheme.colorScheme.surface)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (reason == option) "●" else "○", color = if (reason == option) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(option, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (reason == "Outro motivo") {
                OutlinedTextField(
                    value = customReason,
                    onValueChange = { customReason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Descreva o motivo") },
                    minLines = 2,
                )
            }
            Spacer(Modifier.weight(1f))
            val finalReason = if (reason == "Outro motivo") customReason.trim() else reason
            Button(
                onClick = { onConfirm(finalReason) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = finalReason.isNotBlank() && !busy,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "CANCELANDO…" else "CANCELAR PEDIDO", fontWeight = FontWeight.Black)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriverSelectionScreen(
    order: Order,
    drivers: List<Driver>,
    repository: OrdersRepository,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onSent: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var repasseText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val available = drivers.filter { it.available }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chamar entregador", fontWeight = FontWeight.Black)
                        Text("Pedido #${order.number}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack, enabled = !sending) { Icon(Icons.Default.ArrowBack, "Voltar") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp).navigationBarsPadding()) {
            OutlinedTextField(
                value = repasseText,
                onValueChange = { repasseText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } },
                label = { Text("Repasse ao entregador (R$)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Text("Entregadores disponíveis", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            if (available.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2B1717))) {
                    Text("Nenhum entregador online e livre agora.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 10.dp)
                ) {
                    items(available, key = { it.id }) { driver ->
                        Card(
                            onClick = { if (!sending) selectedId = driver.id },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = if (selectedId == driver.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = Color(0xFF182814), shape = RoundedCornerShape(50)) {
                                    Icon(Icons.Default.DeliveryDining, null, modifier = Modifier.padding(10.dp), tint = Color(0xFF65D66E))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(driver.name, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                    Text("Disponível • ${driver.status}", fontSize = 12.sp, color = Color(0xFF65D66E))
                                }
                                Text(if (selectedId == driver.id) "●" else "○", fontSize = 22.sp, color = if (selectedId == driver.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val driver = available.firstOrNull { it.id == selectedId }
                    val repasse = repasseText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    if (driver == null) {
                        onMessage("Selecione um entregador")
                        return@Button
                    }
                    sending = true
                    repository.dispatchToDriver(order, driver, repasse, onDone = {
                        sending = false
                        onMessage("Oferta enviada para ${driver.name}")
                        onSent()
                    }, onError = {
                        sending = false
                        onMessage(it.message ?: "Erro ao chamar entregador")
                    })
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = !sending && selectedId != null && repasseText.isNotBlank(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.DeliveryDining, null)
                Spacer(Modifier.width(7.dp))
                Text(if (sending) "ENVIANDO…" else "CONFIRMAR ENTREGADOR", fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerChatScreen(
    order: Order,
    repository: OrdersRepository,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var chatId by remember { mutableStateOf<String?>(null) }
    var chat by remember { mutableStateOf<OrderChat?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var registration by remember { mutableStateOf<ListenerRegistration?>(null) }

    DisposableEffect(order.id) {
        repository.ensureChat(order, onReady = { chatId = it }, onError = { onMessage(it.message ?: "Erro ao abrir chat") })
        onDispose { registration?.remove() }
    }
    DisposableEffect(chatId) {
        registration?.remove()
        registration = chatId?.let { id ->
            repository.listenChat(id, onData = { chat = it }, onError = { onMessage(it.message ?: "Erro no chat") })
        }
        onDispose {
            registration?.remove()
            registration = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat com cliente", fontWeight = FontWeight.Black)
                        Text("Pedido #${order.number} • ${order.clientName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).imePadding().navigationBarsPadding()
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val messages = chat?.messages.orEmpty()
                if (chatId == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (messages.isEmpty()) {
                    Text("Nenhuma mensagem ainda.", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        items(messages) { message -> ChatBubble(message) }
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                items(listOf(
                    "Seu pedido já está em preparo.",
                    "Seu pedido está pronto.",
                    "Item indisponível. Podemos substituir?",
                    "O entregador já está a caminho."
                )) { quick ->
                    AssistChip(onClick = { text = quick }, label = { Text(quick, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Digite uma mensagem…") },
                    minLines = 1,
                    maxLines = 4,
                )
                Button(
                    onClick = {
                        val id = chatId ?: return@Button
                        sending = true
                        repository.sendChatMessage(id, order, text, onDone = {
                            text = ""
                            sending = false
                        }, onError = {
                            sending = false
                            onMessage(it.message ?: "Erro ao enviar mensagem")
                        })
                    },
                    enabled = chatId != null && text.isNotBlank() && !sending,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (sending) "…" else "ENVIAR", fontWeight = FontWeight.Black) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CancelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cancelar pedido") },
        text = {
            Column {
                Text("Informe o motivo. O motivo fica registrado no pedido.")
                Spacer(Modifier.height(10.dp))
                listOf("Item indisponível", "Cliente solicitou", "Pagamento não confirmado", "Endereço fora da área").forEach { item ->
                    AssistChip(onClick = { reason = item }, label = { Text(item) })
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("Motivo") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) { Text("CANCELAR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("VOLTAR") } }
    )
}



@Composable
private fun IssueDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var type by remember { mutableStateOf("Item em falta") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Marcar problema no pedido") },
        text = {
            Column {
                Text("O pedido fica destacado até a equipe resolver.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                listOf("Cliente não responde", "Item em falta", "Endereço incorreto", "Pagamento", "Motoboy", "Outro").forEach { option ->
                    FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) })
                    Spacer(Modifier.height(3.dp))
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Observação opcional") })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(type, note) }) { Text("MARCAR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("VOLTAR") } }
    )
}

@Composable
private fun AlterationProposalDialog(
    order: Order,
    repository: OrdersRepository,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var type by remember { mutableStateOf("SUBSTITUIR") }
    var currentItem by remember { mutableStateOf(order.items.firstOrNull()?.name.orEmpty()) }
    var newItem by remember { mutableStateOf("") }
    var observation by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Propor alteração ao cliente") },
        text = {
            Column(Modifier.fillMaxHeight(.72f).verticalScroll(rememberScrollState())) {
                Text("Nada é alterado automaticamente. O cliente recebe a proposta e pode aprovar ou recusar.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(9.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("SUBSTITUIR", "EXCLUIR", "ADICIONAR")) { option ->
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(option) })
                    }
                }
                if (type != "ADICIONAR") {
                    Spacer(Modifier.height(8.dp))
                    Text("Item atual", fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                        items(order.items, key = { it.name }) { item ->
                            FilterChip(selected = currentItem == item.name, onClick = { currentItem = item.name }, label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
                if (type != "EXCLUIR") {
                    OutlinedTextField(
                        value = newItem,
                        onValueChange = { newItem = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (type == "SUBSTITUIR") "Novo item / substituição" else "Item para adicionar") },
                        placeholder = { Text(if (type == "SUBSTITUIR") "Ex.: Paçoca no lugar de Granola" else "Ex.: 1 Coca-Cola 350ml") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = observation,
                    onValueChange = { observation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mensagem para o cliente") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (type != "EXCLUIR" && newItem.isBlank()) {
                        onMessage("Informe o item desejado")
                        return@Button
                    }
                    sending = true
                    repository.proposeAlteration(
                        order = order,
                        type = type,
                        currentItem = if (type == "ADICIONAR") "" else currentItem,
                        newItem = if (type == "EXCLUIR") "" else newItem,
                        description = observation,
                        onDone = {
                            sending = false
                            onMessage("Proposta enviada ao cliente")
                            onDismiss()
                        },
                        onError = {
                            sending = false
                            onMessage(it.message ?: "Erro ao enviar proposta")
                        }
                    )
                },
                enabled = !sending
            ) { Text(if (sending) "ENVIANDO…" else "ENVIAR PROPOSTA") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("VOLTAR") } }
    )
}

@Composable
private fun DriverDialog(
    order: Order,
    drivers: List<Driver>,
    repository: OrdersRepository,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onSent: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var repasseText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val available = drivers.filter { it.available }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("Chamar entregador UP") },
        text = {
            Column(Modifier.fillMaxHeight(.7f)) {
                Text("Pedido #${order.number} • escolha manualmente quem receberá a oferta.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = repasseText,
                    onValueChange = { repasseText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' } },
                    label = { Text("Repasse ao entregador (R$)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                if (available.isEmpty()) {
                    Text("Nenhum entregador online e livre agora.", color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(available, key = { it.id }) { driver ->
                            FilterChip(
                                selected = selectedId == driver.id,
                                onClick = { selectedId = driver.id },
                                label = {
                                    Column {
                                        Text(driver.name, fontWeight = FontWeight.Bold)
                                        Text(driver.status, fontSize = 12.sp)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.DeliveryDining, null) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val driver = available.firstOrNull { it.id == selectedId }
                    val repasse = repasseText.replace(',', '.').toDoubleOrNull() ?: 0.0
                    if (driver == null) {
                        onMessage("Selecione um entregador")
                        return@Button
                    }
                    sending = true
                    repository.dispatchToDriver(order, driver, repasse, onDone = {
                        sending = false
                        onMessage("Oferta enviada para ${driver.name}")
                        onSent()
                    }, onError = {
                        sending = false
                        onMessage(it.message ?: "Erro ao chamar entregador")
                    })
                },
                enabled = !sending && selectedId != null && repasseText.isNotBlank()
            ) { Text(if (sending) "ENVIANDO…" else "ENVIAR OFERTA") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("VOLTAR") } }
    )
}

@Composable
private fun ChatDialog(
    order: Order,
    repository: OrdersRepository,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var chatId by remember { mutableStateOf<String?>(null) }
    var chat by remember { mutableStateOf<OrderChat?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var registration by remember { mutableStateOf<ListenerRegistration?>(null) }

    DisposableEffect(order.id) {
        repository.ensureChat(order, onReady = { chatId = it }, onError = { onMessage(it.message ?: "Erro ao abrir chat") })
        onDispose { registration?.remove() }
    }

    DisposableEffect(chatId) {
        registration?.remove()
        registration = chatId?.let { id ->
            repository.listenChat(id, onData = { chat = it }, onError = { onMessage(it.message ?: "Erro no chat") })
        }
        onDispose {
            registration?.remove()
            registration = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conversa • Pedido #${order.number}") },
        text = {
            Column(Modifier.fillMaxHeight(.68f).imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val messages = chat?.messages.orEmpty()
                    if (chatId == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else if (messages.isEmpty()) {
                        Text("Nenhuma mensagem ainda.", modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            items(messages) { message -> ChatBubble(message) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
                    items(listOf(
                        "Seu pedido já está em preparo.",
                        "Seu pedido está pronto.",
                        "Item indisponível. Podemos substituir?",
                        "O entregador já está a caminho."
                    )) { quick ->
                        AssistChip(onClick = { text = quick }, label = { Text(quick, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Mensagem para o cliente") },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val id = chatId ?: return@Button
                    sending = true
                    repository.sendChatMessage(id, order, text, onDone = {
                        text = ""
                        sending = false
                    }, onError = {
                        sending = false
                        onMessage(it.message ?: "Erro ao enviar mensagem")
                    })
                },
                enabled = chatId != null && text.isNotBlank() && !sending
            ) { Text(if (sending) "ENVIANDO…" else "ENVIAR") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("FECHAR") } }
    )
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val mine = message.sender.uppercase(Locale.ROOT) in setOf("GESTOR", "LOJA")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(.82f),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(if (mine) "LOJA" else "CLIENTE", fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text(message.text, fontSize = 16.sp)
                if (message.time.isNotBlank()) Text(message.time, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
