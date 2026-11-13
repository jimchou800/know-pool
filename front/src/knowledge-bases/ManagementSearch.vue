<script setup>
import { ref, computed, onBeforeUnmount, nextTick } from 'vue';
import { createManagementSearchClient } from './search-client';
import EvidenceLocation from './EvidenceLocation.vue';
import EvidencePreview from './EvidencePreview.vue';

const props = defineProps({
  client: { type: Object, required: true },
  knowledgeBaseId: { type: String, required: true },
  writable: { type: Boolean, default: true },
});
const api = createManagementSearchClient(props.client);
const query = ref(''),
  pending = ref(null),
  error = ref(''),
  busy = ref(false),
  selected = ref(null),
  evidenceError = ref(''),
  evidenceBusy = ref(false),
  fields = ref({}),
  evidencePanel = ref(null),
  summary = ref(null);
const active = computed(
  () =>
    pending.value && ['queued', 'running'].includes(pending.value.task.state),
);
let key,
  payload,
  timer,
  sequence = 0,
  evidenceSequence = 0,
  trigger;
const updateStates = {
  ready: '当前内容已生效',
  updating: '资料正在更新',
  failed: '资料更新失败',
};
function errorMessage(failure) {
  return failure.code === 'RESULT_EXPIRED'
    ? '结果已失效，请手动重新检索。'
    : failure.message;
}
function accept(value) {
  pending.value = value;
  if (['queued', 'running'].includes(value.task.state))
    timer = setTimeout(read, 750);
}
async function submit() {
  if (busy.value || active.value || !props.writable) return;
  error.value = '';
  fields.value = {};
  if (
    !key &&
    (!/\S/.test(query.value) || Array.from(query.value).length > 20000)
  ) {
    error.value = '请输入有效的检索内容（最多20000码点）。';
    fields.value['/query'] = error.value;
    await nextTick();
    summary.value?.focus();
    return;
  }
  if (!key) {
    key = crypto.randomUUID();
    payload = query.value;
  }
  const current = ++sequence;
  selected.value = null;
  ++evidenceSequence;
  evidenceBusy.value = false;
  evidenceError.value = '';
  busy.value = true;
  try {
    const value = await api.submit(props.knowledgeBaseId, payload, key);
    if (current !== sequence) return;
    key = undefined;
    payload = undefined;
    accept(value);
  } catch (failure) {
    if (current !== sequence) return;
    error.value = errorMessage(failure);
    for (const field of failure.fieldErrors || [])
      fields.value[field.field] = field.message;
    if (failure.code) {
      key = undefined;
      payload = undefined;
    }
    await nextTick();
    summary.value?.focus();
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function read() {
  if (busy.value || !pending.value) return;
  const current = sequence;
  busy.value = true;
  error.value = '';
  try {
    const value = await api.get(pending.value.id, props.knowledgeBaseId);
    if (current === sequence) accept(value);
  } catch (failure) {
    if (current === sequence) {
      error.value = errorMessage(failure);
      if (failure.code === 'RESULT_EXPIRED') {
        clearTimeout(timer);
        pending.value = null;
        selected.value = null;
        ++evidenceSequence;
      }
    }
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function openEvidence(item, event) {
  const current = ++evidenceSequence;
  trigger = event.currentTarget;
  selected.value = null;
  evidenceError.value = '';
  evidenceBusy.value = true;
  try {
    const value = await api.evidence(
      pending.value.id,
      item.id,
      props.knowledgeBaseId,
    );
    if (current !== evidenceSequence) return;
    if (
      [
        'chunkId',
        'fileId',
        'contentVersionId',
        'indexVersionId',
        'configVersionId',
      ].some((field) => value[field] !== item[field])
    )
      throw new Error('原文依据与本次检索版本不一致，请重新读取。');
    selected.value = value;
    await nextTick();
    if (current === evidenceSequence) evidencePanel.value?.focus();
  } catch (failure) {
    if (current === evidenceSequence) {
      evidenceError.value = errorMessage(failure);
      if (failure.code === 'RESULT_EXPIRED') {
        clearTimeout(timer);
        pending.value = null;
        selected.value = null;
      }
    }
  } finally {
    if (current === evidenceSequence) evidenceBusy.value = false;
  }
}
async function closeEvidence() {
  const current = ++evidenceSequence;
  selected.value = null;
  evidenceError.value = '';
  evidenceBusy.value = false;
  await nextTick();
  if (current === evidenceSequence && trigger?.isConnected) trigger.focus();
}
onBeforeUnmount(() => {
  ++sequence;
  ++evidenceSequence;
  clearTimeout(timer);
});
</script>

<template>
  <section class="knowledge-section" aria-label="单库检索测试">
    <h3>单库检索测试</h3>
    <p>
      仅检索当前知识库的生效索引，允许未发布或已下线知识库；不生成回答。可能调用嵌入服务并产生费用。
    </p>
    <form novalidate @submit.prevent="submit">
      <label :for="`management-query-${knowledgeBaseId}`">检索内容</label>
      <textarea
        :id="`management-query-${knowledgeBaseId}`"
        v-model="query"
        :disabled="busy || active || !!key"
        :aria-invalid="!!fields['/query']"
        :aria-describedby="`management-query-error-${knowledgeBaseId}`"
      />
      <p :id="`management-query-error-${knowledgeBaseId}`" class="field-error">
        {{ fields['/query'] }}
      </p>
      <button class="primary-button" :disabled="busy || active || !writable">
        {{ busy ? '正在处理…' : key ? '继续确认本次检索' : '执行单库检索' }}
      </button>
    </form>
    <p v-if="error" ref="summary" tabindex="-1" role="alert">
      {{ error
      }}<button v-if="pending" :disabled="busy" @click="read">
        继续查询原请求
      </button>
    </p>
    <template v-if="pending">
      <p v-if="active" role="status">
        检索已受理，正在查询原请求。尚未取得结果。
      </p>
      <p v-else-if="pending.task.failure" role="alert">
        {{
          pending.task.failure.code === 'NO_EFFECTIVE_INDEX'
            ? '没有生效索引，请先完成资料处理。'
            : pending.task.failure.message
        }}
      </p>
      <p v-else-if="pending.task.state === 'cancelled'" role="status">
        检索已取消。
      </p>
      <p v-else-if="pending.result?.outcome === 'no_matches'" role="status">
        未找到相关内容。
      </p>
      <p v-for="warning in pending.task.warnings" :key="warning">
        {{ warning }}
      </p>
      <p
        v-for="omission in pending.scope?.omitted || []"
        :key="omission.knowledgeBaseId"
      >
        范围不可用：{{ omission.message }}
      </p>
      <template v-if="pending.result">
        <p v-for="warning in pending.result.warnings" :key="warning">
          {{ warning }}
        </p>
        <ul class="knowledge-files">
          <li v-for="item in pending.result.evidence" :key="item.id">
            <strong>{{ item.filenameAtCapture }}</strong>
            <p
              v-if="
                item.currentFilename &&
                item.currentFilename !== item.filenameAtCapture
              "
            >
              当前名称：{{ item.currentFilename }}
            </p>
            <p>
              所属知识库：{{ item.knowledgeBaseId }}；内容版本：{{
                item.contentVersionId
              }}
            </p>
            <p>
              {{ updateStates[item.updateState]
              }}<span v-if="item.usingPreviousVersion">，仍使用旧生效版本</span>
            </p>
            <pre class="source-text">{{ item.text }}</pre>
            <EvidenceLocation :location="item.location" />
            <button
              :disabled="evidenceBusy"
              @click="openEvidence(item, $event)"
            >
              查看原文依据
            </button>
          </li>
        </ul>
      </template>
    </template>
    <p v-if="evidenceBusy" role="status">正在读取原文依据…</p>
    <p v-if="evidenceError" role="alert">{{ evidenceError }}</p>
    <section
      v-if="selected"
      ref="evidencePanel"
      tabindex="-1"
      role="region"
      aria-label="原文依据"
      class="knowledge-section"
      @keydown.esc="closeEvidence"
    >
      <h4>{{ selected.filenameAtCapture }}</h4>
      <button @click="closeEvidence">关闭原文依据</button>
      <p>
        内容版本：{{ selected.contentVersionId }}；索引版本：{{
          selected.indexVersionId
        }}；配置版本：{{ selected.configVersionId }}
      </p>
      <EvidenceLocation :location="selected.location" />
      <p v-if="!selected.originalAvailable">
        完整原文件不可用，当前展示本次检索的完整切片证据快照。
      </p>
      <p v-else>当前展示本次检索的完整切片证据快照。</p>
      <pre class="source-text">{{ selected.text }}</pre>
      <EvidencePreview :key="`${selected.id}-${selected.contentVersionId}`" :client="client" :evidence="selected" />
    </section>
  </section>
</template>
