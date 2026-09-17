from pathlib import Path
import re

path = Path("app/src/main/java/com/rodrigues/gestor/data/OrdersRepository.kt")
text = path.read_text(encoding="utf-8")


def replace_function(name: str, replacement: str) -> None:
    global text
    pattern = rf"    fun {re.escape(name)}\(.*?(?=\n    (?:fun|private fun) |\Z)"
    updated, count = re.subn(pattern, replacement.rstrip() + "\n", text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"Falha ao aplicar patch Supabase: {name} (encontrados={count})")
    text = updated


replace_function(
    "listenOrders",
    '''    fun listenOrders(
        onData: (List<Order>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = SupabaseOrdersApi.listenOrders(onData, onError)
''',
)

replace_function(
    "listenDrivers",
    '''    fun listenDrivers(
        onData: (List<Driver>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = SupabaseOrdersApi.listenDrivers(onData, onError)
''',
)

replace_function(
    "updateStatus",
    '''    fun updateStatus(order: Order, nextStatus: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.updateStatus(order.id, nextStatus.uppercase(Locale.ROOT), onDone, onError)
    }
''',
)

replace_function(
    "finishPickup",
    '''    fun finishPickup(order: Order, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.finishPickup(order.id, onDone, onError)
    }
''',
)

replace_function(
    "cancelOrder",
    '''    fun cancelOrder(order: Order, reason: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.cancelOrder(order.id, reason.trim().ifBlank { "Cancelado pela loja" }, onDone, onError)
    }
''',
)

replace_function(
    "listenOperation",
    '''    fun listenOperation(
        onData: (StoreOperation) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = SupabaseOrdersApi.listenOperation(onData, onError)
''',
)

replace_function(
    "listenCatalogProducts",
    '''    fun listenCatalogProducts(
        onData: (List<CatalogProduct>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = SupabaseOrdersApi.listenCatalogProducts(onData, onError)
''',
)

replace_function(
    "setCatalogProductAvailable",
    '''    fun setCatalogProductAvailable(product: CatalogProduct, available: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.setCatalogProductAvailable(product.id, available, onDone, onError)
    }
''',
)

replace_function(
    "setStoreOpen",
    '''    fun setStoreOpen(open: Boolean, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.setStoreOpen(open, onDone, onError)
    }
''',
)

path.write_text(text, encoding="utf-8")
print("OrdersRepository.kt preparado para pedidos, cardápio, entregadores e operação via Supabase")
