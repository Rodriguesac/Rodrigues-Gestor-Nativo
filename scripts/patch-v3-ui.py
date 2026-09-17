from pathlib import Path

ui_path = Path("app/src/main/java/com/rodrigues/gestor/ui/GestorApp.kt")
ui = ui_path.read_text(encoding="utf-8")

map_anchor = '''            if (upDelivery) {
                DeliveryTrackingCard('''
map_replacement = '''            if (!order.pickup) {
                CustomerAddressMapCard(order)
                Spacer(Modifier.height(10.dp))
            }

            if (upDelivery) {
                DeliveryTrackingCard('''
if map_anchor not in ui:
    raise SystemExit("Falha ao inserir mapa do cliente: âncora não encontrada")
ui = ui.replace(map_anchor, map_replacement, 1)

ui = ui.replace(
    'Text("A lista atualiza automaticamente pelo Firestore.", color = MaterialTheme.colorScheme.onSurfaceVariant)',
    'Text("A lista atualiza automaticamente pelo Supabase.", color = MaterialTheme.colorScheme.onSurfaceVariant)',
    1,
)
ui = ui.replace(
    '"Aqui é operação rápida: pausar ou reativar. Cadastro completo continua no GADM."',
    '"Cardápio completo do Supabase: produtos, bases, coberturas, acompanhamentos e adicionais do Monte seu Pedido. Pause ou reative por aqui."',
    1,
)
ui_path.write_text(ui, encoding="utf-8")

models_path = Path("app/src/main/java/com/rodrigues/gestor/data/Models.kt")
models = models_path.read_text(encoding="utf-8")
status_anchor = '''        "SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE", "EM_ENTREGA", "ENTREGADOR_NO_LOCAL"'''
status_replacement = '''        "DESPACHADO", "SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE", "EM_ENTREGA", "ENTREGADOR_NO_LOCAL"'''
if status_anchor not in models:
    raise SystemExit("Falha ao reconhecer status DESPACHADO: âncora não encontrada")
models = models.replace(status_anchor, status_replacement, 1)
models_path.write_text(models, encoding="utf-8")

api_path = Path("app/src/main/java/com/rodrigues/gestor/data/SupabaseOrdersApi.kt")
api = api_path.read_text(encoding="utf-8")
listener_old = '''): ListenerRegistration = poll(intervalMs, onData, onError, ::fetchProducts)'''
listener_new = '''): ListenerRegistration = poll(intervalMs, onData, onError) {
        fetchProducts() + SupabaseBuilderCatalogApi.fetchProducts()
    }'''
if listener_old not in api:
    raise SystemExit("Falha ao integrar catálogo do Monte seu Pedido: listener não encontrado")
api = api.replace(listener_old, listener_new, 1)

toggle_old = '''    fun setCatalogProductAvailable(
        productId: String,
        available: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = runAction(
        JSONObject().put("action", "product_toggle").put("id", productId).put("available", available),
        onDone,
        onError,
    )'''
toggle_new = '''    fun setCatalogProductAvailable(
        productId: String,
        available: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (productId.startsWith("builder|")) {
            SupabaseBuilderCatalogApi.setAvailable(productId, available, onDone, onError)
            return
        }
        runAction(
            JSONObject().put("action", "product_toggle").put("id", productId).put("available", available),
            onDone,
            onError,
        )
    }'''
if toggle_old not in api:
    raise SystemExit("Falha ao integrar pausa do Monte seu Pedido: função não encontrada")
api = api.replace(toggle_old, toggle_new, 1)
api_path.write_text(api, encoding="utf-8")

print("Gestor v3: mapa, status DESPACHADO e catálogo completo do Monte seu Pedido aplicados")
