import { afterEach, describe, expect, it, vi } from 'vitest'
import { supportApi, supportRoute } from './api'
afterEach(() => vi.unstubAllGlobals())
describe('support API boundaries', () => {
  it('binds the chosen demo profile to the request header', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', fetch)
    await supportApi('/tickets', 'demo-anna')
    expect(fetch).toHaveBeenCalledWith('/api/support/tickets', { method: 'GET', headers: { 'X-Demo-User': 'demo-anna' } })
  })
  it('posts only the explicit command, not arbitrary context', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ id: 'id' }) })
    vi.stubGlobal('fetch', fetch)
    const input = { id: 'id', question: 'Вопрос', provider: 'LOCAL' }
    await supportApi('/tickets/t/messages', 'demo-boris', input)
    expect(fetch.mock.calls[0]![1].body).toBe(JSON.stringify(input))
    expect(fetch.mock.calls[0]![1].headers['X-Demo-User']).toBe('demo-boris')
  })
  it('shows denial instead of accepting an HTML or error response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({ message: 'Обращение недоступно.' }) }))
    await expect(supportApi('/tickets/t', 'demo-boris')).rejects.toThrow('Обращение недоступно.')
  })
  it('only accepts scoped UUID deep links', () => {
    const id = 'dff5cad3-2a70-44af-9989-019ce39db899'
    expect(supportRoute('#support/demo-anna/' + id)).toEqual({ user: 'demo-anna', ticket: id })
    expect(supportRoute('#support/demo-anna/../../users')).toBeNull()
    expect(supportRoute('#reviews')).toBeNull()
  })
})
