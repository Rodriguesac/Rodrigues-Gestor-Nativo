# Bloqueio encontrado no pedido manual

A Edge Function `gestor-app-api` já possui a ação `manual_order` e chama
`admin.rpc('submit_whatsapp_builder_order', {p_payload: ...})`.

Uma consulta **somente leitura** ao projeto Supabase `fdqqwdplprzpqufpgdrm`,
em 13/11/2026, confirmou que não existe a função Postgres com esse nome.

`PROPOSTA-RPC-PEDIDO-MANUAL.sql` é um candidato à correção e **não foi executado**,
não modificou o banco e não criou pedidos. Requer auditoria e testes controlados
antes de migrar para produção; não executar diretamente como arquivo improvisado.

O candidato inclui:
- deduplicação por `submission_id` persistente, transação e chave de concorrência;
- índice exclusivo para fonte GESTOR20_MANUAL;
- validador de telefone, endereço, preço, itens e totais no servidor;
- permissão de execução reservada ao role `service_role` e `SECURITY INVOKER`;
- código numérico de pedido com sequência separada;
- criação do cliente manual separado do usuário autenticado;
- criação transacional de `orders` e `order_items`, aproveitando triggers existentes.

**Bloqueios antes de ativar:** revisar função SQL; teste rollback sem alterar pedido
real; validar idempotência, triggers, códigos e formatação com app Android;
implementar atualização de pagamento e estorno conforme fluxo; conferir permissões
e logs sem expor dados pessoais. A API não deve ser anunciada como funcional até
a função estar instalada e os testes passarem.
