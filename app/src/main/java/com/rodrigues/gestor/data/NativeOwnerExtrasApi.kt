package com.rodrigues.gestor.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Operações administrativas extras, protegidas pela sessão autenticada do dono. */
object NativeOwnerExtrasApi {
    private const val URL_API = "https://fdqqwdplprzpqufpgdrm.supabase.co/functions/v1/gestor-admin-extras"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun call(action: String, data: JSONObject = JSONObject(),
             onSuccess: (JSONObject) -> Unit, onError: (Throwable) -> Unit) {
        NativeCatalogApi.withValidSession(onSuccess = { session ->
            worker.execute {
                try {
                    val body = JSONObject(data.toString()).put("action",action)
                    val connection = (URL(URL_API).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 9_000
                        readTimeout = 20_000
                        useCaches = false
                        doOutput = true
                        setRequestProperty("Content-Type","application/json; charset=utf-8")
                        setRequestProperty("X-Gestor-Session",session)
                        setRequestProperty("Accept","application/json")
                    }
                    try {
                        connection.outputStream.use { it.write(body.toString().toByteArray()) }
                        val status=connection.responseCode
                        val source=(if(status in 200..299) connection.inputStream
                            else connection.errorStream)?.bufferedReader()?.use {
                            it.readText().take(100000)
                        }.orEmpty()
                        val response=JSONObject(source.ifBlank { "{}" })
                        if(status !in 200..299||!response.optBoolean("ok",false))
                            throw IllegalStateException(
                                response.optString("error").ifBlank { "Erro HTTP $status" }
                            )
                        main.post { onSuccess(response) }
                    } finally { connection.disconnect() }
                } catch(error: Throwable) { main.post { onError(error) } }
            }
        }, onError = onError)
    }

    fun list(kind:String, search:String="", onSuccess:(JSONArray)->Unit,
             onError:(Throwable)->Unit) {
        if(kind !in setOf("categories","customers","notices")) {
            onError(IllegalArgumentException("Área administrativa inválida.")); return
        }
        call(kind, JSONObject().put("search",search),
            onSuccess={onSuccess(it.optJSONArray("items")?:JSONArray())},
            onError=onError)
    }

    fun saveCategory(id:String,name:String,description:String,order:Int,active:Boolean,
                     onSuccess:()->Unit,onError:(Throwable)->Unit) {
        if(name.trim().length !in 2..100 || order !in 0..100000) {
            onError(IllegalArgumentException("Informe nome e ordem válidos."));return
        }
        call("category_save", JSONObject().put("id",id).put("name",name.trim())
            .put("description",description.take(1000)).put("sort_order",order)
            .put("active",active),onSuccess={onSuccess()},onError=onError)
    }

    fun saveNotice(id:String,title:String,message:String,imageUrl:String,active:Boolean,
                   onSuccess:()->Unit,onError:(Throwable)->Unit) {
        if(title.trim().length<3||message.trim().length<3) {
            onError(IllegalArgumentException("Informe título e mensagem."));return
        }
        call("notice_save",JSONObject().put("id",id).put("title",title.trim())
            .put("body",message.trim()).put("image_url",imageUrl)
            .put("active",active),onSuccess={onSuccess()},onError=onError)
    }

    fun addresses(customerId:String,onSuccess:(JSONArray)->Unit,onError:(Throwable)->Unit) {
        call("customer_addresses",JSONObject().put("customer_id",customerId),
            onSuccess={onSuccess(it.optJSONArray("items")?:JSONArray())},onError=onError)
    }

    fun assignCoupon(customerId:String,couponCode:String,
                     onSuccess:(Boolean)->Unit,onError:(Throwable)->Unit) {
        call("assign_coupon",JSONObject().put("customer_id",customerId)
            .put("coupon_code",couponCode.trim().uppercase()),
            onSuccess={onSuccess(it.optBoolean("already_assigned"))},onError=onError)
    }
}
