import type { Config } from '../api'
export interface SupportUser { id: string; displayName: string; platform: string }
export interface TicketDraft { id: string; title: string; description: string; gameVersion: string; platform: string; kind: string }
export interface TicketSummary { id: string; title: string; kind: string; gameVersion: string; platform: string; turnCount: number; updatedAt: string }
export interface TicketContext {
  user: SupportUser; ticketId: string; title: string; description: string; gameVersion: string; platform: string
  recentConversation: { question: string; answer: string }[]; ticketVersion: number
}
export interface SupportSource { id: string; path: string; section: string; startLine: number; endLine: number; chunkId: string; fileHash: string; text: string; cosine: number }
export interface SupportTurn {
  id: string; question: string; provider: string; status: string; answer: string; context: TicketContext | null
  sources: SupportSource[]; events: { method: string; status: string; durationMs: number; details: string }[]
  citationStatus: string; knowledgeVersion: string | null; error: string | null; errorCode: string | null
  candidateAnswer?: string | null
  generation: null | { model: string; inputTokens: number | null; outputTokens: number | null; durationMs: number }
}
export interface SupportTicket extends TicketSummary { userId: string; description: string; version: number; turns: SupportTurn[] }
export interface KnowledgeView { id: string; status: string; processed: number; total: number; error: string | null; documents: string[] }
export interface Knowledge { latest: KnowledgeView | null; ready: KnowledgeView | null; knowledgeVersion: string }
export interface Receipt { user: string; ticket: string; id: string; question: string; provider: string }
/** Demo identity передаётся заголовком, а не включается в произвольный LLM context. */
export async function supportApi<T>(path: string, user?: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (user) headers['X-Demo-User'] = user
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch('/api/support' + path, { headers, method: body === undefined ? 'GET' : 'POST',
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const data = await response.json().catch(() => ({ message: 'Некорректный ответ сервера поддержки.' }))
  if (!response.ok) throw new Error(data.message || 'Запрос поддержки не выполнен.')
  return data as T
}
export function supportRoute(hash: string): { user: string; ticket: string } | null {
  const match = /^#support\/(demo-[a-z]{2,20})\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(hash)
  return match ? { user: match[1]!, ticket: match[2]! } : null
}
/** Скачать отчёт можно только с текущей demo identity, не открытым URL чужого тикета. */
export async function downloadTicket(user: string, id: string) {
  const response = await fetch('/api/support/tickets/' + id + '/report', { headers: { 'X-Demo-User': user } })
  if (!response.ok) throw new Error('Не удалось скачать отчёт обращения.')
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a'); link.href = url; link.download = 'day33-' + id + '.md'; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export type SupportConfig = Config
