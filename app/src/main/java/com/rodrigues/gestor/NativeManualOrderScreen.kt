package com.rodrigues.gestor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rodrigues.gestor.data.CatalogItem
import com.rodrigues.gestor.data.ManualOrderDraft
import com.rodrigues.gestor.data.ManualOrderItem
import com.rodrigues.gestor.data.money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

private val DeepPurple = Color(0xFF532084)
private val SoftPurple = Color(0xFFF2E8FA)
private val DeepGreen = Color(0xFF17734E)
private val Danger = Color(0xFFBE4055)

@Composable
internal fun NativeManualOrderScreen(
    draft: ManualOrderDraft,
    catalog: List<CatalogItem>,
    catalogLoading: Boolean,
    sending: Boolean,
    error: String,
    onChange: (ManualOrderDraft) -> Unit,
    onSubmit: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onDiscard: () -> Unit
) {
    var menuSearch by remember { mutableStateOf("") }
    var showProducts by remember { mutableStateOf(false) }
    var extrasFor by remember { mutableStateOf<String?>(null) }
    var extrasSearch by remember { mutableStateOf("") }
    var review by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }

    fun updateItem(id: String, fn: (ManualOrderItem) -> ManualOrderItem) {
        onChange(draft.copy(items = draft.items.map { if (it.id == id) fn(it) else it }))
    }

    val availableProducts = catalog.filter {
        it.forSale && it.name.isNotBlank() &&
            it.collection in setOf("products", "cardapio_acai") &&
            it.price.isFinite() && it.price in 0.0..10000.0 &&
            (menuSearch.isBlank() || it.name.contains(menuSearch.trim(), true))
    }.sortedBy { it.sortOrder }.take(12)

    val extras = catalog.filter {
        it.forSale && it.name.isNotBlank() &&
            it.collection in setOf("adicionais", "coberturas", "acompanhamentos_gratis", "utensilios") &&
            it.price.isFinite() && it.price in 0.0..10000.0 &&
            (extrasSearch.isBlank() || it.name.contains(extrasSearch.trim(), true))
    }.sortedWith(compareBy({ it.collection }, { it.sortOrder })).take(12)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = DeepPurple, shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("PEDIDO PELO GESTOR", color = Color(0xFFEBD8FA),
                    style = MaterialTheme.typography.labelMedium)
                Text("Novo pedido", color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium)
                Text("Monte, confira e registre na central de pedidos.",
                    color = Color(0xFFEADFF4), style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(color = SoftPurple, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Save, null, tint = DeepPurple)
                Spacer(Modifier.width(8.dp))
                Text("Rascunho protegido no aparelho. Só é enviado após confirmação online.",
                    color = DeepPurple, style = MaterialTheme.typography.bodySmall)
            }
        }

        ManualSection("1. Cliente e pagamento") {
            OutlinedTextField(value = draft.customerName, onValueChange = {
                onChange(draft.copy(customerName = it.take(140)))
            }, label = { Text("Nome do cliente *") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true)
            OutlinedTextField(value = draft.phone, onValueChange = {
                onChange(draft.copy(phone = it.filter(Char::isDigit).take(11)))
            }, label = { Text("Telefone com DDD *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Forma de recebimento", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("RETIRADA" to "Retirada", "ENTREGA" to "Entrega").forEach { (v, label) ->
                    FilterChip(selected = draft.fulfillment == v,
                        onClick = { onChange(draft.copy(fulfillment = v)) },
                        label = { Text(label) })
                }
            }
            Text("Forma de pagamento", fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("PIX", "DINHEIRO", "CREDITO", "DEBITO")) { method ->
                    FilterChip(selected = draft.paymentMethod == method,
                        onClick = { onChange(draft.copy(paymentMethod = method)) },
                        label = { Text(when (method) {
                            "CREDITO" -> "Crédito"
                            "DEBITO" -> "Débito"
                            "DINHEIRO" -> "Dinheiro"
                            else -> "Pix"
                        }) })
                }
            }
            if (draft.isDelivery) {
                HorizontalDivider()
                OutlinedTextField(value = draft.street, onValueChange = {
                    onChange(draft.copy(street = it.take(160)))
                }, label = { Text("Rua *") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = draft.number, onValueChange = {
                        onChange(draft.copy(number = it.take(18)))
                    }, label = { Text("Número *") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = draft.neighborhood, onValueChange = {
                        onChange(draft.copy(neighborhood = it.take(80)))
                    }, label = { Text("Bairro *") }, modifier = Modifier.weight(2f))
                }
                OutlinedTextField(value = draft.complement, onValueChange = {
                    onChange(draft.copy(complement = it.take(160)))
                }, label = { Text("Complemento") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = draft.reference, onValueChange = {
                    onChange(draft.copy(reference = it.take(160)))
                }, label = { Text("Referência") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = draft.deliveryFee, onValueChange = {
                    onChange(draft.copy(deliveryFee = it.take(12)))
                }, label = { Text("Taxa de entrega (R$)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        }

        ManualSection("2. Produtos e complementos") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Itens: " + draft.items.size + " / 20", modifier = Modifier.weight(1f),
                    color = Color.Gray)
                TextButton(onClick = onRefreshCatalog, enabled = !catalogLoading) {
                    Icon(Icons.Default.Refresh, null)
                    Text("Atualizar")
                }
            }
            OutlinedButton(onClick = { showProducts = !showProducts },
                enabled = !sending && draft.items.size < 20,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.RestaurantMenu, null)
                Spacer(Modifier.width(8.dp))
                Text("Selecionar produto do cardápio")
            }
            if (showProducts) {
                OutlinedTextField(value = menuSearch, onValueChange = { menuSearch = it },
                    label = { Text("Buscar produto") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth())
                if (catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (availableProducts.isEmpty()) Text("Sem produtos disponíveis nesta pesquisa.",
                    color = Color.Gray)
                availableProducts.forEach { product ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (draft.items.size < 20) {
                            val price = String.format(Locale("pt", "BR"), "%.2f", product.price)
                            onChange(draft.copy(items = draft.items + ManualOrderItem(
                                name = product.name, unitPrice = price)))
                            showProducts = false
                            menuSearch = ""
                        }
                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AddCircleOutline, null, tint = DeepPurple)
                        Spacer(Modifier.width(7.dp))
                        Text(product.name, modifier = Modifier.weight(1f))
                        Text(money(product.price), color = DeepPurple)
                    }
                    HorizontalDivider(color = SoftPurple)
                }
            }
            OutlinedButton(onClick = {
                onChange(draft.copy(items = draft.items + ManualOrderItem()))
            }, enabled = !sending && draft.items.size < 20,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Adicionar produto manualmente")
            }
            draft.items.forEachIndexed { i, item ->
                Surface(color = Color(0xFFF8F5FA), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PRODUTO " + (i + 1), color = DeepPurple,
                                modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = {
                                onChange(draft.copy(items = draft.items.filterNot { it.id == item.id }))
                                if (extrasFor == item.id) extrasFor = null
                            }, enabled = !sending) {
                                Icon(Icons.Default.DeleteOutline, "Remover", tint = Danger)
                            }
                        }
                        OutlinedTextField(value = item.name, onValueChange = { value ->
                            updateItem(item.id) { it.copy(name = value.take(240)) }
                        }, label = { Text("Produto, tamanho e base *") },
                            modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = item.quantity, onValueChange = { value ->
                                updateItem(item.id) { it.copy(quantity = value.filter(Char::isDigit).take(2)) }
                            }, label = { Text("Qtd.") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f))
                            OutlinedTextField(value = item.unitPrice, onValueChange = { value ->
                                updateItem(item.id) { it.copy(unitPrice = value.take(12)) }
                            }, label = { Text("Unitário R$") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(2f))
                        }
                        OutlinedTextField(value = item.details, onValueChange = { value ->
                            updateItem(item.id) { it.copy(details = value.take(2000)) }
                        }, label = { Text("Complementos, um por linha") },
                            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            extrasFor = if (extrasFor == item.id) null else item.id
                            extrasSearch = ""
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Add, null)
                            Text("Escolher complementos")
                        }
                        if (extrasFor == item.id) {
                            OutlinedTextField(value = extrasSearch, onValueChange = { extrasSearch = it },
                                label = { Text("Buscar complemento") },
                                modifier = Modifier.fillMaxWidth())
                            if (extras.isEmpty()) Text("Nenhum complemento disponível.", color = Color.Gray)
                            extras.forEach { extra ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    val old = ManualOrderDraft.price(item.unitPrice)
                                    if (old == null) localError = "Corrija o preço antes de incluir adicionais."
                                    else {
                                        val newPrice = (old + BigDecimal.valueOf(extra.price))
                                            .setScale(2, RoundingMode.HALF_UP)
                                        updateItem(item.id) {
                                            it.copy(
                                                details = (it.details.trimEnd() + "\n" + extra.name).trim(),
                                                unitPrice = newPrice.toPlainString().replace('.', ',')
                                            )
                                        }
                                        localError = ""
                                    }
                                }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AddCircleOutline, null, tint = DeepPurple)
                                    Spacer(Modifier.width(7.dp))
                                    Text(extra.name, modifier = Modifier.weight(1f))
                                    Text(if (extra.price > 0) "+" + money(extra.price) else "Grátis",
                                        color = DeepGreen)
                                }
                            }
                            Text("O preço de adicionais pagos entra no valor unitário.",
                                style = MaterialTheme.typography.labelSmall, color = DeepPurple)
                        }
                        OutlinedTextField(value = item.notes, onValueChange = { value ->
                            updateItem(item.id) { it.copy(notes = value.take(1000)) }
                        }, label = { Text("Observação do item") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        ManualSection("3. Conferência do pedido") {
            OutlinedTextField(value = draft.customerNote, onValueChange = {
                onChange(draft.copy(customerNote = it.take(2000)))
            }, label = { Text("Observação geral") }, modifier = Modifier.fillMaxWidth(),
                minLines = 2)
            Row {
                Text("Produtos", modifier = Modifier.weight(1f))
                Text(money(draft.subtotal.toDouble()))
            }
            if (draft.isDelivery) Row {
                Text("Entrega", modifier = Modifier.weight(1f))
                Text(money((ManualOrderDraft.price(draft.deliveryFee) ?: BigDecimal.ZERO).toDouble()))
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TOTAL", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(money(draft.total.toDouble()), color = DeepPurple,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
            }
        }

        if (localError.isNotBlank()) Text(localError, color = Danger)
        if (error.isNotBlank()) Surface(color = Color(0xFFFFE8EF),
            shape = RoundedCornerShape(12.dp)) {
            Text(error, Modifier.padding(12.dp), color = Danger,
                style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            localError = draft.validate().orEmpty()
            if (localError.isEmpty()) review = true
        }, enabled = !sending, modifier = Modifier.fillMaxWidth().height(55.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DeepPurple)) {
            Icon(Icons.Default.CheckCircle, null)
            Spacer(Modifier.width(8.dp))
            Text(if (sending) "Registrando…" else "Revisar e registrar")
        }
        TextButton(onClick = { discard = true }, enabled = !sending,
            modifier = Modifier.fillMaxWidth()) {
            Text("Descartar rascunho", color = Danger)
        }
        Text("Sem internet, continue preenchendo. O envio exige confirmação online. Uma tentativa com falha conserva a mesma identificação.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(30.dp))
    }

    if (review) AlertDialog(
        onDismissRequest = { if (!sending) review = false },
        title = { Text("Confirmar pedido manual") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cliente: " + draft.customerName)
                Text(if (draft.isDelivery) "Entrega: " + draft.street + ", " + draft.number
                    else "Retirada na loja")
                Text("Pagamento: " + draft.paymentMethod)
                Text("Produtos: " + draft.items.size)
                HorizontalDivider()
                Text("TOTAL: " + money(draft.total.toDouble()), color = DeepPurple,
                    fontWeight = FontWeight.Bold)
                Text("Ao confirmar, o pedido será registrado no sistema real.",
                    style = MaterialTheme.typography.bodySmall, color = Danger)
            }
        },
        confirmButton = { TextButton(onClick = { review = false; onSubmit() },
            enabled = !sending) { Text("Registrar agora") } },
        dismissButton = { TextButton(onClick = { review = false },
            enabled = !sending) { Text("Voltar") } }
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("Descartar rascunho?") },
        text = { Text("As informações preenchidas serão apagadas deste aparelho.") },
        confirmButton = { TextButton(onClick = {
            discard = false
            extrasFor = null
            showProducts = false
            onDiscard()
        }) { Text("Descartar", color = Danger) } },
        dismissButton = { TextButton(onClick = { discard = false }) {
            Text("Continuar preenchendo")
        } }
    )
}

@Composable
private fun ManualSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = DeepPurple,
                style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = SoftPurple)
            content()
        }
    }
}
