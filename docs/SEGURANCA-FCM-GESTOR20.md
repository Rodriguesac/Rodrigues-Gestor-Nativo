# Segurança FCM / cadastro de aparelhos do Gestor20

## Situação (13/11/2026)

A rota antiga `gestor-device-register` permite registrar qualquer token de
FCM a partir de um corpo de solicitação com `package=com.rodrigues.gestor`.
Isso **não prova** que o aparelho pertence a um gestor autorizado.

**Não desativamos a rota antiga nesta etapa**, porque o aplicativo já usado na
loja pode depender dela para registrar novos tokens. Desativar imediatamente
poderia interromper notificações no expediente.

## Nova rota segura — criada e ativa

- Edge Function adicional: `gestor-device-register-v2` (`ACTIVE`, versão 1).
- Exige sessão de alta entropia da mesma `gestor-app-api`, valida
  SHA-256 da sessão em `security_gestor_app_sessions` (app_documents),
  prazo de validade e conta de funcionário ativa com versão de permissão igual.
- `service_role` continua somente no servidor.
- Registra token em `fcm_devices`, sem salvar sessão/PIN no aparelho nem
  divulgar o token em logs.
- `verify_jwt=false` somente porque usa autenticação **própria** via sessão
  longa do Gestor20; não é uma rota de registro anônimo.
- O aplicativo Android novo usa essa rota, com diagnóstico do último cadastro.
- O build de prévia continua sem registrar token na operação real.

**Validação feita**: função ativa e conteúdo verificados via Supabase MCP.
Ainda é necessário executar testes HTTP de recusa de sessão inválida,
cadastro com sessão autorizada em um aparelho de testes, envio de
notificação FCM ao token real e verificação de clique/toque em Android.
Não afirmar que o envio FCM está aprovado até essas verificações.

## Retirada segura da rota antiga

Somente depois de distribuir a atualização assinada e confirmar que os
aparelhos ativos migraram para a v2:
1. conferir tokens registrados, versões e eventos FCM sem expor clientes;
2. revalidar o fluxo do usuário gestor e envio de notificações com app fechado;
3. desligar a rota legada e evitar novas inscrições anônimas;
4. auditar e desativar tokens de origem não reconhecida, preservando os ativos legítimos.

A rota antiga permanece um risco até a conclusão dessa transição.

## Teste HTTP externo da v2

GitHub Actions `43495421742` **aprovado**: a rota implantada respondeu com
401 sem sessão, 401 para sessão inventada, 403 para nome de app incorreto
e 405 para acesso por GET. Nenhum token de FCM real foi usado.
Consulta posterior em `fcm_devices` confirmou 0 registros v2 (esperado).
A rota antiga não foi alterada e continua atendendo a base existente.
**Ainda não testado:** registro positivo com sessão de funcionário real,
recebimento da mensagem no Android e desativação segura do endpoint legado.
