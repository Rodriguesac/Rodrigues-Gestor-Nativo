# Rodrigues Gestor — Android nativo

Aplicativo Android nativo do Gestor/Montador da Rodrigues Açaí e Cia, focado em operação rápida no celular.

## Firebase já encaixado

O arquivo `google-services.json` recebido foi colocado em `app/google-services.json` e o app usa o package cadastrado nele: `com.rodrigues.gestor`.

## O que esta versão já implementa

- Pedidos em tempo real pela coleção `pedidos` do Firestore.
- Compatibilidade com os campos usados pelo Gestor Mobile V2.7.
- Central mobile com Novos, Em preparo, Prontos, Entrega, Atrasados e vendas do dia.
- Navegação principal: Pedidos, Histórico, Produtos, Loja e Mais.
- Busca por pedido, cliente, telefone e item.
- Tela de pedido com fonte grande e sem a antiga conferência de itens.
- Aceitar pedido.
- Iniciar preparo.
- Marcar pronto.
- Confirmar retirada.
- Finalizar entrega.
- Cancelar pedido com motivo.
- Chat na coleção `chats`, compatível com o chat do Cliente.
- Lista de entregadores da coleção `entregadores`.
- Despacho manual para UP Entregas, criando `rides`, `rotas_entrega`, `corridas`, `alertas_operacionais` e `app_notifications` no formato já usado pelo Gestor web.
- Status e dados de Pagamento visíveis no pedido.
- Alerta nativo com toque selecionável e vibração.
- Aviso curto com som e vibração quando um pedido é cancelado, inclusive fora da tela.
- Ação **ACEITAR** diretamente na notificação.
- Firebase Cloud Messaging preparado e inscrição automática no tópico `gestor-pedidos`.
- Registro do token do aparelho em `gestor_dispositivos`.
- Workflow GitHub Actions para gerar APK sem Android Studio.

## Como gerar o APK com pouco trabalho

1. Criar/usar um repositório GitHub.
2. Subir todo o conteúdo deste ZIP na raiz do repositório.
3. Abrir **Actions → Gerar APK Rodrigues Gestor → Run workflow**.
4. Ao terminar, baixar o artefato **Rodrigues-Gestor-APK**.

O workflow também roda automaticamente após `push` na branch `main` ou `master`.

## Alerta quando o app está fora da tela

O app já recebe FCM. A pasta `backend-netlify` contém a função que envia `NEW_ORDER` para o tópico `gestor-pedidos`. A ativação do backend exige uma conta de serviço Firebase guardada somente nas variáveis protegidas do Netlify. Não coloque chave privada no APK.

## Versões de build

- Application ID: `com.rodrigues.gestor`
- minSdk: 23
- targetSdk: 36
- compileSdk: 36
- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Firebase BoM: 34.17.0
- Compose BoM: 2026.06.01 (Compose 1.11.4 — compatível com compileSdk 36 / AGP 8.13.2)


## v1.3.0 — Tema branco operacional

- Tema claro branco/cinza, com vermelho para ação principal.
- Saudação sem “Rodrigues Gestor” ou “Gestor” no topo.
- Pessoas online visíveis na Home.
- Status Novos / Em preparo / Prontos / Entrega viraram filtros tocáveis e compactos.
- Pedido continua abrindo em tela própria para operação.
- Linha de progresso mais limpa no detalhe.
- Segurar por aproximadamente 1 segundo para marcar pronto, confirmar retirada e finalizar entrega.
- Aceitar, iniciar preparo e chamar entregador continuam com 1 toque.
- FCM, Firestore, impressão, chat, Produtos, Loja, Histórico e UP Entregas preservados.

## v1.2.0 — Central de Pedidos Mobile

- Tema escuro operacional no estilo aprovado para uso no celular.
- Tela urgente dentro do app quando entra um novo pedido.
- Aceite rápido, visualização do pedido e opção de silenciar o toque sem cancelar.
- Home com métricas grandes: Novos, Preparando, Prontos, Entrega, Atrasados e Hoje.
- Histórico separado com busca e filtros Hoje / 7 dias / Todos.
- Produtos separado para pausa/reativação rápida.
- Loja separado para abrir, fechar, pausar temporariamente, definir preparo e mensagens.
- Mais concentra UP Entregas, Mensagens, alterações e configurações.
- Itens do pedido aparecem de forma limpa, sem repetir detalhes de cobertura/acompanhamentos embaixo do nome final.
- FCM, toque/vibração, impressão, chat, alterações, pagamentos e despacho manual preservados.
- Cancelamento, chat e seleção de entregador usam telas próprias para celular.

## v1.1.0 — Operação completa do Gestor

- Tema claro Rodrigues (roxo + verde), cards menores e textos sem colunas estreitas.
- Navegação: Pedidos, Entregas, Mensagens, Operação e Mais.
- Alertas configuráveis: som, vibração, repetição, limite, pedido sem resposta, mensagens, alterações, UP e pagamentos.
- Atrasos com destaque amarelo/vermelho.
- Operação da loja integrada ao documento `gadm_operacao/master` usado pelo Cliente.
- Abrir/fechar loja, pausa temporária, tempo de preparo e mensagens da operação.
- Pausa rápida de produtos em `catalogo_produtos`.
- Chat do cliente + respostas rápidas.
- Solicitações de alteração (`alteracoes_pedido`) com aprovar/recusar e proposta da loja sujeita à decisão do cliente.
- Impressão de comanda 58/80 mm, 1–3 vias, com opção de imprimir ao aceitar.
- Pagamento: confirmar pago/pendente.
- UP Entregas: lista de entregadores e despacho manual preservado.
- Códigos de retirada/entrega e entregador no pedido quando disponíveis.
- Problema operacional por pedido, com resolução simples.
- Saúde do aparelho: internet, notificações e bateria.
- Contador de clientes no site via coleção `presenca_site` (requer Cliente V22.1 com `presence-v22.js`).
- Sem histórico de ações de funcionários.
- Sem PIN para cancelamento.
