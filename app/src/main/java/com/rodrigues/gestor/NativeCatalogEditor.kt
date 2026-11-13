package com.rodrigues.gestor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rodrigues.gestor.data.CATALOG_GROUPS
import com.rodrigues.gestor.data.CatalogDraft
import com.rodrigues.gestor.data.NativeMediaApi
import com.rodrigues.gestor.data.NativeOwnerExtrasApi

/** Editor verdadeiro: criação, foto, descrição, posição, disponibilidade e preços por base. */
@Composable
internal fun NativeCatalogEditor(
    initial: CatalogDraft,
    busy: Boolean,
    error: String,
    onSave: (CatalogDraft, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var draft by remember(initial.id, initial.collection) { mutableStateOf(initial) }
    var uploading by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }
    var chooseGroup by remember { mutableStateOf(false) }
    var chooseCategory by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<Pair<String,String>>>(emptyList()) }
    var categoriesError by remember { mutableStateOf("") }
    LaunchedEffect(initial.id, initial.collection) {
        NativeOwnerExtrasApi.catalogStructure(onSuccess = { response ->
            val rows = response.optJSONArray("categories")
            val all = mutableListOf<Pair<String,String>>()
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optBoolean("active",true))
                    all.add(row.optString("id") to row.optString("name"))
            }
            categories = all
            if (initial.collection == "products" && initial.id.isNotBlank()) {
                val products = response.optJSONArray("products")
                if (products != null) for (i in 0 until products.length()) {
                    val row = products.optJSONObject(i) ?: continue
                    if (row.optString("id") == initial.id) {
                        draft = draft.copy(categoryId = row.optString("category_id"))
                        break
                    }
                }
            }
        }, onError = { categoriesError = "Não foi possível carregar categorias: " +
                (it.message ?: "sem conexão") })
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true
            imageError = ""
            NativeMediaApi.upload(context, uri, onSuccess = { url ->
                draft = draft.copy(imageUrl = url)
                uploading = false
            }, onError = {
                uploading = false
                imageError = "Foto não enviada: " + (it.message ?: "tente outra imagem")
            })
        }
    }

    Dialog(onDismissRequest = { if (!busy && !uploading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.95f),
            color = Color(0xFFF8F6FA), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 15.dp)) {
                    Text(if (initial.id.isBlank()) "Adicionar item" else "Editar item",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall)
                    Text("Alterações salvas diretamente no cardápio da loja.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Categoria", fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(onClick = { chooseGroup = true },
                            enabled = initial.id.isBlank() && !busy) {
                            Text(CATALOG_GROUPS.firstOrNull { it.first == draft.collection }?.second
                                ?: draft.collection)
                        }
                        DropdownMenu(expanded = chooseGroup, onDismissRequest = { chooseGroup = false }) {
                            CATALOG_GROUPS.forEach { (key,title) ->
                                DropdownMenuItem(text = { Text(title) }, onClick = {
                                    draft = draft.copy(collection = key)
                                    chooseGroup = false
                                })
                            }
                        }
                    }
                    if (draft.collection == "products") {
                        Text("Categoria do site *", fontWeight = FontWeight.SemiBold)
                        Box {
                            OutlinedButton(onClick = { chooseCategory = true },
                                enabled = categories.isNotEmpty() && !busy) {
                                Text(categories.firstOrNull { it.first == draft.categoryId }?.second
                                    ?: "Selecionar categoria")
                            }
                            DropdownMenu(expanded = chooseCategory,
                                onDismissRequest = { chooseCategory = false }) {
                                categories.forEach { (id,name) ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = {
                                        draft = draft.copy(categoryId = id)
                                        chooseCategory = false
                                    })
                                }
                            }
                        }
                        if (categoriesError.isNotBlank())
                            Text(categoriesError,color=MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(160)) },
                        label = { Text("Nome do item *") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.description,
                        { draft = draft.copy(description = it.take(3000)) },
                        label = { Text("Descrição para o cliente") },
                        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    if (draft.imageUrl.isNotBlank()) {
                        AsyncImage(model = draft.imageUrl, contentDescription = "Foto atual do item",
                            modifier = Modifier.fillMaxWidth().height(165.dp),
                            contentScale = ContentScale.Fit)
                    } else {
                        Surface(color = Color(0xFFEDE5F5), shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().height(90.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center) {
                                Icon(Icons.Default.Image, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Text("Sem foto")
                            }
                        }
                    }
                    OutlinedButton(onClick = { picker.launch("image/*") },
                        enabled = !busy && !uploading, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (uploading) "Enviando imagem…" else "Selecionar foto da galeria")
                    }
                    if (uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (imageError.isNotBlank()) Text(imageError, color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(draft.imageUrl, { draft = draft.copy(imageUrl = it.trim().take(2048)) },
                        label = { Text("Ou cole a URL da imagem (HTTPS)") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(draft.price, { draft = draft.copy(price = it.take(10)) },
                            label = { Text("Preço R$") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(draft.sortOrder,
                            { draft = draft.copy(sortOrder = it.filter(Char::isDigit).take(6)) },
                            label = { Text("Ordem") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                    }
                    if (draft.collection == "products") {
                        OutlinedTextField(draft.compareAtPrice,
                            { draft = draft.copy(compareAtPrice = it.take(10)) },
                            label = { Text("Preço anterior opcional (R$)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (draft.collection == "cardapio_acai") {
                        Text("Preços por base", fontWeight = FontWeight.Bold)
                        Text("Defina Misto, Origem, Cupuaçu e Polpanorte separadamente.",
                            color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        listOf("misto" to "Misto", "origem" to "Origem",
                            "cupuacu" to "Cupuaçu", "polpanorte" to "Polpanorte").forEach { (key,title) ->
                            OutlinedTextField(
                                draft.variantPrices[key].orEmpty(),
                                { value -> draft = draft.copy(
                                    variantPrices = draft.variantPrices + (key to value.take(10))) },
                                label = { Text("R$ " + title) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ativo", fontWeight = FontWeight.Medium)
                            Text("Aparece no cardápio do cliente", color = Color.Gray,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(draft.active, { draft = draft.copy(active = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Disponível para compra", fontWeight = FontWeight.Medium)
                            Text("Desative para pausar sem excluir o produto",
                                color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(draft.available, { draft = draft.copy(available = it) })
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !busy && !uploading,
                        modifier = Modifier.weight(1f)) { Text("Voltar") }
                    Button(onClick = {
                        validation = draft.validate().orEmpty()
                        if (validation.isEmpty()) onSave(draft, onDismiss)
                    }, enabled = !busy && !uploading, modifier = Modifier.weight(1.4f)) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) "Salvando…" else "Salvar alterações")
                    }
                }
            }
        }
    }
}
