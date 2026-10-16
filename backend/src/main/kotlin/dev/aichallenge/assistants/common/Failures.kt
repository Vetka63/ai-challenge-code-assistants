package dev.aichallenge.assistants.common

/** Безопасная ошибка с машинным кодом и понятным пользователю объяснением. */
class AssistantFailure(val code: String, override val message: String, val status: Int = 400) : RuntimeException(message)
