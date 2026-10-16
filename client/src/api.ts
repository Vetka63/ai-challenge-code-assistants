export interface Context { project: string; branch: string | null; sha: string; fingerprint: string; dirty: boolean; fileCount: number }
export interface Event { method: string; status: string; durationMs: number; details: string; at: string }
export interface Source { id: string; cosine: number; origin?: string; chunk: { chunkId: string; path: string; section: string; startLine: number; endLine: number; text: string; status: string; fileHash: string } }
export interface Turn {
  id: string; question: string; answer: string; provider: string; status: string; createdAt: string
  context: Context | null; sources: Source[]; events: Event[]; citationStatus: string
  indexId: string | null; error: string | null; errorCode: string | null
  indexContext?: Context | null; warnings?: { code: string; message: string }[]
  generation: null | { model: string; durationMs: number; finishReason: string; usage: { inputTokens: number | null; outputTokens: number | null } }
}
export interface Chat { id: string; title: string; createdAt: string }
export interface Index {
  id: string; status: string; processed: number; total: number; error: string | null
  context: Context | null; identity: { model: string; digest: string } | null; documents: string[]; events: Event[]
}
export interface Config { localModel: string; deepseekModel: string; cloudAvailable: boolean; embeddingModel: string }
/** API errors remain visible, and HTML error pages are never rendered as markup. */
export async function api<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch('/api' + path, body === undefined ? undefined : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  })
  const data = await response.json().catch(() => ({ message: 'Сервер вернул некорректный ответ.' }))
  if (!response.ok) throw new Error(data.message || 'Запрос не выполнен.')
  return data as T
}
export const statuses: Record<string, string> = {
  RUNNING: 'Выполняется', COMPLETED: 'Ответ получен', FAILED: 'Не выполнено',
  UNKNOWN: 'Нужны уточнения', INTERRUPTED: 'Прервано перезапуском', TRUNCATED: 'Ответ обрезан',
  BUILDING: 'Индексируется', READY: 'Готов'
}
export const statusLabel = (status: string) => statuses[status] || status
export const stale = (current: Context | null, indexed: Context | null | undefined) =>
  Boolean(current && indexed && (current.sha !== indexed.sha || current.fingerprint !== indexed.fingerprint))
