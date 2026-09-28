# Rodrigues Gestor 3.1

Escopo desta alteração: primeira lista de funções, menu com quatro opções e primeira lista adicional aprovada. A última lista de sugestões da conversa foi excluída.

Implementado nesta versão: Pedidos/Cardápio/Histórico/Mais; cadastro e edição de produtos e ingredientes; pausa/ativação; comanda organizada; modo Montador; aceite, preparo, pronto, entrega e conclusão com proteção contra mudança acidental; rejeição com motivo; chat; vínculo e localização de entregador; impressão; pedido manual com rascunho e identificador estável; registro por operador; perfis dono/atendente/montador com autorização no servidor; fechamento por período; edição de cupons e banners; configuração/teste de som, volume e vibração; central de pendências; aviso de possível duplicidade; estado de conexão e recuperação visual de erro.

A versão usa um endpoint próprio (gestor-app-api) e mantém as APIs anteriores disponíveis. O aplicativo Android usa a mesma sessão nas telas, monitoramento e aceite pela notificação. Não altera regras RLS nem remove pedidos antigos.

## Limites conhecidos

- Vincular um entregador registra o responsável; o acionamento de corrida no UP Entregas ainda precisa ser integrado ao protocolo vigente.
- Chromecast com receptor próprio não foi implementado nesta alteração.
- Pausa automática por prazo e configuração de taxa de entrega ainda não estão ligadas à interface.
- Pedido manual usa entrega ou retirada; balcão como modalidade independente ainda não está incluído.
- A central não calcula mensagens não lidas: essa contagem exige estado de leitura por operador.
- A abertura no WebView é testada em emulador. Entrega FCM com tela apagada, impressora física e aparelhos Xiaomi requerem ensaio no aparelho.
- O app usa consulta periódica (8 segundos na tela; 5 segundos no serviço Android), não assinatura Realtime.
- Os serviços legados de produtos e banners podem manter espelhos no Firestore. É preciso validar visualmente o catálogo no site cliente após editar; esta versão altera as fontes Supabase existentes.

## Validação

- Testes automatizados de normalização, status, limite de aceite, detecção de duplicidade e escape da comanda.
- Fluxos React testados contra respostas simuladas, sem alterar pedidos de clientes.
- Compilação Android e teste de abertura do React embutido em Android 35 via CI.

Esta entrega não deve ser rotulada como sistema completo homologado enquanto os limites acima permanecerem.
