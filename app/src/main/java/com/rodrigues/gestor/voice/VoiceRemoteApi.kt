package com.rodrigues.gestor.voice

import android.content.Context
import com.rodrigues.gestor.data.GestorCredentials
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max

object VoiceRemoteApi {
    private const val APP_ENDPOINT = "https://jgjmntezfjuyuxhcnvhd.supabase.co/functions/v1/gestor-app-api"
    private const val ORIGIN = "https://appassets.androidplatform.net"
    private const val PREFS = "rodrigues_voice"
    private const val KEY_SESSION = "gestor_app_session"

    data class Candidate(
        val kind: String,
        val id: String,
        val collection: String = "",
        val name: String,
        val score: Int,
    )

    fun setStoreOpen(
        context: Context,
        open: Boolean,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) = thread(name = "RodriguesVoiceStore") {
        try {
            withSession(context) { token ->
                appRequest(JSONObject().put("action", "set_operation").put("open", open).put("session_token", token))
            }
            onDone(if (open) "Loja aberta e aceitando pedidos." else "Loja fechada para novos pedidos.")
        } catch (error: Throwable) {
            onError(error)
        }
    }

    fun setItemAvailable(
        context: Context,
        target: String,
        available: Boolean,
        onDone: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) = thread(name = "RodriguesVoiceCatalog") {
        try {
            val cleanTarget = VoiceStateStore.normalize(target)
            if (cleanTarget.length < 2) error("Diga o nome do item.")
            val candidates = withSession(context) { token -> loadCandidates(token, cleanTarget) }
            if (candidates.isEmpty() || candidates.first().score < 60) {
                error("Não encontrei " + target + " no cardápio.")
            }

            val bestScore = candidates.first().score
            val best = candidates.filter { it.score == bestScore }
            val bestNames = best.map { VoiceStateStore.normalize(it.name) }.distinct()
            if (bestNames.size > 1) {
                val names = best.map { it.name }.distinct().take(4).joinToString(", ")
                error("Encontrei mais de um item: " + names + ". Diga o nome mais completo.")
            }

            val selected = best.filter { VoiceStateStore.normalize(it.name) == bestNames.first() }
            withSession(context) { token ->
                for (candidate in selected) {
                    val payload = if (candidate.kind == "builder") {
                        JSONObject()
                            .put("action", "update_builder_item")
                            .put("collection", candidate.collection)
                            .put("document_id", candidate.id)
                            .put("available", available)
                    } else {
                        JSONObject()
                            .put("action", "update_product")
                            .put("product_id", candidate.id)
                            .put("available", available)
                    }
                    payload.put("session_token", token)
                    appRequest(payload)
                }
            }

            val label = selected.first().name
            onDone(if (available) label + " reativado no cardápio." else label + " pausado no cardápio.")
        } catch (error: Throwable) {
            onError(error)
        }
    }

    private fun loadCandidates(token: String, target: String): List<Candidate> {
        val output = mutableListOf<Candidate>()
        val builder = appRequest(JSONObject().put("action", "builder_catalog").put("session_token", token))
        val builderItems = builder.optJSONArray("items") ?: JSONArray()
        for (i in 0 until builderItems.length()) {
            val row = builderItems.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            val score = score(name, target)
            if (score > 0) {
                output += Candidate(
                    kind = "builder",
                    id = row.optString("id"),
                    collection = row.optString("collection"),
                    name = name,
                    score = score,
                )
            }
        }

        val products = appRequest(JSONObject().put("action", "products").put("session_token", token))
            .optJSONArray("products") ?: JSONArray()
        for (i in 0 until products.length()) {
            val row = products.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            val score = score(name, target)
            if (score > 0) {
                output += Candidate(
                    kind = "product",
                    id = row.optString("id"),
                    name = name,
                    score = score,
                )
            }
        }
        return output.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.name })
    }

    private fun score(name: String, target: String): Int {
        val alias = VoiceStateStore.normalize(name)
        if (alias.isBlank()) return 0
        if (alias == target) return 100
        if (alias.startsWith(target + " ") || target.startsWith(alias + " ")) return 88
        if (alias.contains(target)) return 78
        val words = target.split(' ').filter { it.length > 1 }
        if (words.isNotEmpty() && words.all(alias::contains)) return max(60, 70 - (alias.split(' ').size - words.size))
        return 0
    }

    private fun <T> withSession(context: Context, block: (String) -> T): T {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_SESSION, null).orEmpty()
        if (token.isBlank()) {
            token = login()
            prefs.edit().putString(KEY_SESSION, token).apply()
        }
        return try {
            block(token)
        } catch (error: HttpError) {
            if (error.code != 401) throw error
            token = login()
            prefs.edit().putString(KEY_SESSION, token).apply()
            block(token)
        }
    }

    private fun login(): String {
        val pin = GestorCredentials.pin.trim()
        if (pin.length != 6) error("PIN do Gestor não configurado.")
        val response = appRequest(JSONObject().put("action", "login").put("pin", pin))
        return response.optString("session_token").takeIf { it.isNotBlank() }
            ?: error("Não foi possível iniciar a sessão de voz.")
    }

    private class HttpError(val code: Int, message: String) : IllegalStateException(message)

    private fun appRequest(payload: JSONObject): JSONObject {
        val connection = (URL(APP_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Origin", ORIGIN)
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            if (code !in 200..299 || !json.optBoolean("ok", false)) {
                val message = json.optString("message").ifBlank {
                    json.optString("error").ifBlank { "Falha no comando (" + code + ")." }
                }
                throw HttpError(code, message)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
