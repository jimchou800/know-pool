<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { createKnowledgeBaseClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  task: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['updated']);
const api = createKnowledgeBaseClient(props.client);
const allowed = computed(
  () =>
    ['index_file', 'rebuild_knowledge_base', 'search', 'question'].includes(
      props.task.kind,
    ) && ['queued', 'running'].includes(props.task.state),
);
const open = ref(false),
  busy = ref(false),
  error = ref(''),
  trigger = ref(null),
  panel = ref(null);
let sequence = 0;
async function confirm() {
  open.value = true;
  error.value = '';
  await nextTick();
  panel.value?.focus();
}
async function close() {
  if (busy.value) return;
  open.value = false;
  await nextTick();
  if (trigger.value?.isConnected) trigger.value.focus();
}
async function cancel() {
  if (busy.value || !allowed.value || !props.writable) return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  try {
    const value = await api.cancel(props.task);
    if (current !== sequence) return;
    open.value = false;
    emit('updated', value);
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <div v-if="allowed">
    <button ref="trigger" :disabled="open || !writable" @click="confirm">
      取消原任务
    </button>
    <section
      v-if="open"
      ref="panel"
      role="region"
      aria-label="确认取消原任务"
      tabindex="-1"
      @keydown.esc.stop="close"
    >
      <p>
        尽力取消这个原任务，已发出的调用可能已经产生费用。若完成先发生，将展示实际完成状态；取消不会恢复旧文件，也不会撤回已成功切换的版本。
      </p>
      <p v-if="busy" role="status">正在取消原任务…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <button :disabled="busy || !writable" @click="cancel">
        确认取消原任务
      </button>
      <button :disabled="busy" @click="close">继续等待任务</button>
    </section>
  </div>
</template>
