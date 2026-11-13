<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { createCleanupClient } from './client';
import { createKnowledgeBaseClient } from '../knowledge-bases/client';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const api = createCleanupClient(props.client),
  tasks = createKnowledgeBaseClient(props.client),
  rows = ref([]),
  cursor = ref(null),
  state = ref(''),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  target = ref(null),
  key = ref(null),
  panel = ref(null);
const states = {
  queued: '等待清理',
  running: '正在清理',
  succeeded: '清理完成',
  failed: '清理失败',
  cancelled: '清理取消',
  interrupted: '清理中断',
};
let sequence = 0,
  trigger;
async function load(more = false) {
  if (busy.value) return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  try {
    const page = await api.list(more ? cursor.value : undefined, state.value);
    if (current !== sequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...page.items.filter(
            (item) => !rows.value.some((old) => old.id === item.id),
          ),
        ]
      : page.items;
    cursor.value = page.nextCursor;
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function read(id) {
  if (busy.value) return;
  const current = sequence;
  busy.value = true;
  error.value = '';
  try {
    const task = await tasks.task(id, 'cleanup');
    if (current === sequence)
      rows.value = rows.value.map((item) => (item.id === id ? task : item));
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function retry(item, event) {
  trigger = event?.currentTarget;
  target.value = item;
  key.value = null;
  await nextTick();
  panel.value?.focus();
}
async function close() {
  if (busy.value || key.value) return;
  target.value = null;
  await nextTick();
  if (trigger?.isConnected) trigger.focus();
}
async function execute() {
  if (busy.value || !target.value || !props.writable) return;
  const current = sequence;
  busy.value = true;
  error.value = '';
  key.value ||= crypto.randomUUID();
  try {
    const value = await api.retry(target.value.id, key.value);
    if (current !== sequence) return;
    rows.value.unshift(value.task);
    key.value = null;
    target.value = null;
    notice.value = '清理重试已受理，不恢复已删除对象，请查询新任务实际状态。';
    await nextTick();
    if (trigger?.isConnected) trigger.focus();
  } catch (failure) {
    if (current === sequence) {
      error.value = failure.message;
      if (failure.code) key.value = null;
    }
  } finally {
    if (current === sequence) busy.value = false;
  }
}
onBeforeUnmount(() => ++sequence);
load();
</script>
<template>
  <section class="datasets-page" aria-labelledby="cleanup-title">
    <h1 id="cleanup-title">清理任务</h1>
    <p>
      删除立即生效，后台只清理明确归属资源。失败或重试都不恢复对象；历史引用快照继续保留。
    </p>
    <label
      >清理状态<select
        v-model="state"
        :disabled="busy || !!target"
        @change="load()"
      >
        <option value="">全部状态</option>
        <option v-for="(label, value) in states" :key="value" :value="value">
          {{ label }}
        </option>
      </select></label
    ><button :disabled="busy || !!target" @click="load()">刷新清理任务</button>
    <p v-if="busy" role="status">正在读取或受理清理任务…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="!busy && !rows.length">暂无清理任务。</p>
    <section
      v-if="target"
      ref="panel"
      role="region"
      aria-label="确认重试失败清理"
      tabindex="-1"
      @keydown.esc="close"
    >
      <p>仅重试失败清理任务{{ target.id }}，不恢复对象，不重新嵌入。</p>
      <p v-if="key">受理尚未确认，恢复时沿用本次操作标识。</p>
      <button :disabled="busy || !writable" @click="execute">
        {{ key ? '恢复原清理重试' : '确认重试清理' }}</button
      ><button :disabled="busy || !!key" @click="close">暂不重试清理</button>
    </section>
    <ul class="knowledge-files">
      <li v-for="item in rows" :key="item.id">
        <strong>{{ states[item.state] }}</strong>
        <p>
          任务：{{ item.id }}；知识库：{{
            item.knowledgeBaseId || '无'
          }}；资料：{{ item.fileId || '无' }}
        </p>
        <p>
          阶段：{{ item.phase }}；已完成{{ item.progressCompleted }} /
          {{ item.progressTotal === null ? '总量未知' : item.progressTotal }}
        </p>
        <p v-if="item.retryOfTaskId">
          原失败清理任务：{{ item.retryOfTaskId }}
        </p>
        <p v-if="item.failure" role="status">
          {{ item.failure.code }}：{{ item.failure.message }}
        </p>
        <p v-for="warning in item.warnings" :key="warning">{{ warning }}</p>
        <button :disabled="busy || !!target" @click="read(item.id)">
          查询此清理任务</button
        ><button
          v-if="item.state === 'failed'"
          :disabled="busy || !!target || !writable"
          @click="retry(item, $event)"
        >
          重试失败清理
        </button>
      </li>
    </ul>
    <button
      v-if="cursor !== null"
      :disabled="busy || !!target"
      @click="load(true)"
    >
      更多清理任务
    </button>
  </section>
</template>
