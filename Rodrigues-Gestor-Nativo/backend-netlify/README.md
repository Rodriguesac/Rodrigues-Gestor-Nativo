# Backend FCM do Rodrigues Gestor

O APK já se inscreve no tópico `gestor-pedidos`. Esta função é a ponte para tocar o aparelho mesmo quando o Gestor não está na tela.

Para ativar em produção depois:

1. Criar uma conta de serviço no projeto Firebase `rodrigues-d6566`.
2. No Netlify, criar a variável protegida `FIREBASE_SERVICE_ACCOUNT_JSON` com o JSON completo da conta de serviço.
3. Opcionalmente criar `GESTOR_NOTIFY_SECRET`.
4. O Cliente chama `/.netlify/functions/notify-gestor` após o pedido ser gravado no Firestore.

Não coloque a chave privada dentro do APK nem em JavaScript público.
