# Gestor20 — implantação da função do pedido manual

**Situação em 13/11/2026:** a função `public.submit_whatsapp_builder_order(jsonb)` já foi instalada no projeto Supabase `fdqqwdplprzpqufpgdrm`.

Migração Supabase: `20261113175813_gestor20_manual_order_rpc_idempotent_v1`.

- GitHub Actions PostgreSQL 17: testes de parâmetros, totais, itens, permissões e idempotência passaram.
- Teste com **12 solicitações simultâneas** e a mesma chave resultou em **um único pedido**, sem itens ou clientes duplicados.
- A função é `SECURITY INVOKER`; execução liberada apenas para `service_role`.
- `anon` e `authenticated` **não** têm permissão para executar a função diretamente.
- Teste técnico no banco da loja executado em uma transação e revertido (`ROLLBACK`). Verificação posterior confirmou **0 pedidos de teste persistidos**.
- Nenhum APK foi gerado nesta etapa.

**Ainda não está validado:** criação completa do pedido pela interface Android autenticada com um operador real, sinal de notificação no aparelho, pagamento, impressão e conexão com o Gestor Web. Não inventar aprovação de produção por causa dos testes SQL.

O SQL em `PROPOSTA-RPC-PEDIDO-MANUAL.sql` representa a lógica implantada. Não reaplicar manualmente; o histórico de migração já está no Supabase.

CI isolada: https://github.com/Rodriguesac/Rodrigues-Gestor-Nativo/actions/runs/43477851672
