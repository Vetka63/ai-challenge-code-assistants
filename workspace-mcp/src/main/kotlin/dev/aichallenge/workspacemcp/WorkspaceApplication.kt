package dev.aichallenge.workspacemcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Отдельная файловая граница без ключей LLM и без RW-монтирования игры. */
@SpringBootApplication
class WorkspaceApplication
/** Запускает внутренний REST и MCP Streamable HTTP. */
fun main(args: Array<String>) { runApplication<WorkspaceApplication>(*args) }
