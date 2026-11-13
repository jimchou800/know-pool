<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { createKnowledgeBaseClient } from './client';
import { createSettingsClient } from '../settings/client';
const props = defineProps({
  client: { type: Object, required: true },
  base: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['updated']);
const api = createKnowledgeBaseClient(props.client);
const settings = createSettingsClient(props.client);
const action = ref(''),
  busy = ref(false),
  error = ref(''),
  notice = ref('');
const consent = ref(false),
  task = ref(null),
  result = ref(null);
const panel = ref(null),
  publishButton = ref(null),
  offlineButton = ref(null),
  checkButton = ref(null);
const active = computed(
  () => task.value && ['queued', 'running'].includes(task.value.state),
);
const publishLabel = computed(() =>
  props.base.publicationState === 'offline' ? '重新发布知识库' : '发布知识库',
);
const labels = computed(() => ({
  publish: publishLabel.value,
  offline: '主动下线知识库',
  check: '复查可用性',
}));
const states = {
  queued: '等待复查',
  running: '正在复查',
  succeeded: '复查已完成',
  failed: '复查未完成',
  interrupted: '复查已中断',
  cancelled: '复查已取消',
};
let sequence = 0,
  timer,
  key;
async function open(value) {
  action.value = value;
  consent.value = false;
  error.value = '';
  notice.value = '';
  await nextTick();
  panel.value?.focus();
}
async function close() {
  if (busy.value || key) return;
  const trigger = {
    publish: publishButton,
    offline: offlineButton,
    check: checkButton,
  }[action.value];
  action.value = '';
  await nextTick();
  (
    trigger?.value ||
    publishButton.value ||
    offlineButton.value ||
    checkButton.value
  )?.focus();
}
async function read() {
  if (!task.value || busy.value) return;
  const current = sequence;
  busy.value = true;
  error.value = '';
  try {
    const value = await api.task(task.value.id, 'model_test');
    if (current !== sequence) return;
    if (value.knowledgeBaseId !== props.base.id)
      throw new Error('复查任务所属知识库不一致。');
    task.value = value;
    if (active.value) timer = setTimeout(read, 800);
    else {
      if (value.state === 'succeeded') {
        const checked = await settings.result(value.id);
        if (current !== sequence) return;
        result.value = checked;
      }
      const base = await api.get(props.base.id);
      if (current !== sequence) return;
      emit('updated', base);
    }
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function execute() {
  if (
    busy.value ||
    !props.writable ||
    !action.value ||
    (action.value === 'check' && !consent.value)
  )
    return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  try {
    if (action.value === 'check') {
      key ||= crypto.randomUUID();
      const accepted = await api.check(props.base.id, key, true);
      if (current !== sequence) return;
      task.value = accepted.task;
      result.value = null;
      key = undefined;
      busy.value = false;
      await close();
      await read();
    } else {
      const value = await api[action.value](props.base.id);
      if (current !== sequence) return;
      notice.value =
        action.value === 'offline'
          ? '知识库已下线，处理任务与索引继续保留。'
          : '发布操作已完成，当前状态以服务端结果为准。';
      emit('updated', value);
      busy.value = false;
      await close();
    }
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
watch(
  () => props.base.id,
  () => {
    ++sequence;
    clearTimeout(timer);
    key = undefined;
    task.value = null;
    result.value = null;
    action.value = '';
    busy.value = false;
    error.value = '';
    notice.value = '';
  },
);
onBeforeUnmount(() => {
  ++sequence;
  clearTimeout(timer);
});
</script>

<template>
  <section class="knowledge-section" aria-label="发布与可用性管理">
    <h3>发布与可用性管理</h3>
    <p v-if="base.offlineReason">
      最近下线原因：{{
        base.offlineReason === 'manual' ? '主动下线' : '无可检索资料'
      }}。
    </p>
    <p v-if="base.publishedAt">最近发布时间：{{ base.publishedAt }}</p>
    <p v-if="base.offlineAt">最近下线时间：{{ base.offlineAt }}</p>
    <p>
      至少一份未排除、有效关联且已完成索引的资料才能发布，发布时由服务端重新检查。
    </p>
    <p>恢复资料或完成重建不会自动重新发布；运行故障与发布状态分别记录。</p>
    <p v-if="base.searchableFileCount === 0">
      当前无可检索资料，请关联资料、取消排除或等待索引完成后再发布。
    </p>
    <div class="dataset-form-actions">
      <button
        v-if="base.publicationState !== 'published'"
        ref="publishButton"
        :disabled="!writable || busy || !!action"
        @click="open('publish')"
      >
        {{ publishLabel }}
      </button>
      <button
        v-else
        ref="offlineButton"
        :disabled="!writable || busy || !!action"
        @click="open('offline')"
      >
        主动下线知识库
      </button>
      <button
        ref="checkButton"
        :disabled="!writable || busy || !!action || !!active"
        @click="open('check')"
      >
        复查可用性
      </button>
    </div>
    <section
      v-if="action"
      ref="panel"
      tabindex="-1"
      role="region"
      :aria-label="`确认${labels[action]}`"
      @keydown.esc.stop="close"
    >
      <p v-if="action === 'publish'">
        向知识服务开放本库；条件不满足时保留原发布状态并说明原因。
      </p>
      <p v-else-if="action === 'offline'">
        立即阻止知识服务的新请求引用本库，已开始的问答可以完成。不删除资料、索引，也不取消处理任务。
      </p>
      <template v-else>
        <p>
          仅检查依赖，不发送资料；可能调用嵌入服务并产生费用。不自动重试或持续轮询依赖，不改变发布状态。
        </p>
        <label class="knowledge-check"
          ><input
            v-model="consent"
            type="checkbox"
            :disabled="busy || !!key"
          />我同意本次复查可能产生模型费用</label
        >
        <p v-if="key">
          受理结果尚未确认，再次提交复用本次操作标识，不创建另一轮复查。
        </p>
      </template>
      <button
        :disabled="busy || !writable || (action === 'check' && !consent)"
        @click="execute"
      >
        确认{{ labels[action] }}
      </button>
      <button :disabled="busy || !!key" @click="close">保留当前状态</button>
    </section>
    <p v-if="busy" role="status">正在读取或更新发布及可用状态…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="task">
      <p role="status">{{ states[task.state] }}。任务标识：{{ task.id }}</p>
      <p v-if="task.failure">{{ task.failure.message }}</p>
      <p v-for="warning in task.warnings" :key="warning">{{ warning }}</p>
      <p v-if="result">
        本次探测：{{ result.available ? '运行可用' : '运行不可用' }}；{{
          result.message
        }}。检查时间：{{ result.checkedAt }}
      </p>
      <p>检查结果仅代表本次探测，不保证依赖持续可用。</p>
      <button v-if="error && !action" :disabled="busy" @click="read">
        查询原复查任务
      </button>
    </div>
    <p>最近可用性检查：{{ base.lastCheckedAt || '尚未检查' }}</p>
  </section>
</template>
