<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { createAnswerClient } from './client';
import { createKnowledgeBaseClient } from '../knowledge-bases/client';
import { watchTask } from '../knowledge-bases/task-events';
import EvidenceLocation from '../knowledge-bases/EvidenceLocation.vue';
import EvidencePreview from '../knowledge-bases/EvidencePreview.vue';
import '../datasets/datasets.css';
import '../knowledge-bases/knowledge-bases.css';
import '../search/search.css';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const api = createAnswerClient(props.client),
  bases = createKnowledgeBaseClient(props.client);
const open = ref(false),
  name = ref(''),
  question = ref(''),
  selected = ref([]),
  consent = ref(false),
  choices = ref([]),
  choiceError = ref(''),
  loading = ref(false);
const conversation = ref(null),
  pending = ref(null),
  busy = ref(false),
  reading = ref(false),
  error = ref(''),
  fields = ref({}),
  notice = ref('');
const evidence = ref(null),
  evidenceBusy = ref(false),
  evidenceError = ref(''),
  evidencePanel = ref(null),
  summary = ref(null),
  questionInput = ref(null);
const cancelling = ref(false),
  cancelPanel = ref(null),
  cancelButton = ref(null);
const key = ref(null),
  payload = ref(null);
const active = computed(
  () =>
    pending.value && ['queued', 'running'].includes(pending.value.task.state),
);
const labels = {
  queued: '等待处理',
  retrieving: '正在检索原文',
  generating: '正在生成回答',
  answer_validating: '正在校验回答',
  completed: '处理结束',
};
const outcomes = {
  answered: '已依据资料回答',
  partial: '部分问题有依据',
  no_evidence: '资料中没有足够依据',
  needs_clarification: '需要补充说明',
};
const failures = {
  ANSWER_VALIDATION_FAILED: '回答未通过引用或事实校验，未展示草稿。',
  ANSWER_VALIDATION_TIMEOUT: '回答校验超时，未展示草稿。',
  MODEL_CONFIG_INVALID: '模型配置或调用预算不满足要求，请修正设置后手动重试。',
  MODEL_UNAVAILABLE: '模型服务不可用，本次任务已结束。',
  GENERATION_FAILED: '回答生成失败，本次任务已结束。',
  SCOPE_UNAVAILABLE: '选定知识库全部不可用，请调整范围或修复依赖。',
};
let sequence = 0,
  evidenceSequence = 0,
  choiceSequence = 0,
  controller,
  timer,
  lastEventId,
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
    choices.value = all.filter((b) => b.publicationState === 'published');
  } catch (failure) {
    if (current === choiceSequence) choiceError.value = failure.message;
  } finally {
    if (current === choiceSequence) loading.value = false;
  }
}
async function create() {
  if (active.value || busy.value || key.value) return;
  open.value = true;
  error.value = '';
  await nextTick();
  questionInput.value?.focus();
}
function stop() {
  controller?.abort();
  clearTimeout(timer);
}
function accept(value) {
  pending.value = value;
  if (!['queued', 'running'].includes(value.task.state)) stop();
}
async function read(current = sequence) {
  if (!pending.value || current !== sequence || reading.value) return;
  reading.value = true;
  try {
    const value = await api.get(
      pending.value.task.id,
      payload.value,
      conversation.value.id,
    );
    if (current === sequence) accept(value);
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) reading.value = false;
  }
}
async function follow(current = sequence) {
  if (!active.value || current !== sequence) return;
  controller = new AbortController();
  try {
    await watchTask(pending.value.task.id, {
      question: true,
      signal: controller.signal,
      lastEventId,
      onEvent: async (_, eventId) => {
        if (current !== sequence) return;
        lastEventId = eventId;
        await read(current);
      },
    });
  } catch (failure) {
    if (current !== sequence || failure.name === 'AbortError') return;
    notice.value = '阶段通知已中断，正在查询原任务，不会重新提交问题。';
  }
  if (current !== sequence) return;
  await read(current);
  if (current === sequence && active.value && !error.value)
    timer = setTimeout(() => follow(current), 1500);
}
async function submit() {
  if (busy.value || pending.value || !props.writable) return;
  error.value = '';
  fields.value = {};
  if (!key.value) {
    if (!/\S/.test(question.value) || Array.from(question.value).length > 20000)
      fields.value['/question'] = '问题不能为空，最多20000个码点。';
    if (!selected.value.length || selected.value.length > 100)
      fields.value['/knowledgeBaseIds'] = '请选择一至一百个已发布知识库。';
    if (
      name.value &&
      (!/\S/.test(name.value) || Array.from(name.value).length > 120)
    )
      fields.value['/manualName'] = '名称不能仅空白，最多120个码点。';
    if (!consent.value)
      fields.value['/consent'] = '请确认本次问答可能产生模型费用。';
    if (Object.keys(fields.value).length) {
      error.value = '请修正问题、范围和费用确认。';
      await nextTick();
      summary.value?.focus();
      return;
    }
    key.value = crypto.randomUUID();
    payload.value = {
      question: question.value,
      knowledgeBaseIds: [...selected.value],
      ...(name.value ? { manualName: name.value } : {}),
    };
  }
  const current = ++sequence;
  busy.value = true;
  try {
    const value = await api.first(payload.value, key.value);
    if (current !== sequence) return;
    key.value = null;
    conversation.value = value.conversation;
    accept(value.questionTask);
    if (active.value) follow(current);
  } catch (failure) {
    if (current !== sequence) return;
    error.value = failure.message;
    for (const field of failure.fieldErrors || [])
      fields.value[field.field] = field.message;
    if (failure.code) key.value = null;
    await nextTick();
    summary.value?.focus();
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function openCancel() {
  cancelling.value = true;
  await nextTick();
  cancelPanel.value?.focus();
}
async function closeCancel() {
  if (busy.value) return;
  cancelling.value = false;
  await nextTick();
  (cancelButton.value || questionInput.value)?.focus();
}
async function cancel() {
  if (!active.value || busy.value || !props.writable) return;
  const current = ++sequence;
  stop();
  busy.value = true;
  reading.value = false;
  error.value = '';
  ++evidenceSequence;
  evidence.value = null;
  try {
    const value = await api.cancel(
      pending.value.task.id,
      payload.value,
      conversation.value.id,
    );
    if (current !== sequence) return;
    accept(value);
    busy.value = false;
    await closeCancel();
    if (active.value) follow(current);
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function openEvidence(item, event) {
  const current = ++evidenceSequence;
  trigger = event.currentTarget;
  evidence.value = null;
  evidenceBusy.value = true;
  evidenceError.value = '';
  try {
    const value = await api.evidence(conversation.value.id, item);
    if (current !== evidenceSequence) return;
    evidence.value = value;
    await nextTick();
    evidencePanel.value?.focus();
  } catch (failure) {
    if (current === evidenceSequence) evidenceError.value = failure.message;
  } finally {
    if (current === evidenceSequence) evidenceBusy.value = false;
  }
}
async function closeEvidence() {
  ++evidenceSequence;
  evidence.value = null;
  evidenceBusy.value = false;
  evidenceError.value = '';
  await nextTick();
  if (trigger?.isConnected) trigger.focus();
}
async function recover() {
  const current = ++sequence;
  stop();
  reading.value = false;
  error.value = '';
  await read(current);
  if (current === sequence && active.value && !error.value) follow(current);
}
onBeforeUnmount(() => {
  ++sequence;
  ++choiceSequence;
  ++evidenceSequence;
  stop();
});
loadChoices();
</script>
<template>
  <section class="datasets-page search-page" aria-labelledby="answers-title">
    <header class="datasets-heading">
      <div>
        <p class="welcome-kicker">知识服务</p>
        <h1 id="answers-title">知识问答</h1>
        <p>回答依据本次检索原文，只有通过校验的终态内容才会显示。</p>
      </div>
    </header>
    <p v-if="!writable" role="status">当前写入已暂停，仍可查看已完成回答。</p>
    <div class="datasets-layout">
      <aside class="dataset-list" aria-label="问答实例">
        <div class="dataset-list-heading">
          <h2>问答实例</h2>
          <button
            :disabled="!writable || busy || !!pending || !!key"
            @click="create"
          >
            新建问答
          </button>
        </div>
        <p v-if="conversation">{{ conversation.name }}</p>
        <p v-else-if="open">新问答（首问提交前不保存）</p>
        <p v-else>尚未创建问答。</p>
      </aside>
      <section class="dataset-detail" aria-label="问答窗口">
        <p v-if="!open">请新建问答并选择已发布知识库。</p>
        <template v-else>
          <h2>{{ conversation?.name || name || '新问答' }}</h2>
          <form
            v-if="!pending"
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
            <fieldset :disabled="busy || !!key || !writable">
              <legend>首问条件</legend>
              <label for="answer-name">问答名称（可选）</label
              ><input
                id="answer-name"
                v-model="name"
                :aria-invalid="!!fields['/manualName']"
              /><label for="first-question">问题</label
              ><textarea
                id="first-question"
                ref="questionInput"
                v-model="question"
                rows="5"
                :aria-invalid="!!fields['/question']"
              />
              <p v-if="loading" role="status">正在读取已发布知识库…</p>
              <p v-if="choiceError" role="alert">{{ choiceError }}</p>
              <button type="button" :disabled="loading" @click="loadChoices">
                刷新已发布范围
              </button>
              <p v-if="!loading && !choices.length">
                暂无已发布知识库，请先在管理页发布。
              </p>
              <label
                v-for="base in choices"
                :key="base.id"
                class="knowledge-check"
                ><input v-model="selected" type="checkbox" :value="base.id" />{{
                  base.name
                }}（{{
                  base.availability === 'available' ? '运行可用' : '运行不可用'
                }}）</label
              ><label class="knowledge-check"
                ><input
                  v-model="consent"
                  type="checkbox"
                />我同意本次问答可能调用嵌入、预处理、生成及校验模型并产生费用</label
              >
            </fieldset>
            <p v-if="key">
              受理结果尚未确认，恢复提交复用原问题、范围与操作标识。
            </p>
            <button class="primary-button" :disabled="busy || !writable">
              {{ key ? '恢复首问提交' : '提交首问' }}
            </button>
          </form>
          <p v-if="busy || reading" role="status">正在受理或查询原问答任务…</p>
          <p v-if="notice" role="status">{{ notice }}</p>
          <section
            v-if="pending"
            class="knowledge-section"
            aria-label="本次问答"
          >
            <p>问题：{{ pending.question }}</p>
            <p role="status">
              {{ labels[pending.task.phase] || '正在处理' }}；任务标识：{{
                pending.task.id
              }}
            </p>
            <p v-if="active">检索、生成与校验完成前不展示回答草稿。</p>
            <p v-if="error" role="alert">
              {{ error
              }}<button :disabled="reading" @click="recover">
                查询原问答任务
              </button>
            </p>
            <button
              v-if="active"
              ref="cancelButton"
              :disabled="busy || !writable"
              @click="openCancel"
            >
              取消本次问答
            </button>
            <section
              v-if="cancelling"
              ref="cancelPanel"
              role="region"
              aria-label="确认取消问答"
              tabindex="-1"
              @keydown.esc="closeCancel"
            >
              <p>取消原任务，不展示未校验草稿；外部调用可能已产生费用。</p>
              <button :disabled="busy || !writable" @click="cancel">
                确认取消问答</button
              ><button :disabled="busy" @click="closeCancel">
                继续本次问答
              </button>
            </section>
            <p v-if="pending.task.failure" role="status">
              {{
                failures[pending.task.failure.code] ||
                pending.task.failure.message
              }}
            </p>
            <p v-if="pending.task.state === 'cancelled'" role="status">
              问答已取消，不展示草稿。
            </p>
            <p v-for="warning in pending.task.warnings" :key="warning">
              {{ warning }}
            </p>
            <div v-if="pending.scope">
              <p v-if="pending.scope.incomplete">
                本次范围不完整，回答只覆盖实际参与的知识库。
              </p>
              <p>
                选定范围：{{
                  pending.scope.selectedKnowledgeBaseIds.join('、')
                }}
              </p>
              <p>
                实际范围：{{
                  pending.scope.usedKnowledgeBaseIds.join('、') || '无'
                }}
              </p>
              <p
                v-for="item in pending.scope.omitted"
                :key="item.knowledgeBaseId"
              >
                遗漏知识库：{{ item.knowledgeBaseId }}；{{ item.message }}
              </p>
            </div>
            <section
              v-if="pending.task.state === 'succeeded' && pending.answer"
              aria-label="已校验回答"
            >
              <h3>{{ outcomes[pending.answer.outcome] }}</h3>
              <p class="source-text">{{ pending.answer.text }}</p>
              <p v-for="item in pending.answer.uncoveredQuestions" :key="item">
                缺少依据的问题：{{ item }}
              </p>
              <p v-for="item in pending.answer.contradictions" :key="item">
                资料矛盾：{{ item }}
              </p>
              <p v-for="warning in pending.answer.warnings" :key="warning">
                {{ warning }}
              </p>
              <p>校验通过仍需结合引用原文判断，不代表正确性保证。</p>
              <ul class="knowledge-files">
                <li
                  v-for="item in pending.answer.evidence.filter((e) =>
                    pending.answer.citationEvidenceIds.includes(e.id),
                  )"
                  :key="item.id"
                >
                  <strong>{{ item.filenameAtCapture }}</strong>
                  <p>内容版本：{{ item.contentVersionId }}</p>
                  <EvidenceLocation :location="item.location" /><button
                    :disabled="evidenceBusy"
                    @click="openEvidence(item, $event)"
                  >
                    查看引用原文
                  </button>
                </li>
              </ul>
            </section>
          </section>
          <p v-if="evidenceBusy" role="status">正在读取引用快照…</p>
          <p v-if="evidenceError" role="alert">{{ evidenceError }}</p>
          <section
            v-if="evidence"
            ref="evidencePanel"
            role="region"
            aria-label="引用原文"
            tabindex="-1"
            class="knowledge-section answer-evidence-drawer"
            @keydown.esc="closeEvidence"
          >
            <h3>{{ evidence.filenameAtCapture }}</h3>
            <button @click="closeEvidence">关闭引用原文</button>
            <p>
              实际内容版本：{{ evidence.contentVersionId }}；索引版本：{{
                evidence.indexVersionId
              }}；配置版本：{{ evidence.configVersionId }}
            </p>
            <EvidenceLocation :location="evidence.location" />
            <p>以下为回答引用的完整切片快照，不是整份旧文件。</p>
            <p v-if="!evidence.originalAvailable">
              完整原文件不可用，引用快照仍可核对。
            </p>
            <pre class="source-text">{{ evidence.text }}</pre>
            <EvidencePreview
              :key="evidence.id + '-' + evidence.contentVersionId"
              :client="client"
              :evidence="evidence"
            />
          </section>
        </template>
      </section>
    </div>
  </section>
</template>
<style scoped>
.answer-evidence-drawer {
  position: fixed;
  inset: 70px 0 0 auto;
  width: min(560px, 90vw);
  box-sizing: border-box;
  overflow: auto;
  z-index: 20;
  padding: 24px;
  background: white;
  border-left: 1px solid #d9e0e8;
  box-shadow: -8px 0 24px #1723311a;
}
</style>
