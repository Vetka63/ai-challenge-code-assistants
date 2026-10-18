# Сервис обращений и Support MCP

Kotlin/Spring Boot сервис, единственный владелец users.json и tickets/UUID.json.
REST API /internal/support обслуживает форму и JSON-историю. MCP /mcp открывает
только get_user_context, list_my_tickets и get_ticket_context.

SupportTickets — прикладной контракт обоих входов. JsonTicketRepository
обеспечивает process-lock, атомарную замену и revision compare-and-set.
Хранилище предназначено для одной реплики и вымышленных учебных данных.
Порт сервиса не публикуется на хост. Секретов, LLM и доступа к игре здесь нет.

Переменная SUPPORT_DATA задаёт каталог, по умолчанию /app/support-data.
Для сохранности нужен volume. Удаление volume удалит тикеты.
