import { api } from '../api'
export interface Settings { enabled: boolean; intervalSeconds: number; provider: string; dailyLimit: number }
export interface Evidence { id: string; path: string; hash: string; startLine: number; endLine: number; text: string }
export interface Claim { text: string; kind: string; sources: string[] }
export interface Level { id: string; name: string; kind: string; details: Claim[]; imageIds: string[] }
export interface Image { id: string; path: string; mime: string; width: number; height: number; bytes: number; linkedFrom: string[]; importedAt: string }
export interface Check {
  id: string; trigger: string; provider: string; status: string; createdAt: string; finishedAt: string | null
  snapshotId: string | null; fingerprint: string | null; fileHashes: Record<string, string>
  images: Image[]; sources: Evidence[]; changes: { path: string; kind: string }[]; warnings: string[]
  events: { stage: string; detail: string; at: string }[]; attemptedAt: string | null
  generation: null | { text: string; model: string; durationMs: number; finishReason: string; usage: { inputTokens: number | null; outputTokens: number | null } }
  draft: null | { title: string; overview: Claim[]; mechanics: Claim[]; levels: Level[]; questions: string[] }
  errorCode: string | null; error: string | null
  validationVersion?: number
}
export interface Overview { state: { settings: Settings; activeId: string | null; currentId: string | null; nextCheckAt: string | null; blockedFingerprint: string | null }; current: Check | null; history: Check[]; attemptsToday: number; budgetDay: string }
export const loadPassport = () => api<Overview>('/passport')
export const loadCheck = (id: string) => api<Check>('/passport/checks/' + id)
export const saveSettings = (settings: Settings) => api('/passport/settings', settings)
export const startCheck = (id: string, retryConfirmed = false) => api<Check>('/passport/checks', { id, retryConfirmed })
export const label = (value: string) => ({ CAPTURING: 'Сбор источников', ANALYZING: 'Модель составляет паспорт', COMPLETED: 'Версия сохранена', NO_CHANGES: 'Без изменений · без LLM', FAILED: 'Ошибка · прежняя версия сохранена', INTERRUPTED: 'Прервано перезапуском', BLOCKED: 'Нужно действие', DOCUMENTED: 'Описано в документации', CODE_PRESENT: 'Найдено в коде / конфигурации', REPORTED_TEST: 'Заявлено в отчёте о тесте', PLANNED: 'Запланировано', SCENE: 'Сцена сборки', MODE: 'Игровой режим', MANUAL: 'Вручную', SCHEDULED: 'По расписанию', ADDED: 'Добавлен', MODIFIED: 'Изменён', DELETED: 'Удалён' } as Record<string, string>)[value] || value
