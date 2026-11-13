<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { createSearchClient } from './client';
import { createKnowledgeBaseClient } from '../knowledge-bases/client';
import EvidenceLocation from '../knowledge-bases/EvidenceLocation.vue';
import EvidencePreview from '../knowledge-bases/EvidencePreview.vue';
import '../datasets/datasets.css';
import '../knowledge-bases/knowledge-bases.css';
import './search.css';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const api = createSearchClient(props.client),
  bases = createKnowledgeBaseClient(props.client);
const instance = ref(null),
  creating = ref(false),
  createError = ref('');
const choices = ref([]),
  loading = ref(false),
  choiceError = ref('');
const query = ref(''),
  selectedIds = ref([]),
  consent = ref(false),
  pending = ref(null),
  submitting = ref(false),
  reading = ref(false),
  error = ref(''),
  fields = ref({});
const evidence = ref(null),
  evidenceBusy = ref(false),
  evidenceError = ref(''),
  evidencePanel = ref(null),
  summary = ref(null);
const recoveryKey = ref(null),
  submitted = ref(null);
const active = computed(
  () =>
    pending.value && ['queued', 'running'].includes(pending.value.task.state),
);
const names = computed(() =>
  Object.fromEntries(choices.value.map((b) => [b.id, b.name])),
);
const states = {
  queued: '等待检索',
  running: '正在检索',
  succeeded: '检索完成',
  failed: '检索失败',
  cancelled: '检索已取消',
  interrupted: '检索已中断',
};
const reasons = {
  deleted: '知识库不存在',
  not_published: '知识库未发布或已下线',
  unavailable: '依赖不可用',
  no_effective_index: '没有生效索引',
};
let sequence = 0,
  choiceSequence = 0,
  evidenceSequence = 0,
  timer,
  trigger;
async function loadChoices() {
  const current = ++choiceSequence;
  loading.value = true;
  choiceError.value = '';
  try {
    const all = [],
      seen = new Set();
    let cursor;
    do {
      const page = await bases.list(cursor);
      if (current !== choiceSequence) return;
      all.push(...page.items);
      cursor = page.nextCursor;
      if (cursor !== null && seen.has(cursor))
        throw new Error('知识库分页重复，请重新读取。');
      seen.add(cursor);
    } while (cursor !== null);
    choices.value = all;
  } catch (failure) {
    if (current === choiceSequence) choiceError.value = failure.message;
  } finally {
    if (current === choiceSequence) loading.value = false;
  }
}
async function create() {
  if (creating.value || !props.writable || instance.value) return;
  creating.value = true;
  createError.value = '';
  const current = sequence;
  try {
    const value = await api.create();
    if (current === sequence) instance.value = value;
  } catch (failure) {
    if (current === sequence) createError.value = failure.message;
  } finally {
    if (current === sequence) creating.value = false;
  }
}
function clearEvidence() {
  ++evidenceSequence;
  evidence.value = null;
  evidenceBusy.value = false;
  evidenceError.value = '';
}
function accept(value, current) {
  if (current !== sequence) return;
  pending.value = value;
  if (['queued', 'running'].includes(value.task.state))
    timer = setTimeout(() => read(current), 750);
}
async function submit() {
  if (submitting.value || !props.writable || !instance.value) return;
  fields.value = {};
  error.value = '';
  if (!recoveryKey.value) {
    if (!/\S/.test(query.value) || Array.from(query.value).length > 20000)
      fields.value['/query'] = '查询不能为空，最多20000个码点。';
    if (!selectedIds.value.length || selectedIds.value.length > 100)
      fields.value['/knowledgeBaseIds'] = '请选择一至一百个已发布知识库。';
    if (!consent.value)
      fields.value['/consent'] = '请确认本次检索可能产生嵌入费用。';
    if (Object.keys(fields.value).length) {
      error.value = '请修正检索条件后提交。';
      await nextTick();
      summary.value?.focus();
      return;
    }
    submitted.value = {
      query: query.value,
      knowledgeBaseIds: [...selectedIds.value],
    };
    recoveryKey.value = crypto.randomUUID();
  }
  const current = ++sequence;
  clearTimeout(timer);
  clearEvidence();
  pending.value = null;
  reading.value = false;
  submitting.value = true;
  try {
    const value = await api.submit(
      instance.value.id,
      submitted.value,
      recoveryKey.value,
    );
    if (current !== sequence) return;
    recoveryKey.value = null;
    consent.value = false;
    accept(value, current);
  } catch (failure) {
    if (current !== sequence) return;
    error.value = failure.message;
    for (const field of failure.fieldErrors || [])
      fields.value[field.field] = field.message;
    if (failure.code) recoveryKey.value = null;
    await nextTick();
    summary.value?.focus();
  } finally {
    if (current === sequence) submitting.value = false;
  }
}
async function read(current = sequence) {
  if (!pending.value || reading.value || current !== sequence) return;
  const requestId = pending.value.id;
  reading.value = true;
  error.value = '';
  try {
    const value = await api.get(requestId, instance.value.id, submitted.value);
    if (current === sequence && pending.value?.id === requestId)
      accept(value, current);
  } catch (failure) {
    if (current !== sequence) return;
    error.value =
      failure.code === 'RESULT_EXPIRED'
        ? '结果已失效，请手动重新检索。'
        : failure.message;
    if (failure.code === 'RESULT_EXPIRED') {
      pending.value = null;
      clearEvidence();
    }
  } finally {
    if (current === sequence) reading.value = false;
  }
}
async function openEvidence(item, event) {
  const current = ++evidenceSequence,
    requestId = pending.value.id;
  trigger = event.currentTarget;
  evidence.value = null;
  evidenceError.value = '';
  evidenceBusy.value = true;
  try {
    const value = await api.evidence(requestId, item);
    if (current !== evidenceSequence || requestId !== pending.value?.id) return;
    evidence.value = value;
    await nextTick();
    evidencePanel.value?.focus();
  } catch (failure) {
    if (current !== evidenceSequence) return;
    evidenceError.value =
      failure.code === 'RESULT_EXPIRED'
        ? '结果已失效，请手动重新检索。'
        : failure.message;
  } finally {
    if (current === evidenceSequence) evidenceBusy.value = false;
  }
}
async function closeEvidence() {
  clearEvidence();
  await nextTick();
  if (trigger?.isConnected) trigger.focus();
}
onBeforeUnmount(() => {
  ++sequence;
  ++choiceSequence;
  ++evidenceSequence;
  clearTimeout(timer);
});
loadChoices();
</script>

