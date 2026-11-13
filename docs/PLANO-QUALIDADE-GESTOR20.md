# Gestor20 RAC — contrato de entrega

**Regra do usuário (13/11/2026): não produzir/distribuir APK enquanto o aplicativo não estiver funcional e validado.**
Branch atual: `feature/gestor20-completo-sem-apk`. Workflow de validação compila somente Kotlin: não executa `assembleDebug`, não publica artefatos e não altera o sistema em produção.

## Funcionalidade implementada em código na branch

- Pedidos recebidos são gravados localmente tanto pela tela quanto pelo serviço de monitoramento. Sem internet, apenas a leitura de informações já recebidas é garantida.
- Fila automática de envio de alterações offline foi desativada. Aceite de notificação abre o pedido para confirmação.
- Autenticação inicial exige confirmação online do PIN na API, antes de armazená-lo cifrado com Android Keystore. Credencial previamente válida permite consultar o cache sem rede.
- Cardápio integrado aos produtos e itens reais da `gestor-app-api`: consultar, ativar, pausar e alterar preço dos produtos.
- Operação: abrir/fechar loja, prazo e mensagem, todos confirmados pelo servidor.
- Pedidos: aceitar, preparar, pronto, retirada, despacho próprio (STORE) e oferta UP. Transições comuns passam pela mesma API de pedidos do Gestor web.
- Despacho UP nunca deve ser marcado como entregue pelo botão de finalização manual. A ação final manual exige `delivery_dispatch_mode=STORE` e ausência de missão UP.
- Chat por pedido via `conversation_for_order`, `messages`, `send_message`, com consulta periódica enquanto aberto; resposta só marcada como enviada após sucesso.
- Cancelamento com justificativa obrigatória e bloqueios de backend para missão UP; o gestor deve tratar reembolso separadamente.
- Histórico real dos últimos 30 dias (até 200 registros por página) com cache, separado de pedidos ativos. Paginação ainda pendente.
- Financeiro com relatórios reais de 1, 7 e 30 dias por forma de pagamento, total concluído e cancelamentos. O relatório não é conciliação bancária nem reembolso.
- Toque Nokia fornecido pelo usuário, alertas distintos, impressão e compartilhamento de pedido.
- Prévia Android possui identificador separado `com.rodrigues.gestor.preview`, sem registrar o token FCM como aparelho de produção.

## Restrições atuais / gates antes do APK final

1. **Compilação de todas as mudanças** no workflow sem geração de APK. Qualquer falha de dependência ou compilação deve ser investigada e resolvida.
2. **Testes automatizados** para serialização, estados de pedidos, autenticação, tempos, idempotência e concorrência entre gestores. Ainda insuficientes.
3. **Validação do fluxo UP fim-a-fim**, com entrega, recusa, desistência, falha, cancelamento, pagamento e conflito concorrente. Não acionar pedidos de produção sem cenário controlado autorizado.
4. **Pedido manual:** tela e rascunho AES implementados, preços e dados obrigatórios testados na JVM. A função `submit_whatsapp_builder_order` foi implantada no Supabase sob a migração `20261113175813_gestor20_manual_order_rpc_idempotent_v1`. Teste isolado PostgreSQL 17 e 12 chamadas concorrentes aprovados; teste reversível no banco real confirmou zero pedidos persistidos. Falta validação da interface Android autenticada no celular, do checkout completo e das notificações.
5. **Histórico paginado e relatórios filtráveis** com valores reconciliados com o backend.
6. **Cliente e mensagens**: confirmar comunicação cliente↔gestor e notificações push funcionando também com o app fechado.
7. **Login/sessões**: avaliar renovação de sessão sem exigir PIN para cada chamada e permissões granulares por funcionário.
8. **UI/UX e acessibilidade** em aparelhos Android pequenos e grandes, status corretos, tema roxo, animações e estados de erro.
9. **Notificações no Android 13–16**, permissões, limites do serviço, toque Nokia, vibração, repetição, economia de bateria, áudio silenciado.
10. **Impressão 58/80 mm e PDF**, atualização/assinatura de release e instalação sem comprometer dados da loja.
11. **Testes no aparelho** com todos os fluxos, sem reproduzir em produção mudanças destrutivas.
12. Aprovação final baseada em evidências, não apenas em compilação bem-sucedida.

## Relato de testes

- Commits `5e5fcb1`, `01532fa`, `08ff465`, `49c5dbb`: validação Kotlin sem APK concluída com sucesso.
- O commit `74c647d6` passou na validação Kotlin **sem gerar APK**, workflow GitHub Actions `43463308303` (13/11/2026). Esse resultado atesta compilação, não testes funcionais em aparelho ou aprovação de produção.
- Um build intermediário falhou por indisponibilidade de dependências Maven/Google; a verificação seguinte foi concluída com sucesso.

## Decisão de entrega

A versão de desenvolvimento **não é um aplicativo final** e **nenhum APK deve ser gerado para o usuário até os gates acima**. A produção permanece intocada.

## Rodada 13/11/2026 — pedido manual

- UI nativa: cliente, telefone, retirada/entrega, endereço, taxa, Pix/dinheiro/crédito/débito, lista editável de produtos, seleção de produtos e complementos ativos, cálculo de total e confirmação final.
- Rascunho criptografado com Android Keystore, inclusive chave de submissão estável em caso de resposta ambígua da rede. Não há envio offline automático.
- Servidor existente chama a RPC inexistente `submit_whatsapp_builder_order`; a proposta de função SQL foi documentada sem aplicar alterações.
- GitHub Actions `43467810641`: `compileDebugKotlin`, `testDebugUnitTest` e **verificação de ausência de APK** concluídos com sucesso. Não substitui testes reais do aplicativo.
- Nenhum pedido de produção foi criado/cancelado nesta rodada. Nenhum APK produzido ou distribuído.

## Migração do backend aplicada (13/11/2026)

- Projeto Supabase `fdqqwdplprzpqufpgdrm`: migração `20261113175813_gestor20_manual_order_rpc_idempotent_v1` aplicada e verificada.
- `service_role` pode executar a RPC; `anon` e `authenticated` não podem. Sem `SECURITY DEFINER`.
- Testes CI no PostgreSQL 17, incluindo 12 tentativas simultâneas, concluídos com sucesso: https://github.com/Rodriguesac/Rodrigues-Gestor-Nativo/actions/runs/43477851672
- Teste real reversível executou a função e confirmou `persisted_test_orders=0` após `ROLLBACK`.
- A aplicação Android atual segue intacta. A conclusão da funcionalidade exige teste autenticado na interface; **ainda não gerar APK final**.
