import { describe, expect, it } from 'vitest'
import { stale, statusLabel, type Context } from './api'
const context: Context = { project: 'game', branch: 'main', sha: 'a', fingerprint: 'x', dirty: true, fileCount: 3 }
describe('snapshot freshness', () => {
  it('does not treat dirty alone as a stale index', () => expect(stale(context, { ...context, dirty: false })).toBe(false))
  it('detects changed document bytes even without a commit', () => expect(stale(context, { ...context, fingerprint: 'y' })).toBe(true))
  it('detects another source version', () => expect(stale(context, { ...context, sha: 'b' })).toBe(true))
  it('handles the first launch', () => expect(stale(null, null)).toBe(false))
  it('renders interruption and truncation separately', () => {
    expect(statusLabel('INTERRUPTED')).toBe('Прервано перезапуском')
    expect(statusLabel('TRUNCATED')).toBe('Ответ обрезан')
  })
})
