package com.rodrigues.gestor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rodrigues.gestor.data.NativeAdminApi
import com.rodrigues.gestor.data.NativeOwnerExtrasApi
import org.json.JSONArray
import com.rodrigues.gestor.data.NativeMediaApi
import org.json.JSONObject

private val AdminPurple = Color(0xFF57248A)
private val AdminLight = Color(0xFFF2EAF9)

private fun JSONObject.txt(key: String) = optString(key).takeUnless { it == "null" }.orEmpty()

@Composable
internal fun NativeAdministrationScreen(owner: Boolean, onOpenCatalog: () -> Unit) {
    val modes = listOf("coupons" to "Cupons", "banners" to "Banners",
        "categories" to "Categorias", "notices" to "Avisos",
        "customers" to "Clientes", "staff" to "Equipe")
    var mode by remember { mutableStateOf("coupons") }
    val list = remember { mutableStateListOf<JSONObject>() }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<JSONObject?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(mode, version, owner) {
        if (!owner) return@LaunchedEffect
        loading = true
        error = ""
        val received: (JSONArray) -> Unit = { rows ->
            list.clear()
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { list.add(it) }
            loading = false
        }
        val failed: (Throwable) -> Unit = {
            error = "Não foi possível carregar " + (modes.firstOrNull { it.first == mode }?.second
                ?: "o módulo") + ": " + (it.message ?: "sem conexão")
            loading = false
        }
        if (mode in setOf("categories", "notices", "customers"))
            NativeOwnerExtrasApi.list(mode,onSuccess=received,onError=failed)
        else NativeAdminApi.list(mode,onSuccess=received,onError=failed)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Administração", fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall)
        Text("Controle da loja no mesmo sistema do site do cliente.",
            color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Surface(color = AdminLight, shape = RoundedCornerShape(15.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenCatalog).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RestaurantMenu, null, tint = AdminPurple)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Cardápio completo", fontWeight = FontWeight.Bold, color = AdminPurple)
                    Text("Produtos, imagens, categorias, preços e ingredientes",
                        style = MaterialTheme.typography.labelSmall, color = AdminPurple)
                }
                Icon(Icons.Default.ChevronRight, null, tint = AdminPurple)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(modes) { (key,label) ->
                FilterChip(selected = mode == key, onClick = { mode = key },
                    label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(modes.first { it.first == mode }.second,
                modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            IconButton(onClick = { version++ }, enabled = !saving) {
                Icon(Icons.Default.Refresh, "Atualizar")
            }
            Button(onClick = { editor = JSONObject() },
                enabled = owner && !saving && mode != "customers") {
                Icon(Icons.Default.Add, null)
                Text("Novo")
            }
        }
        if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!owner) Text("Somente o dono pode administrar cupons, banners e equipe.",
            color = MaterialTheme.colorScheme.error)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (list.isEmpty()) item {
                Text(if (loading) "Consultando…" else "Nenhum registro nesta área.",
                    color = Color.Gray)
            }
            items(list, key = { it.txt("id").ifBlank { it.txt("code") } }) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = owner && !saving) {
                        editor = JSONObject(row.toString())
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (mode == "banners" && row.txt("image_url").isNotBlank()) {
                            AsyncImage(model = row.txt("image_url"),
                                contentDescription = "Imagem do banner",
                                modifier = Modifier.size(64.dp),
                                contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(when (mode) {
                                "coupons" -> row.txt("code")
                                "banners", "notices" -> row.txt("title")
                                else -> row.txt("name")
                            }, fontWeight = FontWeight.Bold)
                            Text(when (mode) {
                                "coupons" -> when (row.txt("discount_type")) {
                                    "percent" -> row.optDouble("discount_value",0.0).toString()+"% de desconto"
                                    "fixed" -> "R$ " + row.optDouble("discount_value",0.0)
                                    else -> "Entrega grátis"
                                }
                                "banners" -> "Banner da página inicial"
                                "notices" -> row.txt("body").take(100)
                                "categories" -> row.txt("description").ifBlank { "Categoria do site" }
                                "customers" -> row.txt("phone") + " • " + row.txt("email")
                                else -> row.txt("role").replaceFirstChar { it.uppercase() }
                            }, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            if(mode != "customers") Text(
                                if (row.optBoolean("active",true)) "Ativo" else "Desativado",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.optBoolean("active",true)) Color(0xFF147B5B)
                                else Color(0xFFCB4958))
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = AdminPurple)
                    }
                }
            }
        }
    }

    editor?.let { initial ->
        if(mode in setOf("categories", "notices", "customers")) {
            NativeExtrasEditor(kind = mode, source = initial,
                onSaved = { editor = null; version++ }, onDismiss = { editor = null })
        } else AdminRecordEditor(kind = mode, initial = initial, saving = saving,
            error = error, onDismiss = { editor = null },
            onSaveCoupon = { id, code, desc, type, value, min, active ->
                saving = true; error = ""
                NativeAdminApi.saveCoupon(id,code,desc,type,value,min,active,
                    onSuccess = { saving = false; editor = null; version++ },
                    onError = { saving = false; error = it.message ?: "Erro ao salvar cupom." })
            },
            onSaveBanner = { id,title,image,target,active,position ->
                saving = true; error = ""
                NativeAdminApi.saveBanner(id,title,image,target,active,position,
                    onSuccess = { saving = false; editor = null; version++ },
                    onError = { saving = false; error = it.message ?: "Erro ao salvar banner." })
            },
            onSaveStaff = { id,name,role,pin,active ->
                saving = true; error = ""
                NativeAdminApi.saveStaff(id,name,role,pin,active,
                    onSuccess = { saving = false; editor = null; version++ },
                    onError = { saving = false; error = it.message ?: "Erro ao salvar usuário." })
            })
    }
}

