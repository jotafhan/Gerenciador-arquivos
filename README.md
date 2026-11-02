# Gerenciador de Arquivos (fase 1)

Esqueleto de um gerenciador de arquivos Android moderno (Kotlin + Jetpack Compose + Material 3).

**Fase 1 faz:** pedir acesso ao armazenamento, navegar por pastas, ordenar (pastas primeiro), mostrar/ocultar arquivos ocultos, tema dinâmico claro/escuro.

## Compilar na nuvem (GitHub Actions)
1. Crie um repositório no GitHub e envie todos os arquivos desta pasta (inclusive `.github`).
2. Abra a aba **Actions**. O workflow **Build APK** roda a cada push (ou use *Run workflow*).
3. Quando terminar (5 a 10 min na primeira vez), baixe o artefato **app-debug-apk** no fim da página da execução.
4. Descompacte o zip e instale o `app-debug.apk` no aparelho.

## Compilar no Android Studio
Abra esta pasta (*File > Open*), aguarde a sincronização do Gradle e use *Run*.

## Próximas fases
Copiar/mover/excluir, busca, categorias, compactação (ZIP/7z), rede (SMB/FTP/SFTP/WebDAV), servidor HTTP local, editor de texto e cofre.
