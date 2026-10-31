# Rodrigues Gestor Android — integração com o ecossistema

## Origem localizada

Aplicativo: `Rodriguesac/Rodrigues-Gestor-Nativo`  
Package Android: `com.rodrigues.gestor`  
Versão base: `3.3.2-mobile`  
UI: React/Vite embarcado num WebView nativo. Recursos nativos existentes: impressão, FCM, serviço de alertas, voz, PIN de operador e notificação de novos pedidos.

Projetos conectados:
- Cliente + Gadministra: `Rodriguesac/rodriguesacaiecia`, branch `rebuild/ecossistema-integrado-20261030`.
- UP Entregas: `Rodriguesac/UpEntregas`, branch `rebuild/up-core-integration-20261030`.
- Rodrigues Gestor: branch `rebuild/gestor-integrado-20261030`.
- Supabase operacional: `rodriguesacaiecia2` (project `fdqqwdplprzpqufpgdrm`) e autenticação Firebase existente.

## Fluxo de entrega padronizado

1. O Cliente envia o pedido; o pedido é salvo na fonte de dados atual.
2. O Rodrigues Gestor aceita, prepara e marca pronto.
3. O operador acessa o pedido → **UP Entregas**, escolhe motoboy disponível e envia **oferta direcionada**. A API do Gestor é `gestor-app-api`, autenticada pela sessão **própria do Gestor**, não pelo PIN do Gadministra.
4. O backend registra `up_target_courier_id`, `up_state=OFFER_PENDING` e estado `AGUARDANDO_ENTREGADOR`, sem atribuir `courier_id` antecipadamente.
5. O motoboy recebe notificação FCM caso configurada, ou recebe a oferta na consulta periódica do UP, sempre mediante verificação da identidade.
6. Após aceite, UP registra estados `TO_STORE`, `AT_STORE`, `TO_CUSTOMER`, `AT_CUSTOMER` e conclusão. O Cliente só acompanha a localização quando a autorização do mapa estiver habilitada e a entrega já tiver sido retirada.
7. O Rodrigues Gestor pode **recolher uma oferta ainda não aceita**, mantendo o pedido aberto para enviar a outro motoboy. O aplicativo bloqueia o antigo botão rápido que pulava diretamente para `EM_ENTREGA` ou `CONCLUIDO` em entrega por motoboy.

## Interface do Gestor

Tela de pedido → UP Entregas:
- Lista de entregadores com disponibilidade.
- `Enviar oferta` somente para entregador ativo, sem missão concorrente e pedido em estado aceito pelo servidor.
- `Recolher oferta pendente` enquanto não houve aceite.
- Controle de rastreamento por pedido; não liga o GPS do entregador antes da retirada.
- Mensagens de confirmação, recusa e indisponibilidade, preservando impressão e chat existentes.

## Contrato de API

As ações adicionadas ao `gestor-app-api` no repositório Cliente são:
- `up_offer_to_courier`: recebe `order_id` e `courier_id`.
- `up_recall_offer`: recebe `order_id`.
- `up_set_tracking`: recebe `order_id` e `enabled: boolean`.

Regras: sessão autenticada; perfis `owner` e `atendente`; `montador` sem autorização para despacho. Não confiar em IDs, estados ou permissões enviadas pelo frontend. Pagamento InfinityPay precisa de confirmação no servidor.

## Acompanhamento e conservação de dados

O aplicativo antigo já possuía dois caminhos de dados: Firestore legado e Supabase operacional. **Não copiar coleções ou alterar tabelas em produção para forçar compatibilidade.** As ações novas de despacho são direcionadas à mesma fonte canônica do UP (`public.orders`).

A versão instalada atualmente permanece intocada. A branch deve ser compilada, testada em dispositivo, submetida à homologação com funções Supabase isoladas e publicada apenas com rollback verificado. APK de debug não deve substituir o aplicativo de caixa/gestão em operação.

## Testes

- `cd web && npm ci && npm test && npm run build`.
- Contratos do backend: `supabase/functions/_shared/gestor-dispatch.test.mjs` no repositório Cliente.
- Integração cruzada: `scripts/test-gestor-up-bridge.mjs` no repositório Cliente, com checkout das branches dos dois apps.
- Android: GitHub Actions **Gerar APK Rodrigues Gestor Hybrid** (APK de desenvolvimento, não produção).

## Correção adicional — ação ACEITAR nas notificações nativas

O serviço de notificações recebe pedidos com UUID da tabela `public.orders`. O receiver antigo tentava aceitar qualquer ID na coleção Firestore `pedidos`, podendo falhar silenciosamente para pedidos novos. Agora, ao identificar UUID, chama `SupabaseOrdersApi.updateStatus(orderId, "CONFIRMADO")` após carregar o PIN do operador, mantendo a validação e o controle de concorrência no backend `gestor-orders`. IDs legados continuam usando o fluxo existente do Firestore. O teste `web/tests/native-accept.test.mjs` impede regressão.

## Cancelamento de corrida por Rodrigues Gestor

O botão `Cancelar` de um pedido com motoboy designado ou oferta pendente agora usa `up_cancel_delivery` e encaminha o motivo ao `gestor-app-api`. A API valida sessão/operador e chama a RPC de cancelamento. Sem a RPC instalada, a operação retorna 503 e não altera o pedido por um caminho antigo.

Antes da retirada, o pedido pode ser cancelado e a missão é liberada. Depois da retirada ou de pagamento confirmado, mostra **Cancelamento em análise**, sem exibir um estorno fictício nem liberar o motoboy sem ação do suporte. A interface deixa de mostrar `Cancelar` enquanto existe análise pendente; pagamentos precisam de conciliação separada.

Teste web `web/tests/up-cancel.test.mjs`; teste cruzado no repositório Cliente `scripts/test-up-cancellation.mjs`. O APK Android de homologação não deve substituir o app instalado antes dos testes reais.

## Resolução de revisão sem estorno

No detalhe de pedido → UP Entregas, o proprietário pode confirmar a **devolução física dos produtos**, escrever o registro e solicitar a conclusão da análise. A API `up_resolve_cancel_review` exige sessão de proprietário e delega a decisão financeira à RPC transacional `up_resolve_cancel_review`.

Se houver pagamento InfinitePay, transação, checkout ou indicação de estorno, a interface avisa que precisa de conciliação financeira, e o backend rejeita conclusão operacional. Nenhuma promessa de estorno é exibida.

Os testes da UI verificam o fluxo e a compilação React; a versão Android de homologação continua separada da instalada na loja.
