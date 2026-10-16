# Подключение моделей

Embeddings и Generator — независимые порты. ModelGateway реализует
Ollama /api/embed и /api/chat, а также DeepSeek chat/completions.
Поиск/индексация не обращаются к облачной модели.

Генерация вызывается один раз для явно выбранного провайдера.
Ключ передаётся только HTTP-заголовком Authorization. Текст вопроса,
источники и история передаются выбранной модели; для DeepSeek это
внешняя передача данных. Скрытого fallback/retry нет.
Usage — значения prompt/completion токенов из ответа API.

Источники интеграции:
https://docs.ollama.com/api/embed
https://docs.ollama.com/api/chat
https://api-docs.deepseek.com/api/create-chat-completion
