<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { createKnowledgeBaseClient } from './client';
import { createSettingsClient } from '../settings/client';
import { createDatasetClient, trimName } from '../datasets/client';
import { createFileClient } from '../files/client';
import IndexTasks from './IndexTasks.vue';
import ManagementSearch from './ManagementSearch.vue';
import ProcessingEditor from './ProcessingEditor.vue';
import SourceAction from './SourceAction.vue';
import Exclusions from './Exclusions.vue';
import '../datasets/datasets.css';
import './knowledge-bases.css';

const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const route = useRoute();
const router = useRouter();
const api = createKnowledgeBaseClient(props.client);
const settings = createSettingsClient(props.client),
  datasetsApi = createDatasetClient(props.client);
const filesApi = createFileClient(props.client);
const rows = ref([]),
  selected = ref(null),
  snapshot = ref(null),
  sources = ref([]),
  files = ref([]);
const loading = ref(false),
  detailLoading = ref(false),
  error = ref(''),
  detailError = ref('');
const cursors = ref([undefined]),
  page = ref(0),
  nextCursor = ref(null);
const sourceCursor = ref(null),
  fileCursor = ref(null);
const moreLoading = ref({ sources: false, files: false });
let listSequence = 0,
  detailSequence = 0;
const creating = ref(false),
  editing = ref(false),
  saving = ref(false),
  choicesLoading = ref(false),
  formError = ref(''),
  fields = ref({}),
  notice = ref('');
const name = ref(''),
  description = ref(''),
  versions = ref([]),
  datasets = ref([]),
  versionId = ref(''),
  datasetIds = ref([]);
const strategy = ref('structure'),
  overlap = ref(200),
  removeHeaders = ref(false),
  dimensions = ref(1024),
  distance = ref('cosine'),
  threshold = ref(''),
  candidates = ref(''),
  metadata = ref([]);
const pending = ref(null),
  pendingError = ref(''),
  createKey = ref(null),
  createPayload = ref(null);
const nameInput = ref(null),
  errorSummary = ref(null),
  createButton = ref(null),
  editButton = ref(null);
let formSequence = 0,
  timer;
const associating = ref(false),
  sourceType = ref('dataset'),
  sourceDataset = ref(''),
  sourceFile = ref(''),
  availableFiles = ref([]),
  sourceBusy = ref(false),
  sourceError = ref(''),
  sourceFields = ref({}),
  sourceSummary = ref(null),
  sourceButton = ref(null),
  fileNames = ref({}),
  sourceNames = ref({}),
  metadataError = ref('');
