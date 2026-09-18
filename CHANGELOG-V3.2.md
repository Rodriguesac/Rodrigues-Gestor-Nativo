# Rodrigues Gestor 3.2 — híbrido

Interface local baseada no gestor20rac completo v4 (18/09), em WebView HTTPS com WebViewAssetLoader. Os dados continuam no Supabase e exigem sessão de operador. A interface também contém manifest e service worker para uso web; o APK não depende de service worker para tocar em segundo plano.

Android preserva FCM, serviço de pedidos, som, widget, painel flutuante, comando de voz e impressão nativa. Menu Mais → Recursos Android abre as ferramentas nativas já existentes. O controle de voz permanece protegido pelo fluxo GADM existente.

Correções: feed Supabase direto no código; falhas de consulta visíveis; aliases de status; cancelamento encerra toque do pedido correspondente; FCM atrasado não inicia toque por conta própria; aceitar pela notificação usa Supabase. Arraste interrompido não confirma ação. Cancelamento por prazo continua no servidor.

Validação 18/09/2026: o cancelamento automático de aceite está ativo no Supabase via pg_cron a cada minuto, com motivo "O tempo para aceite acabou, pedido cancelado automaticamente". A verificação confirmou o cron executando com sucesso e sem pedidos vencidos ainda pendentes no momento do teste. O app híbrido e o serviço Android consultam a mesma tabela de pedidos do Supabase.

Validação do app: testes unitários de status/prazo, compilação Android e testes locais de navegação, arraste, cancelamento, erro de conexão e integração de mensagens da interface.

Limite: as ferramentas legadas de despacho UP, alterações e presença permanecem disponíveis na área Android e exigem validação com os respectivos serviços e aparelho. Não substituir uma instalação em operação sem conferir assinatura do APK.
