# Контекст проекта через MCP

ProjectGateway — порт прикладного контекста. McpProjectGateway использует
официальный клиент SDK и отдельный HTTP MCP-сервер, не читает игру напрямую.
Показываемые события initialize/tools/list/tools/call относятся к реальным
операциям. Короткие сеансы устойчивы к restart сервера без старого session ID.

project и branch относятся к игре; ветка ассистента не подставляется.
Dirty — диагностика, а freshness проверяется SHA и fingerprint документов.
После ответа повторная проверка MCP не уничтожает уже сохранённый текст.

Официальный SDK: https://java.sdk.modelcontextprotocol.io/v0.18.4/client/
