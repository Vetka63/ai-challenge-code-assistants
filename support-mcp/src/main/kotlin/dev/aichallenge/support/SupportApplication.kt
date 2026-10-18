package dev.aichallenge.support

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Отдельный владелец JSON: REST для формы, read-only MCP для агента. */
@SpringBootApplication
class SupportApplication
fun main(args: Array<String>) { runApplication<SupportApplication>(*args) }
