import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { api, stale, type Chat, type Turn, type Config, type Context, type Index, type Event } from './api'
interface Receipt { chatId: string; id: string; message: string; provider: string }
/** UI owns navigation and polling; backend owns RAG, permissions and agent execution. */
export function useWorkspace() {
  const chats = ref<Chat[]>([]), turns = ref<Turn[]>([]), selected = ref('')
  const config = ref<Config | null>(null), context = ref<Context | null>(null)
  const contextEvents = ref<Event[]>([]), latest = ref<Index | null>(null), ready = ref<Index | null>(null)
  const question = ref(''), provider = ref('LOCAL'), error = ref(''), busy = ref(false)
  const focusedId = ref(''), sidebar = ref(false), inspector = ref(true)
  const chatPane = ref<HTMLElement | null>(null)
  const receipt = ref<Receipt | null>(null)
  const focused = computed(() => turns.value.find(t => t.id === focusedId.value) || turns.value.at(-1))
  const running = computed(() => turns.value.some(t => t.status === 'RUNNING'))
  const indexStale = computed(() => stale(context.value, ready.value?.context))
  let timer: ReturnType<typeof setInterval> | undefined
  let polling = false
  /** Explicitly refresh live MCP context; polling itself does not call the model. */
  async function refreshContext() {
    try {
      const result = await api<{context: Context; events: Event[]}>('/project/context')
      context.value = result.context; contextEvents.value = result.events
    } catch (e) { error.value = String((e as Error).message) }
  }
  async function refreshIndex() {
    const result = await api<{latest: Index | null; ready: Index | null}>('/indexes')
    latest.value = result.latest; ready.value = result.ready
  }
  async function loadChat(id: string) {
    const result = await api<{chat: Chat; turns: Turn[]}>('/chats/' + id)
    if (selected.value !== id) return
    turns.value = result.turns
    if (result.turns.length && !focusedId.value) focusedId.value = result.turns.at(-1)!.id
  }
  async function selectChat(id: string) {
    selected.value = id; focusedId.value = ''; sidebar.value = false
    localStorage.setItem('desk-chat', id); location.hash = 'chat/' + id
    try { await loadChat(id); await scrollDown() } catch (e) { error.value = (e as Error).message }
  }
  async function newChat() {
    try {
      const chat = await api<Chat>('/chats', {})
      chats.value.unshift(chat); await selectChat(chat.id)
    } catch (e) { error.value = (e as Error).message }
  }
  async function buildIndex() {
    error.value = ''
    try { await api('/indexes', {}); await refreshIndex() } catch (e) { error.value = (e as Error).message }
  }
  async function scrollDown() {
    await nextTick()
    if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight
  }
  /** Retry preserves request UUID: an uncertain network response cannot double-charge. */
  async function submitReceipt(item: Receipt) {
    busy.value = true; error.value = ''
    try {
      const turn = await api<Turn>('/chats/' + item.chatId + '/messages', { id: item.id, message: item.message, provider: item.provider })
      receipt.value = null; localStorage.removeItem('desk-receipt')
      question.value = ''; focusedId.value = turn.id
      await loadChat(item.chatId); chats.value = await api<Chat[]>('/chats'); await scrollDown()
    } catch (e) { error.value = (e as Error).message }
    finally { busy.value = false }
  }
  async function send() {
    if (!question.value.trim() || busy.value || running.value) return
    if (receipt.value) { error.value = 'Сначала повторите неподтверждённый запрос с тем же ID или отмените повтор.'; return }
    if (!selected.value) await newChat()
    if (!selected.value) return
    const item = { chatId: selected.value, id: crypto.randomUUID(), message: question.value, provider: provider.value }
    receipt.value = item; localStorage.setItem('desk-receipt', JSON.stringify(item))
    await submitReceipt(item)
  }
  async function retry() {
    if (!receipt.value) return
    await selectChat(receipt.value.chatId)
    await submitReceipt(receipt.value)
  }
  function discardReceipt() { receipt.value = null; localStorage.removeItem('desk-receipt') }
  /** Deep links and browser Back/Forward must select the same chat as the URL. */
  function navigateHash() {
    const id = location.hash.startsWith('#chat/') ? location.hash.slice(6) : ''
    if (id && id !== selected.value) void selectChat(id)
    else if (!id) { selected.value = ''; turns.value = []; focusedId.value = ''; localStorage.removeItem('desk-chat') }
  }
  /** Only changed turn/status/text triggers auto-scroll; source expansion does not jump the chat. */
  watch(() => turns.value.map(t => t.id + t.status + t.answer.length).join('|'), scrollDown)
  onMounted(async () => {
    window.addEventListener('hashchange', navigateHash)
    try {
      const [settings, list] = await Promise.all([api<Config>('/config'), api<Chat[]>('/chats')])
      config.value = settings; chats.value = list
      if (settings.cloudAvailable) provider.value = 'DEEPSEEK'
      await refreshIndex(); await refreshContext()
      const raw = localStorage.getItem('desk-receipt')
      if (raw) {
        try { receipt.value = JSON.parse(raw) as Receipt } catch { localStorage.removeItem('desk-receipt') }
      }
      const id = location.hash.startsWith('#chat/') ? location.hash.slice(6) : !location.hash ? localStorage.getItem('desk-chat') : null
      if (id && list.some(c => c.id === id)) await selectChat(id)
      timer = setInterval(async () => {
        if (polling) return
        polling = true
        try {
          if (running.value && selected.value) await loadChat(selected.value)
          if (latest.value?.status === 'BUILDING') { await refreshIndex(); if (latest.value?.status !== 'BUILDING') await refreshContext() }
        } catch (e) { error.value = (e as Error).message }
        finally { polling = false }
      }, 1500)
    } catch (e) { error.value = (e as Error).message }
  })
  onUnmounted(() => { if (timer) clearInterval(timer); window.removeEventListener('hashchange', navigateHash) })
  return { chats, turns, selected, config, context, contextEvents, latest, ready, question, provider,
    error, busy, focusedId, focused, sidebar, inspector, chatPane, receipt, running, indexStale,
    refreshContext, selectChat, newChat, buildIndex, send, retry, discardReceipt }
}
