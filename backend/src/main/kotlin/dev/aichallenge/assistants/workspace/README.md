# Workspace агент

WorkspaceAgent владеет сценарием выполнения цели и сохранённой историей.
WorkspaceController адаптирует HTTP для Vue и CLI. WorkspaceGateway отделяет
управление снимками от MCP-сессии, жёстко привязанной к snapshot/run.

Файлы принадлежат Workspace MCP, не backend. Контракты — workspace-contracts.
Состояние запуска — SQLite kind workspace-run. Общие ModelGateway и Jobs
не знают правил файлового домена.

Полный алгоритм и ограничения: docs/day34-architecture.md в корне репозитория.
