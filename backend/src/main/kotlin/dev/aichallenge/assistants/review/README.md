# Автоматическое ревью PR

models — контракт задания и отчёта. policy — доверие к repo/PR, unified diff,
структура ответа и проверка изменённых строк/источников. github — ограниченный
REST-клиент без произвольных URL и исполнения кода. rag — текстовый C# chunking
и отдельный индекс immutable-снимка. repository — SQLite/CAS/idempotency.
service — ReviewUseCase, worker и Markdown. api — тонкая HTTP-граница.

Поток: Actions artifact → poller → SQLite → snapshot → RAG → LLM → report →
публикация. Полная архитектура: docs/day32-architecture.md. Секреты и
сырой код PR не являются командами агента. /help не меняет свою политику.
