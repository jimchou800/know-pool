<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import AnswerWindow from './AnswerWindow.vue';
import { createAnswerClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
  initialId: { type: String, default: null },
});
const api = createAnswerClient(props.client);
const rows = ref([]),
  windows = ref({}),
  selected = ref(null),
  cursor = ref(null),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('');
const action = ref(''),
  name = ref(''),
  fields = ref([]),
  panel = ref(null),
  nameInput = ref(null),
  createButton = ref(null);
let sequence = 0,
  listSequence = 0,
  selectionSequence = 0,
  key,
  target,
  trigger;
const current = () => windows.value[selected.value]?.conversation;
function sort() {
  rows.value.sort(
    (a, b) =>
      b.updatedAt.localeCompare(a.updatedAt) || b.id.localeCompare(a.id),
  );
}
async function select(id) {
  if (action.value || busy.value) return;
  const selection = ++selectionSequence;
  const existing = Object.values(windows.value).find(
    (window) => window.conversation?.id === id,
  );
  if (existing) {
    selected.value = existing.key;
    return;
  }
  const version = sequence;
  error.value = '';
  try {
    const value = await api.getConversation(id);
    if (version !== sequence || selection !== selectionSequence) return;
    windows.value[id] = { key: id, conversation: value };
    selected.value = id;
    if (!rows.value.some((item) => item.id === id)) {
      rows.value.push(value);
      sort();
    }
  } catch (failure) {
    if (version === sequence) error.value = failure.message;
  }
}
async function load(more = false) {
  if (loading.value) return;
  const version = ++listSequence;
  loading.value = true;
  error.value = '';
  try {
    const value = await api.list(more ? cursor.value : undefined);
    if (version !== listSequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...value.items.filter(
            (item) => !rows.value.some((old) => old.id === item.id),
          ),
        ]
      : value.items;
    cursor.value = value.nextCursor;
    if (!selected.value) {
      if (props.initialId) await select(props.initialId);
      else if (rows.value.length) await select(rows.value[0].id);
    }
  } catch (failure) {
    if (version === listSequence) error.value = failure.message;
  } finally {
    if (version === listSequence) loading.value = false;
  }
}
async function create() {
  if (!props.writable || busy.value || action.value) return;
  ++selectionSequence;
  const id = crypto.randomUUID();
  windows.value[id] = { key: id, conversation: null };
  selected.value = id;
  await nextTick();
  document.getElementById(`question-${id}`)?.focus();
}
function saved(windowKey, value) {
  if (!windows.value[windowKey]) return;
  windows.value[windowKey].conversation = value;
  if (!rows.value.some((item) => item.id === value.id)) rows.value.push(value);
  sort();
}
function selectDraft(id) {
  if (action.value || busy.value) return;
  ++selectionSequence;
  selected.value = id;
}
async function changed(value) {
  const version = sequence,
    window = Object.values(windows.value).find(
      (item) => item.conversation?.id === value.id,
    );
  if (!window) return;
  const revision = (window.revision || 0) + 1;
  window.revision = revision;
  try {
    if (value.reload) value = await api.getConversation(value.id);
    if (
      version !== sequence ||
      !windows.value[window.key] ||
      window.revision !== revision
    )
      return;
    window.conversation = value;
    rows.value = rows.value.map((item) =>
      item.id === value.id ? value : item,
    );
    sort();
  } catch (failure) {
    if (version === sequence) error.value = failure.message;
  }
}
async function open(kind, event) {
  if (!current() || busy.value || !props.writable) return;
  target = current().id;
  trigger = event.currentTarget;
  action.value = kind;
  name.value = current().name;
  key = undefined;
  error.value = '';
  fields.value = [];
  await nextTick();
  (kind === 'rename' ? nameInput.value : panel.value)?.focus();
}
async function close() {
  if (busy.value || key) return;
  action.value = '';
  await nextTick();
  if (trigger?.isConnected) trigger.focus();
  else createButton.value?.focus();
}
async function execute() {
  if (busy.value || !props.writable) return;
  const version = sequence;
  busy.value = true;
  error.value = '';
  fields.value = [];
  try {
    if (action.value === 'rename') {
      const value = await api.rename(target, name.value);
      if (version !== sequence) return;
      await changed(value);
      busy.value = false;
      await close();
    } else {
      key ||= crypto.randomUUID();
      await api.remove(target, key);
      if (version !== sequence) return;
      ++sequence;
      ++listSequence;
      loading.value = false;
      rows.value = rows.value.filter((item) => item.id !== target);
      for (const [id, window] of Object.entries(windows.value)) {
        if (window.conversation?.id === target) {
          delete windows.value[id];
          if (selected.value === id) selected.value = null;
        }
      }
      key = undefined;
      action.value = '';
      busy.value = false;
      notice.value =
        '会话记录及专属引用快照已删除，源资料、知识库和其他会话不受影响。';
      if (rows.value.length) await select(rows.value[0].id);
      await nextTick();
      createButton.value?.focus();
    }
  } catch (failure) {
    if (version === sequence) {
      error.value = failure.message;
      fields.value = failure.fieldErrors || [];
      if (failure.code) key = undefined;
      await nextTick();
      panel.value?.focus();
    }
  } finally {
    busy.value = false;
  }
}
watch(
  () => props.initialId,
  (id) => {
    if (id) select(id);
  },
);
onBeforeUnmount(() => {
  ++sequence;
  ++listSequence;
});
load();
</script>
<template>
  <section class="datasets-page search-page" aria-labelledby="answers-title">
    <header class="datasets-heading">
      <div>
        <p class="welcome-kicker">知识服务</p>
        <h1 id="answers-title">知识问答</h1>
        <p>会话自动保存在本地；切换保留输入并继续原任务，不自动重新收费。</p>
      </div>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div class="datasets-layout">
      <aside class="dataset-list" aria-label="问答实例">
        <div class="dataset-list-heading">
          <h2>问答实例</h2>
          <button
            ref="createButton"
            :disabled="busy || !writable || !!action"
            @click="create"
          >
            新建问答
          </button>
        </div>
        <button :disabled="loading || !!action" @click="load()">
          刷新问答列表
        </button>
        <p v-if="loading" role="status">正在读取会话…</p>
        <p v-if="!loading && !rows.length">尚无已保存问答。</p>
        <ul class="dataset-rows">
          <li
            v-for="item in rows"
            :key="item.id"
            :data-conversation-id="item.id"
          >
            <button
              :disabled="busy || !!action"
              :aria-current="current()?.id === item.id ? 'true' : undefined"
              @click="select(item.id)"
            >
              <strong>{{ item.name }}</strong
              ><span
                >{{
                  item.activeTask
                    ? '处理中'
                    : item.lastTask?.state === 'interrupted'
                      ? '已中断'
                      : '已保存'
                }}
                · {{ item.updatedAt }}</span
              >
            </button>
          </li>
          <li
            v-for="draft in Object.values(windows).filter(
              (item) => !item.conversation,
            )"
            :key="draft.key"
          >
            <button :disabled="!!action" @click="selectDraft(draft.key)">
              新问答（首问前不保存）
            </button>
          </li>
        </ul>
        <button v-if="cursor !== null" :disabled="loading" @click="load(true)">
          更多问答实例
        </button>
      </aside>
      <div class="search-window-host">
        <div v-if="current()" class="dataset-form-actions">
          <button
            :disabled="busy || !writable || !!action"
            @click="open('rename', $event)"
          >
            重命名当前问答</button
          ><button
            :disabled="busy || !writable || !!action"
            @click="open('delete', $event)"
          >
            删除当前问答
          </button>
        </div>
        <section
          v-if="action"
          ref="panel"
          tabindex="-1"
          role="region"
          :aria-label="
            action === 'rename' ? '重命名问答实例' : '确认删除问答实例'
          "
          class="dataset-detail"
          @keydown.esc="close"
        >
          <form v-if="action === 'rename'" @submit.prevent="execute">
            <label for="conversation-name">问答名称</label
            ><input
              id="conversation-name"
              ref="nameInput"
              v-model="name"
              :disabled="busy"
            />
            <p>名称允许重复，手动命名不被后续提问覆盖。</p>
            <button :disabled="busy || !writable">保存问答名称</button>
          </form>
          <template v-else
            ><p>
              将取消此会话活动任务，永久删除问答记录和专属引用快照；不删除源资料、数据集、知识库或其他会话，无回收站恢复。
            </p>
            <p v-if="key">受理尚未确认，恢复删除复用本次操作标识。</p>
            <button :disabled="busy || !writable" @click="execute">
              确认删除问答实例
            </button></template
          >
          <p v-for="field in fields" :key="field.field">{{ field.message }}</p>
          <button :disabled="busy || !!key" @click="close">保留当前会话</button>
        </section>
        <p v-if="!selected">新建或选择一个问答实例。</p>
        <AnswerWindow
          v-for="window in windows"
          v-show="selected === window.key"
          :key="window.key"
          class="managed-answer"
          managed
          :window-key="window.key"
          :visible="selected === window.key && !action"
          :client="client"
          :initial-conversation="window.conversation"
          :writable="writable && !action"
          @saved="saved(window.key, $event)"
          @changed="changed"
        />
      </div>
    </div>
  </section>
</template>
<style scoped>
.managed-answer {
  padding: 0;
}
</style>
