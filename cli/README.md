# Терминальный клиент ассистента

Kotlin CLI использует общий HTTP API backend дня 34.
Он не вызывает модель напрямую и не получает LLM-ключи.

Команды: help, snapshot, snapshots, run, continue, runs, show, artifacts, export.
Gradle :cli:installDist собирает JVM-дистрибутив с assistant и assistant.bat.
Dockerfile.cli упаковывает тот же клиент без установки JDK на хост.
scripts/Assistant.ps1 запускает его во внутренней Compose-сети.

Примеры и проверка: docs/day34-guide.md.
