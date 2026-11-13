<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { createCleanupClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  kind: { type: String, required: true },
  resourceId: { type: String, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['deleted']),
  api = createCleanupClient(props.client);
const labels = {
  files: '资料',
  datasets: '数据集',
  'knowledge-bases': '知识库',
};
const open = ref(false),
  busy = ref(false),
  impact = ref(null),
  error = ref(''),
  key = ref(null),
  panel = ref(null),
  trigger = ref(null);
let sequence = 0;
async function show() {
  open.value = true;
  await nextTick();
  panel.value?.focus();
  await load();
}
async function load() {
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  impact.value = null;
  try {
    const value = await api.impact(props.kind, props.resourceId);
    if (current === sequence) impact.value = value;
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function close() {
  if (busy.value || key.value) return;
  ++sequence;
  open.value = false;
  await nextTick();
  trigger.value?.focus();
}
async function remove() {
  if (busy.value || !impact.value || !props.writable) return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  key.value ||= crypto.randomUUID();
  try {
    const value = await api.remove(props.kind, props.resourceId, key.value);
    if (current !== sequence) return;
    key.value = null;
    emit('deleted', { id: props.resourceId, task: value.task });
  } catch (failure) {
    if (current === sequence) {
      error.value = failure.message;
      if (failure.code) key.value = null;
      await nextTick();
      panel.value?.focus();
    }
  } finally {
    if (current === sequence) busy.value = false;
  }
}
onBeforeUnmount(() => ++sequence);
</script>
<template>
  <section>
    <button ref="trigger" :disabled="open || !writable" @click="show">
      删除{{ labels[kind] }}
    </button>
    <section
      v-if="open"
      ref="panel"
      role="region"
      :aria-label="`确认删除${labels[kind]}`"
      tabindex="-1"
      @keydown.esc="close"
    >
      <h3>确认删除{{ labels[kind] }}</h3>
      <p v-if="busy" role="status">正在读取影响或确认删除…</p>
      <p v-if="error" role="alert">
        {{ error
        }}<button v-if="!impact" :disabled="busy" @click="load">
          重新读取删除影响
        </button>
      </p>
      <template v-if="impact"
        ><p>
          涉及资料：{{ impact.fileCount }}份；活动任务：{{
            impact.activeTaskCount
          }}项。
        </p>
        <p>受影响知识库：{{ impact.knowledgeBaseIds.join('、') || '无' }}。</p>
        <p v-if="kind === 'knowledge-bases'">
          仅删除知识库专属数据，保留数据集和源资料，不影响其他知识库。
        </p>
        <p v-else-if="kind === 'datasets'">
          删除此数据集及其资料，从关联知识库立即下线；知识库及其他数据集保留。
        </p>
        <p v-else>
          资料立即从所有关联知识库下线，停止新请求引用；其他资料和知识库保留。
        </p>
        <p>
          历史会话的引用快照保留。在途读取按保护规则完成后再清理副本；删除不可撤销，清理失败不恢复对象，可到清理任务页重试。
        </p>
        <p v-for="warning in impact.warnings" :key="warning">{{ warning }}</p>
        <p v-if="key">受理结果尚未确认，恢复删除复用本次操作标识。</p>
        <button :disabled="busy || !writable" @click="remove">
          {{ key ? '恢复原删除提交' : `确认删除${labels[kind]}` }}
        </button></template
      ><button :disabled="busy || !!key" @click="close">
        保留当前{{ labels[kind] }}
      </button>
    </section>
  </section>
</template>
