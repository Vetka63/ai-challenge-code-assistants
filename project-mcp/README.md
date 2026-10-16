# Read-only Project MCP

Отдельный Kotlin/Spring Boot процесс с официальным MCP Java SDK 0.18.4.
McpConfiguration регистрирует пять tools. ProjectFiles владеет allowlist,
каноническим корнем, чтением Git и согласованным снимком документов.
MCP не вызывает LLM и не строит индекс.

McpProtocolTest проверяет настоящие HTTP initialize/tools/list/tools/call.
Файловые и Git-тесты используют временный fixture-проект, не игру пользователя.
