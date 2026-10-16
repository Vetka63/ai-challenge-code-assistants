# Настройка GitHub токена для локального ревью

Токен вводится только на своём ПК через скрытый PowerShell prompt. Не
передавайте его в чат, коммиты, скриншоты или frontend. DeepSeek-ключ
остаётся отдельным секретом, GitHub Action его не получает.

## Создать токен

1. Откройте https://github.com/settings/personal-access-tokens/new.
2. Название: AI Challenge Day 32. Срок действия: например, 30 дней.
3. Resource owner: Vetka63.
4. Repository access: Only select repositories → ai-challenge-game.
5. Repository permissions:
   - Actions: Read.
   - Contents: Read.
   - Pull requests: Read and write.
   - Metadata: автоматически Read.
6. Нажмите Generate token. Скопируйте значение, но не показывайте его
   на записи экрана. Широкий classic PAT и доступ ко всем repo не нужны.

## Сохранить на ПК

В PowerShell выполните:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "F:\ai-advent-challenge\ai-challenge-code-assistants\scripts\Set-Day32GitHubToken.ps1"

Вставьте токен в скрытый запрос и нажмите Enter. Символы не отображаются.
Скрипт сохраняет .local/day32/github-token, исключённый из Git.
Разрешение выполнения сценария действует только в этом процессе.
Файл монтируется в backend read-only, значение перечитывается при запросах:
после замены токена перезапускать backend не нужно.

## Проверить

1. Откройте http://localhost:8683/#reviews.
2. Убедитесь, что написано «Токен сохранён на backend».
3. Нажмите «Проверить подключение». Это проверка чтения GitHub, без LLM.
4. Право публикации проверяется при отправке комментария, без фиктивной
   пробной записи. Успешное чтение не доказывает право записи.
5. До публикации workflow оставьте автоматику выключенной.

401: срок действия или значение токена. 403: права или rate limit.
404: доступ к repo, номер PR или workflow. Ошибка не выводит секрет.
Случайно опубликованный токен нужно отозвать на GitHub и создать новый.
Для остановки фоновых проверок отключите автоматику в UI.
