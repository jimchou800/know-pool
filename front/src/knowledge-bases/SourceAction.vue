<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { createKnowledgeBaseClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  knowledgeBaseId: { type: String, required: true },
  resourceId: { type: String, required: true },
  action: { type: String, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['changed']);
const api = createKnowledgeBaseClient(props.client);
const labels = {
  remove: '撤销此来源',
  exclude: '排除此资料',
  include: '取消排除',
};
const explanations = {
  remove:
    '仅撤销这一来源，其他有效来源仍保留；若是最后来源，该资料立即从本库下线。不会删除原文件。',
  exclude:
    '排除优先于全部来源，资料立即从本库下线。以后新增单文件关联也不会自动取消排除。',
  include:
    '仅仍有有效来源的资料按最新内容重新排队；没有有效来源时仍不纳入本库。可能调用嵌入服务并产生费用。',
};
const label = computed(() => labels[props.action]);
const confirming = ref(false),
  busy = ref(false),
  error = ref(''),
  trigger = ref(null),
  panel = ref(null);
let sequence = 0;
async function open() {
  confirming.value = true;
  error.value = '';
  await nextTick();
  panel.value?.focus();
}
async function close() {
  if (busy.value) return;
  confirming.value = false;
  await nextTick();
  if (trigger.value?.isConnected) trigger.value.focus();
}
async function execute() {
  if (busy.value || !props.writable) return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  try {
    const methods = {
      remove: 'removeSource',
      exclude: 'exclude',
      include: 'include',
    };
    await api[methods[props.action]](props.knowledgeBaseId, props.resourceId);
    if (current !== sequence) return;
    confirming.value = false;
    emit('changed');
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
  <div class="source-action">
    <button ref="trigger" :disabled="!writable || confirming" @click="open">
      {{ label }}
    </button>
    <section
      v-if="confirming"
      ref="panel"
      role="region"
      :aria-label="`确认${label}`"
      tabindex="-1"
      @keydown.esc.stop="close"
    >
      <p>{{ explanations[action] }}</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="busy" role="status">正在更新来源范围…</p>
      <button :disabled="busy || !writable" @click="execute">
        确认{{ label }}
      </button>
      <button :disabled="busy" @click="close">保留当前范围</button>
    </section>
  </div>
</template>
