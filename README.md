# Gerenciador de Arquivos (v0.5)

Esqueleto de um gerenciador de arquivos Android moderno (Kotlin + Jetpack Compose + Material 3).

**Fase 1:** pedir acesso ao armazenamento, navegar por pastas, mostrar/ocultar arquivos ocultos, tema dinâmico claro/escuro.

**Fase 2:** seleção múltipla (toque longo), copiar, mover, renomear, excluir, nova pasta, abrir arquivos em outros apps, busca recursiva, ordenação (nome/data/tamanho) e atalhos de pastas.

**Fase 3:** lixeira (restaurar, excluir de vez, esvaziar), compactar em ZIP, extrair ZIP (toque no .zip ou menu da seleção) e compartilhar arquivos com outros apps.

**Fase 4:** categorias (Imagens, Vídeos, Áudio, Documentos, APKs), analisador de espaço, localizador de duplicados (SHA-256) e detalhes de itens/pastas.

**Tela inicial:** cartões de armazenamento e memória, botões redondos (ferramentas, pastas, mídia), arquivos recentes e menu lateral.

## Compilar na nuvem (GitHub Actions)
1. Crie um repositório no GitHub e envie todos os arquivos desta pasta (inclusive `.github`).
2. Abra a aba **Actions**. O workflow **Build APK** roda a cada push (ou use *Run workflow*).
3. Quando terminar (5 a 10 min na primeira vez), baixe o artefato **app-debug-apk** no fim da página da execução.
4. Descompacte o zip e instale o `app-debug.apk` no aparelho.

## Compilar no Android Studio
Abra esta pasta (*File > Open*), aguarde a sincronização do Gradle e use *Run*.

## Próximas fases
7z/RAR/TAR, rede (SMB/FTP/SFTP/WebDAV), servidor HTTP local, editor de texto e cofre.
