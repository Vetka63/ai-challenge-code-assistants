<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { reviewsApi, reviewLabels, type ReviewJob, type ReviewSettings } from './api'
const jobs = ref<ReviewJob[]>([]), settings = ref<ReviewSettings | null>(null)
const selected = ref(''), prNumber = ref<number | ''>(''), provider = ref('DEEPSEEK'), publish = ref(false)
const busy = ref(false), error = ref(''), notice = ref(''), expanded = ref(false)
const focused = computed(() => jobs.value.find(item => item.id === selected.value) || jobs.value[0])
let timer: ReturnType<typeof setInterval> | undefined, polling = false
async function refresh() {
  const [list, config] = await Promise.all([reviewsApi.list(), reviewsApi.settings()])
  jobs.value = list; settings.value = config
}
async function action(run: () => Promise<unknown>, message = '') {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { await run(); await refresh(); notice.value = message } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
async function start() {
  if (!Number.isInteger(prNumber.value) || Number(prNumber.value) < 1) return
  await action(async () => {
    const result = await api<ReviewJob>('/reviews', { pr: prNumber.value, provider: provider.value, publish: publish.value })
    selected.value = result.id
  }, 'Версия PR зарегистрирована. Повтор того же запроса не создаёт второе ревью.')
}
onMounted(async () => {
  try { await refresh() } catch (e) { error.value = (e as Error).message }
  timer = setInterval(async () => {
    if (polling) return
    polling = true
    try { await refresh() } catch (e) { error.value = (e as Error).message } finally { polling = false }
  }, 3000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>
<template>
  <main class="review-panel">
    <header class="topbar"><div><strong>Ревью pull request</strong><small>День 32 · локальный ассистент + GitHub Actions</small></div><a href="#chat/">← К чату</a></header>
    <div class="review-scroll">
      <p v-if="settings?.runtimeEnabled === false" class="banner warning" role="status">День 32 приостановлен. Очередь не обрабатывается, обращения к GitHub/LLM и публикации автоматически не выполняются.</p>
      <p class="banner">GitHub создаёт задание. Этот backend выполняет RAG и анализ. Успех Action ≠ готовое ревью.</p>
      <div v-if="error" role="alert" class="alert">{{ error }}</div>
      <div v-if="notice" role="status" class="banner">{{ notice }}</div>
      <section class="review-settings">
        <div><h2>Подключение GitHub</h2><p>{{ settings?.repository || 'Загружается…' }}</p>
          <p class="muted">{{ settings?.tokenConfigured ? 'Токен сохранён на backend' : 'Токен ещё не настроен' }} · входящих подключений нет</p></div>
        <div class="review-controls">
          <button @click="action(() => api('/reviews/connection', {}), 'Чтение GitHub доступно. Право публикации проверится при записи комментария.')" :disabled="busy || !settings?.tokenConfigured">Проверить подключение</button>
          <button @click="action(() => api('/reviews/settings', { enabled: !settings?.enabled }))" :disabled="busy || !settings?.tokenConfigured || settings?.runtimeEnabled === false">{{ settings?.enabled ? 'Отключить автоматику' : 'Включить автоматику' }}</button>
          <button @click="action(() => api('/reviews/sync', {}), 'Результат синхронизации обновлён. Причины пропуска заданий показаны ниже.')" :disabled="busy || !settings?.tokenConfigured || settings?.runtimeEnabled === false">Забрать и запустить задания</button>
        </div>
        <p class="quiet">Автоматика: {{ settings?.enabled ? 'включена, проверка каждые 30 секунд' : 'выключена' }}. До {{ settings?.dailyAttemptLimit || 20 }} попыток генерации за 24 часа.</p>
        <p v-if="settings?.poll.checkedAt" class="quiet">Последняя синхронизация: {{ new Date(settings.poll.checkedAt).toLocaleString() }} · найдено {{ settings.poll.discovered }}</p>
        <p v-if="settings?.poll.error" class="inline-error">{{ settings.poll.error }}</p>
        <details><summary>Как настроить токен</summary><p class="muted">Fine-grained token: только ai-challenge-game; Actions Read, Contents Read, Pull requests Read and write. Сохраните его через scripts/Set-Day32GitHubToken.ps1 на ПК. Не вставляйте ключ в чат или форму.</p></details>
      </section>
      <form class="review-create" @submit.prevent="start">
        <label>Номер PR<input v-model.number="prNumber" type="number" min="1" max="10000000" placeholder="Например, 1" required></label>
        <label>Модель<select v-model="provider"><option value="DEEPSEEK">DeepSeek Pro</option><option value="LOCAL">Локальная Ollama</option></select></label>
        <label class="review-checkbox"><input v-model="publish" type="checkbox">После анализа опубликовать в PR</label>
        <button class="primary" :disabled="busy || !settings?.tokenConfigured || !prNumber || settings?.runtimeEnabled === false">Запустить ревью</button>
      </form>
      <div class="review-layout">
        <section class="review-list"><h2>Проверки · {{ jobs.length }}</h2>
          <p v-if="!jobs.length" class="muted">Пока заданий нет. Создайте PR с workflow или запустите анализ по его номеру.</p>
          <button v-for="job in jobs" :key="job.id" @click="selected = job.id; expanded = false" :class="{ active: focused?.id === job.id }">
            <strong>PR #{{ job.request.pr }}</strong><span>{{ reviewLabels[job.status] || job.status }}</span><small>{{ job.request.headSha.slice(0, 10) }} · {{ job.origin }}</small>
          </button>
        </section>
        <article v-if="focused" class="review-result">
          <div class="review-result-title"><h2>PR #{{ focused.request.pr }} · {{ focused.pr?.title || 'Получение данных' }}</h2><span class="badge">{{ reviewLabels[focused.status] || focused.status }}</span></div>
          <p class="quiet">Base {{ focused.request.baseSha }}<br>Head {{ focused.request.headSha }}</p>
          <div class="review-controls"><a v-if="focused.pr" :href="focused.pr.url" target="_blank" rel="noopener noreferrer">Открыть PR</a><a :href="'/api/reviews/' + focused.id + '/report'">Скачать MD</a><a v-if="focused.commentUrl" :href="focused.commentUrl" target="_blank" rel="noopener noreferrer">Комментарий в GitHub</a></div>
          <div v-if="focused.error" class="inline-error">{{ focused.error }}<small>{{ focused.errorCode }}</small></div>
          <div v-if="focused.status === 'ANALYZING' || focused.status === 'INDEXING'" class="banner">Идёт работа. Обновление страницы не повторяет вызов модели.</div>
          <details v-if="focused.files?.length"><summary>Анализируемые файлы и diff · {{ focused.files.length }}</summary>
            <details v-for="file in focused.files" :key="file.path" class="source-card"><summary>{{ file.path }} · {{ file.status }}</summary><div class="source-body"><pre>{{ file.patch }}</pre></div></details>
          </details>
          <template v-if="focused.report">
            <p class="answer">{{ focused.report.summary }}</p>
            <p class="quiet">Покрытие: {{ focused.report.coverage === 'PARTIAL' ? 'частичное' : 'поддерживаемые текстовые файлы' }} · {{ focused.report.generation.model }} · {{ (focused.report.generation.durationMs / 1000).toFixed(1) }} с · API-токены {{ focused.report.generation.usage.inputTokens ?? '—' }} / {{ focused.report.generation.usage.outputTokens ?? '—' }}</p>
            <p v-if="!focused.report.findings.length" class="banner">Обоснованных замечаний в результате нет. Это не гарантия отсутствия ошибок.</p>
            <section v-for="(finding, i) in focused.report.findings" :key="i" class="review-finding">
              <div class="quiet">{{ finding.category }} · {{ finding.severity }}</div><h3>{{ finding.title }}</h3><p class="mono">{{ finding.path }}:{{ finding.line }} · {{ finding.side }}</p>
              <p>{{ finding.explanation }}</p><p><strong>Рекомендация:</strong> {{ finding.recommendation }}</p><p class="quiet">Источники: {{ finding.sourceIds.join(', ') }}</p>
            </section>
            <details><summary>Ограничения анализа</summary><ul><li v-for="note in focused.report.limitations" :key="note">{{ note }}</li></ul></details>
            <details :open="expanded" @toggle="expanded = ($event.target as HTMLDetailsElement).open"><summary>RAG-источники · {{ focused.report.sources.length }}</summary>
              <details v-for="source in focused.report.sources" :key="source.id" class="source-card"><summary>{{ source.id }} · {{ source.chunk.path }}:{{ source.chunk.startLine }}–{{ source.chunk.endLine }}</summary><div class="source-body"><strong>{{ source.chunk.section }}</strong><pre>{{ source.chunk.text }}</pre></div></details>
            </details>
          </template>
          <div class="review-controls review-bottom">
            <button v-if="focused.status === 'REPORT_READY'" :disabled="busy || settings?.runtimeEnabled === false" @click="action(() => api('/reviews/' + focused!.id + '/publish', {}))">Опубликовать сохранённый отчёт — без LLM</button>
            <button v-if="['FAILED', 'INTERRUPTED'].includes(focused.status) && !focused.report" :disabled="busy || settings?.runtimeEnabled === false" @click="action(() => api('/reviews/' + focused!.id + '/retry-analysis', { confirmed: true }))">Повторить анализ — новый вызов LLM</button>
          </div>
        </article>
      </div>
    </div>
  </main>
</template>