let sourceSequence = 0;
let fileChoiceSequence = 0;
const sourceFileLoading = ref(false);
async function loadNames() {
  const sequence = detailSequence;
  metadataError.value = '';
  try {
    const names = await Promise.all(
      files.value.map(async (f) => [
        f.fileId,
        (await filesApi.get(f.fileId)).name,
      ]),
    );
    const namesOfSources = await Promise.all(
      sources.value.map(async (s) => [
        s.resourceId,
        s.type === 'dataset'
          ? (await datasetsApi.get(s.resourceId)).name
          : (await filesApi.get(s.resourceId)).name,
      ]),
    );
    if (sequence === detailSequence) {
      fileNames.value = Object.fromEntries(names);
      sourceNames.value = Object.fromEntries(namesOfSources);
    }
  } catch (failure) {
    if (sequence === detailSequence) metadataError.value = failure.message;
  }
}
async function openSources() {
  associating.value = true;
  sourceType.value = 'dataset';
  sourceDataset.value = '';
  sourceFile.value = '';
  availableFiles.value = [];
  sourceError.value = '';
  sourceFields.value = {};
  const sequence = ++sourceSequence;
  sourceBusy.value = true;
  try {
    const result = await allPages((c) => datasetsApi.list(c));
    if (sequence === sourceSequence) datasets.value = result;
  } catch (failure) {
    if (sequence === sourceSequence) sourceError.value = failure.message;
  } finally {
    if (sequence === sourceSequence) sourceBusy.value = false;
  }
}
async function loadChoiceFiles() {
  const sequence = ++fileChoiceSequence;
  availableFiles.value = [];
  sourceFile.value = '';
  sourceError.value = '';
  sourceFileLoading.value = false;
  if (sourceType.value !== 'file' || !sourceDataset.value) return;
  sourceFileLoading.value = true;
  try {
    const result = await allPages((c) => filesApi.list(sourceDataset.value, c));
    if (sequence === fileChoiceSequence) availableFiles.value = result;
  } catch (failure) {
    if (sequence === fileChoiceSequence) sourceError.value = failure.message;
  } finally {
    if (sequence === fileChoiceSequence) sourceFileLoading.value = false;
  }
}
async function addSource() {
  if (sourceBusy.value || sourceFileLoading.value) return;
  sourceError.value = '';
  sourceFields.value = {};
  const resourceId =
    sourceType.value === 'dataset' ? sourceDataset.value : sourceFile.value;
  if (!resourceId) {
    sourceError.value = '请选择要关联的数据集或资料。';
    sourceFields.value['/resourceId'] = sourceError.value;
    await nextTick();
    sourceSummary.value?.focus();
    return;
  }
  const sequence = detailSequence;
  sourceBusy.value = true;
  try {
    await api.addSource(selected.value.id, {
      type: sourceType.value,
      resourceId,
    });
    if (sequence !== detailSequence) return;
    associating.value = false;
    notice.value = '来源已关联，文件仍须完成处理才能检索。';
    await loadDetail();
    await loadPage();
    await nextTick();
    sourceButton.value?.focus();
  } catch (failure) {
    if (sequence === detailSequence) {
      sourceError.value = failure.message;
      for (const field of failure.fieldErrors || [])
        sourceFields.value[field.field] = field.message;
      await nextTick();
      sourceSummary.value?.focus();
    }
  } finally {
    if (sequence === detailSequence || !associating.value)
      sourceBusy.value = false;
  }
}
async function closeSources() {
  ++sourceSequence;
  ++fileChoiceSequence;
  sourceFileLoading.value = false;
  associating.value = false;
  sourceBusy.value = false;
  await nextTick();
  sourceButton.value?.focus();
}
async function allPages(load) {
  const items = [],
    seen = new Set();
  let cursor;
  do {
    const result = await load(cursor);
    items.push(...result.items);
    cursor = result.nextCursor;
    if (cursor !== null && seen.has(cursor))
      throw new Error('分页响应重复，请重新读取。');
    seen.add(cursor);
  } while (cursor !== null);
  return items;
}
async function openForm(create) {
  if (saving.value || pending.value || createKey.value) return;
  const sequence = ++formSequence;
  creating.value = create;
  editing.value = !create;
  formError.value = '';
  fields.value = {};
  notice.value = '';
  pending.value = null;
  pendingError.value = '';
  createKey.value = null;
  createPayload.value = null;
  name.value = create ? '' : selected.value.name;
  description.value = create ? '' : selected.value.description;
  if (create) {
    versions.value = [];
    datasets.value = [];
    versionId.value = '';
    datasetIds.value = [];
    threshold.value = '';
    candidates.value = '';
    metadata.value = [];
    strategy.value = 'structure';
    overlap.value = 200;
    removeHeaders.value = false;
    dimensions.value = 1024;
    distance.value = 'cosine';
    choicesLoading.value = true;
    try {
      const [v, d] = await Promise.all([
        allPages((c) => settings.versions(c)),
        allPages((c) => datasetsApi.list(c)),
      ]);
      if (sequence !== formSequence) return;
      versions.value = v.filter((item) => item.complete);
      datasets.value = d;
      versionId.value = versions.value[0]?.versionId || '';
    } catch (failure) {
      if (sequence === formSequence) formError.value = failure.message;
    } finally {
      if (sequence === formSequence) choicesLoading.value = false;
    }
  }
  await nextTick();
  nameInput.value?.focus();
}
async function closeForm() {
  const create = creating.value;
  ++formSequence;
  clearTimeout(timer);
  creating.value = false;
  editing.value = false;
  pending.value = null;
  await nextTick();
  (create ? createButton.value : editButton.value)?.focus();
}
async function failedForm(failure) {
  formError.value = failure.message;
  for (const field of failure.fieldErrors || []) {
    const path =
      field.field.startsWith('/processing/chunking/customMetadata') ||
      field.field.startsWith('/processing/chunking/embeddedMetadataFields')
        ? '/processing/chunking/customMetadata'
        : field.field;
    fields.value[path] = field.message;
  }
  await nextTick();
  errorSummary.value?.focus();
}
async function pollTask() {
  if (!pending.value) return;
  const sequence = formSequence;
  pendingError.value = '';
  try {
    const result = await api.task(pending.value.id);
    if (sequence !== formSequence) return;
    pending.value = result;
    if (result.state === 'succeeded') {
      await closeForm();
      notice.value = '知识库已创建，尚未发布。';
      await router.push(`/knowledge-bases/${result.resultResourceId}`);
      await loadPage(0);
    } else if (['queued', 'running'].includes(result.state))
      timer = setTimeout(pollTask, 600);
  } catch (failure) {
    if (sequence === formSequence) pendingError.value = failure.message;
  }
}
async function submit() {
  if (saving.value || choicesLoading.value || pending.value) return;
  fields.value = {};
  formError.value = '';
  if (!trimName(name.value) || Array.from(name.value).length > 120)
    fields.value['/name'] = '名称不能为空且最多 120 字。';
  if (Array.from(description.value).length > 2000)
    fields.value['/description'] = '说明最多 2000 字。';
  const embedding = versions.value.find((v) => v.versionId === versionId.value);
  if (creating.value) {
    if (!embedding)
      fields.value['/processing/embedding/modelConfigVersionId'] =
        '请先在设置中保存完整嵌入配置。';
    if (threshold.value === '' || !Number.isFinite(Number(threshold.value)))
      fields.value['/processing/embedding/similarityThreshold'] =
        '请显式填写有限的相似度阈值。';
    if (
      !Number.isInteger(Number(candidates.value)) ||
      Number(candidates.value) < 1 ||
      Number(candidates.value) > 1000
    )
      fields.value['/processing/embedding/candidatesPerQuery'] =
        '候选数须为 1 至 1000 的整数。';
    if (
      !Number.isInteger(Number(dimensions.value)) ||
      Number(dimensions.value) < 1 ||
      Number(dimensions.value) > 65536
    )
      fields.value['/processing/embedding/dimensions'] =
        '维度须为 1 至 65536 的整数。';
    if (
      !Number.isInteger(Number(overlap.value)) ||
      Number(overlap.value) < 0 ||
      Number(overlap.value) >= 2000
    )
      fields.value['/processing/chunking/overlapCodePoints'] =
        '目标重叠须为 0 至 1999 个码点。';
    if (
      metadata.value.some(
        (m) =>
          !/^user_[A-Za-z0-9_]{1,60}$/.test(m.key) ||
          Array.from(m.value).length > 2000,
      ) ||
      new Set(metadata.value.map((m) => m.key)).size !== metadata.value.length
    )
      fields.value['/processing/chunking/customMetadata'] =
        '自定义字段须为唯一的 user_ 英文标识，值最多 2000 字。';
  }
  if (Object.keys(fields.value).length) {
    await failedForm(new Error('请检查以下字段后重新提交。'));
    return;
  }
  const sequence = formSequence;
  saving.value = true;
  try {
    if (creating.value) {
      if (!createKey.value) {
        createKey.value = crypto.randomUUID();
        createPayload.value = {
          name: name.value,
          description: description.value,
          processing: {
            chunking: {
              strategy: strategy.value,
              overlapCodePoints: Number(overlap.value),
              removePageHeadersFooters: removeHeaders.value,
              customMetadata: Object.fromEntries(
                metadata.value.map((m) => [m.key, m.value]),
              ),
              embeddedMetadataFields: metadata.value
                .filter((m) => m.embedded)
                .map((m) => m.key),
            },
            embedding: {
              modelConfigVersionId: embedding.versionId,
              dimensions: Number(dimensions.value),
              distance: distance.value,
              similarityThreshold: Number(threshold.value),
              candidatesPerQuery: Number(candidates.value),
              modelSnapshot: {
                endpoint: embedding.endpoint,
                model: embedding.model,
              },
            },
          },
          datasetIds: datasetIds.value,
        };
      }
      const result = await api.create(createPayload.value, createKey.value);
      if (sequence !== formSequence) return;
      pending.value = result.task;
      if (['queued', 'running'].includes(result.task.state)) await pollTask();
      else if (result.task.state === 'succeeded') await pollTask();
    } else {
      const result = await api.update(selected.value.id, {
        name: name.value,
        description: description.value,
      });
      if (sequence !== formSequence) return;
      selected.value = result;
      await closeForm();
      notice.value = '知识库名称与说明已保存。';
      await loadPage();
    }
  } catch (failure) {
    if (sequence === formSequence) {
      await failedForm(failure);
      if (failure.code) {
        createKey.value = null;
        createPayload.value = null;
      }
    }
  } finally {
    if (sequence === formSequence || !creating.value) saving.value = false;
  }
}
const distances = { cosine: '余弦相似度', dot: '点积', euclidean: '欧氏距离' };
const strategies = { structure: '结构优先', fixed: '固定长度', page: '按页' };
const taskStates = {
  succeeded: '已完成',
  failed: '失败',
  cancelled: '已取消',
  interrupted: '已中断',
};
const publication = {
  unpublished: '未发布',
  published: '已发布',
  offline: '已下线',
};
const processing = {
  idle: '暂无处理任务',
  queued: '等待处理',
  running: '正在处理',
  failed: '处理失败',
};
const updates = {
  not_included: '未纳入',
  queued: '待处理',
  processing: '处理中',
  ready: '已生效',
  failed: '处理失败',
};
async function loadPage(index = page.value) {
  const sequence = ++listSequence;
  loading.value = true;
  error.value = '';
  try {
    const result = await api.list(cursors.value[index]);
    if (sequence !== listSequence) return;
    rows.value = result.items;
    nextCursor.value = result.nextCursor;
    page.value = index;
  } catch (failure) {
    if (sequence === listSequence) error.value = failure.message;
  } finally {
    if (sequence === listSequence) loading.value = false;
  }
}
async function nextPage() {
  cursors.value[page.value + 1] = nextCursor.value;
  await loadPage(page.value + 1);
}
async function loadDetail() {
  const sequence = ++detailSequence;
  moreLoading.value = { sources: false, files: false };
  selected.value = null;
  snapshot.value = null;
  sources.value = [];
  files.value = [];
  detailError.value = '';
  detailLoading.value = false;
  ++formSequence;
  clearTimeout(timer);
  creating.value = false;
  editing.value = false;
  saving.value = false;
  pending.value = null;
  createKey.value = null;
  createPayload.value = null;
  ++sourceSequence;
  ++fileChoiceSequence;
  sourceFileLoading.value = false;
  associating.value = false;
  sourceBusy.value = false;
  fileNames.value = {};
  sourceNames.value = {};
  metadataError.value = '';
  if (route.path === '/knowledge-bases') return;
  const id = route.path.slice('/knowledge-bases/'.length);
  detailLoading.value = true;
  try {
    const [base, config, sourcePage, filePage] = await Promise.all([
      api.get(id),
      api.config(id),
      api.sources(id),
      api.files(id),
    ]);
    if (sequence !== detailSequence) return;
    selected.value = base;
    snapshot.value = config;
    sources.value = sourcePage.items;
    files.value = filePage.items;
    sourceCursor.value = sourcePage.nextCursor;
    fileCursor.value = filePage.nextCursor;
    await loadNames();
  } catch (failure) {
    if (sequence === detailSequence) detailError.value = failure.message;
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}
async function loadMore(kind) {
  if (!selected.value || moreLoading.value[kind]) return;
  const sequence = detailSequence;
  moreLoading.value[kind] = true;
  detailError.value = '';
  try {
    const result = await api[kind](
      selected.value.id,
      kind === 'sources' ? sourceCursor.value : fileCursor.value,
    );
    if (sequence !== detailSequence) return;
    if (kind === 'sources') {
      sources.value.push(...result.items);
      sourceCursor.value = result.nextCursor;
    } else {
      files.value.push(...result.items);
      fileCursor.value = result.nextCursor;
    }
    await loadNames();
  } catch (failure) {
    if (sequence === detailSequence) detailError.value = failure.message;
  } finally {
    if (sequence === detailSequence) moreLoading.value[kind] = false;
  }
}
watch(() => route.path, loadDetail, { immediate: true });
watch([sourceType, sourceDataset], loadChoiceFiles);
onBeforeUnmount(() => {
  ++listSequence;
  ++detailSequence;
  ++formSequence;
  ++sourceSequence;
  ++fileChoiceSequence;
  clearTimeout(timer);
});
loadPage(0);
</script>

<template>
  <section
    class="datasets-page knowledge-bases-page"
    aria-labelledby="knowledge-bases-title"
  >
    <header class="datasets-heading">
      <div>
        <p class="welcome-kicker">数据接入</p>
        <h1 id="knowledge-bases-title">知识库</h1>
        <p>先保存独立配置，再关联资料，逐步构建可检索的知识。</p>
      </div>
      <button
        ref="createButton"
        class="primary-button"
        :disabled="!writable || saving || !!pending || !!createKey"
        @click="openForm(true)"
      >
        创建知识库
      </button>
    </header>
    <p v-if="!writable" role="status">当前写入已暂停，仍可查看已有知识库。</p>
    <p v-if="notice" role="status" class="dataset-notice">{{ notice }}</p>
    <div class="datasets-layout">
      <section
        class="dataset-list"
        aria-label="知识库列表"
        :aria-busy="loading"
      >
        <div class="dataset-list-heading">
          <h2>全部知识库</h2>
          <button :disabled="loading" @click="loadPage()">刷新列表</button>
        </div>
        <p v-if="loading" role="status">正在加载知识库…</p>
        <p v-if="error" role="alert">
          {{ error }} <button @click="loadPage()">重试列表</button>
        </p>
        <p v-if="!loading && !error && !rows.length" class="dataset-empty">
          尚无知识库，可先创建空库。
        </p>
        <ul class="dataset-rows">
          <li v-for="item in rows" :key="item.id">
            <RouterLink
              :to="`/knowledge-bases/${item.id}`"
              :aria-current="selected?.id === item.id ? 'page' : undefined"
              ><strong>{{ item.name }}</strong
              ><span>{{ item.description || '暂无说明' }}</span
              ><small
                >{{ publication[item.publicationState] }} ·
                {{ item.includedFileCount }} 份纳入资料</small
              ></RouterLink
            >
          </li>
        </ul>
        <div class="dataset-pagination">
          <button :disabled="loading || page === 0" @click="loadPage(page - 1)">
            上一页</button
          ><span>第 {{ page + 1 }} 页</span
          ><button :disabled="loading || nextCursor === null" @click="nextPage">
            下一页
          </button>
        </div>
      </section>
      <section
        class="dataset-detail"
        aria-label="知识库详情"
        :aria-busy="detailLoading"
      >
        <form
          v-if="creating || editing"
          class="dataset-form knowledge-form"
          novalidate
          @submit.prevent="submit"
          @keydown.esc="!saving && !pending && !createKey && closeForm()"
        >
          <h2>{{ creating ? '创建知识库' : '修改名称与说明' }}</h2>
          <p v-if="choicesLoading" role="status">正在读取配置和数据集…</p>
          <div
            v-if="formError"
            ref="errorSummary"
            class="dataset-error"
            role="alert"
            tabindex="-1"
          >
            <p>{{ formError }}</p>
            <p v-for="(message, field) in fields" :key="field">{{ message }}</p>
          </div>
          <fieldset :disabled="saving || !!pending || !!createKey">
            <legend>知识库配置</legend>
            <label for="kb-name">名称</label
            ><input
              id="kb-name"
              ref="nameInput"
              v-model="name"
              :aria-invalid="!!fields['/name']"
              aria-describedby="kb-name-error"
            />
            <p id="kb-name-error" class="field-error">{{ fields['/name'] }}</p>
            <label for="kb-description">说明</label
            ><textarea
              id="kb-description"
              v-model="description"
              :aria-invalid="!!fields['/description']"
              aria-describedby="kb-description-error"
              rows="3"
            ></textarea>
            <p id="kb-description-error" class="field-error">
              {{ fields['/description'] }}
            </p>
            <template v-if="creating">
              <label for="kb-version">嵌入配置版本</label
              ><select
                id="kb-version"
                v-model="versionId"
                :aria-invalid="
                  !!fields['/processing/embedding/modelConfigVersionId']
                "
                aria-describedby="kb-version-error"
              >
                <option value="">请选择完整嵌入配置</option>
                <option
                  v-for="version in versions"
                  :key="version.versionId"
                  :value="version.versionId"
                >
                  {{ version.model }} · {{ version.endpoint }} ·
                  {{ version.versionId }}
                </option>
              </select>
              <p id="kb-version-error" class="field-error">
                {{ fields['/processing/embedding/modelConfigVersionId'] }}
              </p>
              <p v-if="!choicesLoading && !versions.length">
                尚无完整嵌入配置，请先前往<RouterLink to="/settings"
                  >设置</RouterLink
                >保存配置。
              </p>
              <div class="knowledge-input-grid">
                <div>
                  <label for="kb-dimensions">嵌入维度</label
                  ><input
                    id="kb-dimensions"
                    v-model="dimensions"
                    type="number"
                    :aria-invalid="!!fields['/processing/embedding/dimensions']"
                    aria-describedby="kb-dimensions-error"
                  />
                  <p id="kb-dimensions-error" class="field-error">
                    {{ fields['/processing/embedding/dimensions'] }}
                  </p>
                </div>
                <div>
                  <label for="kb-distance">相似度度量</label
                  ><select id="kb-distance" v-model="distance">
                    <option value="cosine">余弦相似度</option>
                    <option value="dot">点积</option>
                    <option value="euclidean">欧氏距离</option>
                  </select>
                </div>
                <div>
                  <label for="kb-threshold">相似度阈值（必填）</label
                  ><input
                    id="kb-threshold"
                    v-model="threshold"
                    type="number"
                    step="any"
                    :aria-invalid="
                      !!fields['/processing/embedding/similarityThreshold']
                    "
                    aria-describedby="kb-threshold-error"
                  />
                  <p id="kb-threshold-error" class="field-error">
                    {{ fields['/processing/embedding/similarityThreshold'] }}
                  </p>
                </div>
                <div>
                  <label for="kb-candidates">每条查询候选数（必填）</label
                  ><input
                    id="kb-candidates"
                    v-model="candidates"
                    type="number"
                    :aria-invalid="
                      !!fields['/processing/embedding/candidatesPerQuery']
                    "
                    aria-describedby="kb-candidates-error"
                  />
                  <p id="kb-candidates-error" class="field-error">
                    {{ fields['/processing/embedding/candidatesPerQuery'] }}
                  </p>
                </div>
                <div>
                  <label for="kb-strategy">切片策略</label
                  ><select id="kb-strategy" v-model="strategy">
                    <option value="structure">结构优先</option>
                    <option value="fixed">固定长度</option>
                    <option value="page">按页（仅限 PDF）</option>
                  </select>
                </div>
                <div>
                  <label for="kb-overlap">目标重叠码点数</label
                  ><input
                    id="kb-overlap"
                    v-model="overlap"
                    type="number"
                    :aria-invalid="
                      !!fields['/processing/chunking/overlapCodePoints']
                    "
                    aria-describedby="kb-overlap-error"
                  />
                  <p id="kb-overlap-error" class="field-error">
                    {{ fields['/processing/chunking/overlapCodePoints'] }}
                  </p>
                </div>
              </div>
              <p class="dataset-help">
                切片硬上限为 2000
                个码点，重叠须低于上限。初始维度与重叠不是效果保证；阈值应按所选模型与度量评测。创建仅初始化集合，不调用模型验证兼容性。
              </p>
              <label class="knowledge-check"
                ><input
                  v-model="removeHeaders"
                  type="checkbox"
                />移除页眉页脚</label
              >
              <fieldset>
                <legend>自定义元信息（可选）</legend>
                <div
                  v-for="(item, index) in metadata"
                  :key="index"
                  class="knowledge-metadata"
                >
                  <label :for="`kb-meta-key-${index}`"
                    >字段标识 {{ index + 1 }}</label
                  ><input
                    :id="`kb-meta-key-${index}`"
                    v-model="item.key"
                    :aria-invalid="
                      !!fields['/processing/chunking/customMetadata']
                    "
                    aria-describedby="kb-meta-error"
                    placeholder="user_topic"
                  /><label :for="`kb-meta-value-${index}`"
                    >字段内容 {{ index + 1 }}</label
                  ><input
                    :id="`kb-meta-value-${index}`"
                    v-model="item.value"
                    :aria-invalid="
                      !!fields['/processing/chunking/customMetadata']
                    "
                    aria-describedby="kb-meta-error"
                  /><label class="knowledge-check"
                    ><input
                      v-model="item.embedded"
                      type="checkbox"
                    />加入嵌入输入</label
                  ><button type="button" @click="metadata.splice(index, 1)">
                    移除字段 {{ index + 1 }}
                  </button>
                </div>
                <p id="kb-meta-error" class="field-error">
                  {{ fields['/processing/chunking/customMetadata'] }}
                </p>
                <button
                  type="button"
                  :disabled="metadata.length >= 20"
                  @click="
                    metadata.push({ key: 'user_', value: '', embedded: false })
                  "
                >
                  添加元信息字段
                </button>
              </fieldset>
              <fieldset>
                <legend>初始关联数据集（可选，可保存空库）</legend>
                <label
                  v-for="dataset in datasets"
                  :key="dataset.id"
                  class="knowledge-check"
                  ><input
                    v-model="datasetIds"
                    type="checkbox"
                    :value="dataset.id"
                    :disabled="
                      datasetIds.length >= 100 &&
                      !datasetIds.includes(dataset.id)
                    "
                  />{{ dataset.name }}</label
                >
                <p v-if="!datasets.length">尚无数据集，可稍后关联。</p>
              </fieldset>
            </template>
          </fieldset>
          <div v-if="pending" role="status" class="knowledge-section">
            <p>创建任务：{{ pending.id }}</p>
            <p v-if="['queued', 'running'].includes(pending.state)">
              已受理，正在初始化。尚未创建成功。
            </p>
            <template v-else
              ><p>
                {{
                  pending.state === 'succeeded' ? '已完成初始化' : '创建未完成'
                }}：{{ pending.failure?.message || taskStates[pending.state] }}
              </p>
              <p v-for="warning in pending.warnings" :key="warning">
                {{ warning }}
              </p>
              <button
                v-if="pending.state !== 'succeeded'"
                type="button"
                @click="
                  pending = null;
                  createKey = null;
                  createPayload = null;
                "
              >
                修改后重新创建
              </button></template
            >
            <p v-if="pendingError" role="alert">
              {{ pendingError
              }}<button type="button" @click="pollTask">继续查询原任务</button>
            </p>
          </div>
          <div class="dataset-form-actions">
            <button
              v-if="!pending"
              class="primary-button"
              :disabled="saving || choicesLoading || !writable"
            >
              {{
                saving
                  ? '正在保存…'
                  : createKey
                    ? '继续确认本次创建'
                    : creating
                      ? '保存知识库'
                      : '保存修改'
              }}</button
            ><button
              type="button"
              :disabled="saving || !!pending || !!createKey"
              @click="closeForm"
            >
              取消
            </button>
          </div>
        </form>
        <template v-else>
          <p v-if="detailLoading" role="status">正在读取知识库…</p>
          <p v-if="detailError" role="alert">
            {{ detailError }} <button @click="loadDetail">重试详情</button>
          </p>
          <template v-if="selected">
            <div class="dataset-detail-heading">
              <h2>{{ selected.name }}</h2>
              <button
                ref="editButton"
                :disabled="!writable"
                @click="openForm(false)"
              >
                修改名称与说明
              </button>
            </div>
            <p class="dataset-description">
              {{ selected.description || '暂无说明' }}
            </p>
            <div class="knowledge-states" aria-label="知识库独立状态">
              <span>发布：{{ publication[selected.publicationState] }}</span
              ><span
                >可用性：{{
                  selected.availability === 'available'
                    ? '运行可用'
                    : '运行不可用'
                }}</span
              ><span>处理：{{ processing[selected.processingState] }}</span>
            </div>
            <p v-if="selected.availabilityReason">
              {{ selected.availabilityReason }}
            </p>
            <p>
              纳入 {{ selected.includedFileCount }} 份，当前可检索
              {{ selected.searchableFileCount }} 份。关联成功不代表可检索。
            </p>
            <section
              v-if="snapshot"
              class="knowledge-section"
              aria-label="目标处理配置"
            >
              <h3>目标处理配置</h3>
              <p>此快照独立保存，当前只读。</p>
              <dl>
                <div>
                  <dt>嵌入模型</dt>
                  <dd>{{ snapshot.input.embedding.modelSnapshot.model }}</dd>
                </div>
                <div>
                  <dt>接口地址</dt>
                  <dd>{{ snapshot.input.embedding.modelSnapshot.endpoint }}</dd>
                </div>
                <div>
                  <dt>配置版本</dt>
                  <dd>{{ snapshot.input.embedding.modelConfigVersionId }}</dd>
                </div>
                <div>
                  <dt>维度与度量</dt>
                  <dd>
                    {{ snapshot.input.embedding.dimensions }} /
                    {{ distances[snapshot.input.embedding.distance] }}
                  </dd>
                </div>
                <div>
                  <dt>阈值与候选数</dt>
                  <dd>
                    {{ snapshot.input.embedding.similarityThreshold }} /
                    {{ snapshot.input.embedding.candidatesPerQuery }}
                  </dd>
                </div>
                <div>
                  <dt>切片策略</dt>
                  <dd>{{ strategies[snapshot.input.chunking.strategy] }}</dd>
                </div>
                <div>
                  <dt>目标重叠</dt>
                  <dd>
                    {{ snapshot.input.chunking.overlapCodePoints }} 个码点
                  </dd>
                </div>
                <div>
                  <dt>移除页眉页脚</dt>
                  <dd>
                    {{
                      snapshot.input.chunking.removePageHeadersFooters
                        ? '是'
                        : '否'
                    }}
                  </dd>
                </div>
                <div>
                  <dt>解析、切片与长度规则版本</dt>
                  <dd>
                    {{ snapshot.parserRuleVersion }} /
                    {{ snapshot.chunkRuleVersion }} /
                    {{ snapshot.lengthMeterVersion }}
                  </dd>
                </div>
                <div>
                  <dt>自定义元信息</dt>
                  <dd>
                    <template
                      v-if="
                        Object.keys(snapshot.input.chunking.customMetadata)
                          .length
                      "
                      ><p
                        v-for="(value, key) in snapshot.input.chunking
                          .customMetadata"
                        :key="key"
                      >
                        {{ key }}：{{ value
                        }}{{
                          snapshot.input.chunking.embeddedMetadataFields.includes(
                            key,
                          )
                            ? '（加入嵌入输入）'
                            : ''
                        }}
                      </p></template
                    ><template v-else>无</template>
                  </dd>
                </div>
              </dl>
            </section>
            <section class="knowledge-section" aria-label="关联来源">
              <div class="dataset-detail-heading">
                <h3>关联来源</h3>
                <button
                  ref="sourceButton"
                  :disabled="!writable || associating"
                  @click="openSources"
                >
                  关联资料
                </button>
              </div>
              <p>
                数据集中的新增及内容更新会自动排队同步；多个来源独立保留，重复关联不重复创建索引。排除优先全部来源。
              </p>
              <button :disabled="detailLoading" @click="loadDetail">
                刷新同步状态
              </button>
              <form
                v-if="associating"
                class="dataset-form knowledge-form"
                aria-label="关联资料表单"
                novalidate
                @submit.prevent="addSource"
                @keydown.esc="!sourceBusy && closeSources()"
              >
                <div
                  v-if="sourceError"
                  ref="sourceSummary"
                  class="dataset-error"
                  role="alert"
                  tabindex="-1"
                >
                  {{ sourceError }}
                  <p v-for="(message, field) in sourceFields" :key="field">
                    {{ message }}
                  </p>
                </div>
                <label for="kb-source-type">关联类型</label
                ><select
                  id="kb-source-type"
                  v-model="sourceType"
                  :disabled="sourceBusy"
                  :aria-invalid="!!sourceFields['/type']"
                  aria-describedby="kb-source-type-error"
                >
                  <option value="dataset">整个数据集</option>
                  <option value="file">单份资料</option>
                </select>
                <p id="kb-source-type-error" class="field-error">
                  {{ sourceFields['/type'] }}
                </p>
                <label for="kb-source-dataset">选择数据集</label
                ><select
                  id="kb-source-dataset"
                  v-model="sourceDataset"
                  :disabled="sourceBusy"
                  :aria-invalid="!!sourceFields['/resourceId']"
                  aria-describedby="kb-source-error"
                >
                  <option value="">请选择数据集</option>
                  <option
                    v-for="dataset in datasets"
                    :key="dataset.id"
                    :value="dataset.id"
                  >
                    {{ dataset.name }}
                  </option>
                </select>
                <template v-if="sourceType === 'file'"
                  ><label for="kb-source-file">选择资料</label
                  ><select
                    id="kb-source-file"
                    v-model="sourceFile"
                    :disabled="sourceBusy || sourceFileLoading"
                    :aria-invalid="!!sourceFields['/resourceId']"
                    aria-describedby="kb-source-error"
                  >
                    <option value="">请选择资料</option>
                    <option
                      v-for="item in availableFiles"
                      :key="item.id"
                      :value="item.id"
                    >
                      {{ item.name }}
                    </option>
                  </select></template
                >
                <p id="kb-source-error" class="field-error">
                  {{ sourceFields['/resourceId'] }}
                </p>
                <p v-if="sourceBusy" role="status">正在读取或保存来源…</p>
                <p v-if="sourceFileLoading" role="status">正在读取资料选项…</p>
                <div class="dataset-form-actions">
                  <button
                    class="primary-button"
                    :disabled="sourceBusy || sourceFileLoading || !writable"
                  >
                    保存关联</button
                  ><button
                    type="button"
                    :disabled="sourceBusy"
                    @click="closeSources"
                  >
                    取消关联</button
                  ><button
                    v-if="sourceError && !datasets.length"
                    type="button"
                    @click="openSources"
                  >
                    重试读取数据集
                  </button>
                </div>
              </form>
              <p v-if="!sources.length">尚无关联来源。</p>
              <ul>
                <li v-for="source in sources" :key="source.id">
                  {{ source.type === 'dataset' ? '数据集' : '单份资料' }}：{{
                    sourceNames[source.resourceId] || source.resourceId
                  }}
                  <SourceAction
                    :client="client"
                    :knowledge-base-id="selected.id"
                    :resource-id="source.id"
                    action="remove"
                    :writable="writable"
                    @changed="loadDetail"
                  />
                </li>
              </ul>
              <button
                v-if="sourceCursor !== null"
                :disabled="moreLoading.sources"
                @click="loadMore('sources')"
              >
                更多来源
              </button>
            </section>
            <p v-if="metadataError" role="alert">
              名称读取失败：{{ metadataError
              }}<button @click="loadNames">重试名称</button>
            </p>
            <section class="knowledge-section" aria-label="知识库文件">
              <h3>纳入资料</h3>
              <p v-if="!files.length">尚无有效文件。</p>
              <ul class="knowledge-files">
                <li v-for="file in files" :key="file.fileId">
                  <strong>{{ fileNames[file.fileId] || file.fileId }}</strong>
                  <p>
                    {{ updates[file.updateState] }} ·
                    {{
                      file.excluded
                        ? '已排除'
                        : file.included
                          ? '已纳入'
                          : '未纳入'
                    }}
                  </p>
                  <p>
                    生效版本：{{ file.effectiveContentVersionId || '尚未生效'
                    }}<span v-if="file.usingPreviousVersion"
                      >，继续使用旧版本</span
                    >
                  </p>
                  <p v-if="file.failure">{{ file.failure.message }}</p>
                  <p>
                    最新内容版本：{{
                      file.latestContentVersionId
                    }}；目标内容版本：{{
                      file.targetContentVersionId || '未排队'
                    }}
                  </p>
                  <p v-if="!file.sources.length">
                    无有效来源，取消排除也不会自动纳入。
                  </p>
                  <ul>
                    <li v-for="source in file.sources" :key="source.id">
                      {{
                        source.type === 'dataset' ? '数据集来源' : '单文件来源'
                      }}：{{
                        sourceNames[source.resourceId] || source.resourceId
                      }}
                    </li>
                  </ul>
                  <SourceAction
                    :client="client"
                    :knowledge-base-id="selected.id"
                    :resource-id="file.fileId"
                    :action="file.excluded ? 'include' : 'exclude'"
                    :writable="writable"
                    @changed="loadDetail"
                  />
                </li>
              </ul>
              <button
                v-if="fileCursor !== null"
                :disabled="moreLoading.files"
                @click="loadMore('files')"
              >
                更多文件
              </button>
            </section>
            <Exclusions
              :key="`exclusions-${selected.id}`"
              :client="client"
              :knowledge-base-id="selected.id"
              :writable="writable"
              @changed="loadDetail"
            />
            <ProcessingEditor
              v-if="snapshot"
              :key="`processing-${selected.id}`"
              :client="client"
              :config="snapshot"
              :writable="writable"
              @completed="loadDetail"
            />
            <IndexTasks
              :key="selected.id"
              :client="client"
              :knowledge-base-id="selected.id"
              @completed="loadDetail"
            />
            <ManagementSearch
              :key="`search-${selected.id}`"
              :client="client"
              :knowledge-base-id="selected.id"
              :writable="writable"
            />
          </template>
          <p v-else-if="!detailLoading && !detailError" class="dataset-empty">
            从左侧选择知识库，查看配置与来源。
          </p>
        </template>
      </section>
    </div>
  </section>
</template>
