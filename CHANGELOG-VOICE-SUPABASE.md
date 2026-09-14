# Rodrigues Gestor Nativo v1.3.5 — Voz + Supabase

Base preservada: commit `4008e97951b295317ffd948379ddeb0bdae854df` (Rodrigues Gestor Nativo v1.3.1).

## Controle por voz no Android

- Botão flutuante **COMANDO DE VOZ** dentro do Gestor.
- Reconhecimento em português pelo serviço de voz disponível no Android.
- Atalho do aplicativo **Falar** abre diretamente o reconhecimento.
- Resposta visual e falada após cada comando.
- Comandos: abrir/fechar loja e pausar/reativar produto ou adicional pelo nome.
- Exemplos: “fechar a loja”, “abrir a loja”, “pausar a maçã que acabou” e “reativar maçã”.

## Supabase dinâmico

- Nenhum nome de produto ou adicional fica gravado no APK.
- Cada comando consulta o catálogo atual do Supabase.
- Produtos usam a tabela `products` e sincronizam a cópia `app_documents/catalogo_produtos`.
- Adicionais são procurados nas coleções administrativas do catálogo.
- Abrir/fechar atualiza `store_settings/operacao` e `app_documents/gadm_operacao/master`.
- Itens ambíguos não são alterados; o app pede o nome completo ou o tamanho.
- O PIN do GADM é usado apenas para criar uma sessão administrativa e não é salvo.

## Versão

- versionCode: 12
- versionName: 1.3.5-voice-supabase
