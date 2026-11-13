<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue';
import Search from './Search.vue';
import { createSearchClient } from './client';
import '../datasets/datasets.css';
import './search.css';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const api = createSearchClient(props.client);
const rows = ref([]),
  windows = ref({}),
  selected = ref(null),
  cursor = ref(null);
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('');
const action = ref(''),
  name = ref(''),
  fields = ref([]),
  panel = ref(null),
  nameInput = ref(null),
  createButton = ref(null),
  actionTrigger = ref(null);
const states = {
  never_submitted: '未提交',
  running: '检索中',
  available: '已完成',
  failed: '失败',
  cancelled: '已取消',
  rerun_required: '待重新检索',
};
let sequence = 0,
  listSequence = 0,
  key,
  target;
async function select(id) {
  if (action.value || busy.value) return;
  selected.value = id;
  if (windows.value[id]) return;
  const current = sequence;
  error.value = '';
  try {
    const value = await api.getInstance(id);
    if (current === sequence && rows.value.some((r) => r.id === id))
      windows.value[id] = value;
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  }
}
async function load(more = false) {
  if (loading.value) return;
  const current = ++listSequence;
  loading.value = true;
  error.value = '';
  try {
    const value = await api.list(more ? cursor.value : undefined);
    if (current !== listSequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...value.items.filter(
            (item) => !rows.value.some((r) => r.id === item.id),
          ),
        ]
      : value.items;
    cursor.value = value.nextCursor;
    if (!selected.value && rows.value.length) await select(rows.value[0].id);
  } catch (failure) {
    if (current === listSequence) error.value = failure.message;
  } finally {
    if (current === listSequence) loading.value = false;
  }
}
async function changed(value) {
  if (!rows.value.some((r) => r.id === value.id)) return;
  const current = sequence;
  if (value.reload) {
    try {
      value = await api.getInstance(value.id);
    } catch (failure) {
      if (current === sequence) error.value = failure.message;
      return;
    }
  }
  if (current !== sequence || !rows.value.some((r) => r.id === value.id))
    return;
  rows.value = rows.value.map((r) =>
    r.id === value.id ? { ...r, ...value } : r,
  );
  if (windows.value[value.id])
    windows.value[value.id] = { ...windows.value[value.id], ...value };
}
async function create() {
  if (busy.value || !props.writable || action.value) return;
  const current = sequence;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    const value = await api.create();
    if (current !== sequence) return;
    rows.value.unshift(value);
    windows.value[value.id] = value;
    selected.value = value.id;
    await nextTick();
    document.getElementById(`search-query-${value.id}`)?.focus();
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function open(kind, event) {
  if (!selected.value || busy.value || !props.writable) return;
  target = selected.value;
  actionTrigger.value = event.currentTarget;
  action.value = kind;
  name.value = rows.value.find((r) => r.id === target).name;
  error.value = '';
  fields.value = [];
  key = undefined;
  await nextTick();
  (kind === 'rename' ? nameInput.value : panel.value)?.focus();
}
async function close() {
  if (busy.value || key) return;
  action.value = '';
  await nextTick();
  if (actionTrigger.value?.isConnected) actionTrigger.value.focus();
  else createButton.value?.focus();
}
async function execute() {
  if (busy.value || !props.writable) return;
  busy.value = true;
  error.value = '';
  fields.value = [];
  const current = sequence;
  try {
    if (action.value === 'rename') {
      const value = await api.rename(target, name.value);
      if (current !== sequence) return;
      await changed(value);
      busy.value = false;
      await close();
    } else {
      key ||= crypto.randomUUID();
      await api.remove(target, key);
      if (current !== sequence) return;
      ++sequence;
      ++listSequence;
      loading.value = false;
      rows.value = rows.value.filter((r) => r.id !== target);
      delete windows.value[target];
      if (selected.value === target) selected.value = null;
      key = undefined;
      busy.value = false;
      action.value = '';
      notice.value =
        '实例已删除，原请求与临时证据已失效；数据集和知识库不受影响。';
      if (rows.value.length) await select(rows.value[0].id);
      await nextTick();
      createButton.value?.focus();
    }
  } catch (failure) {
    if (current === sequence) {
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
onBeforeUnmount(() => {
  ++sequence;
  ++listSequence;
});
load();
</script>
<template>
  <section class="datasets-page search-page" aria-labelledby="search-title">
    <header class="datasets-heading">
      <div>
        <p class="welcome-kicker">知识服务</p>
        <h1 id="search-title">知识检索</h1>
        <p>各实例保留自己的查询、范围与运行期结果。切换不会取消任务。</p>
      </div>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div class="datasets-layout">
      <aside class="dataset-list" aria-label="检索实例">
        <div class="dataset-list-heading">
          <h2>检索实例</h2>
          <button
            ref="createButton"
            :disabled="busy || !writable || !!action"
            @click="create"
          >
            新建检索
          </button>
        </div>
        <button :disabled="loading || !!action" @click="load()">
          刷新实例列表
        </button>
        <p v-if="loading" role="status">正在读取实例…</p>
        <p v-if="!loading && !rows.length">尚无检索实例。</p>
        <ul class="dataset-rows">
          <li v-for="item in rows" :key="item.id" :data-instance-id="item.id">
            <button
              :aria-current="selected === item.id ? 'true' : undefined"
              :disabled="busy || !!action"
              @click="select(item.id)"
            >
              <strong>{{ item.name }}</strong
              ><span>{{ states[item.resultState] }}</span>
            </button>
          </li>
        </ul>
        <button v-if="cursor !== null" :disabled="loading" @click="load(true)">
          更多检索实例
        </button>
      </aside>
      <div class="search-window-host">
        <div v-if="selected" class="dataset-form-actions">
          <button
            :disabled="busy || !writable || !!action"
            @click="open('rename', $event)"
          >
            重命名当前检索</button
          ><button
            :disabled="busy || !writable || !!action"
            @click="open('delete', $event)"
          >
            删除当前检索
          </button>
        </div>
        <section
          v-if="action"
          ref="panel"
          tabindex="-1"
          role="region"
          :aria-label="
            action === 'rename' ? '重命名检索实例' : '确认删除检索实例'
          "
          class="dataset-detail"
          @keydown.esc="close"
        >
          <form v-if="action === 'rename'" @submit.prevent="execute">
            <label for="search-name">检索名称</label
            ><input
              id="search-name"
              ref="nameInput"
              v-model="name"
              :disabled="busy"
            />
            <p>名称允许重复；手动命名后不会被后续查询覆盖。</p>
            <button :disabled="busy || !writable">保存检索名称</button>
          </form>
          <template v-else
            ><p>
              将删除当前实例的名称、最近查询、范围与运行期结果，取消其活动请求。不影响数据集、知识库或其他实例，无回收站恢复。
            </p>
            <p v-if="key">受理尚未确认，恢复删除复用本次操作标识。</p>
            <button :disabled="busy || !writable" @click="execute">
              确认删除检索实例
            </button></template
          >
          <p v-for="field in fields" :key="field.field">{{ field.message }}</p>
          <button :disabled="busy || !!key" @click="close">保留当前实例</button>
        </section>
        <p v-if="!selected">新建或选择一个检索实例。</p>
        <Search
          v-for="item in windows"
          v-show="selected === item.id"
          :key="item.id"
          class="managed-search"
          :client="client"
          :initial-instance="item"
          :writable="writable && !action"
          @changed="changed"
        />
      </div>
    </div>
  </section>
</template>
