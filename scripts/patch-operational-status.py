from pathlib import Path

path = Path("app/src/main/java/com/rodrigues/gestor/data/Models.kt")
text = path.read_text(encoding="utf-8")
old = '"SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE", "EM_ENTREGA", "ENTREGADOR_NO_LOCAL"'
new = '"DESPACHADO", "SAIU_ENTREGA", "SAIU_PARA_ENTREGA", "A_CAMINHO_CLIENTE", "EM_ENTREGA", "ENTREGADOR_NO_LOCAL"'
if old not in text and '"DESPACHADO", "SAIU_ENTREGA"' not in text:
    raise SystemExit("StatusGroups.DELIVERY anchor not found")
if '"DESPACHADO", "SAIU_ENTREGA"' not in text:
    text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("DESPACHADO enabled as DELIVERY")
