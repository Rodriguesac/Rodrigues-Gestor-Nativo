# RAC Print 0.1.1 — instalação no Android 14

Se o Android perguntar "qual app usar" e depois não iniciar a instalação, salve o APK no aparelho e abra em **Gerenciador de arquivos > Downloads**, fora do ChatGPT. Em Configurações > Apps > Acesso especial > Instalar apps desconhecidos, autorize temporariamente o **Gerenciador de arquivos** (ou o navegador que fez o download). Instale apenas o APK publicado no GitHub do projeto. Se tiver tentado instalar uma versão anterior e o Android disser que já existe o aplicativo, desinstale a versão antiga antes de instalar a nova; builds de teste podem ter assinaturas diferentes.

O arquivo de instalação deve terminar em `.apk`, não em `.zip`, `.html` ou `.bin`.

# RAC Print — Android + Windows (v0.1.0)

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
