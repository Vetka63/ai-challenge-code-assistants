package dev.aichallenge.assistants

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Модульное приложение; каждый прикладной ассистент владеет своими политиками. */
@SpringBootApplication
@EnableScheduling
class AssistantsApplication

/** Запускает API, не запускает Unity и не изменяет игровой проект. */
fun main(args: Array<String>) { runApplication<AssistantsApplication>(*args) }
