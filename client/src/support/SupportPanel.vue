<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, statusLabel } from '../api'
import { supportApi, supportRoute, downloadTicket, type SupportUser, type SupportTicket, type TicketSummary,
  type TicketDraft, type Knowledge, type Receipt, type SupportConfig } from './api'
const users = ref<SupportUser[]>([]), user = ref('demo-anna'), list = ref<TicketSummary[]>([])
const ticket = ref<SupportTicket | null>(null), selected = ref(''), knowledge = ref<Knowledge | null>(null)
const config = ref<SupportConfig | null>(null), provider = ref('LOCAL'), question = ref('')
const error = ref(''), busy = ref(false), formOpen = ref(false), showTickets = ref(false)
const initializing = ref(true)
const receipt = ref<Receipt | null>(null), chatPane = ref<HTMLElement | null>(null)
const draft = ref<TicketDraft>({ id: '', title: '', description: '', gameVersion: 'HeroAnimations', platform: 'WINDOWS', kind: 'ISSUE' })
const running = computed(() => ticket.value?.turns.some(turn => turn.status === 'RUNNING') || false)
let timer: ReturnType<typeof setInterval> | undefined, polling = false, alive = true
const fieldValid = computed(() => draft.value.title.trim().length >= 3 && draft.value.description.trim().length >= 10 && draft.value.gameVersion.trim().length > 0)
async function action(run: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await run() } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function refreshList() {
  const identity = user.value
  const result = await supportApi<TicketSummary[]>('/tickets', identity)
  if (alive && user.value === identity) list.value = result
}
async function loadTicket() {
  const identity = user.value, id = selected.value
  if (!id) return
  const result = await supportApi<SupportTicket>('/tickets/' + id, identity)
  if (alive && identity === user.value && id === selected.value) ticket.value = result
}
async function refreshKnowledge() {
  const result = await supportApi<Knowledge>('/knowledge')
  if (alive) knowledge.value = result
}
async function choose(id: string) {
  selected.value = id; ticket.value = null; showTickets.value = false; formOpen.value = false; error.value = ''
  location.hash = 'support/' + user.value + '/' + id
  try { await loadTicket() } catch (e) { error.value = (e as Error).message }
}
async function changeUser(id: string) {
  user.value = id; selected.value = ''; ticket.value = null; list.value = []; question.value = ''; error.value = ''
  location.hash = 'support'; localStorage.setItem('support-user', id)
  try { await refreshList() } catch (e) { error.value = (e as Error).message }
}
function newTicket() {
  formOpen.value = true; showTickets.value = false; error.value = ''
  draft.value = { id: crypto.randomUUID(), title: '', description: '', gameVersion: 'HeroAnimations', platform: 'WINDOWS', kind: 'ISSUE' }
}
async function createTicket() {
  if (!fieldValid.value) return
  await action(async () => {
    const created = await supportApi<SupportTicket>('/tickets', user.value, draft.value)
    await refreshList(); await choose(created.id)
  })
}
async function generalQuestion() {
  await action(async () => {
    const created = await supportApi<SupportTicket>('/tickets', user.value, {
      id: crypto.randomUUID(), title: 'Общий вопрос по игре', description: 'Общие вопросы по FAQ и документации игры.',
      gameVersion: 'Не указана', platform: users.value.find(item => item.id === user.value)?.platform || 'WINDOWS', kind: 'QUESTION'
    })
    await refreshList(); await choose(created.id)
  })
}
async function buildIndex() { await action(async () => { await supportApi('/knowledge', undefined, {}); await refreshKnowledge() }) }
async function submit(item: Receipt) {
  await action(async () => {
    await supportApi('/tickets/' + item.ticket + '/messages', item.user, { id: item.id, question: item.question, provider: item.provider })
    receipt.value = null; localStorage.removeItem('support-receipt'); question.value = ''
    if (user.value === item.user && selected.value === item.ticket) { await loadTicket(); await refreshList() }
  })
}
async function send() {
  if (!selected.value || !question.value.trim() || running.value || busy.value || receipt.value || !knowledge.value?.ready) return
  const item = { user: user.value, ticket: selected.value, id: crypto.randomUUID(), question: question.value, provider: provider.value }
  receipt.value = item; localStorage.setItem('support-receipt', JSON.stringify(item))
  await submit(item)
}
async function retry() {
  const item = receipt.value
  if (!item || item.user !== user.value) return
  await choose(item.ticket); await submit(item)
}
function discardReceipt() { receipt.value = null; localStorage.removeItem('support-receipt') }
async function download() { if (ticket.value) await action(async () => { await downloadTicket(user.value, ticket.value!.id) }) }
function navigate() {
  const target = supportRoute(location.hash)
  if (target && users.value.some(item => item.id === target.user) && (target.user !== user.value || target.ticket !== selected.value)) {
    user.value = target.user; selected.value = target.ticket; ticket.value = null
    void Promise.all([loadTicket(), refreshList()]).catch(e => { error.value = e.message })
  }
}
watch(() => ticket.value?.turns.map(turn => turn.id + turn.status + turn.answer.length).join('|'), async () => {
  await nextTick()
  if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight
})
onMounted(async () => {
  window.addEventListener('hashchange', navigate)
  try {
    const [profiles, settings] = await Promise.all([supportApi<SupportUser[]>('/users'), api<SupportConfig>('/config')])
    if (!alive) return
    users.value = profiles; config.value = settings
    if (settings.cloudAvailable) provider.value = 'DEEPSEEK'
    const route = supportRoute(location.hash), saved = localStorage.getItem('support-user')
    user.value = profiles.find(item => item.id === (route?.user || saved))?.id || profiles[0]?.id || 'demo-anna'
    const raw = localStorage.getItem('support-receipt')
    if (raw) { try { const value = JSON.parse(raw) as Receipt; if (typeof value.id === 'string' && typeof value.question === 'string') receipt.value = value } catch { localStorage.removeItem('support-receipt') } }
    await Promise.all([refreshList(), refreshKnowledge()])
    if (route) await choose(route.ticket)
    timer = setInterval(async () => {
      if (polling || !alive) return
      polling = true
      try {
        if (running.value) { await loadTicket(); await refreshList() }
        if (knowledge.value?.latest?.status === 'BUILDING') await refreshKnowledge()
      } catch (e) { error.value = (e as Error).message } finally { polling = false }
    }, 1500)
  } catch (e) { error.value = (e as Error).message }
  finally { initializing.value = false }
})
onUnmounted(() => { alive = false; if (timer) clearInterval(timer); window.removeEventListener('hashchange', navigate) })
</script>

