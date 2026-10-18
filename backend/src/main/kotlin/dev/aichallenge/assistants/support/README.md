# Домен поддержки

SupportAgent владеет сценарием вопроса: проверка профиля и тикета, сохранение
вопроса, обязательный MCP-контекст, локальный FAQ retrieval и выбранная LLM.
SupportKnowledge хранит отдельный индекс. HttpSupportData обращается к владельцу
JSON по REST, McpSupportContext получает обстоятельства через официальный MCP SDK.
Контроллеры не читают JSON-файлы и не собирают prompt.

Контракты в support-contracts, сервис-владелец в support-mcp.
Подробности: docs/day33-architecture.md и docs/day33-guide.md.
