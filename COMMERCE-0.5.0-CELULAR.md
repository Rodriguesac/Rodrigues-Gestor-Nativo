# Rodrigues Commerce Gestor 0.5.0 — gerar APK pelo celular

O projeto antigo deste repositório foi preservado. O Commerce 0.5.0 fica separado em `commerce-0.5.0/`.

## No celular

1. Abra este repositório no GitHub.
2. Toque em **Actions**.
3. Abra **Build Rodrigues Commerce 0.5.0 APK**.
4. Toque em **Run workflow** e confirme.
5. Quando a execução ficar verde, abra a execução.
6. Em **Artifacts**, toque em **Rodrigues-Gestor-0.5.0**.
7. Baixe o ZIP do artifact.
8. Extraia `Rodrigues-Gestor-0.5.0.apk`.
9. Toque no APK para instalar.

O Android pode pedir autorização para instalar aplicativos provenientes do navegador/GitHub.

## Firebase / notificações com app fechado

O APK pode ser gerado mesmo sem Firebase.

Para push FCM funcionar quando o Gestor estiver totalmente fechado, adicione no GitHub Actions Secret:

`GOOGLE_SERVICES_JSON_B64`

Esse secret deve conter o conteúdo do `google-services.json` convertido para Base64.

No Supabase também é necessário configurar `FIREBASE_SERVICE_ACCOUNT_JSON` para o servidor enviar as notificações FCM.

Nunca coloque service-role, sb_secret ou conta de serviço Firebase dentro do código do aplicativo.
