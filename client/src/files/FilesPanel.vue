<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, nextTick } from 'vue'
import { api, type Config } from '../api'
import { filesApi, fileRoute, fileStatus, compactDiff, type Snapshot, type FileRun, type Goal, type Artifact } from './api'
defineEmits<{ menu: [] }>()
const snapshots = ref<Snapshot[]>([])
const runs = ref<FileRun[]>([])
const snapshotId = ref('')
const selected = ref<FileRun | null>(null)
const artifacts = ref<Artifact[]>([])
const sourceFragments = ref<{path: string; hash: string; startLine: number; endLine: number; numberedText: string}[]>([])
const config = ref<Config | null>(null)
const goal = ref('')
const provider = ref('LOCAL')
const continuing = ref(false)
const busy = ref(false)
const error = ref('')
const ready = ref(false)
const receipt = ref<Goal | null>(null)
const pane = ref<HTMLElement | null>(null)
let alive = true
let loading = false
let timer: ReturnType<typeof setInterval> | undefined
const active = computed(() => selected.value?.status === 'RUNNING')
const canContinue = computed(() => !!selected.value && ['COMPLETED', 'ANALYZED'].includes(selected.value.status))
const snapshot = computed(() => snapshots.value.find(s => s.id === snapshotId.value))
const uniqueFiles = computed(() => new Set(selected.value?.files.map(f => f.path)).size)
const examples = [
  'Проверь инструкцию клавиатурного управления ASCII-прототипом первого акта по его коду и подготовь diff её исправления. Исторические описания не переписывай. Если расхождений нет, сохрани отчёт проверки.',
  'Найди использования ArenaSettings и создай документ о том, какие части игры зависят от этих настроек. Приведи пути и строки исходников.'
]
const diffMode = ref<Record<string, boolean>>({})
function message(e: unknown) { return e instanceof Error ? e.message : 'Операция не выполнена.' }
async function loadList() { runs.value = await filesApi<FileRun[]>('/runs') }
async function loadRun(id: string) {
  const run = await filesApi<FileRun>('/runs/' + id)
  if (!alive || fileRoute(location.hash) !== id) return
  selected.value = run; snapshotId.value = run.snapshotId
  const saved = await filesApi<Artifact[]>('/runs/' + id + '/artifacts')
  if (alive && fileRoute(location.hash) === id) artifacts.value = saved
}
async function navigate() {
  sourceFragments.value = []
  const id = fileRoute(location.hash)
  error.value = ''
  if (!id) { selected.value = null; artifacts.value = []; continuing.value = false; return }
  try { await loadRun(id); continuing.value = canContinue.value; if (selected.value?.id === id) provider.value = selected.value.provider }
  catch (e) { error.value = message(e) }
}
function chooseRun(id: string) { location.hash = 'workspace/' + id }
function newTask() { location.hash = 'workspace'; selected.value = null; artifacts.value = []; sourceFragments.value = []; continuing.value = false; goal.value = '' }
async function showSources() {
  const id = selected.value?.id
  if (!id) return
  try {
    const result = await filesApi<typeof sourceFragments.value>('/runs/' + id + '/sources')
    if (selected.value?.id === id) sourceFragments.value = result
  } catch (e) { error.value = message(e) }
}
async function makeSnapshot() {
  busy.value = true; error.value = ''
  const id = crypto.randomUUID()
  try {
    const created = await filesApi<Snapshot>('/snapshots', { id })
    snapshots.value = [created, ...snapshots.value.filter(s => s.id !== created.id)]
    newTask(); snapshotId.value = created.id
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
function forgetReceipt() { receipt.value = null; localStorage.removeItem('day34-receipt') }
async function submit(retry = false) {
  if (busy.value || active.value || (!retry && (!goal.value.trim() || !snapshotId.value))) return
  error.value = ''; busy.value = true
  try {
    const command: Goal = retry && receipt.value ? receipt.value : {
      id: crypto.randomUUID(), snapshotId: snapshotId.value, goal: goal.value.trim(), provider: provider.value,
      parentId: continuing.value ? selected.value?.id || null : null
    }
    receipt.value = command; localStorage.setItem('day34-receipt', JSON.stringify(command))
    const run = await filesApi<FileRun>('/runs', command)
    if (!alive) return
    forgetReceipt(); selected.value = run; artifacts.value = []; goal.value = ''; continuing.value = false
    location.hash = 'workspace/' + run.id; await loadList()
    await nextTick(); pane.value?.scrollTo({ top: 0 })
  } catch (e) { error.value = message(e) + ' Сохранён ID отправки: можно безопасно повторить тот же запрос.' }
  finally { busy.value = false }
}
onMounted(async () => {
  try {
    const [s, r, c] = await Promise.all([filesApi<Snapshot[]>('/snapshots'), filesApi<FileRun[]>('/runs'), api<Config>('/config')])
    if (!alive) return
    snapshots.value = s; runs.value = r; config.value = c; snapshotId.value = s[0]?.id || ''
    try {
      const pending = JSON.parse(localStorage.getItem('day34-receipt') || 'null') as Goal | null
      if (pending && typeof pending.goal === 'string' && fileRoute('#workspace/' + pending.id)) receipt.value = pending
    } catch { localStorage.removeItem('day34-receipt') }
    await navigate()
  } catch (e) { error.value = message(e) }
  finally { ready.value = true }
  window.addEventListener('hashchange', navigate)
  timer = setInterval(async () => {
    if (!alive || loading || !active.value) return
    loading = true
    try {
      const id = selected.value!.id
      await loadRun(id)
      if (!active.value) { continuing.value = canContinue.value; await loadList() }
    } catch (e) { error.value = message(e) }
    finally { loading = false }
  }, 1500)
})
onUnmounted(() => { alive = false; if (timer) clearInterval(timer); window.removeEventListener('hashchange', navigate) })
</script>

<template>
  <section class="file-panel" aria-label="Работа с файлами">
    <header class="file-header">
      <button class="icon mobile-nav" @click="$emit('menu')" aria-label="Открыть меню">☰</button>
      <div><small>ДЕНЬ 34 · WORKSPACE AGENT</small><h1>От цели — к изменениям</h1></div>
      <button class="primary" @click="newTask" :disabled="busy || active">＋ Новая задача</button>
    </header>
    <div class="file-safety">Изолированная рабочая область · основная игра только для чтения · ревью дня 32 выключено</div>
    <div v-if="error" class="alert" role="alert">{{ error }}<button @click="error = ''" class="icon" aria-label="Закрыть ошибку">×</button></div>
    <div v-if="!ready" class="banner">Загружаю рабочую область…</div>
    <div class="file-scroll" ref="pane" :inert="!ready">
      <section class="file-controls">
        <label>Снимок проекта
          <select v-model="snapshotId" :disabled="busy || active || !!selected" aria-label="Снимок проекта">
            <option value="" disabled>Сначала создайте снимок</option>
            <option v-for="s in snapshots" :key="s.id" :value="s.id">{{ new Date(s.createdAt).toLocaleString() }} · {{ s.fileCount }} файлов · {{ s.fingerprint.slice(0, 8) }}</option>
          </select>
        </label>
        <button class="subtle" @click="makeSnapshot" :disabled="busy || active">Создать свежий снимок</button>
        <label>Сохранённые задачи
          <select :value="selected?.id || ''" @change="chooseRun(($event.target as HTMLSelectElement).value)" :disabled="busy" aria-label="Сохранённые задачи">
            <option value="" disabled>Выберите задачу</option>
            <option v-for="r in runs" :key="r.id" :value="r.id">{{ r.goal.slice(0, 65) }} · {{ fileStatus(r.status) }}</option>
          </select>
        </label>
      </section>
      <p class="quiet" v-if="snapshot">Снимок {{ snapshot.id }} · fingerprint {{ snapshot.fingerprint }}. Новые изменения игры в него не подмешиваются.</p>
      <section v-if="!selected" class="file-welcome">
        <h2>Задайте цель, а не список файлов</h2>
        <p>Агент сам выберет поиск и чтение, изучит минимум три исходника и сохранит документ или diff отдельно от игры.</p>
        <button v-for="example in examples" :key="example" class="example" @click="goal = example">{{ example }} ↗</button>
      </section>
      <section v-if="selected" class="file-result" aria-live="polite">
        <div class="user-message">{{ selected.goal }}</div>
        <div class="file-run-heading"><h2>{{ fileStatus(selected.status) }}</h2><span class="pill">{{ selected.provider }} · {{ selected.usage.length }} LLM-вызовов</span></div>
        <p v-if="active" class="thinking"><span class="pulse"></span>Модель самостоятельно выбирает следующий инструмент…</p>
        <p v-if="selected.error" class="inline-error">{{ selected.error }} · {{ selected.errorCode }}</p>
        <p v-if="selected.status === 'ANALYZED'" class="file-notice">Ответ сохранён, но файл или diff не создан. Такой запуск не обозначается готовым изменением.</p>
        <div class="answer" v-if="selected.answer">{{ selected.answer }}</div>
        <div class="file-proof">
          <details open><summary>Действия агента · {{ selected.events.length }}</summary>
            <ol class="file-events"><li v-for="(event, index) in selected.events" :key="index" :class="event.status.toLowerCase()">
              <strong>{{ event.stage }}</strong><span>{{ event.status }} <small v-if="event.durationMs">· {{ event.durationMs }} мс</small></span><p>{{ event.details }}</p>
            </li></ol>
          </details>
          <details open><summary>Прочитано файлов · {{ uniqueFiles }}</summary>
            <ul class="file-read-list"><li v-for="(f, i) in selected.files" :key="i"><code>{{ f.path }}</code><span>Строки {{ f.startLine }}–{{ f.endLine }} · SHA {{ f.hash.slice(0, 12) }}</span></li></ul>
            <p class="quiet">Это действительно прочитанные диапазоны, а не весь проект. Поиск — буквальный, не анализ ссылок Unity.</p>
            <button class="subtle" @click="showSources">Показать прочитанные фрагменты</button>
          </details>
        </div>
        <details v-for="(fragment, i) in sourceFragments" :key="'source-' + i" class="file-artifact">
          <summary>{{ fragment.path }} · строки {{ fragment.startLine }}–{{ fragment.endLine }}</summary>
          <pre>{{ fragment.numberedText }}</pre>
          <p class="quiet">Сохранённый ответ MCP, переданный модели. Нового чтения или LLM-запроса нет.</p>
        </details>
        <section class="file-artifacts" aria-label="Сохранённые документы и diff">
          <h2>Сохранённые результаты · {{ artifacts.length }}</h2>
          <p v-if="artifacts.length" class="quiet">Предложения LLM требуют проверки. Сохранение файла не доказывает правильность выводов; тесты игры не запускались.</p>
          <p class="muted" v-if="!artifacts.length">Документы появятся после успешного инструмента сохранения. Обычный ответ модели сам по себе файл не создаёт.</p>
          <article v-for="a in artifacts" :key="a.id" class="file-artifact">
            <header><div><strong>{{ a.path }}</strong><small>{{ a.kind === 'CREATED_DOCUMENT' ? 'Новый документ сохранён' : 'Изменение предложено, исходник не изменён' }}</small></div>
              <div><a :href="'/api/workspace/runs/' + selected.id + '/artifacts/' + a.id + '/md'">Скачать MD</a><a :href="'/api/workspace/runs/' + selected.id + '/artifacts/' + a.id + '/patch'">Скачать patch</a></div>
            </header>
            <div class="file-tabs"><button :class="{ current: !diffMode[a.id] }" @click="diffMode[a.id] = false">Документ</button><button :class="{ current: diffMode[a.id] }" @click="diffMode[a.id] = true">Diff</button></div>
            <p v-if="diffMode[a.id]" class="quiet">Краткий просмотр с контекстом. Полный серверный patch доступен по ссылке скачивания.</p>
            <pre :class="{ 'file-diff': diffMode[a.id] }">{{ diffMode[a.id] ? compactDiff(a.diff) : a.content }}</pre>
            <details><summary>Основания · {{ a.evidence.length }} файлов</summary><ul><li v-for="path in a.evidence" :key="path"><code>{{ path }}</code></li></ul><p class="quiet">Исходный хеш: {{ a.baseHash || 'новый файл' }}. Документ и patch сохранены в рабочей области, не в основной игре.</p></details>
          </article>
        </section>
        <details><summary>Расход и скорость · {{ selected.usage.length }} запросов</summary>
          <ul class="file-usage"><li v-for="(u, i) in selected.usage" :key="i">Шаг {{ i + 1 }} · {{ u.model }} · {{ (u.durationMs / 1000).toFixed(1) }} с · input/output {{ u.inputTokens ?? '—' }} / {{ u.outputTokens ?? '—' }} · {{ u.finishReason }}</li></ul>
        </details>
        <p class="quiet">ID запуска {{ selected.id }}<template v-if="selected.parentId"> · продолжение {{ selected.parentId }}</template></p>
      </section>
    </div>
    <footer class="file-composer">
      <div v-if="receipt" class="receipt">Отправка могла быть принята. Повтор использует прежний UUID.<button @click="submit(true)" :disabled="busy || active">Повторить отправку</button><button @click="forgetReceipt">Убрать уведомление</button></div>
      <form @submit.prevent="submit()">
        <label class="sr-only" for="file-goal">Цель работы с файлами</label>
        <textarea id="file-goal" v-model="goal" maxlength="4000" rows="3" placeholder="Например: проверь документацию управления по текущему коду и подготовь исправления" :disabled="busy || active"></textarea>
        <div class="file-composer-bottom">
          <label>Модель<select v-model="provider" aria-label="Модель файлового агента" :disabled="busy || active"><option value="LOCAL">Ollama · {{ config?.localModel || 'локально' }}</option><option value="DEEPSEEK" :disabled="!config?.cloudAvailable">DeepSeek · {{ config?.deepseekModel || 'не настроен' }}</option></select></label>
          <label v-if="canContinue" class="file-continue"><input type="checkbox" v-model="continuing">Продолжить эту задачу</label>
          <button type="submit" class="primary" :disabled="!ready || !snapshotId || !goal.trim() || busy || active || !!receipt">{{ active ? 'Агент работает…' : busy ? 'Отправляю…' : 'Поручить агенту ↑' }}</button>
        </div>
      </form>
      <p class="composer-note">{{ provider === 'DEEPSEEK' ? 'Выбранные фрагменты кода и документации отправляются DeepSeek.' : 'Выбрана локальная модель; скрытого перехода в облако нет.' }} До 24 LLM-шагов и 64 tool calls. Применение к игре и commit/push отключены.</p>
    </footer>
  </section>
</template>
