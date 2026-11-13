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

## Rodada 13/11/2026 — pagamento, PDF e avisos

- A normalização de pedidos prioriza `orders.payment_status` (fonte canônica) sobre `payment_details.status` e o `raw_payload` histórico.
- A interface de pedido separa pagamento pago, pendente, recusado e reembolsado; nenhum clique marca Pix/cartão como pago automaticamente. **Ainda não há conciliação financeira completa.**
- A comanda pode ser salva em um PDF real por meio do Storage Access Framework (`ACTION_CREATE_DOCUMENT`) e impressa pela interface Android existente. Documento identificado como **não fiscal**. Ainda falta validar renderização, paginação e impressoras térmicas em aparelho.
- FCM corrigido: deduplicação usa ID de evento/mensagem quando disponível, com fallback tipo+pedido+estado para evitar descartar pagamentos/mensagens de um pedido que já gerou alerta de pedido novo.
- Diagnóstico de notificações consulta permissões e canal de pedidos, com atalho às configurações Android. **Isso não prova recebimento de push com o aplicativo fechado**, especialmente porque a prévia não registra seu token como dispositivo de produção.
- Testes JVM adicionados para status canônico, conteúdo da comanda e deduplicação dos avisos.
- **Validação GitHub Actions:** run `43483987160` em 13/11/2026: compilação Kotlin, testes JVM e verificação de ausência de APK **todas aprovadas**.
- Nenhum APK gerado/distribuído e nenhum pedido real alterado nesta rodada.

## Rodada 13/11/2026 — confiabilidade da conexão e do cache

- Pedido manual: a confirmação HTTP isolada não apaga o rascunho. `NativeManualOrderApi.submit` agora exige `result.order_id` (UUID) e `result.order_code` numérico; o retorno idempotente informa quando o pedido já estava registrado. Testes JVM adicionados para confirmação válida e respostas malformadas.
- Conexão Realtime: `SupabaseOrdersApi.listenOrders` utiliza gerações de websocket e controle atômico para uma única reconexão programada. Callbacks atrasados não substituem o estado do socket atual. `RealtimeReconnectGate` possui testes JVM de concorrência de 12 chamadas, fechamento do listener, gerações antigas e do callback de reconexão atrasado.
- Cache: `OfflineOrderStore` fornece a data da última gravação confirmada. A tela mostra o modo offline e o horário dos dados que já foram recebidos, sem prometer pedidos novos ou atualização de status durante a queda de rede.
- Erro de SQLite: falhas de abertura ou gravação geram mensagem em vez de encerrar imediatamente o aplicativo. O aplicativo não anuncia cópia offline bem-sucedida se a persistência falhar.
- GitHub Actions: fluxo `validar-gestor20-sem-apk.yml` agora possui `concurrency` por branch, cancelando execuções antigas para reduzir consumo de cota. Continua executando compilação Kotlin, testes JVM e verificação de que não foi gerado APK.
- Provas anteriores: CI `43487893382` (comprovação de pedido manual) e `43488551171` (controle Realtime e testes) aprovadas. Última validação do refinamento do callback antigo: `43489225045`, **aprovada** (compilação Kotlin, testes JVM e ausência de APK).
- O app em produção, banco Supabase e pedidos reais não foram modificados por estas mudanças. Ainda não há comprovação de teste Android em aparelho, entrega UP ponta a ponta ou push com aplicativo fechado.

**Próximo gate:** testes instrumentados em ambiente Android seguro, validar PIN e reconexão após perda de rede, SMS/FCM, som Nokia sob restrições de Android e pedido manual por interface. Não distribuir APK final até concluir.

## Rodada — histórico paginado, entrega UP e FCM autenticado (13/11/2026)

Implementações salvas na branch **feature/gestor20-completo-sem-apk**:

- **Histórico real paginado**: filtro hoje/7/30/90 dias, janela temporal estável por busca, busca de páginas de 200 linhas usando `offset` do backend e botão Carregar mais; pedidos com IDs repetidos são mesclados sem duplicidade. Não inventar total de registros ainda não consultados.
- **Acompanhamento de despacho**: status UP/entrega própria, oferta ainda pendente e botão Recolher oferta somente antes do aceite. Todas as ações continuam sujeitas à validação do servidor, e não há localização GPS fictícia.
- **Regra de status centralizada**: aceite, preparo, pronto, retirada, despacho próprio e bloqueio de finalização de entrega UP controlados por `OrderFlowPolicy` com testes JVM.
- **Correção de dados nulos**: `courier_id` SQL NULL não pode virar texto `null` e bloquear despacho.
- **Registro FCM v2**: rota nova e aditiva `gestor-device-register-v2` criada e confirmada ACTIVE no Supabase; exige sessão da `gestor-app-api` e conta de operador válida. O app novo usa a v2, não a rota antiga sem autenticação. Diagnóstico de cadastro na tela de alertas.
- **Compatibilidade com produção**: `gestor-device-register` legado não foi desativado; nenhuma alteração aos pedidos reais ou instalações atuais. A retirada do legado deve ocorrer após a atualização dos aparelhos autorizados.
- CI base da etapa anterior aprovado em `43492071662` (Kotlin, JUnit e nenhum APK). Última rodada com autenticação FCM e políticas de fluxo em `43493839198`: consultar resultado antes de declarar validada.

**Ainda bloqueia o APK final:** teste no celular/emulador dos fluxos principais, impressão 58/80mm e PDF, teste de push com app aberto/fechado, som Nokia com economia de bateria, sessão e PIN, integração com UP com um entregador aprovado, pagamentos e reconciliação, cenário de perda/volta de internet, revisão de acesso de funcionários, assinatura e distribuição de release. Unidade Kotlin/CI verde não equivale a essa validação.
