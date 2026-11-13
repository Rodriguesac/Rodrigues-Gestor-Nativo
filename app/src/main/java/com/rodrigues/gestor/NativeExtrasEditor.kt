package com.rodrigues.gestor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.rodrigues.gestor.data.NativeMediaApi
import com.rodrigues.gestor.data.NativeOwnerExtrasApi
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun NativeExtrasEditor(
    kind: String,
    source: JSONObject,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val id = source.optString("id")
    var name by remember(source) { mutableStateOf(source.optString(if(kind=="categories")"name" else "title")) }
    var description by remember(source) { mutableStateOf(
        source.optString(if(kind=="categories")"description" else "body")) }
    var imageUrl by remember(source) { mutableStateOf(source.optString("image_url")) }
    var order by remember(source) { mutableStateOf(source.optInt("sort_order",0).toString()) }
    var active by remember(source) { mutableStateOf(source.optBoolean("active",true)) }
    var busy by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var couponCode by remember { mutableStateOf("") }
    var assigned by remember { mutableStateOf("") }
    var addresses by remember { mutableStateOf(JSONArray()) }

    LaunchedEffect(id,kind) {
        if(kind == "customers" && id.isNotBlank())
            NativeOwnerExtrasApi.addresses(id,onSuccess={addresses=it},
                onError={error=it.message?:"Não foi possível abrir endereços."})
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri != null) {
            photoBusy=true
            NativeMediaApi.upload(context,uri,
                onSuccess={imageUrl=it;photoBusy=false},
                onError={error=it.message?:"Erro no upload";photoBusy=false})
        }
    }
    Dialog(onDismissRequest={if(!busy&&!photoBusy)onDismiss()},
        properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.97f).fillMaxHeight(.88f),
            shape=RoundedCornerShape(18.dp),color=Color.White) {
            Column {
                Text(when(kind) {
                    "categories" -> "Categoria do cardápio"
                    "customers" -> "Cadastro do cliente"
                    else -> "Aviso para o site"
                },Modifier.padding(16.dp),fontWeight=FontWeight.Bold,
                    style=MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(kind=="customers") {
                        Text(source.optString("name"),fontWeight=FontWeight.Bold)
                        Text("Telefone: "+source.optString("phone"),color=Color.Gray)
                        Text("E-mail: "+source.optString("email"),color=Color.Gray)
                        Text("Endereços cadastrados",fontWeight=FontWeight.Bold)
                        if(addresses.length()==0) Text("Nenhum endereço cadastrado.",
                            color=Color.Gray)
                        for(i in 0 until addresses.length()) {
                            val a=addresses.optJSONObject(i)?:continue
                            Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF4F0F9))) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(a.optString("street")+", "+a.optString("number"))
                                    Text(a.optString("district")+" • "+a.optString("city"),
                                        style=MaterialTheme.typography.bodySmall)
                                    if(a.optString("complement").isNotBlank())
                                        Text(a.optString("complement"),
                                            style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        HorizontalDivider()
                        Text("Vincular cupom ao cliente",fontWeight=FontWeight.Bold)
                        OutlinedTextField(couponCode,{couponCode=it.uppercase().take(40)},
                            label={Text("Código de um cupom ativo")},
                            modifier=Modifier.fillMaxWidth(),singleLine=true)
                        OutlinedButton(onClick={
                            busy=true
                            NativeOwnerExtrasApi.assignCoupon(id,couponCode,
                                onSuccess={
                                    busy=false
                                    assigned=if(it)"Este cupom já estava vinculado."
                                        else "Cupom vinculado ao cliente."
                                },
                                onError={busy=false;error=it.message?:"Falha ao vincular."})
                        },enabled=!busy&&couponCode.length>=3,
                            modifier=Modifier.fillMaxWidth()) {
                            Text(if(busy)"Vinculando…" else "Vincular cupom")
                        }
                        if(assigned.isNotBlank()) Text(assigned,color=Color(0xFF147B5B))
                        Text("O vínculo não envia mensagem automática ao cliente.",
                            style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                    } else {
                        OutlinedTextField(name,{name=it.take(160)},
                            label={Text(if(kind=="categories")"Nome da categoria" else "Título do aviso")},
                            modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(description,{description=it.take(1500)},
                            label={Text(if(kind=="categories")"Descrição" else "Texto do aviso")},
                            modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)
                        if(kind=="notices") {
                            if(imageUrl.isNotBlank()) AsyncImage(model=imageUrl,
                                contentDescription="Imagem do aviso",
                                modifier=Modifier.fillMaxWidth().height(140.dp))
                            OutlinedButton(onClick={picker.launch("image/*")},enabled=!busy&&!photoBusy,
                                modifier=Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.AddPhotoAlternate,null)
                                Text(if(photoBusy)"Enviando…" else "Selecionar imagem")
                            }
                            OutlinedTextField(imageUrl,{imageUrl=it.take(2048)},
                                label={Text("URL da imagem (opcional)")},
                                modifier=Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(order,{
                                order=it.filter(Char::isDigit).take(6)
                            },label={Text("Ordem da categoria")},
                                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                                modifier=Modifier.fillMaxWidth())
                        }
                        Row {
                            Text("Ativo",modifier=Modifier.weight(1f),fontWeight=FontWeight.Bold)
                            Switch(active,onCheckedChange={active=it})
                        }
                        if(kind=="notices") Text(
                            "O aviso é publicado na base de dados utilizada pelo GADMINISTRA. " +
                                "Não significa que um push foi enviado.",
                            color=Color.Gray,style=MaterialTheme.typography.labelSmall)
                    }
                    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=onDismiss,enabled=!busy&&!photoBusy,
                        modifier=Modifier.weight(1f)){Text("Voltar")}
                    if(kind!="customers") Button(onClick={
                        busy=true;error=""
                        val success={busy=false;onSaved()}
                        val failure:(Throwable)->Unit={busy=false;error=it.message?:"Erro ao salvar."}
                        if(kind=="categories")
                            NativeOwnerExtrasApi.saveCategory(id,name,description,order.toIntOrNull()?:0,
                                active,success,failure)
                        else NativeOwnerExtrasApi.saveNotice(id,name,description,imageUrl,
                            active,success,failure)
                    },enabled=!busy&&!photoBusy,
                        modifier=Modifier.weight(1.3f)){
                        Icon(Icons.Default.Save,null)
                        Text(if(busy)"Salvando…" else "Salvar")
                    }
                }
            }
        }
    }
}
