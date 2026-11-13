# Gestor20 RAC — critérios para entrega final

Esta branch (**feature/gestor20-completo-sem-apk**) é de desenvolvimento contínuo e **não** dispara a geração automática de APK. O usuário solicitou receber o APK apenas quando o aplicativo estiver completo e validado.

## Feito nesta etapa
- O PIN de primeiro acesso é verificado no backend antes de ser criptografado pelo Android Keystore e antes de liberar a interface.
- PIN já vinculado ao aparelho permanece protegido no Keystore e permite consultar pedidos que tenham sido previamente salvos durante uma queda de conexão.
- Histórico real: consulta a `gestor-app-api`, usa a mesma normalização dos pedidos e salva o retorno localmente para consulta offline.
- Pedidos ativos e encerrados são separados na interface. A busca atual é dos últimos 30 dias, página de até 200 registros, com limitação indicada em tela.
- Ações de pedido não usam uma fila de envio offline: só mostram sucesso após confirmação online.

## Ainda NÃO pronto para entregar
- Histórico com paginação completa e filtros por período.
- Pedido manual com montagem, atendimento por chat, negociação, cancelamentos e financeiro totalmente nativos.
- Fluxo UP (oferta, recusa, recolhimento, estados, falhas e conflitos) validado de ponta a ponta em ambiente seguro.
- Sessão longa por credencial renovável, evitando depender continuamente de PIN salvo.
- Push da versão de teste, toque e repetição sob todas as restrições de Android recentes.
- Testes instrumentados, testes automatizados de backend, assinatura de release, atualização e distribuição segura.
- Auditoria de segurança e testes reais autorizados no celular sem prejudicar pedidos da loja.

**Não gerar nem distribuir APK de produção apenas por uma compilação ter passado.**
