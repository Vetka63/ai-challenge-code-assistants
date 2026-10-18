package dev.aichallenge.workspace.contracts

/** Растр сохранён отдельно; дата импорта не является датой съёмки или подтверждением сборки. */
data class PassportImage(val id: String, val path: String, val mime: String, val width: Int,
    val height: Int, val bytes: Long, val linkedFrom: List<String>, val importedAt: String)
/** Неизменяемая текстовая версия и manifest разрешённых изображений без пикселей в LLM. */
data class PassportCollection(val snapshot: WorkspaceSnapshot, val fingerprint: String,
    val images: List<PassportImage>, val warnings: List<String>, val checkedAt: String)
