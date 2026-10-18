/** Файловый агент не получает filesystem root или секреты из браузера. */
export interface Snapshot { id: string; fingerprint: string; fileCount: number; createdAt: string; origin: string }
export interface FileEvidence { path: string; hash: string; startLine: number; endLine: number }
export interface FileEvent { stage: string; status: string; details: string; at: string; durationMs: number }
export interface FileUsage { model: string; inputTokens: number | null; outputTokens: number | null; durationMs: number; finishReason: string }
export interface Goal { id: string; snapshotId: string; goal: string; provider: string; parentId: string | null }
export interface FileRun extends Goal {
  status: string; answer: string; events: FileEvent[]; files: FileEvidence[];
  artifactIds: string[]; usage: FileUsage[]; errorCode: string | null; error: string | null; createdAt: string;
}
export interface Artifact {
  id: string; snapshotId: string; runId: string; path: string; kind: string; baseHash: string | null;
  content: string; diff: string; evidence: string[]; createdAt: string;
}
/** Общий контракт с CLI; повтор POST возможен только с прежним UUID. */
export async function filesApi<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch('/api/workspace' + path, body === undefined ? undefined : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  })
  const value = await response.json().catch(() => ({ message: 'Backend вернул некорректный ответ.' }))
  if (!response.ok) throw new Error(value.message || 'Операция не выполнена.')
  return value as T
}
export function fileRoute(hash: string): string | null {
  return /^#workspace\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/.exec(hash)?.[1] || null
}
export const fileStatus = (status: string) => ({
  RUNNING: 'Агент работает', COMPLETED: 'Документ / diff сохранён', ANALYZED: 'Анализ без изменений',
  FAILED: 'Запуск остановлен', INTERRUPTED: 'Прерван перезапуском'
}[status] || status)
/** Компактный просмотр полной замены. Скачиваемый серверный patch остаётся неизменным. */
export function compactDiff(diff: string): string {
  const lines = diff.split('\n')
  if (!lines[2]?.startsWith('@@ ') || diff.includes('\\ No newline at end of file')) return diff
  const old = lines.slice(3).filter(line => line.startsWith('-')).map(line => line.slice(1))
  const next = lines.slice(3).filter(line => line.startsWith('+')).map(line => line.slice(1))
  let prefix = 0
  while (prefix < old.length && prefix < next.length && old[prefix] === next[prefix]) prefix++
  let suffix = 0
  while (suffix < old.length - prefix && suffix < next.length - prefix &&
    old[old.length - 1 - suffix] === next[next.length - 1 - suffix]) suffix++
  if (prefix === old.length && prefix === next.length) return diff
  const start = Math.max(0, prefix - 3)
  const oldEnd = Math.min(old.length, old.length - suffix + 3)
  const nextEnd = Math.min(next.length, next.length - suffix + 3)
  const content = [
    ...old.slice(start, prefix).map(line => ' ' + line),
    ...old.slice(prefix, old.length - suffix).map(line => '-' + line),
    ...next.slice(prefix, next.length - suffix).map(line => '+' + line),
    ...old.slice(old.length - suffix, oldEnd).map(line => ' ' + line)
  ]
  return [lines[0], lines[1], '@@ -' + (oldEnd === start ? 0 : start + 1) + ',' + (oldEnd - start) +
    ' +' + (nextEnd === start ? 0 : start + 1) + ',' + (nextEnd - start) + ' @@', ...content].join('\n')
}
