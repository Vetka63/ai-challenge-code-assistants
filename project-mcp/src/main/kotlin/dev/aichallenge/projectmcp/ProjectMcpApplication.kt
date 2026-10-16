package dev.aichallenge.projectmcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Отдельный read-only MCP-процесс; не содержит LLM и не изменяет игру. */
@SpringBootApplication
class ProjectMcpApplication

/** Запускает HTTP MCP и служебный health endpoint. */
fun main(args: Array<String>) { runApplication<ProjectMcpApplication>(*args) }
