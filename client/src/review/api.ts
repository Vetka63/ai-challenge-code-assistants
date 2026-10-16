import { api, type Source } from '../api'
export interface ReviewJob {
  id: string; origin: string; status: string; createdAt: string; updatedAt: string
  request: { repository: string; pr: number; baseSha: string; headSha: string; provider: string; runId: number | null }
  pr: null | { title: string; url: string; changedFiles: number }
  publishRequested: boolean; errorCode: string | null; error: string | null; commentUrl: string | null
  generationAttempts: string[]; limitations: string[]; sources: Source[]
  files?: { path: string; status: string; patch: string | null }[]
  report: null | {
    summary: string; coverage: string; limitations: string[]; sources: Source[]
    findings: { category: string; severity: string; path: string; line: number; side: string; title: string; explanation: string; recommendation: string; sourceIds: string[] }[]
    generation: { model: string; durationMs: number; usage: { inputTokens: number | null; outputTokens: number | null } }
  }
}
export interface ReviewSettings {
  repository: string; author: string; workflow: string; tokenConfigured: boolean; enabled: boolean; dailyAttemptLimit: number
  poll: { checkedAt: string | null; error: string | null; discovered: number }
}
export const reviewLabels: Record<string, string> = {
  QUEUED: 'В очереди', INDEXING: 'Снимок PR и RAG', ANALYZING: 'Анализ модели',
  REPORT_READY: 'Отчёт готов', PUBLISHING: 'Публикация', COMPLETED: 'Опубликовано',
  FAILED: 'Ошибка', INTERRUPTED: 'Прервано — нужен явный повтор', SUPERSEDED: 'Версия PR устарела'
}
export const analysisActive = (status: string) => ['QUEUED', 'INDEXING', 'ANALYZING', 'PUBLISHING'].includes(status)
/** Повторяются только GET состояния, не POST генерации. */
export const reviewsApi = {
  list: () => api<ReviewJob[]>('/reviews'),
  settings: () => api<ReviewSettings>('/reviews/settings')
}
