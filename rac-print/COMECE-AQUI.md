# Atualização para impressão com mais contraste (v0.2.1)

A foto da Bematech comprovou que a impressão funciona, mas está muito clara. O receptor Windows foi atualizado para converter tons de cinza em **preto puro e branco puro** antes de enviar ao driver. Isso melhora o contraste de PDFs térmicos, sem garantia de corrigir cabeçote, papel ou ajuste físico de densidade.

**É preciso atualizar somente o receptor Windows** para obter esta correção de contraste; o RAC Print Android v0.2.0 continua compatível. Pare o receptor em execução no PC, substitua o executável na mesma pasta e abra novamente. O IP, a chave e a impressora selecionada ficam preservados na configuração de usuário em `%APPDATA%\\RAC Print\\config.json`. Não deixe duas instâncias executando. Se o cupom permanecer claro, aumente a **densidade/escuridão/energia** no driver da Bematech e confira a qualidade do papel e cabeçote. A versão 0.2.1 da impressão pode alterar a aparência de fotos e logotipos, pois prioriza preto e branco para texto.

# RAC Print 0.2.1 — impressão mais escura no receptor Windows

O aplicativo agora declara um **serviço de impressão do Android**. Quando o iFood abrir a tela de impressão do Android (mesmo se a opção inicial for "Salvar como PDF"), a impressora **RAC Print — impressora do PC** poderá aparecer na lista.

## Configuração inicial, uma única vez

1. No Windows, com a Bematech MP-4200 TH ligada por USB e driver instalado, execute o receptor RAC Print, escolha a Bematech e configure o início automático (veja o instalador Windows).
2. No Android, instale o arquivo APK **v0.2.0**. Se o instalador não abrir dentro do ChatGPT ou navegador, abra o APK baixado pelo **Gerenciador de arquivos > Downloads** e autorize a instalação dessa fonte.
3. Abra RAC Print, informe o **IP local** e a **chave** mostrados no receptor Windows, e toque em **Verificar conexão**.
4. Toque **Ativar RAC Print nas impressoras do Android**. Na tela do sistema, habilite **RAC Print** (o nome exato do menu varia conforme o aparelho).
5. No iFood, abra o pedido e escolha **Imprimir**. Na pré-visualização do Android, toque na lista de destinos (talvez comece em **Salvar como PDF**), selecione **RAC Print — impressora do PC**, e confirme a impressão.

Depois da configuração, o PC só precisa permanecer **ligado com o receptor em execução** e o celular deve estar na mesma rede Wi-Fi. Não é necessário abrir o RAC Print para cada pedido.

**Limitação real do iFood:** isto funciona quando o aplicativo aciona a tela de impressão do Android. Caso o iFood ofereça exclusivamente "baixar arquivo", o Android não permite a outro aplicativo substituir silenciosamente esse fluxo; nesse caso use Compartilhar/Abrir com RAC Print.

**Limitação de segurança:** a conexão usa HTTP com chave apenas dentro da rede local privada. Não exponha a porta 8765 à internet. O serviço não imprime automaticamente quando chega pedido: ele executa o trabalho depois que o usuário confirma **Imprimir** no aplicativo de origem.

**Estado:** fonte implementada para 0.2.0; qualquer APK precisa passar pelo GitHub Actions; a saída real da Bematech MP-4200 TH ainda deve ser validada, principalmente formato da bobina e corte do papel.

---

# RAC Print — instalação no Android 14

Se o Android perguntar "qual app usar" e depois não iniciar a instalação, salve o APK no aparelho e abra em **Gerenciador de arquivos > Downloads**, fora do ChatGPT. Em Configurações > Apps > Acesso especial > Instalar apps desconhecidos, autorize temporariamente o **Gerenciador de arquivos** (ou o navegador que fez o download). Instale apenas o APK publicado no GitHub do projeto. Se tiver tentado instalar uma versão anterior e o Android disser que já existe o aplicativo, desinstale a versão antiga antes de instalar a nova; builds de teste podem ter assinaturas diferentes.

O arquivo de instalação deve terminar em `.apk`, não em `.zip`, `.html` ou `.bin`.

# RAC Print — Android + Windows (v0.2.0)

Alternativa gratuita para imprimir arquivos do Android em impressoras USB instaladas no Windows, incluindo Bematech MP-4200 TH. **Versão inicial: não testada em impressora física.**

## Instalação (somente com celular + PC ligado)

1. No GitHub Actions, baixe os artefatos **RAC-Print-Android-APK** e **RAC-Print-Receptor-Windows** da última execução bem-sucedida.
2. Instale o APK no Android. Ao solicitar permissão para instalar apps dessa fonte, autorize apenas se reconhecer o arquivo deste projeto.
3. No Windows (primeira vez), extraia o ZIP do receptor para uma pasta permanente. Execute `INSTALAR-WINDOWS.bat` — isso abre o receptor e cria um atalho para iniciar junto com o Windows.
4. Se o Firewall do Windows perguntar, permita o acesso **somente nas redes privadas**, nunca públicas.
5. Com PC e celular no mesmo Wi-Fi, copie do receptor o IP e a chave. Cole os dois no app Android e toque em **Verificar conexão**.
6. Selecione um PDF/PNG/JPG/WEBP/TXT, cole um texto de cupom ou compartilhe o documento de outro app com **RAC Print**, e toque em **Imprimir**.

## Observações importantes

- Precisa do PC ligado, com a impressora conectada e driver Windows instalado. **Não imprime sem o receptor em execução**.
- A chave de conexão permanece no PC; é exigida em todas as solicitações de impressão. É tráfego HTTP **apenas pela rede Wi-Fi local**, não VPN, internet pública ou nuvem. Não use em Wi-Fi compartilhado com desconhecidos; dados não são criptografados na rede.
- O servidor anuncia o IP local e usa a porta `8765`. Se o Windows estiver em rede pública ou barrar a porta, troque a rede para "Privada" após confirmar que é confiável.
- Somente PDF é enviado ao receptor. O PDF original é preservado em trânsito; imagens e cupons digitados são convertidos em PDF no Android, sem capturas de tela.
- A impressora térmica não entende PDF nativo; o programa o renderiza para o driver em resolução apropriada. Assim, a qualidade depende da configuração do papel e do driver.
- Na Bematech, escolha no Windows o tamanho de papel do cupom (usualmente 80 mm). O driver também pode necessitar modo ESC/POS.
- "Enviado ao spooler" não garante saída física; confira papel, USB e fila de impressão.
- Arquivos acima de 15 MB e PDFs com mais de 40 páginas não são suportados. Cópias: máximo de 3.
- A instalação do Windows exige execução inicial uma vez; é desnecessário Android Studio ou VS Code para instalar os pacotes prontos.

## Desenvolvimento e testes

- Android: Java nativo, sem SDKs externos em runtime; pacote `br.com.rac.print`.
- Windows: Python 3.11, PyInstaller, PyMuPDF, pywin32 e Pillow.
- `python -m unittest discover -s rac-print/tests -v`
- `gradle -p rac-print/android :app:assembleDebug`
- O build automatizado é feito por `.github/workflows/rac-print.yml` na branch `rac-print`.

## Segurança

- Não exponha a porta 8765 para a internet ou configure redirecionamento de porta no roteador.
- Tráfego local não criptografado. Uma versão futura pode parear por QR e usar TLS local.
- PDFs são mantidos em memória apenas durante a impressão, sem gravar cópias no disco do receptor.
- Código aberto; sem assinatura, sem impressão remota e sem coleta de dados.
