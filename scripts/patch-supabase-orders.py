from pathlib import Path
import re

path = Path("app/src/main/java/com/rodrigues/gestor/data/OrdersRepository.kt")
text = path.read_text(encoding="utf-8")


def replace_once(pattern: str, replacement: str, label: str) -> None:
    global text
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"Falha ao aplicar patch: {label} (encontrados={count})")
    text = updated


replace_once(
    r"    fun listenOrders\(.*?\n    fun listenDrivers",
    '''    fun listenOrders(
        onData: (List<Order>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = SupabaseOrdersApi.listenOrders(onData, onError)

    fun listenDrivers''',
    "listenOrders",
)

replace_once(
    r"    fun updateStatus\(.*?\n    fun finishPickup",
    '''    fun updateStatus(order: Order, nextStatus: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.updateStatus(order.id, nextStatus.uppercase(Locale.ROOT), onDone, onError)
    }

    fun finishPickup''',
    "updateStatus",
)

replace_once(
    r"    fun finishPickup\(.*?\n    fun cancelOrder",
    '''    fun finishPickup(order: Order, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.finishPickup(order.id, onDone, onError)
    }

    fun cancelOrder''',
    "finishPickup",
)

replace_once(
    r"    fun cancelOrder\(.*?\n    fun ensureChat",
    '''    fun cancelOrder(order: Order, reason: String, onDone: () -> Unit, onError: (Throwable) -> Unit) {
        SupabaseOrdersApi.cancelOrder(order.id, reason.trim().ifBlank { "Cancelado pela loja" }, onDone, onError)
    }

    fun ensureChat''',
    "cancelOrder",
)

path.write_text(text, encoding="utf-8")
print("OrdersRepository.kt preparado para pedidos Supabase")