<template>
  <section class="datasets-page search-page" aria-labelledby="search-title">
    <header class="datasets-heading">
      <div>
        <p class="welcome-kicker">知识服务</p>
        <h1 id="search-title">知识检索</h1>
        <p>选择已发布知识库，直接核对原文片段、内容版本与位置。</p>
      </div>
    </header>
    <p v-if="!writable" role="status">当前写入已暂停，仍可查看本次结果。</p>
    <div class="datasets-layout">
      <aside class="dataset-list" aria-label="检索实例">
        <div class="dataset-list-heading">
          <h2>检索实例</h2>
          <button
            :disabled="!writable || creating || !!instance"
            @click="create"
          >
            新建检索
          </button>
        </div>
        <p v-if="creating" role="status">正在创建检索实例…</p>
        <p v-if="createError" role="alert">
          {{ createError }} 创建结果尚未确认，请勿连续重复创建。
        </p>
        <p v-if="!instance">新建“新检索”后填写查询与知识库范围。</p>
        <p v-else>
          <strong>{{ instance.name }}</strong
          ><br />{{ pending ? states[pending.task.state] : '等待提交' }}
        </p>
      </aside>
      <section class="dataset-detail" aria-label="检索窗口">
        <p v-if="!instance">请先新建检索实例。</p>
        <template v-else>
          <h2>{{ instance.name }}</h2>
          <form
            class="dataset-form knowledge-form"
            novalidate
            @submit.prevent="submit"
          >
            <div
              v-if="error"
              ref="summary"
              tabindex="-1"
              role="alert"
              class="dataset-error"
            >
              {{ error }}
              <p v-for="(message, field) in fields" :key="field">
                {{ message }}
              </p>
            </div>
            <fieldset :disabled="submitting || !!recoveryKey || !writable">
              <legend>本次检索条件</legend>
              <label for="search-query">检索内容</label
              ><textarea
                id="search-query"
                v-model="query"
                rows="4"
                :aria-invalid="!!fields['/query']"
                aria-describedby="search-query-error"
              />
              <p id="search-query-error" class="field-error">
                {{ fields['/query'] }}
              </p>
              <p>仅已发布知识库可选择；提交后重新校验当前范围，不自动扩围。</p>
              <p v-if="loading" role="status">正在读取知识库…</p>
              <p v-if="choiceError" role="alert">{{ choiceError }}</p>
              <button type="button" :disabled="loading" @click="loadChoices">
                刷新知识库范围
              </button>
              <p
                v-if="
                  !loading &&
                  !choiceError &&
                  !choices.some((b) => b.publicationState === 'published')
                "
              >
                暂无已发布知识库，请先在知识库管理中发布。
              </p>
              <label
                v-for="base in choices.filter(
                  (b) =>
                    b.publicationState === 'published' ||
                    selectedIds.includes(b.id),
                )"
                :key="base.id"
                class="knowledge-check"
                ><input
                  v-model="selectedIds"
                  type="checkbox"
                  :value="base.id"
                  :disabled="
                    base.publicationState !== 'published' &&
                    !selectedIds.includes(base.id)
                  "
                />{{ base.name }}（{{
                  base.publicationState === 'published'
                    ? base.availability === 'available'
                      ? '运行可用'
                      : '运行不可用'
                    : '已不在发布范围，请移除或重新检查'
                }}）</label
              >
              <p class="field-error">{{ fields['/knowledgeBaseIds'] }}</p>
              <label class="knowledge-check"
                ><input
                  v-model="consent"
                  type="checkbox"
                />我同意本次检索可能调用嵌入服务并产生费用</label
              >
              <p class="field-error">{{ fields['/consent'] }}</p>
            </fieldset>
            <p v-if="active">
              再次提交会取消本实例上一检索；外部在途调用可能已经计费，旧结果不会覆盖本次提交。
            </p>
            <p v-if="recoveryKey">
              受理结果尚未确认；恢复提交复用原查询、范围与幂等键，不重复发起另一轮检索。
            </p>
            <button
              class="primary-button"
              :disabled="!writable || submitting"
              type="submit"
            >
              {{ recoveryKey ? '恢复本次提交' : '开始检索' }}
            </button>
          </form>
          <p v-if="submitting || reading" role="status">
            正在受理或查询原检索请求…
          </p>
          <section
            v-if="pending"
            aria-label="本次检索结果"
            class="knowledge-section"
          >
            <h3>{{ states[pending.task.state] }}</h3>
            <p>请求标识：{{ pending.id }}</p>
            <button v-if="error" :disabled="reading" @click="read()">
              查询原检索请求
            </button>
            <p v-if="pending.task.failure" role="status">
              {{ pending.task.failure.message }}
            </p>
            <p
              v-if="
                pending.task.state === 'failed' &&
                pending.scope?.usedKnowledgeBaseIds.length === 0
              "
            >
              选定范围全部不可用，检索已终止，请修复依赖或重新选择范围。
            </p>
            <p v-for="warning in pending.task.warnings" :key="warning">
              {{ warning }}
            </p>
            <template v-if="pending.scope">
              <p v-if="active">
                当前展示已完成的范围检查；尚未检查的知识库不计入实际检索或遗漏。
              </p>
              <p v-if="pending.scope.incomplete" role="status">
                检索范围不完整，以下结果仅来自实际可用知识库。
              </p>
              <p>
                选定范围：{{
                  pending.scope.selectedKnowledgeBaseIds
                    .map((id) => names[id] || id)
                    .join('、')
                }}
              </p>
              <p>
                实际检索：{{
                  pending.scope.usedKnowledgeBaseIds
                    .map((id) => names[id] || id)
                    .join('、') || '无'
                }}
              </p>
              <p
                v-for="item in pending.scope.omitted"
                :key="item.knowledgeBaseId"
              >
                遗漏：{{
                  names[item.knowledgeBaseId] || item.knowledgeBaseId
                }}；{{ reasons[item.reason] }}；{{ item.message }}
              </p>
            </template>
            <template v-if="pending.result">
              <p>本次查询：{{ pending.result.query }}</p>
              <p v-if="pending.result.outcome === 'no_matches'" role="status">
                未找到相关内容。{{
                  pending.scope.incomplete
                    ? '本次范围不完整，不能代表全部选定资料均无匹配。'
                    : ''
                }}
              </p>
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
                    来源知识库：{{
                      names[item.knowledgeBaseId] || item.knowledgeBaseId
                    }}；内容版本：{{ item.contentVersionId }}
                  </p>
                  <p v-if="item.usingPreviousVersion">
                    仍使用旧生效版本；{{
                      item.updateState === 'failed'
                        ? '资料更新失败'
                        : '资料正在更新'
                    }}。
                  </p>
                  <pre class="source-text">{{ item.text }}</pre>
                  <EvidenceLocation :location="item.location" /><button
                    :disabled="evidenceBusy"
                    @click="openEvidence(item, $event)"
                  >
                    查看原文依据
                  </button>
                </li>
              </ul>
            </template>
          </section>
          <p v-if="evidenceBusy" role="status">正在读取原文依据…</p>
          <p v-if="evidenceError" role="alert">{{ evidenceError }}</p>
          <section
            v-if="evidence"
            ref="evidencePanel"
            tabindex="-1"
            role="region"
            aria-label="原文依据"
            class="knowledge-section"
            @keydown.esc="closeEvidence"
          >
            <h3>{{ evidence.filenameAtCapture }}</h3>
            <button @click="closeEvidence">关闭原文依据</button>
            <p>
              内容版本：{{ evidence.contentVersionId }}；索引版本：{{
                evidence.indexVersionId
              }}；配置版本：{{ evidence.configVersionId }}
            </p>
            <EvidenceLocation :location="evidence.location" />
            <p v-if="!evidence.originalAvailable">
              完整原文件不可用，当前展示本次检索的完整切片证据快照。
            </p>
            <pre class="source-text">{{ evidence.text }}</pre>
            <EvidencePreview
              :key="`${evidence.id}-${evidence.contentVersionId}`"
              :client="client"
              :evidence="evidence"
            />
          </section>
        </template>
      </section>
    </div>
  </section>
</template>
