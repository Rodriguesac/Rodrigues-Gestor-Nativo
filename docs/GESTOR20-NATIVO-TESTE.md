# Gestor20 Android — prévia segura v3.5

Esta branch cria `app-debug.apk` com o ID Android **com.rodrigues.gestor.preview**,
instalável ao lado do Gestor de produção. Não desinstale a versão principal.

O modo prévia usa os dados reais de pedidos e cardápio: confirmar ações no
aplicativo **altera dados reais**. Teste apenas com pedidos autorizados.

Notificações: o som Nokia está embutido; o monitoramento de pedidos tem
alertas locais. O token FCM da prévia **não é registrado** na operação real
para não substituir o token de notificações do Gestor principal. A entrega
de push da prévia precisa de registro próprio antes de ser declarada pronta.

Offline: as informações recebidas são gravadas quando o Gestor ativo ou o
serviço de monitoramento obtém o pedido. Não se criam novos pedidos sem rede
nem se aceitam pedidos automaticamente depois de reconectar. Filas antigas
de versões de testes não são processadas.