<template>
  <main class="support-panel">
    <header class="topbar"><div><strong>Поддержка игроков</strong><small>День 33 · FAQ + контекст обращения через MCP</small></div><a href="#chat/">← К ассистентам</a></header>
    <div class="support-demo">Учебный режим · вымышленные пользователи · не настоящая авторизация
      <label>Профиль <select :value="user" :disabled="busy" @change="changeUser(($event.target as HTMLSelectElement).value)" aria-label="Учебный пользователь"><option v-for="profile in users" :key="profile.id" :value="profile.id">{{ profile.displayName }}</option></select></label>
    </div>
    <div v-if="error" class="alert" role="alert">{{ error }}</div>
    <p v-if="initializing" class="banner" role="status">Загружаю профили, обращения и состояние FAQ…</p>
    <div class="support-body" :inert="initializing || undefined">
      <aside class="support-tickets" :class="{ expanded: showTickets }">
        <div class="support-ticket-buttons"><button class="primary" @click="newTicket" :disabled="busy">＋ Создать обращение</button><button @click="generalQuestion" :disabled="busy">Вопрос по FAQ</button></div>
        <h2>Мои обращения <small>{{ list.length }}</small></h2>
        <button v-for="item in list" :key="item.id" class="support-ticket" :class="{ selected: selected === item.id }" @click="choose(item.id)" :disabled="busy">
          <strong>{{ item.title }}</strong><small>{{ item.kind === 'QUESTION' ? 'FAQ' : 'Обращение' }} · {{ item.gameVersion }} · сообщений: {{ item.turnCount }}</small>
        </button>
        <p v-if="!list.length" class="muted">У этого профиля пока нет обращений.</p>
        <section class="support-knowledge">
          <h3>База знаний поддержки</h3><p class="quiet">{{ knowledge?.knowledgeVersion }}</p>
          <p class="muted">{{ knowledge?.ready ? 'FAQ готов · ' + knowledge.ready.total + ' фрагментов' : 'Индекс FAQ ещё не создан' }}</p>
          <p v-if="knowledge?.latest?.status === 'BUILDING'" role="status">Индексация {{ knowledge.latest.processed }} / {{ knowledge.latest.total }}</p>
          <p v-if="knowledge?.latest?.error" class="inline-error">{{ knowledge.latest.error }}</p>
          <button @click="buildIndex" :disabled="busy || running || knowledge?.latest?.status === 'BUILDING'">{{ knowledge?.ready ? 'Обновить индекс FAQ' : 'Создать индекс FAQ' }}</button>
          <p class="quiet">Embeddings и поиск локальные. Тикеты и профили в индекс не попадают.</p>
        </section>
      </aside>
      <section class="support-chat">
        <button class="support-mobile-list" @click="showTickets = !showTickets">{{ showTickets ? 'Скрыть обращения' : 'Мои обращения и база знаний' }}</button>
        <section v-if="formOpen" class="support-form">
          <h1>Новое обращение</h1><p class="muted">Расскажи об обстоятельствах один раз — ассистент получит их через MCP перед каждым ответом.</p>
          <form @submit.prevent="createTicket">
            <label>Тема<input v-model="draft.title" minlength="3" maxlength="120" required placeholder="Не работает «Продолжить»"></label>
            <div class="support-form-row"><label>Сборка игры<input v-model="draft.gameVersion" maxlength="100" required></label><label>Платформа<select v-model="draft.platform"><option value="WINDOWS">Windows</option><option value="LINUX">Linux</option><option value="MAC">macOS</option><option value="OTHER">Другая</option></select></label></div>
            <label>Что ожидалось и что произошло<textarea v-model="draft.description" minlength="10" maxlength="6000" rows="5" required placeholder="Опиши последовательность действий и сообщение об ошибке. Пароли и персональные данные не нужны."></textarea></label>
            <div class="support-form-row"><button class="primary" :disabled="busy || !fieldValid">Создать обращение</button><button type="button" @click="formOpen = false">Отмена</button></div>
          </form>
        </section>
        <template v-else-if="ticket">
          <div class="support-ticket-heading"><div><h1>{{ ticket.title }}</h1><small>{{ ticket.gameVersion }} · {{ ticket.platform }} · JSON · {{ ticket.id.slice(0, 8) }}</small></div><button @click="download" :disabled="busy">Скачать MD</button></div>
          <details class="support-description"><summary>Обстоятельства обращения</summary><p>{{ ticket.description }}</p><small>Заявлено игроком, не подтверждённый диагноз.</small></details>
          <section ref="chatPane" class="support-messages" aria-label="Переписка поддержки" aria-live="polite">
            <p v-if="!ticket.turns.length" class="muted">Задай вопрос. Описание, сборку и платформу повторять не нужно.</p>
            <article v-for="turn in ticket.turns" :key="turn.id" class="turn">
              <div class="user-message">{{ turn.question }}</div>
              <div class="answer-heading"><strong>Поддержка</strong><span class="badge">{{ statusLabel(turn.status) }}</span></div>
              <p v-if="turn.status === 'RUNNING'" class="thinking">Получаю контекст через MCP, ищу FAQ и готовлю ответ…</p>
              <div v-if="turn.answer" class="answer">{{ turn.answer }}</div>
              <p v-if="!turn.candidateAnswer && turn.answer && ['MISSING', 'INVALID_IDS'].includes(turn.citationStatus)" class="inline-error">Ответ сохранён без корректных ссылок. Его рекомендации нельзя считать подтверждёнными FAQ.</p>
              <p v-if="turn.error" class="inline-error">{{ turn.error }} · {{ turn.errorCode }}</p>
              <details v-if="turn.candidateAnswer" class="support-proof"><summary>Непроверенный черновик модели — не инструкция к действию</summary><pre>{{ turn.candidateAnswer }}</pre></details>
              <details v-if="turn.context" class="support-proof"><summary>Контекст тикета через MCP</summary><dl><dt>Пользователь</dt><dd>{{ turn.context.user.displayName }}</dd><dt>Сборка / платформа</dt><dd>{{ turn.context.gameVersion }} / {{ turn.context.platform }}</dd><dt>Обстоятельства</dt><dd>{{ turn.context.description }}</dd><dt>Предыдущих обменов</dt><dd>{{ turn.context.recentConversation.length }}</dd></dl></details>
              <details v-if="turn.sources.length" class="support-proof"><summary>Источники FAQ · {{ turn.sources.length }}</summary>
                <p class="quiet">{{ turn.knowledgeVersion }} · ссылки {{ turn.citationStatus }}. Проверка ID не доказывает истинность ответа.</p>
                <details v-for="source in turn.sources" :key="source.id"><summary>[{{ source.id }}] {{ source.section }} · {{ source.path }}:{{ source.startLine }}–{{ source.endLine }}</summary><pre>{{ source.text }}</pre><small>chunk {{ source.chunkId }} · hash {{ source.fileHash.slice(0, 12) }}</small></details>
              </details>
              <details class="support-proof"><summary>Журнал MCP · {{ turn.events.length }}</summary><p v-for="(event, i) in turn.events" :key="i" class="quiet">{{ event.method }} · {{ event.status }} · {{ event.durationMs }} мс<br>{{ event.details }}</p></details>
              <p v-if="turn.generation" class="metrics">{{ turn.generation.model }} · {{ (turn.generation.durationMs / 1000).toFixed(1) }} с · API-токены {{ turn.generation.inputTokens ?? '—' }} / {{ turn.generation.outputTokens ?? '—' }}</p>
            </article>
          </section>
          <footer class="support-composer">
            <div v-if="receipt" class="receipt">Есть неподтверждённая отправка.<button v-if="receipt.user === user" @click="retry" :disabled="busy">Повторить с тем же ID</button><button @click="discardReceipt" :disabled="busy">Отменить повтор</button></div>
            <p v-if="!knowledge?.ready" class="banner">Сначала создайте индекс FAQ в панели обращений.</p>
            <form class="composer" @submit.prevent="send"><textarea v-model="question" rows="2" maxlength="4000" aria-label="Вопрос поддержке" placeholder="Что мне проверить в моей ситуации?" @keydown.enter.exact.prevent="send"></textarea>
              <div class="composer-bottom"><label class="provider">Модель<select v-model="provider" aria-label="Модель поддержки"><option value="LOCAL">Локально · {{ config?.localModel }}</option><option value="DEEPSEEK" :disabled="!config?.cloudAvailable">DeepSeek · {{ config?.deepseekModel }}</option></select></label><button class="send" :disabled="busy || running || !question.trim() || !!receipt || !knowledge?.ready">{{ running ? 'Готовлю ответ…' : 'Отправить ↑' }}</button></div>
            </form><p class="composer-note">{{ provider === 'DEEPSEEK' ? 'Вопрос, вымышленный тикет и выбранные фрагменты отправятся DeepSeek.' : 'Контекст, поиск и генерация остаются локальными.' }}</p>
          </footer>
        </template>
        <div v-else class="support-empty"><h1>Поможем разобраться в игре</h1><p>Общий вопрос — по базе знаний. Обращение — с учётом твоей сборки и обстоятельств.</p><button class="primary" @click="newTicket">Создать обращение</button><button @click="generalQuestion" :disabled="busy">Задать вопрос по FAQ</button><p class="quiet">Игра не изменяется. Автоматическое ревью PR выключено.</p></div>
      </section>
    </div>
  </main>
</template>
