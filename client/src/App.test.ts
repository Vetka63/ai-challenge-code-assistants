import { createSSRApp, ref, computed } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { useWorkspace } from './useWorkspace'
import type { Context, Turn } from './api'

vi.mock('./useWorkspace', () => ({ useWorkspace: vi.fn() }))
const context: Context = { project: 'game', branch: 'main', sha: 'current-sha', fingerprint: 'current-docs', dirty: true, fileCount: 1 }
const indexed: Context = { ...context, sha: 'indexed-sha', fingerprint: 'indexed-docs' }
function workspace(stale = true, turns: Turn[] = []) {
  return {
    chats: ref([]), turns: ref(turns), selected: ref('chat'), config: ref(null),
    context: ref(context), contextEvents: ref([]), latest: ref(null),
    ready: ref({ id: 'idx', status: 'READY', processed: 1, total: 1, error: null, context: indexed, identity: null, documents: ['README.md'], events: [] }),
    question: ref('/help Вопрос'), provider: ref('LOCAL'), error: ref(''), busy: ref(false),
    focusedId: ref(''), focused: computed(() => turns.at(-1)), sidebar: ref(false),
    inspector: ref(true), chatPane: ref(null), receipt: ref(null), running: computed(() => false),
    indexStale: computed(() => stale), refreshContext: vi.fn(), selectChat: vi.fn(),
    newChat: vi.fn(), buildIndex: vi.fn(), send: vi.fn(), retry: vi.fn(), discardReceipt: vi.fn()
  } as ReturnType<typeof useWorkspace>
}
function turn(): Turn {
  return { id: 'turn', question: 'Вопрос', answer: 'Ответ [S1]', provider: 'LOCAL', status: 'COMPLETED',
    createdAt: '2026-10-16', context, sources: [], events: [], citationStatus: 'IDS_VALID',
    indexId: 'idx', error: null, errorCode: null, generation: null }
}
describe('stale index is a warning, not a send restriction', () => {
  beforeEach(() => vi.clearAllMocks())
  it('shows the banner and keeps send enabled for a ready stale index', async () => {
    vi.mocked(useWorkspace).mockReturnValue(workspace())
    const html = await renderToString(createSSRApp(App))
    expect(html).toContain('Информация могла устареть.')
    const sendButton = html.match(/<button class="send"[^>]*>/)?.[0]
    expect(sendButton).toBeDefined(); expect(sendButton).not.toContain('disabled')
  })
  it('preserves per-answer warnings even when the current index is fresh', async () => {
    const oldAnswer = { ...turn(), indexContext: indexed, warnings: [{ code: 'STALE_INDEX', message: 'Ответ по сохранённому индексу: информация могла устареть.' }] }
    vi.mocked(useWorkspace).mockReturnValue(workspace(false, [oldAnswer]))
    const html = await renderToString(createSSRApp(App))
    expect(html).toContain(oldAnswer.warnings[0]!.message)
    expect(html).toContain('Источники индекса: indexed-sh')
    expect(html).toContain('Ответ [S1]')
  })
  it('renders old saved answers without new optional fields', async () => {
    vi.mocked(useWorkspace).mockReturnValue(workspace(false, [turn()]))
    expect(await renderToString(createSSRApp(App))).toContain('Ответ [S1]')
  })
})
