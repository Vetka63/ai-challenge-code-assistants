package dev.aichallenge.assistants.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Серверная конфигурация. Ключ никогда не возвращается клиенту или модели. */
@Component
class RuntimeConfig(
    @Value("$" + "{ASSISTANTS_DB:data/assistants.sqlite}") val database: String,
    @Value("$" + "{PROJECT_MCP_URL:http://127.0.0.1:8684}") val mcpUrl: String,
    @Value("$" + "{OLLAMA_BASE_URL:http://127.0.0.1:11438}") val ollamaUrl: String,
    @Value("$" + "{EMBEDDING_MODEL:qwen3-embedding:0.6b}") val embeddingModel: String,
    @Value("$" + "{LOCAL_MODEL:qwen3:8b}") val localModel: String,
    @Value("$" + "{DEEPSEEK_API_KEY:}") val deepseekKey: String,
    @Value("$" + "{DEEPSEEK_MODEL:deepseek-v4-pro}") val deepseekModel: String
)
