from pathlib import Path

path = Path("app/src/main/java/com/rodrigues/gestor/ui/GestorApp.kt")
text = path.read_text(encoding="utf-8")

import_anchor = "import com.rodrigues.gestor.notifications.AlertPreferences\n"
import_line = "import com.rodrigues.gestor.notifications.FloatingPanelController\n"
if import_line not in text:
    if import_anchor not in text:
        raise SystemExit("Import anchor not found")
    text = text.replace(import_anchor, import_anchor + import_line, 1)

state_anchor = "    var trackingDefault by remember { mutableStateOf(AlertPreferences.customerTrackingDefault(context)) }\n"
state_line = "    var floatingPanel by remember { mutableStateOf(AlertPreferences.floatingPanel(context)) }\n"
if state_line not in text:
    if state_anchor not in text:
        raise SystemExit("Floating panel state anchor not found")
    text = text.replace(state_anchor, state_anchor + state_line, 1)

compact_block = '''                SettingSwitchRow("Cards compactos", "Mostra mais pedidos por tela sem cortar nomes", compactCards) {
                    AlertPreferences.setCompactCards(context, it); onCompactChanged(it)
                }
'''
float_block = '''                SettingSwitchRow("Painel flutuante", "Mostra um atalho sobre outros apps com os contadores de hoje", floatingPanel) { enabled ->
                    floatingPanel = enabled
                    if (enabled) {
                        val granted = FloatingPanelController.enableOrRequest(context)
                        onMessage(if (granted) "Painel flutuante ativado" else "Autorize o Rodrigues Gestor a aparecer sobre outros apps")
                    } else {
                        FloatingPanelController.disable(context)
                        onMessage("Painel flutuante desativado")
                    }
                }
'''
if float_block not in text:
    if compact_block not in text:
        raise SystemExit("Tela e aparência anchor not found")
    text = text.replace(compact_block, compact_block + float_block, 1)

path.write_text(text, encoding="utf-8")
print("Floating panel UI patch applied")
