# Homologação operacional: Rodrigues Gestor + UP Entregas

**Ambiente:** Rodrigues Açaí e Cia, site em manutenção, backend Supabase operacional atualizado. Não liberar pedidos públicos nem realizar cobrança real.

## Build identificável
- Rodrigues Gestor: usar artefato **Rodrigues-Gestor-3.3.2-Mobile-APK** do workflow **Gerar APK Rodrigues Gestor Hybrid**, executado especificamente na branch `rebuild/gestor-integrado-20261030`.
- UP Entregas: usar artefato **UP-Entregas-APK** do workflow **Gerar APK UP Entregas**, executado na branch `rebuild/up-core-integration-20261030` e verificar que o build corresponde ao commit `640f7c399a5ded71b03680356690f7e41ba6c39a`.
- O `UP-Gestor-APK` do repositório UP Entregas **não é** o app Rodrigues Gestor. Não trocar os aplicativos.
- Android packages: `com.rodrigues.gestor` e `com.rodriguesacai.entregador`.
- APKs de desenvolvimento são `assembleDebug`; possível incompatibilidade de assinatura com instalações anteriores. Não desinstalar nem limpar dados sem backup.
- O teste físico exige permissões de notificações e localização apropriadas, sem expor token, senha ou chaves na conversa.

## Roteiro com dois telefones
1. Identificar as versões instaladas nos dois aparelhos e, se seguro, instalar os APKs homologados.
2. No Gestor: autenticar, verificar tela de pedidos, estado de manutenção e conectividade.
3. No UP Entregas: autenticar entregador de teste aprovado, verificar status e opção online. Conceder GPS somente durante a missão conforme necessidade.
4. Criar pedido sintético no site em manutenção, sem cobrar de verdade (pagamento em dinheiro/teste). Confirmar que aparece uma única vez no Gestor.
5. Aceitar, iniciar preparo e marcar pronto. Oferecer exclusivamente ao entregador de teste.
6. No UP: conferir notificação, oferta, aceite único, ida à loja, chegada e retirada.
7. No cliente: rastreamento só aparece **após** retirada e autorização do Gestor; antes disso, nenhuma coordenada é exposta. A posição `0,0`, nula, antiga ou de outra corrida nunca aparece.
8. Confirmar chegada e entrega com código de teste quando aplicável. Verificar status final em todos os sistemas e nenhuma posição pública após concluir.
9. Testar recusa/reoferta, app em segundo plano, rede oscilante e cancelamento **em pedido sintético distinto**.
10. Pagamento InfinitePay é uma etapa separada: jamais pagar ou simular estorno como concluído sem confirmação externa.

## Evidências e critérios
Anotar apenas: data, versão do APK, aparelhos Android (modelo/versão), etapas que passaram/falharam, captura de tela sem dados pessoais. Não divulgar PIN, senhas, tokens, telefone ou endereço de terceiros.

**Sem aprovação automática:** build bem-sucedido e HTTP 200 não provam homologação ponta a ponta. A loja continua em manutenção até todos os critérios essenciais serem aprovados.
