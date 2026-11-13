package com.rodrigues.gestor.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class CatalogDraft(
    val id: String = "",
    val collection: String = "products",
    val name: String = "",
    val description: String = "",
    val price: String = "0,00",
    val compareAtPrice: String = "",
    val imageUrl: String = "",
    val sortOrder: String = "0",
    val active: Boolean = true,
    val available: Boolean = true,
    val variantPrices: Map<String, String> = emptyMap(),
    val categoryId: String = ""
) {
    fun validate(): String? {
        if (collection !in CATALOG_GROUPS.map { it.first }) return "Tipo de item inválido."
        if (collection == "products" && !categoryId.matches(
                Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
            )) return "Selecione a categoria do produto."
        if (name.trim().length !in 2..160) return "Informe um nome de 2 a 160 caracteres."
        if (description.length > 3000) return "Descrição maior que o permitido."
        if (MoneyInput.parse(price) == null) return "Preço inválido."
        if (compareAtPrice.isNotBlank() && MoneyInput.parse(compareAtPrice) == null)
            return "Preço de comparação inválido."
        if ((sortOrder.toIntOrNull() ?: -1) !in 0..100000) return "Ordem inválida."
        if (imageUrl.isNotBlank() && (!imageUrl.startsWith("https://") ||
            imageUrl.any { it.isWhitespace() })) return "Imagem deve usar URL HTTPS."
        if (variantPrices.any { (key,value) ->
            key !in setOf("misto","origem","cupuacu","polpanorte") ||
                (value.isNotBlank() && MoneyInput.parse(value) == null)
        }) return "Confira os valores de cada base."
        return null
    }
    companion object {
        fun from(item: CatalogItem): CatalogDraft = CatalogDraft(
            item.id, item.collection, item.name, item.description,
            MoneyInput.format(item.price),
            if (item.compareAtPrice > 0.0) MoneyInput.format(item.compareAtPrice) else "",
            item.imageUrl, item.sortOrder.toString(), item.active, item.available,
            item.variantPrices.mapValues { MoneyInput.format(it.value) }
        )
    }
}
val CATALOG_GROUPS = listOf(
    "products" to "Produtos",
    "bases" to "Bases",
    "cardapio_acai" to "Tamanhos de açaí",
    "acompanhamentos_gratis" to "Complementos grátis",
    "adicionais" to "Adicionais pagos",
    "coberturas" to "Coberturas",
    "utensilios" to "Utensílios"
)

object MoneyInput {
    fun parse(value: String): Double? {
        val cleaned = value.trim().replace(',', '.')
        if (!cleaned.matches(Regex("^[0-9]{1,5}([.][0-9]{1,2})?$"))) return null
        val number = cleaned.toDoubleOrNull() ?: return null
        return number.takeIf { it.isFinite() && it in 0.0..10000.0 }
    }
    fun format(value: Double) = String.format(Locale("pt", "BR"), "%.2f", value)
}

object NativeAdminApi {
    fun saveCatalog(draft: CatalogDraft, onSuccess: () -> Unit,
                    onError: (Throwable) -> Unit) {
        val problem = draft.validate()
        if (problem != null) { onError(IllegalArgumentException(problem)); return }
        val prices = JSONObject()
        draft.variantPrices.filterValues { it.isNotBlank() }.forEach { (key,value) ->
            prices.put(key, MoneyInput.parse(value))
        }
        val payload = JSONObject()
            .put("id", draft.id)
            .put("collection", draft.collection)
            .put("name", draft.name.trim())
            .put("description", draft.description.trim())
            .put("price", MoneyInput.parse(draft.price))
            .put("compare_at_price", if (draft.compareAtPrice.isBlank()) JSONObject.NULL
                else MoneyInput.parse(draft.compareAtPrice))
            .put("image_url", draft.imageUrl.trim())
            .put("sort_order", draft.sortOrder.toInt())
            .put("active", draft.active)
            .put("available", draft.available)
            .put("category_id", draft.categoryId)
            .put("variant_prices", prices)
        NativeOwnerExtrasApi.call("catalog_item_save", payload,
            onSuccess = { onSuccess() }, onError = onError)
    }

    fun list(kind: String, onSuccess: (JSONArray) -> Unit, onError: (Throwable) -> Unit) {
        if (kind !in setOf("coupons","banners","staff")) {
            onError(IllegalArgumentException("Módulo inválido.")); return
        }
        NativeCatalogApi.execute(if (kind == "staff") "staff_list" else "marketing_list",
            if (kind == "staff") JSONObject() else JSONObject().put("kind", kind),
            onSuccess = { res ->
                onSuccess(res.optJSONArray(if (kind == "staff") "staff" else "items") ?: JSONArray())
            }, onError = onError)
    }

    fun saveCoupon(id: String, code: String, description: String, type: String,
                   value: Double, minimum: Double, active: Boolean,
                   onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        if (!code.matches(Regex("[A-Za-z0-9_-]{3,40}")) ||
            type !in setOf("percent", "fixed", "free_delivery") ||
            !value.isFinite() || value !in 0.0..10000.0 ||
            (type == "percent" && value > 100.0) ||
            !minimum.isFinite() || minimum !in 0.0..100000.0) {
            onError(IllegalArgumentException("Confira o código e os valores do cupom.")); return
        }
        val p = JSONObject().put("kind","coupons").put("code", code.uppercase(Locale.ROOT))
            .put("description", description.take(1000)).put("discount_type", type)
            .put("discount_value", if (type=="free_delivery") 0.0 else value)
            .put("min_order_value", minimum).put("active", active)
        if (id.isNotBlank()) p.put("id",id)
        NativeCatalogApi.execute("marketing_save",p,
            onSuccess = { onSuccess() }, onError = onError)
    }

    fun saveBanner(id: String, title: String, imageUrl: String, targetUrl: String,
                   active: Boolean, position: Int, onSuccess: () -> Unit,
                   onError: (Throwable) -> Unit) {
        if (title.isBlank() || !imageUrl.startsWith("https://") ||
            (targetUrl.isNotBlank() && !targetUrl.startsWith("https://"))) {
            onError(IllegalArgumentException("Preencha título, imagem e link HTTPS.")); return
        }
        val p = JSONObject().put("kind","banners").put("title",title.take(160))
            .put("image_url", imageUrl).put("target_url",targetUrl).put("sort_order",position)
            .put("active",active)
        if(id.isNotBlank()) p.put("id",id)
        NativeCatalogApi.execute("marketing_save",p,
            onSuccess = { onSuccess() }, onError = onError)
    }

    fun saveStaff(id: String, name: String, role: String, pin: String, active: Boolean,
                  onSuccess: () -> Unit, onError: (Throwable) -> Unit) {
        if(!id.matches(Regex("[a-z0-9_-]{3,30}")) || name.isBlank() ||
            role !in setOf("atendente","montador") ||
            (pin.isNotBlank() && !pin.matches(Regex("[0-9]{6}")))) {
            onError(IllegalArgumentException("Código, nome, perfil ou PIN inválidos.")); return
        }
        val p=JSONObject().put("id",id).put("name",name.take(100))
            .put("role",role).put("active",active)
        if(pin.isNotBlank())p.put("pin",pin)
        NativeCatalogApi.execute("staff_save",p,
            onSuccess={onSuccess()},onError=onError)
    }
}
