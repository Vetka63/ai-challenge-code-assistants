package dev.aichallenge.assistants

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Модульное приложение; каждый прикладной ассистент владеет своими политиками. */
@SpringBootApplication
class AssistantsApplication

/** Запускает API, не запускает Unity и не изменяет игровой проект. */
fun main(args: Array<String>) { runApplication<AssistantsApplication>(*args) }
