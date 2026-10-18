import { afterEach, describe, expect, it, vi } from 'vitest'
import { loadPassport, startCheck, saveSettings, label } from './passportApi'
afterEach(() => vi.unstubAllGlobals())
describe('passport boundaries', () => {
  it('keeps the idempotency ID and explicit retry intent', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }); vi.stubGlobal('fetch', fetch)
    await startCheck('request', true)
    expect(fetch.mock.calls[0]![0]).toBe('/api/passport/checks')
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ id: 'request', retryConfirmed: true })
  })
  it('persists the visible interval, provider, pause and quota', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }); vi.stubGlobal('fetch', fetch)
    const settings = { enabled: false, intervalSeconds: 600, provider: 'DEEPSEEK', dailyLimit: 5 }
    await saveSettings(settings)
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual(settings)
  })
  it('does not turn a failed check into a successful version', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({ message: 'Проверка не удалась' }) }))
    await expect(loadPassport()).rejects.toThrow('Проверка не удалась')
  })
  it('distinguishes documented facts, code presence, plans and no-charge checks', () => {
    expect(label('NO_CHANGES')).toContain('без LLM'); expect(label('CODE_PRESENT')).toContain('Найдено в коде')
    expect(label('SCENE')).not.toContain('уровень'); expect(label('PLANNED')).toBe('Запланировано')
  })
})
