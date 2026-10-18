import { afterEach, describe, expect, it, vi } from 'vitest'
import { filesApi, fileRoute, fileStatus, compactDiff } from './api'
afterEach(() => vi.unstubAllGlobals())
describe('workspace boundaries', () => {
  it('uses the same API for browser commands', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', fetch)
    await filesApi('/snapshots')
    expect(fetch).toHaveBeenCalledWith('/api/workspace/snapshots', undefined)
  })
  it('posts the original idempotency UUID and no filesystem root', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) })
    vi.stubGlobal('fetch', fetch)
    const goal = { id: 'request', snapshotId: 'snapshot', goal: 'Проверь docs', provider: 'LOCAL', parentId: null }
    await filesApi('/runs', goal)
    expect(fetch.mock.calls[0]![1].body).toBe(JSON.stringify(goal))
  })
  it('does not accept an error as a successful save', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({ message: 'Конфликт версии' }) }))
    await expect(filesApi('/runs', {})).rejects.toThrow('Конфликт версии')
  })
  it('only parses scoped canonical deep links', () => {
    const id = '00000000-0000-4000-8000-000000000001'
    expect(fileRoute('#workspace/' + id)).toBe(id)
    expect(fileRoute('#workspace/../.env')).toBeNull()
    expect(fileRoute('#support/' + id)).toBeNull()
  })
  it('distinguishes analysis from persisted changes', () => {
    expect(fileStatus('COMPLETED')).toContain('сохранён')
    expect(fileStatus('ANALYZED')).toBe('Анализ без изменений')
  })
  it('shows the actual change without repeating the whole unchanged document', () => {
    const before = [...Array.from({ length: 20 }, (_, i) => 'line ' + i), 'old', 'tail']
    const after = [...before.slice(0, 20), 'new', 'tail']
    const full = '--- a/docs/a.md\n+++ b/docs/a.md\n@@ -1,22 +1,22 @@\n' +
      before.map(line => '-' + line).join('\n') + '\n' + after.map(line => '+' + line).join('\n') + '\n'
    const preview = compactDiff(full)
    expect(preview).toContain('-old\n+new')
    expect(preview).toContain('@@ -18,5 +18,5 @@')
    expect(preview).not.toContain('line 0')
  })
  it('keeps new files and newline-sensitive patches understandable', () => {
    const added = '--- /dev/null\n+++ b/docs/a.md\n@@ -0,0 +1,1 @@\n+# A\n'
    expect(compactDiff(added)).toContain('+# A')
    const noNewline = added + '\\ No newline at end of file\n'
    expect(compactDiff(noNewline)).toBe(noNewline)
  })
})
