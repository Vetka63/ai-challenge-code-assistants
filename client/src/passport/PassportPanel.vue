<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type Config } from '../api'
import { label, loadPassport, loadCheck, saveSettings, startCheck, type Check, type Overview, type Settings } from './passportApi'
defineEmits(['menu'])
const overview = ref<Overview | null>(null), selected = ref<Check | null>(null), active = ref<Check | null>(null)
const settings = ref<Settings>({ enabled: false, intervalSeconds: 600, provider: 'LOCAL', dailyLimit: 12 })
const config = ref<Config | null>(null), error = ref(''), pollError = ref(''), busy = ref(false), viewingHistory = ref(false)
const receipt = ref(typeof localStorage === 'undefined' ? '' : localStorage.getItem('passport-pending-id') || '')
let timer: ReturnType<typeof setInterval> | undefined, polling = false, stopped = false
const date = (value: string | null | undefined) => value ? new Date(value).toLocaleString('ru-RU') : '—'
const model = computed(() => settings.value.provider === 'DEEPSEEK' ? config.value?.deepseekModel : config.value?.localModel)
const settingsDirty = computed(() => !!overview.value && JSON.stringify(settings.value) !== JSON.stringify(overview.value.state.settings))
async function refresh(initial = false) {
  if (polling) return
  polling = true
  try {
    const value = await loadPassport()
    if (stopped) return
    overview.value = value
    pollError.value = ''
    if (initial) settings.value = { ...value.state.settings }
    if (!viewingHistory.value) selected.value = value.current
    active.value = value.state.activeId ? await loadCheck(value.state.activeId) : value.history[0] ? await loadCheck(value.history[0].id) : null
  } catch (e) { pollError.value = (e as Error).message } finally { polling = false }
}
async function save() {
  busy.value = true; error.value = ''
  try { await saveSettings(settings.value); await refresh() } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function pause() { if (!overview.value) return; settings.value = { ...overview.value.state.settings, enabled: false }; await save() }
async function checkNow(retry = false) {
  if (retry && !confirm('Повторить генерацию для неизменившихся или ранее ошибочных источников? Это новый вызов выбранной модели, он может быть платным.')) return
  busy.value = true; error.value = ''
  try {
    const id = receipt.value || crypto.randomUUID()
    receipt.value = id; localStorage.setItem('passport-pending-id', id)
    await startCheck(id, retry)
    receipt.value = ''; localStorage.removeItem('passport-pending-id')
    viewingHistory.value = false
    await refresh()
  } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function select(id: string) {
  try { selected.value = await loadCheck(id); viewingHistory.value = true } catch (e) { error.value = (e as Error).message }
}
function current() { viewingHistory.value = false; selected.value = overview.value?.current || null }
function source(id: string) { document.getElementById('passport-source-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); const el = document.getElementById('passport-source-' + id) as HTMLDetailsElement | null; if (el) el.open = true }
onMounted(async () => { config.value = await api<Config>('/config').catch(() => null); await refresh(true); timer = setInterval(() => void refresh(), 3000) })
onUnmounted(() => { stopped = true; if (timer) clearInterval(timer) })
</script>

<template>
  <main class="passport-panel">
    <header class="passport-top"><div class="passport-heading"><button class="icon" @click="$emit('menu')" aria-label="Меню">☰</button><div><small>ДЕНЬ 35 · АВТОМАТИЗАЦИЯ РЕАЛЬНОЙ ЗАДАЧИ</small><h1>Живой паспорт игры</h1><p>Документация и код → MCP → AI → новая версия. Игра только для чтения.</p></div></div><span class="passport-tag">{{ overview?.state.settings.enabled ? 'Наблюдение включено' : 'Наблюдение на паузе' }}</span></header>
    <div class="passport-scroll">
      <div v-if="error || pollError" class="passport-error" role="alert">{{ error || pollError }}<button @click="error = ''; pollError = ''" aria-label="Закрыть ошибку">×</button></div>
      <section class="passport-settings" aria-label="Настройки наблюдения">
        <label>Модель<select v-model="settings.provider"><option value="LOCAL">Локальная · {{ config?.localModel || 'Ollama' }}</option><option value="DEEPSEEK" :disabled="!config?.cloudAvailable">DeepSeek Pro</option></select></label>
        <label>Интервал после завершения<select v-model.number="settings.intervalSeconds"><option :value="600">10 минут · рабочий режим</option><option :value="30">30 секунд · ДЕМО ДЛЯ ВИДЕО</option></select></label>
        <label>Генераций в сутки (UTC)<input v-model.number="settings.dailyLimit" type="number" min="1" max="500" /></label>
        <label class="passport-toggle"><input v-model="settings.enabled" type="checkbox" />Проверять автоматически</label>
        <button class="primary" @click="save" :disabled="busy">Сохранить настройки</button>
        <p class="passport-wide">{{ model || 'Модель' }} · Без изменений вызов LLM пропускается. {{ settings.provider === 'DEEPSEEK' ? 'Выбранные тексты будут отправлены в DeepSeek; изображения не отправляются.' : 'Генерация на локальной модели.' }}</p>
      </section>
      <div class="passport-status-grid"><div><small>Последняя проверка</small><strong>{{ date(overview?.history[0]?.finishedAt || overview?.history[0]?.createdAt) }}</strong></div><div><small>Следующая проверка</small><strong>{{ date(overview?.state.nextCheckAt) }}</strong></div><div><small>Генерации сегодня (UTC)</small><strong>{{ overview?.attemptsToday ?? 0 }} / {{ overview?.state.settings.dailyLimit ?? 12 }}</strong></div><div><small>Активный интервал</small><strong>{{ overview?.state.settings.intervalSeconds === 30 ? 'ДЕМО · 30 секунд' : '10 минут' }}</strong></div></div>
      <p v-if="settingsDirty" class="passport-notice">Настройки изменены, но ещё не сохранены. Сначала нажмите «Сохранить настройки».</p>
      <div class="passport-actions"><button class="primary" :disabled="busy || !!overview?.state.activeId || (settingsDirty && !receipt)" @click="checkNow()">{{ receipt ? 'Проверить отправку с тем же ID' : 'Проверить сейчас' }}</button><button class="subtle" :disabled="busy || !overview?.state.settings.enabled" @click="pause">Пауза наблюдения</button><button class="subtle" :disabled="busy || !!overview?.state.activeId || !!receipt || settingsDirty" @click="checkNow(true)">Повторить генерацию с подтверждением…</button></div>
      <p v-if="receipt" class="passport-notice">Отправка не подтверждена. Повтор использует тот же ID и не создаст второй вызов.</p>
      <section v-if="active" class="passport-activity" aria-live="polite"><div><strong>{{ label(active.status) }}</strong><small>{{ label(active.trigger) }} · {{ date(active.createdAt) }}</small></div><p v-if="active.error" class="passport-error">{{ active.error }} · {{ active.errorCode }}</p><details><summary>Алгоритм и журнал проверки · {{ active.events.length }} операций</summary><ol><li v-for="(event, i) in active.events" :key="i"><strong>{{ event.stage }}</strong> · {{ event.detail }}<small>{{ date(event.at) }}</small></li></ol></details><details v-if="active.generation && active.status !== 'COMPLETED'"><summary>Сохранённый ответ модели для диагностики</summary><pre>{{ active.generation.text }}</pre></details></section>
      <div class="passport-content">
        <section class="passport-document">
          <div class="passport-title"><div><small>{{ viewingHistory ? selected?.status === 'COMPLETED' ? 'ИСТОРИЧЕСКАЯ ВЕРСИЯ' : 'ПРОВЕРКА БЕЗ НОВОЙ ВЕРСИИ' : 'ПОСЛЕДНЯЯ УСПЕШНАЯ ВЕРСИЯ' }}</small><h2>{{ selected?.draft?.title || (selected ? label(selected.status) : 'Паспорт ещё не сформирован') }}</h2></div><a v-if="selected" :href="'/api/passport/checks/' + selected.id + '/report'">Скачать MD ↓</a></div>
          <p v-if="!selected" class="passport-empty">Сохраните модель в настройках и нажмите «Проверить сейчас». История, источники и карточки появятся здесь после успешной генерации.</p>
          <template v-if="selected">
            <p v-if="(selected.validationVersion ?? 1) < 2" class="passport-notice">Ранняя диагностическая версия: она создана до усиления проверки «план / готовая реализация». Для текущего состояния используйте новый паспорт. История сохранена без переписывания.</p>
            <div class="passport-version">Версия {{ selected.id.slice(0, 8) }} · {{ date(selected.finishedAt) }} · {{ selected.provider }}<br />Снимок {{ selected.snapshotId }}<br /><span>SHA-256 {{ selected.fingerprint }}</span></div>
            <p v-if="selected.error" class="passport-error">{{ selected.error }}</p>
            <template v-if="selected.draft">
              <h3>Что за игра</h3><ul class="passport-claims"><li v-for="(claim, i) in selected.draft.overview" :key="i"><p>{{ claim.text }}</p><small>{{ label(claim.kind) }}</small> <button v-for="id in claim.sources" :key="id" class="passport-citation" @click="source(id)">[{{ id }}]</button></li></ul>
              <h3>Основные механики</h3><ul class="passport-claims"><li v-for="(claim, i) in selected.draft.mechanics" :key="i"><p>{{ claim.text }}</p><small>{{ label(claim.kind) }}</small> <button v-for="id in claim.sources" :key="id" class="passport-citation" @click="source(id)">[{{ id }}]</button></li></ul>
              <h3>Сцены, режимы и запланированные уровни</h3><p class="passport-caption">Сцена в настройках сборки не равна отдельному пройденному уровню.</p><div class="passport-levels"><article v-for="level in selected.draft.levels" :key="level.id" class="passport-level"><span class="passport-tag">{{ label(level.kind) }}</span><h4>{{ level.name }}</h4><div v-for="(claim, i) in level.details" :key="i"><p>{{ claim.text }}</p><small>{{ label(claim.kind) }}</small> <button v-for="id in claim.sources" :key="id" class="passport-citation" @click="source(id)">[{{ id }}]</button></div></article></div>
              <h3>Неизвестное и вопросы</h3><ul><li v-for="(question, i) in selected.draft.questions" :key="i">{{ question }}</li></ul>
            </template>
            <h3>Галерея из проекта</h3><p class="passport-caption">Изображения модель не анализировала. Это сохранённые иллюстрации: дата импорта не равна дате съёмки; соответствие текущей сборке не подтверждено.</p><p v-if="!selected.images.length" class="passport-caption">Разрешённых PNG/JPEG не найдено.</p><div class="passport-gallery"><figure v-for="image in selected.images" :key="image.id + image.path"><a :href="'/api/passport/checks/' + selected.id + '/images/' + image.id" target="_blank" rel="noopener"><img :src="'/api/passport/checks/' + selected.id + '/images/' + image.id" :alt="image.path" loading="lazy" /></a><figcaption>{{ image.path }}<small>{{ image.width }}×{{ image.height }} · импорт {{ date(image.importedAt) }}<br />{{ image.linkedFrom.length ? 'Связанные документы: ' + image.linkedFrom.join(', ') : 'Папка Screenshots; связь с карточкой не установлена' }}</small></figcaption></figure></div>
            <details><summary>Изменения относительно предыдущего успешного паспорта · {{ selected.changes.length }}</summary><ul><li v-for="change in selected.changes" :key="change.path">{{ label(change.kind) }} · {{ change.path }}</li></ul><p v-if="!selected.changes.length">Тексты не изменились. При новой версии могли измениться только изображения.</p></details>
            <h3>Проверяемые источники · {{ selected.sources.length }}</h3><p class="passport-caption">Проверка ID и хеша не доказывает истинность вывода. Читайте фрагменты и сравнивайте с формулировками.</p><details v-for="item in selected.sources" :id="'passport-source-' + item.id" :key="item.id" class="passport-source"><summary>[{{ item.id }}] {{ item.path }}:{{ item.startLine }}–{{ item.endLine }}</summary><small>SHA-256 {{ item.hash }}</small><pre>{{ item.text }}</pre></details>
            <details open><summary>Охват и ограничения</summary><ul><li v-for="warning in selected.warnings" :key="warning">{{ warning }}</li></ul></details>
            <p v-if="selected.generation" class="passport-caption">{{ selected.generation.model }} · {{ (selected.generation.durationMs / 1000).toFixed(1) }} с · API input/output: {{ selected.generation.usage.inputTokens ?? '—' }} / {{ selected.generation.usage.outputTokens ?? '—' }} · {{ selected.generation.finishReason }}</p>
          </template>
        </section>
        <aside class="passport-history"><div><h2>История проверок</h2><button class="subtle" @click="current">К текущему паспорту</button></div><p class="passport-caption">Каждый тик виден, даже без вызова модели. Старые версии не перезаписываются.</p><button v-for="item in overview?.history || []" :key="item.id" :class="{ selected: selected?.id === item.id }" @click="select(item.id)"><strong>{{ label(item.status) }}</strong><span>{{ label(item.trigger) }} · {{ date(item.createdAt) }}</span><small>{{ item.generation ? item.generation.model : item.attemptedAt ? 'Попытка LLM: итог может быть неизвестен' : 'Без вызова LLM' }}</small></button></aside>
      </div>
    </div>
  </main>
</template>

<style scoped>
.passport-panel{display:flex;flex-direction:column;min-width:0;min-height:0;background:#f4f7f8;grid-column:2/-1}.passport-top{padding:25px 32px;background:#fff;border-bottom:1px solid #dfe7eb;display:flex;justify-content:space-between;align-items:center;gap:20px}.passport-heading{display:flex;gap:12px;align-items:flex-start}.passport-heading small{letter-spacing:1px;color:#64816e;font-size:10px}.passport-heading h1{margin:7px 0;font-size:27px}.passport-heading p{font-size:12px;color:#71838c;margin:0}.passport-scroll{overflow:auto;padding:25px 32px 60px;min-height:0}.passport-tag{display:inline-block;background:#e7f1eb;color:#466b57;border-radius:20px;padding:6px 10px;font-size:10px;white-space:nowrap}.passport-settings{display:flex;flex-wrap:wrap;gap:16px;background:#fff;padding:20px;border:1px solid #dce5e6;border-radius:13px;align-items:end}.passport-settings label{display:flex;flex-direction:column;gap:8px;font-size:11px;color:#607881}.passport-settings select,.passport-settings input[type=number]{border:1px solid #d2dfe1;background:#f8faf9;border-radius:7px;padding:10px;max-width:240px;font-size:12px}.passport-settings input[type=number]{width:100px}.passport-settings .passport-toggle{flex-direction:row;align-items:center;padding:10px 0}.passport-wide{flex-basis:100%;font-size:11px;color:#708788;margin:0}.passport-status-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:18px 0}.passport-status-grid>div{padding:14px 16px;border:1px solid #e0e7e9;background:#fff;border-radius:10px}.passport-status-grid small,.passport-status-grid strong{display:block}.passport-status-grid small{color:#778d92}.passport-status-grid strong{font-size:12px;margin-top:8px}.passport-actions{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.passport-activity{background:#edf4f5;border:1px solid #d6e4e6;padding:16px 20px;border-radius:10px;margin-bottom:25px;font-size:12px}.passport-activity>div{display:flex;justify-content:space-between;gap:15px}.passport-activity ol{padding-left:18px;line-height:1.8}.passport-activity li small{display:block;color:#829297}.passport-content{display:grid;grid-template-columns:minmax(0,1fr) 270px;gap:24px;align-items:start}.passport-document{background:#fff;border:1px solid #dce5e6;border-radius:14px;padding:30px;min-width:0;font-size:14px;line-height:1.8}.passport-title{display:flex;justify-content:space-between;gap:15px;align-items:start}.passport-title small{font-size:9px;letter-spacing:1px;color:#718b7b}.passport-title h2{font-size:25px;line-height:1.4;margin:7px 0 20px}.passport-title a{font-size:11px;white-space:nowrap}.passport-version{font-size:10px;color:#819198;overflow-wrap:anywhere;border-bottom:1px solid #edf1f2;padding-bottom:18px;margin-bottom:26px}.passport-document h3{font-size:17px;margin:30px 0 12px}.passport-claims{padding:0;list-style:none}.passport-claims li{padding:12px 0;border-bottom:1px solid #edf1f2}.passport-claims p{margin:0 0 6px}.passport-claims small,.passport-level small{color:#83928e;font-size:10px}.passport-citation{padding:2px 6px;color:#49775b;background:#edf5ee;border-radius:4px;font-size:10px;margin-left:4px}.passport-levels{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.passport-level{border:1px solid #dce6df;border-radius:12px;padding:18px;background:#f9fcfa}.passport-level h4{font-size:19px;margin:12px 0}.passport-level p{font-size:12px;margin:14px 0 3px}.passport-caption{font-size:11px;color:#7c8d94;line-height:1.8}.passport-gallery{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:15px}.passport-gallery figure{margin:0;border:1px solid #e1e7e9;border-radius:9px;overflow:hidden;min-width:0}.passport-gallery img{display:block;width:100%;height:190px;object-fit:contain;background:#121d23}.passport-gallery figcaption{padding:11px;font-size:10px;overflow-wrap:anywhere}.passport-gallery small{display:block;color:#859396;font-size:9px}.passport-source{border:1px solid #dce6e8;border-radius:7px;padding:10px 13px;scroll-margin:25px}.passport-source small{overflow-wrap:anywhere;font-size:9px}.passport-source pre,.passport-activity pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:430px;overflow:auto;font-size:11px;line-height:1.8}.passport-history{position:sticky;top:0;max-height:85vh;overflow:auto;padding:20px;background:#eaf0f2;border-radius:12px}.passport-history h2{font-size:15px;margin-bottom:6px}.passport-history>button{display:block;text-align:left;width:100%;padding:13px 12px;background:#fff;border:1px solid #dde7e8;border-radius:8px;margin:10px 0;color:#334b55}.passport-history>button.selected{border-color:#6b967f;background:#f1f8f3}.passport-history strong,.passport-history span,.passport-history small{display:block}.passport-history strong{font-size:11px;line-height:1.7}.passport-history span{font-size:10px;color:#7e9298;margin:4px 0}.passport-history small{font-size:9px;color:#6d8c76}.passport-error{background:#fff0eb;color:#944a39;border:1px solid #f0d4ca;padding:12px;border-radius:8px;font-size:12px;margin-bottom:15px;display:flex;justify-content:space-between;gap:10px}.passport-error button{background:transparent}.passport-notice{background:#fff4d9;padding:12px;color:#8b7042;font-size:12px}.passport-empty{padding:35px 0;color:#7f9297}.primary{padding:11px 16px;background:#416e59;color:#fff;border-radius:8px;font-size:12px}.primary:hover{background:#315a46}
@media(max-width:1200px){.passport-content{grid-template-columns:minmax(0,1fr)}.passport-history{position:static;max-height:400px}.passport-status-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:700px){.passport-panel{width:100%}.passport-scroll{padding:18px 14px}.passport-top{padding:18px 14px;align-items:start}.passport-top>.passport-tag{display:none}.passport-heading h1{font-size:23px}.passport-levels,.passport-gallery{grid-template-columns:1fr}.passport-document{padding:20px}.passport-title{flex-direction:column}.passport-settings label,.passport-settings select{max-width:100%}.passport-activity>div{flex-direction:column}}
.passport-scroll{overflow-x:hidden;overflow-wrap:anywhere}
</style>
