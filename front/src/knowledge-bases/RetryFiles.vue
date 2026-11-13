<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { createKnowledgeBaseClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  knowledgeBaseId: { type: String, required: true },
  fileId: { type: String, default: null },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['completed']);
const api = createKnowledgeBaseClient(props.client),
  open = ref(false),
  busy = ref(false),
  consent = ref(false),
  error = ref(''),
  result = ref(null),
  key = ref(null),
  panel = ref(null),
  trigger = ref(null);
const labels = {
  enqueued: '已入队',
  already_active: '已有任务',
  ineligible: '不再符合条件',
};
let sequence = 0;
async function show() {
  open.value = true;
  consent.value = false;
  error.value = '';
  result.value = null;
  key.value = null;
  await nextTick();
  panel.value?.focus();
}
async function close() {
  if (busy.value || key.value) return;
  open.value = false;
  await nextTick();
  trigger.value?.focus();
}
async function execute() {
  if (busy.value || !props.writable) return;
  if (!consent.value) {
    error.value = '请先确认重试可能再次嵌入并产生费用。';
    return;
  }
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  key.value ||= crypto.randomUUID();
  try {
    const value = await api.retry(
      props.knowledgeBaseId,
      props.fileId,
      key.value,
    );
    if (current !== sequence) return;
    result.value = value;
    key.value = null;
    emit('completed');
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
  <section
    class="knowledge-section"
    :aria-label="fileId ? '单份资料重试' : '全部失败项重试'"
  >
    <button ref="trigger" :disabled="open || !writable" @click="show">
      {{ fileId ? '重试此失败资料' : '重试全部失败项' }}
    </button>
    <section
      v-if="open"
      ref="panel"
      role="region"
      :aria-label="fileId ? '确认重试此失败资料' : '确认重试全部失败项'"
      tabindex="-1"
      @keydown.esc="close"
    >
      <p>
        仅重试仍有效关联且未被排除的失败资料，使用当前内容及目标配置。可能再次调用嵌入服务并产生费用。成功项不重做，已有活动任务不重复创建，取消、过时或已删除任务不复活。
      </p>
      <p>
        新任务关联原失败历史；每个可重试索引操作最多三次尝试（含首次），不适用于问答。受理不代表处理成功，请在任务列表核对实际结果。
      </p>
      <p v-if="error" role="alert">{{ error }}</p>
      <label
        ><input
          v-model="consent"
          type="checkbox"
          :disabled="busy || !!key || !!result"
        />我确认重试可能再次嵌入并产生费用</label
      >
      <p v-if="key">
        受理结果尚未确认，恢复重试沿用本次操作标识，不重复提交新任务。
      </p>
      <button v-if="!result" :disabled="busy || !writable" @click="execute">
        {{ key ? '恢复原重试提交' : '确认重试失败资料' }}
      </button>
      <p v-if="busy" role="status">正在受理原重试操作…</p>
      <template v-if="result"
        ><p v-if="!result.items.length">
          没有可报告的失败项，未声明任何资料处理成功。
        </p>
        <ul>
          <li v-for="item in result.items" :key="item.fileId">
            文件：{{ item.fileId }}；{{ labels[item.outcome] }}；任务：{{
              item.taskId || '未创建'
            }}<span v-if="item.reason">；{{ item.reason }}</span>
          </li>
        </ul></template
      ><button :disabled="busy || !!key" @click="close">关闭失败项重试</button>
    </section>
  </section>
</template>
