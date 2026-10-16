<script setup lang="ts">
import { useWorkspace } from './useWorkspace'
import { statusLabel } from './api'
const { chats, turns, selected, config, context, contextEvents, latest, ready, question, provider,
  error, busy, focusedId, focused, sidebar, inspector, chatPane, receipt, running, indexStale,
  refreshContext, selectChat, newChat, buildIndex, send, retry, discardReceipt } = useWorkspace()
const examples = [
  '/help Как устроена связь ECS и Zenject в проекте?',
  '/help Как сейчас появляются боссы и чем заканчивается забег?',
  '/help Забег уже длится два часа или это только предложение?'
]
</script>

<template>
  <div class="workspace" :class="{ 'without-inspector': !inspector }">
    <nav class="navigation" :class="{ open: sidebar }" aria-label="Диалоги">
      <div class="brand"><span class="brand-icon">⌘</span><div>Project Desk<small>AI Challenge · день 31</small></div></div>
      <button class="new-chat" @click="newChat">＋ Новый диалог</button>
      <div class="nav-label">Ассистенты</div>
      <div class="agent-card"><span>◇</span><div>Помощник разработчика<small>README · docs · MCP</small></div></div>
      <div class="nav-label">Диалоги <span>{{ chats.length }}</span></div>
      <div class="chat-list">
        <button v-for="chat in chats" :key="chat.id" :class="{ active: selected === chat.id }" @click="selectChat(chat.id)">{{ chat.title }}</button>
        <p v-if="!chats.length" class="muted">Создайте первый диалог</p>
      </div>
      <div class="nav-footer"><span class="safe-dot"></span> Только чтение проекта<small>Игра работает независимо</small></div>
    </nav>
    <main class="main-panel">
      <header class="topbar">
        <button class="icon mobile-nav" @click="sidebar = !sidebar" aria-label="Открыть диалоги">☰</button>
        <div><strong>Помощник разработчика</strong><small>Вопросы о вашей игре с проверяемыми источниками</small></div>
        <button class="subtle" @click="inspector = !inspector">{{ inspector ? 'Скрыть контекст' : 'Показать контекст' }}</button>
      </header>
      <div v-if="error" class="alert" role="alert">{{ error }}<button class="icon" @click="error = ''" aria-label="Закрыть ошибку">×</button></div>
      <div v-if="indexStale" class="banner warning" role="status">Информация могла устареть. Можно продолжать по сохранённому индексу или обновить его в панели контекста.</div>
      <div v-if="!ready" class="banner">Сначала создайте индекс README и docs в панели «Контекст проекта».</div>
      <section ref="chatPane" class="conversation" aria-label="История диалога" aria-live="polite">
        <div v-if="!turns.length" class="welcome">
          <div class="welcome-icon">◇</div><h1>Разберёмся в проекте</h1>
          <p>Задайте вопрос через <code>/help</code>. Ассистент прочитает документацию через RAG и получит актуальную ветку игры через MCP.</p>
          <button v-for="example in examples" :key="example" class="example" @click="question = example">{{ example.replace('/help ', '') }} <span>↗</span></button>
          <p class="quiet">Это помощник на чтение. Он не меняет код игры и не запускает Unity.</p>
        </div>
        <article v-for="turn in turns" :key="turn.id" class="turn">
          <div class="user-message">{{ turn.question }}</div>
          <div class="assistant-message">
            <div class="answer-heading"><span class="answer-icon">◇</span><strong>Project Desk</strong><span class="badge" :class="turn.status.toLowerCase()">{{ statusLabel(turn.status) }}</span></div>
            <div v-if="turn.status === 'RUNNING'" class="thinking"><span class="pulse"></span> Получаю MCP-контекст, ищу документы и готовлю ответ…</div>
            <div v-for="warning in turn.warnings || []" :key="warning.code" class="turn-warning" role="status">{{ warning.message }}</div>
            <div v-if="turn.answer" class="answer">{{ turn.answer }}</div>
            <div v-if="turn.error" class="inline-error">{{ turn.error }}<small>{{ turn.errorCode }}</small></div>
            <div class="turn-actions">
              <button class="subtle" @click="focusedId = turn.id; inspector = true">Источники и MCP · {{ turn.sources.length }}</button>
              <a v-if="turn.status !== 'RUNNING'" :href="'/api/turns/' + turn.id + '/report'">Скачать MD</a>
            </div>
            <div v-if="turn.generation" class="metrics">{{ turn.generation.model }} · {{ (turn.generation.durationMs / 1000).toFixed(1) }} с · API-токены {{ turn.generation.usage.inputTokens ?? '—' }} / {{ turn.generation.usage.outputTokens ?? '—' }}</div>
          </div>
        </article>
      </section>
      <footer class="composer-area">
        <div v-if="receipt" class="receipt">Есть запрос с неподтверждённой отправкой.<button @click="retry" :disabled="busy">Повторить с тем же ID</button><button @click="discardReceipt">Отменить повтор</button></div>
        <form class="composer" @submit.prevent="send">
          <label class="sr-only" for="message">Вопрос о проекте</label>
          <textarea id="message" v-model="question" rows="3" maxlength="4006" placeholder="/help Как устроен проект?" @keydown.enter.exact.prevent="send"></textarea>
          <div class="composer-bottom">
            <label class="provider">Модель
              <select v-model="provider" aria-label="Модель ответа">
                <option value="LOCAL">Локально · {{ config?.localModel || 'Ollama' }}</option>
                <option value="DEEPSEEK" :disabled="!config?.cloudAvailable">DeepSeek · {{ config?.deepseekModel || 'не настроен' }}</option>
              </select>
            </label>
            <button class="send" type="submit" :disabled="!question.trim() || busy || running || !!receipt || !ready" aria-label="Отправить вопрос">{{ busy || running ? 'Подождите…' : 'Отправить ↑' }}</button>
          </div>
        </form>
        <p class="composer-note">{{ provider === 'DEEPSEEK' ? 'Вопрос и выбранные фрагменты будут отправлены DeepSeek.' : 'Генерация и поиск выполняются локально.' }} Enter — отправить · Shift+Enter — новая строка</p>
      </footer>
    </main>
    <aside v-if="inspector" class="inspector" aria-label="Контекст проекта">
      <div class="inspector-heading"><h2>Контекст проекта</h2><button class="icon" @click="refreshContext" aria-label="Обновить MCP-контекст">↻</button></div>
      <div class="project-card">
        <strong>ai-challenge-game</strong>
        <dl><dt>Ветка через MCP</dt><dd>{{ context?.branch || (context ? 'detached HEAD' : 'Не получена') }}</dd><dt>Git SHA</dt><dd class="mono">{{ context?.sha?.slice(0, 12) || '—' }}</dd><dt>Рабочие изменения</dt><dd>{{ context ? (context.dirty ? 'Есть незакоммиченные' : 'Нет') : '—' }}</dd></dl>
        <span class="pill">Read-only</span>
      </div>
      <section class="index-card">
        <h3>База знаний</h3>
        <p>{{ latest ? statusLabel(latest.status) : 'Индекс ещё не создан' }}</p>
        <p v-if="latest?.status === 'BUILDING'">{{ latest.processed }} / {{ latest.total || '…' }} фрагментов</p>
        <p v-if="latest?.error" class="inline-error">{{ latest.error }}</p>
        <p v-if="ready" class="muted">{{ ready.context?.fileCount }} документов · {{ ready.total }} фрагментов<br>{{ ready.identity?.model }}</p>
        <button class="primary" @click="buildIndex" :disabled="latest?.status === 'BUILDING' || running">{{ ready ? 'Обновить индекс' : 'Создать индекс README + docs' }}</button>
        <details v-if="ready"><summary>Документы в индексе</summary><ul class="file-list"><li v-for="file in ready.documents" :key="file">{{ file }}</li></ul></details>
      </section>
      <section class="sources">
        <h3>Источники ответа <span>{{ focused?.sources.length || 0 }}</span></h3>
        <p v-if="!focused?.sources.length" class="muted">Здесь появятся фрагменты, реально переданные модели.</p>
        <p v-if="focused?.context" class="quiet">Git на момент вопроса: {{ focused.context.sha.slice(0, 10) }}<br>Индекс: {{ focused.indexId?.slice(0, 8) || '—' }}</p>
        <p v-if="focused?.indexContext" class="quiet">Источники индекса: {{ focused.indexContext.sha.slice(0, 10) }}<br>Хеш документов: {{ focused.indexContext.fingerprint.slice(0, 12) }}</p>
        <details v-for="source in focused?.sources || []" :key="source.id" class="source-card">
          <summary><span class="source-id">{{ source.id }}</span><span>{{ source.chunk.path }}<small>строки {{ source.chunk.startLine }}–{{ source.chunk.endLine }}</small></span></summary>
          <div class="source-body"><strong>{{ source.chunk.section }}</strong><p class="quiet">{{ source.chunk.status }} · cosine {{ source.cosine.toFixed(3) }}</p><pre>{{ source.chunk.text }}</pre><small class="mono">chunk {{ source.chunk.chunkId }} · hash {{ source.chunk.fileHash.slice(0, 12) }}</small></div>
        </details>
        <p v-if="focused?.generation" class="quiet">Ссылки: {{ focused.citationStatus }}. Проверка ID не доказывает истинность утверждений.</p>
      </section>
      <details class="event-log"><summary>Журнал MCP · {{ (focused?.events || contextEvents).length }}</summary><div v-for="(event, i) in focused?.events || contextEvents" :key="i" class="event"><strong>{{ event.method }}</strong><span>{{ event.status }} · {{ event.durationMs }} мс</span><small>{{ event.details }}</small></div></details>
    </aside>
  </div>
</template>