@Composable
private fun AdminRecordEditor(
    kind: String, initial: JSONObject, saving: Boolean, error: String,
    onDismiss: () -> Unit,
    onSaveCoupon: (String,String,String,String,Double,Double,Boolean) -> Unit,
    onSaveBanner: (String,String,String,String,Boolean,Int) -> Unit,
    onSaveStaff: (String,String,String,String,Boolean) -> Unit
) {
    val ctx = LocalContext.current
    var id by remember(initial,kind) { mutableStateOf(initial.txt("id")) }
    var name by remember(initial,kind) { mutableStateOf(when(kind) {
        "coupons" -> initial.txt("code")
        "banners" -> initial.txt("title")
        else -> initial.txt("name")
    }) }
    var description by remember(initial,kind) { mutableStateOf(initial.txt("description")) }
    var amount by remember(initial,kind) {
        mutableStateOf(if(initial.has("discount_value")) initial.optDouble("discount_value").toString() else "10")
    }
    var minimum by remember(initial,kind) {
        mutableStateOf(if(initial.has("min_order_value")) initial.optDouble("min_order_value").toString() else "0")
    }
    var type by remember(initial,kind) {
        mutableStateOf(initial.txt("discount_type").ifBlank { "percent" })
    }
    var role by remember(initial,kind) {
        mutableStateOf(initial.txt("role").ifBlank { "atendente" })
    }
    var pin by remember(initial,kind) { mutableStateOf("") }
    var imageUrl by remember(initial,kind) { mutableStateOf(initial.txt("image_url")) }
    var targetUrl by remember(initial,kind) { mutableStateOf(initial.txt("target_url")) }
    var position by remember(initial,kind) {
        mutableStateOf(initial.optInt("sort_order",0).toString())
    }
    var active by remember(initial,kind) {
        mutableStateOf(initial.optBoolean("active",true))
    }
    var code by remember(initial,kind) {
        mutableStateOf(if(kind=="staff") initial.txt("id") else "")
    }
    var uploadBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadBusy = true
            localError = ""
            NativeMediaApi.upload(ctx, uri,
                onSuccess = { imageUrl = it; uploadBusy = false },
                onError = { localError = it.message ?: "Falha no envio da foto"; uploadBusy = false })
        }
    }

    Dialog(onDismissRequest = { if (!saving && !uploadBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.90f),
            shape = RoundedCornerShape(22.dp), color = Color.White) {
            Column {
                Text(when(kind) {
                    "coupons" -> "Configurar cupom"
                    "banners" -> "Configurar banner"
                    else -> "Acesso da equipe"
                }, Modifier.padding(18.dp), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if(kind == "staff") {
                        OutlinedTextField(code, { code = it.lowercase().filter { char ->
                            char.isLetterOrDigit() || char=='_' || char=='-'
                        }.take(30) }, label = { Text("Código de acesso *") },
                            enabled = id.isBlank(), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(name, { name = it.take(160) },
                        label = { Text(when(kind) {
                            "coupons" -> "Código do cupom *"
                            "banners" -> "Título do banner *"
                            else -> "Nome da pessoa *"
                        }) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (kind == "coupons") {
                        OutlinedTextField(description,
                            { description=it.take(1000) }, label={Text("Descrição")},
                            modifier=Modifier.fillMaxWidth(), minLines=2)
                        Text("Tipo de desconto", fontWeight = FontWeight.SemiBold)
                        listOf("percent" to "Percentual", "fixed" to "Valor fixo",
                            "free_delivery" to "Entrega grátis").forEach { (key,label) ->
                            Row(Modifier.fillMaxWidth().clickable { type=key },
                                verticalAlignment=Alignment.CenterVertically) {
                                RadioButton(selected=type==key,onClick={type=key})
                                Text(label)
                            }
                        }
                        if (type != "free_delivery") OutlinedTextField(amount,{amount=it.take(12)},
                            label={Text(if(type=="percent")"Desconto (%)" else "Desconto (R$)")},
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                            modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(minimum,{minimum=it.take(12)},
                            label={Text("Pedido mínimo (R$)")},
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                            modifier=Modifier.fillMaxWidth())
                    } else if (kind == "banners") {
                        if (imageUrl.isNotBlank()) AsyncImage(model=imageUrl,
                            contentDescription="Prévia do banner",
                            modifier=Modifier.fillMaxWidth().height(150.dp),
                            contentScale=ContentScale.Fit)
                        OutlinedButton(onClick={picker.launch("image/*")},
                            enabled=!saving&&!uploadBusy,modifier=Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.AddPhotoAlternate,null)
                            Spacer(Modifier.width(8.dp))
                            Text(if(uploadBusy)"Enviando…" else "Escolher imagem da galeria")
                        }
                        if(uploadBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedTextField(imageUrl,{imageUrl=it.take(2048)},
                            label={Text("URL da imagem HTTPS")},
                            modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(targetUrl,{targetUrl=it.take(2048)},
                            label={Text("Link de destino (opcional)")},
                            modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(position,{position=it.filter(Char::isDigit).take(5)},
                            label={Text("Ordem de exibição")},
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                            modifier=Modifier.fillMaxWidth())
                    } else {
                        Text("Permissões", fontWeight=FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth().clickable { role="atendente" },
                            verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(role=="atendente",onClick={role="atendente"})
                            Text("Atendente • pedidos, chat e despacho")
                        }
                        Row(Modifier.fillMaxWidth().clickable { role="montador" },
                            verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(role=="montador",onClick={role="montador"})
                            Text("Montador • preparo de pedidos")
                        }
                        OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(6)},
                            label={Text(if(id.isBlank())"Novo PIN de 6 números *"
                                else "Trocar PIN (opcional)")},
                            visualTransformation=PasswordVisualTransformation(),
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                            modifier=Modifier.fillMaxWidth())
                        Text("Desativar o acesso invalida a sessão existente.",
                            style=MaterialTheme.typography.bodySmall,color=Color.Gray)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Ativo", modifier=Modifier.weight(1f), fontWeight=FontWeight.SemiBold)
                        Switch(active,onCheckedChange={active=it})
                    }
                    if(localError.isNotBlank()) Text(localError,color=MaterialTheme.colorScheme.error)
                    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=onDismiss,enabled=!saving&&!uploadBusy,
                        modifier=Modifier.weight(1f)) { Text("Voltar") }
                    Button(onClick={
                        localError=""
                        when(kind) {
                            "coupons" -> {
                                val value=amount.replace(',','.').toDoubleOrNull()
                                val min=minimum.replace(',','.').toDoubleOrNull()
                                if((type!="free_delivery"&&value==null)||min==null)
                                    localError="Confira os valores do cupom."
                                else onSaveCoupon(id,name.trim().uppercase(),description,type,
                                    value?:0.0,min?:0.0,active)
                            }
                            "banners" -> onSaveBanner(id,name.trim(),imageUrl.trim(),
                                targetUrl.trim(),active,position.toIntOrNull()?:0)
                            "staff" -> onSaveStaff(code,name.trim(),role,pin,active)
                        }
                    },enabled=!saving&&!uploadBusy,
                        modifier=Modifier.weight(1.5f)) {
                        Icon(Icons.Default.Save,null)
                        Text(if(saving)"Salvando…" else "Salvar")
                    }
                }
            }
        }
    }
}
