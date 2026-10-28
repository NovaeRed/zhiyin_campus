<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { message } from 'ant-design-vue';
import WorkspaceShell from '../components/WorkspaceShell.vue';
import Icon from '../components/Icon.vue';
import ragService from '../services/rag';
import type { RagSource } from '../services/rag';
import { getHistory } from '../services/portal';
import { ApiError, errorMessage } from '../services/api';
interface ChatMessage {
  id: number;
  text: string;
  sender: 'user' | 'bot';
  sources?: RagSource[];
}
const route = useRoute(),
  router = useRouter(),
  historyId = computed(() => String(route.query.session || ''));
const messages = ref<ChatMessage[]>([]),
  input = ref(''),
  major = ref(''),
  grade = ref(''),
  cohort = ref('');
const loading = ref(false),
  historyLoading = ref(false),
  historyError = ref(''),
  retry = ref(0),
  sessionId = ref<number>();
const messageViewport = ref<HTMLDivElement>();
let sending = false,
  epoch = 0,
  publishedSession = '',
  localReset = false,
  pending: { signature: string; requestId: string } | null = null;
const topics = [
  {
    icon: 'cap',
    title: '培养方案',
    description: '课程安排，提前心中有数',
    question: '我想查询培养方案，需要提供哪些信息？',
  },
  {
    icon: 'chart',
    title: '学分与课程',
    description: '学分、学期，一问就明白',
    question: '2024级计算机科学与技术专业的大三有哪些课程？',
  },
  {
    icon: 'award',
    title: '奖助学金',
    description: '了解申请条件与评定规则',
    question: '奖学金的申请条件是什么？',
  },
];
function reset() {
  epoch++;
  sending = false;
  pending = null;
  input.value = '';
  major.value = '';
  grade.value = '';
  cohort.value = '';
  messages.value = [];
  sessionId.value = undefined;
  loading.value = false;
  historyLoading.value = false;
  historyError.value = '';
}
watch(
  [historyId, retry],
  async ([id], _previous, onCleanup) => {
    const alreadyReset = localReset && !id;
    localReset = false;
    if (alreadyReset) return;
    if (id && publishedSession === id) {
      publishedSession = '';
      return;
    }
    reset();
    if (!id) return;
    let active = true;
    const context = epoch,
      isCurrent = () => active && context === epoch;
    onCleanup(() => {
      active = false;
    });
    historyLoading.value = true;
    try {
      const numeric = Number(id);
      if (!Number.isSafeInteger(numeric) || numeric < 1) throw new Error('会话编号不合法');
      const restored: ChatMessage[] = [];
      let page = 1,
        total = 0,
        count = 0;
      do {
        const result = await getHistory(numeric, page++);
        if (!isCurrent()) return;
        total = result.total;
        count += result.items.length;
        for (const query of result.items) {
          let sources: RagSource[] = [];
          try {
            const parsed = JSON.parse(query.sourcesJson || '[]');
            if (Array.isArray(parsed)) sources = parsed;
          } catch {
            /* Legacy source snapshots may be absent. */
          }
          restored.push(
            { id: query.queryId * 2, text: query.question, sender: 'user' },
            {
              id: query.queryId * 2 + 1,
              text:
                query.answer ||
                (query.state === 'completed'
                  ? '该历史记录没有保存回答正文。'
                  : '该次问答未完成，可重新提问。'),
              sender: 'bot',
              sources: query.answer ? sources : [],
            },
          );
        }
        if (!result.items.length) break;
      } while (count < total);
      if (isCurrent()) {
        messages.value = restored;
        sessionId.value = numeric;
      }
    } catch (e) {
      if (isCurrent()) historyError.value = errorMessage(e);
    } finally {
      if (isCurrent()) historyLoading.value = false;
    }
  },
  { immediate: true },
);
watch(
  [messages, loading],
  async () => {
    await nextTick();
    const el = messageViewport.value;
    if (el) el.scrollTop = el.scrollHeight;
  },
  { deep: true },
);
onBeforeUnmount(() => {
  epoch++;
});
async function send() {
  if (
    !input.value.trim() ||
    sending ||
    loading.value ||
    historyLoading.value ||
    historyError.value ||
    (grade.value && !/^\d{4}$/.test(grade.value))
  )
    return;
  sending = true;
  const current = epoch,
    question = input.value.trim();
  const request = {
    question,
    sessionId: sessionId.value,
    targetMajor: major.value.trim() || undefined,
    targetGrade: grade.value ? Number(grade.value) : undefined,
    targetCohort: cohort.value.trim() || undefined,
  };
  const signature = JSON.stringify(request);
  if (pending?.signature !== signature) pending = { signature, requestId: crypto.randomUUID() };
  const requestId = pending.requestId,
    id = Date.now();
  messages.value.push({ id, text: question, sender: 'user' });
  input.value = '';
  loading.value = true;
  try {
    const response = await ragService.ask({ ...request, requestId });
    if (current !== epoch) return;
    pending = null;
    sessionId.value = response.sessionId;
    messages.value.push({
      id: Date.now(),
      text: response.answer,
      sender: 'bot',
      sources: response.sources,
    });
    if (!historyId.value) {
      publishedSession = String(response.sessionId);
      await router.replace({ path: '/', query: { session: publishedSession } });
    }
  } catch (e) {
    if (current !== epoch) return;
    if (e instanceof ApiError && e.code === 409) {
      try {
        const status = await ragService.requestStatus(requestId);
        if (current !== epoch) return;
        if (['failed', 'cancelled'].includes(status.state)) pending = null;
      } catch {
        /* Retain the key until outcome is known. */
      }
    }
    if (current !== epoch) return;
    if (e instanceof ApiError && e.code >= 400 && e.code < 500 && e.code !== 409) pending = null;
    message.error(errorMessage(e));
    messages.value = messages.value.filter((item) => item.id !== id);
    if (!input.value) input.value = question;
  } finally {
    if (current === epoch) {
      sending = false;
      loading.value = false;
    }
  }
}
async function newChat() {
  publishedSession = '';
  const navigate = !!historyId.value;
  reset();
  if (navigate) {
    const current = epoch;
    localReset = true;
    historyLoading.value = true;
    try {
      await router.push('/');
    } finally {
      if (current === epoch) historyLoading.value = false;
    }
  }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}
