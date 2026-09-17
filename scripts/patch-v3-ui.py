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
    '"Cardápio sincronizado pelo Supabase. O Monte seu Pedido aparece completo nos detalhes; aqui você pausa ou reativa produtos."',
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

print("Gestor v3: mapa do cliente, textos Supabase e status DESPACHADO aplicados")
