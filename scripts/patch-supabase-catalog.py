from pathlib import Path
import re

repo_path = Path("app/src/main/java/com/rodrigues/gestor/data/OrdersRepository.kt")
text = repo_path.read_text(encoding="utf-8")

pattern = r'''    fun listenCatalogProducts\(.*?\n    fun listenOperation'''
replacement = '''    fun listenCatalogProducts(
        onData: (List<CatalogProduct>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = SupabaseCatalogApi.listenProducts(onData, onError)

    fun setCatalogProductAvailable(
        product: CatalogProduct,
        available: Boolean,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        SupabaseCatalogApi.setAvailable(product, available, onDone, onError)
    }

    fun listenOperation'''
text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
if count != 1:
    raise SystemExit(f"Falha ao trocar catálogo por Supabase (encontrados={count})")
repo_path.write_text(text, encoding="utf-8")

ui_path = Path("app/src/main/java/com/rodrigues/gestor/ui/GestorApp.kt")
ui = ui_path.read_text(encoding="utf-8")
ui = ui.replace(
    '"Aqui é operação rápida: pausar ou reativar. Cadastro completo continua no GADM."',
    '"Cardápio do Supabase: produtos e todo o Monte seu Pedido. Pause ou reative por aqui; cadastro completo continua no GADM."',
    1,
)
ui = ui.replace(
    'Text("A lista atualiza automaticamente pelo Firestore.", color = MaterialTheme.colorScheme.onSurfaceVariant)',
    'Text("A lista atualiza automaticamente pelo Supabase.", color = MaterialTheme.colorScheme.onSurfaceVariant)',
    1,
)
ui_path.write_text(ui, encoding="utf-8")
print("Catálogo completo do Supabase ativado")