</script>
<template>
  <WorkspaceShell
    title="校园问答"
    :busy="loading || historyLoading"
    chat-layout
    @new="newChat"
    ><div class="content-wrap">
      <div
        ref="messageViewport"
        class="chat-scroll"
        tabindex="0"
        aria-label="聊天内容"
      >
        <div
          v-if="historyId"
          class="session-heading"
        >
          <span>历史会话 #{{ historyId }} · 查询范围需按本次问题填写</span
          ><RouterLink to="/history">返回历史列表</RouterLink>
        </div>
        <div
          v-if="historyLoading"
          class="history-load"
        >
          <a-spin tip="正在读取历史对话…" />
        </div>
        <a-alert
          v-else-if="historyError"
          type="error"
          message="无法读取会话"
          :description="historyError"
          ><template #action><a-button @click="retry++">重试</a-button></template></a-alert
        >
        <template v-else-if="!messages.length"
          ><section class="welcome-banner">
            <img
              src="/campus-garden.png"
              alt="绿树环绕的校园图书馆与书本雕塑"
            />
            <div class="banner-copy">
              <span class="eyebrow">YOUR CAMPUS COMPANION</span>
              <h1>校园里的疑问，<br />在这里找到答案<span>。</span></h1>
              <p>从一门课程，到一份成长计划。<br />让复杂的信息，成为清晰的下一步。</p>
              <span class="banner-label"
                ><Icon
                  name="book"
                  :size="16"
                />有来源的解答，更安心的探索</span
              >
            </div>
          </section>
          <section class="explore">
            <div class="section-heading">
              <h2>从你关心的事开始</h2>
              <span>选择一个方向，开启探索</span>
            </div>
            <div class="topic-grid">
              <button
                v-for="(topic, i) in topics"
                :key="topic.title"
                :class="`topic-card topic-${i}`"
                @click="input = topic.question"
              >
                <span class="topic-icon"
                  ><Icon
                    :name="topic.icon"
                    :size="24"
                /></span>
                <h3>{{ topic.title }}</h3>
                <p>{{ topic.description }}</p>
                <span class="topic-link"
                  >试着问一问
                  <Icon
                    name="arrow"
                    :size="17"
                /></span>
              </button>
            </div></section
        ></template>
        <section
          v-else
          class="conversation"
          aria-label="问答记录"
          aria-live="polite"
        >
          <article
            v-for="item in messages"
            :key="item.id"
            :class="['chat-message', item.sender]"
          >
            <div class="message-avatar">
              <Icon
                v-if="item.sender === 'bot'"
                name="book"
              /><template v-else>我</template>
            </div>
            <div class="message-body">
              <h3>{{ item.sender === 'bot' ? '知引' : '你' }}</h3>
              <div class="answer-text">{{ item.text }}</div>
              <div
                v-if="item.sources?.length"
                class="sources"
              >
                <span>参考资料 · {{ item.sources.length }}</span>
                <details
                  v-for="(source, i) in item.sources"
                  :key="`${source.chunkId}-${i}`"
                >
                  <summary>
                    <Icon
                      name="file"
                      :size="16"
                    />[{{ i + 1 }}] {{ source.title }}
                  </summary>
                  <p>{{ source.content }}</p>
                </details>
              </div>
            </div>
          </article>
          <div
            v-if="loading"
            class="thinking"
            role="status"
          >
            正在查阅校园资料，请稍候…
          </div>
        </section>
      </div>
      <section class="composer-area">
        <div class="scope-row">
          <span>查询范围（可选）</span
          ><a-input
            v-model:value="major"
            aria-label="专业"
            placeholder="专业，如计算机科学与技术"
            :disabled="loading"
          /><a-input
            v-model:value="grade"
            aria-label="入学年份"
            placeholder="入学年份，如2024"
            :maxlength="4"
            :disabled="loading"
            @input="grade = grade.replace(/\D/g, '')"
          /><a-input
            v-model:value="cohort"
            class="cohort-field"
            aria-label="毕业届别"
            placeholder="毕业届别，如2028届"
            :disabled="loading"
          />
        </div>
        <div class="composer">
          <a-textarea
            v-model:value="input"
            aria-label="你的校园问题"
            placeholder="有什么校园问题想了解？"
            :auto-size="{ minRows: 2, maxRows: 6 }"
            @keydown="keydown"
          />
          <div class="composer-actions">
            <span class="attach-button">专业、入学年份与毕业届别可分别指定</span
            ><span class="enter-hint">Enter 发送 · Shift + Enter 换行</span
            ><a-button
              type="primary"
              aria-label="发送问题"
              :loading="loading"
              :disabled="
                historyLoading ||
                !!historyError ||
                !input.trim() ||
                (!!grade && !/^\d{4}$/.test(grade))
              "
              @click="send"
              ><Icon name="arrow"
            /></a-button>
          </div>
        </div>
        <p class="disclaimer">
          回答基于已有校园资料生成，请结合引用原文核实；具体事项以学校最新通知为准。
        </p>
      </section>
    </div></WorkspaceShell
  >
</template>
