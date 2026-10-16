package dev.aichallenge.assistants.common

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Один ресурсоёмкий запрос за раз; нет скрытой очереди запросов к GPU/API. */
@Component
class Jobs {
    private val gate = Semaphore(1)
    private val executor = Executors.newSingleThreadExecutor()
    /** Резервирует слот до сохранения операции; конфликт явно возвращается в UI. */
    fun reserve() { if (!gate.tryAcquire()) throw AssistantFailure("BUSY", "Другой запрос ещё выполняется. Дождитесь завершения.", 409) }
    /** Запускает уже сохранённую работу и обязательно освобождает слот. */
    fun start(action: () -> Unit) { executor.submit { try { action() } finally { gate.release() } } }
    /** Освобождает слот, если сохранение операции не удалось. */
    fun release() { gate.release() }
    /** Не оставляет фоновые потоки после остановки приложения. */
    @PreDestroy fun close() { executor.shutdownNow() }
}
