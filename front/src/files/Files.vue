<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
} from 'vue';
import { createFileClient } from './client';
import './files.css';

const props = defineProps({
  client: { type: Object, required: true },
  datasetId: { type: String, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['changed']);
const api = createFileClient(props.client);
const rows = ref([]);
const error = ref('');
const loading = ref(false);
const selected = ref(null);
const detailError = ref('');
const detailLoading = ref(false);
const detailId = ref(null);
const renameOpen = ref(false);
const renameName = ref('');
const renameError = ref('');
const renameSaving = ref(false);
const renameButton = ref(null);
const renameInput = ref(null);
const renameSummary = ref(null);
const versionsOpen = ref(false);
const versions = ref([]);
const versionError = ref('');
const versionLoading = ref(false);
const versionCursors = ref([undefined]);
const versionPage = ref(0);
const versionNextCursor = ref(null);
const chosen = ref([]);
const conflictChoices = ref(Object.create(null));
const chosenGroups = computed(() => {
  const groups = new Map();
  for (const file of chosen.value) {
    const key = file.name.normalize('NFC').toLowerCase();
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(file);
  }
  return [...groups].map(([key, files]) => ({ key, files }));
});
const uploadResults = ref([]);
const saving = ref(false);
const uploadError = ref('');
const notice = ref('');
const fileInput = ref(null);
const summary = ref(null);
const previewButton = ref(null);
const previewPanel = ref(null);
const previewText = ref('');
const previewContent = shallowRef(null);
const previewRenderer = shallowRef(null);
const previewOpen = ref(false);
const previewLoading = ref(false);
const contentError = ref('');
const downloading = ref(false);
const cursors = ref([undefined]);
const page = ref(0);
const nextCursor = ref(null);
let sequence = 0;
let detailSequence = 0;
let uploadSequence = 0;
let contentSequence = 0;
let versionSequence = 0;
function resetDetailActions() {
  renameOpen.value = false;
  renameError.value = '';
  renameSaving.value = false;
  ++versionSequence;
  versionsOpen.value = false;
  versions.value = [];
  versionError.value = '';
  versionLoading.value = false;
  versionCursors.value = [undefined];
  versionPage.value = 0;
  versionNextCursor.value = null;
}
async function startRename() {
  renameName.value = selected.value.name;
  renameError.value = '';
  renameOpen.value = true;
  await nextTick();
  renameInput.value?.focus();
}
async function closeRename() {
  renameOpen.value = false;
  await nextTick();
  renameButton.value?.focus();
}
async function rename() {
  if (renameSaving.value || !props.writable) return;
  const current = detailSequence;
  renameSaving.value = true;
  renameError.value = '';
  try {
    const result = await api.rename(selected.value.id, renameName.value);
    if (current !== detailSequence) return;
    selected.value = result;
    ++contentSequence;
    previewOpen.value = false;
    previewLoading.value = false;
    previewContent.value = null;
    contentError.value = '';
    renameSaving.value = false;
    await closeRename();
    await load();
  } catch (failure) {
    if (current === detailSequence) {
      renameError.value = failure.message;
      await nextTick();
      renameSummary.value?.focus();
    }
  } finally {
    if (current === detailSequence) renameSaving.value = false;
  }
}
async function loadVersions(index = versionPage.value) {
  const current = ++versionSequence;
  versionsOpen.value = true;
  versionLoading.value = true;
  versionError.value = '';
  try {
    const result = await api.versions(
      selected.value.id,
      versionCursors.value[index],
    );
    if (current !== versionSequence) return;
    versions.value = result.items;
    versionNextCursor.value = result.nextCursor;
    versionPage.value = index;
  } catch (failure) {
    if (current === versionSequence) versionError.value = failure.message;
  } finally {
    if (current === versionSequence) versionLoading.value = false;
  }
}
async function nextVersionPage() {
  versionCursors.value[versionPage.value + 1] = versionNextCursor.value;
  await loadVersions(versionPage.value + 1);
}
async function load(index = page.value) {
  const current = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const result = await api.list(props.datasetId, cursors.value[index]);
    if (current === sequence) {
      rows.value = result.items;
      nextCursor.value = result.nextCursor;
      page.value = index;
    }
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) loading.value = false;
  }
}
async function open(id) {
  resetDetailActions();
  detailId.value = id;
  ++contentSequence;
  previewOpen.value = false;
  previewContent.value = null;
  previewText.value = '';
  previewRenderer.value = null;
  previewLoading.value = false;
  contentError.value = '';
  const current = ++detailSequence;
  selected.value = null;
  detailError.value = '';
  detailLoading.value = true;
  try {
    const result = await api.get(id);
    if (current === detailSequence) selected.value = result;
  } catch (failure) {
    if (current === detailSequence) detailError.value = failure.message;
  } finally {
    if (current === detailSequence) detailLoading.value = false;
  }
}
async function preview() {
  const current = ++contentSequence;
  previewOpen.value = true;
  previewLoading.value = true;
  previewText.value = '';
  previewContent.value = null;
  contentError.value = '';
  await nextTick();
  previewPanel.value?.focus();
  try {
    const result = await api.preview(selected.value.id);
    if (current !== contentSequence) return;
    if (result.mediaType !== selected.value.latestVersion.mediaType)
      throw new Error('预览媒体与资料元数据不一致，请重新读取资料。');
    if (result.mediaType === 'application/pdf') {
      let renderer;
      try {
        renderer = (await import('./PdfPreview.vue')).default;
      } catch {
        throw new Error('预览组件加载失败，请刷新页面后重试。');
      }
      if (current !== contentSequence) return;
      previewRenderer.value = renderer;
      previewContent.value = result;
      return;
    }
    if (result.bytes.byteLength > 800_000)
      throw new Error('文本超过预览资源限制，请下载原文件查看。');
    previewText.value = new TextDecoder('utf-8', {
      fatal: true,
      ignoreBOM: true,
    }).decode(result.bytes);
    if (previewText.value.includes('\u0000'))
      throw new Error('资料不是有效的纯文本，请下载原文件查看。');
    if (
      previewText.value.length > 200_000 ||
      previewText.value.split(/\r\n|\r|\n/).length > 10_000
    )
      throw new Error(
        '文本超过预览资源限制（20万字符或1万行），请下载原文件查看。',
      );
    const text = previewText.value;
    let renderer;
    try {
      renderer = (await import('./TextPreview.vue')).default;
    } catch {
      throw new Error('预览组件加载失败，请刷新页面后重试。');
    }
    if (current !== contentSequence) return;
    previewText.value = text;
    previewRenderer.value = renderer;
    previewContent.value = result;
  } catch (failure) {
    if (current === contentSequence)
      contentError.value =
        failure instanceof TypeError
          ? '资料不是有效的UTF-8文本，请下载原文件查看。'
          : failure.message;
  } finally {
    if (current === contentSequence) previewLoading.value = false;
  }
}
async function closePreview() {
  ++contentSequence;
  previewOpen.value = false;
  previewLoading.value = false;
  contentError.value = '';
  previewText.value = '';
  previewContent.value = null;
  previewRenderer.value = null;
  await nextTick();
  previewButton.value?.focus();
}
function reloadPage() {
  window.location.reload();
}
async function download() {
  if (downloading.value) return;
  const current = contentSequence;
  const file = selected.value;
  downloading.value = true;
  contentError.value = '';
  try {
    const result = await api.download(file.id);
    if (current !== contentSequence) return;
    const url = URL.createObjectURL(
      new Blob([result.bytes], { type: result.mediaType }),
    );
    try {
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = file.name;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch (failure) {
    if (current === contentSequence) contentError.value = failure.message;
  } finally {
    downloading.value = false;
  }
}
function choose(event) {
  chosen.value = Array.from(event.target.files || []);
  conflictChoices.value = Object.create(null);
  uploadResults.value = [];
  uploadError.value = '';
  notice.value = '';
}
async function upload(retryEntry) {
  if (saving.value || !props.writable) return;
  const groups = chosenGroups.value.filter(
    (group) =>
      group.files.length === 1 ||
      conflictChoices.value[group.key] !== undefined,
  );
  const files = retryEntry
    ? [retryEntry.source]
    : groups.map((group) => group.files[conflictChoices.value[group.key] ?? 0]);
  if (!files.length || files.length > 1000) {
    uploadError.value = '请选择1至1000份纯文本、Markdown 或文字 PDF 资料。';
    await nextTick();
    summary.value?.focus();
    return;
  }
  const current = ++uploadSequence;
  saving.value = true;
  uploadError.value = '';
  notice.value = '';
  try {
    const result = await api.upload(props.datasetId, files);
    if (current !== uploadSequence) return;
    const entries = result.items.map((item, index) => ({
      ...item,
      source: files[index],
    }));
    if (retryEntry)
      uploadResults.value.splice(
        uploadResults.value.indexOf(retryEntry),
        1,
        entries[0],
      );
    else {
      uploadResults.value.push(...entries);
      chosen.value = chosen.value.filter(
        (file) => !groups.some((group) => group.files.includes(file)),
      );
      fileInput.value.value = '';
    }
    const saved = entries.filter((item) => item.outcome !== 'failed');
    const failed = entries.filter((item) => item.outcome === 'failed');
    if (saved.length) {
      notice.value = saved.every((item) => item.outcome === 'unchanged')
        ? '资料内容未变化，已保留原版本。'
        : '资料已保存，尚不代表已完成知识库索引。';
      cursors.value = [undefined];
      await Promise.all([load(0), open(saved[0].file.id)]);
      if (current === uploadSequence) emit('changed');
    }
    if (current === uploadSequence && failed.length) {
      uploadError.value = failed.map((item) => item.error.message).join('；');
      await nextTick();
      summary.value?.focus();
    }
  } catch (failure) {
    if (current === uploadSequence) {
      uploadError.value = failure.message;
      await nextTick();
      summary.value?.focus();
    }
  } finally {
    if (current === uploadSequence) saving.value = false;
  }
}
async function nextPage() {
  cursors.value[page.value + 1] = nextCursor.value;
  await load(page.value + 1);
}
watch(
  () => props.datasetId,
  () => {
    ++detailSequence;
    ++uploadSequence;
    ++contentSequence;
    resetDetailActions();
    previewOpen.value = false;
    previewContent.value = null;
    previewText.value = '';
    previewRenderer.value = null;
    previewLoading.value = false;
    contentError.value = '';
    selected.value = null;
    rows.value = [];
    saving.value = false;
    detailLoading.value = false;
    chosen.value = [];
    conflictChoices.value = Object.create(null);
    uploadResults.value = [];
    uploadError.value = '';
    notice.value = '';
    detailError.value = '';
    if (fileInput.value) fileInput.value.value = '';
    cursors.value = [undefined];
    load(0);
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
  ++detailSequence;
  ++uploadSequence;
  ++contentSequence;
  ++versionSequence;
});
</script>

<template>
  <section class="files-panel" aria-label="数据集资料">
    <h3>资料</h3>
    <p>保存资料不代表已完成索引，需在知识库中处理后才可检索。</p>
    <form @submit.prevent="upload()">
      <label for="text-file">选择资料（可多选）</label>
      <input
        id="text-file"
        ref="fileInput"
        class="file-input"
        tabindex="-1"
        type="file"
        multiple
        accept=".txt,.md,.markdown,.pdf,text/plain,text/markdown,application/pdf"
        :disabled="!writable || saving"
        @change="choose"
      />
      <button
        type="button"
        :disabled="!writable || saving"
        @click="fileInput.click()"
      >
        选择文件
      </button>
      <span class="chosen-file">{{
        chosen.length ? `已选择 ${chosen.length} 份资料` : '未选择资料'
      }}</span>
      <fieldset
        v-for="(group, groupIndex) in chosenGroups.filter(
          (entry) => entry.files.length > 1,
        )"
        :key="group.key"
        class="upload-conflict"
      >
        <legend>
          同批重名：{{
            group.files[0].name
          }}。请选择保留一份；未选组暂不上传，其他资料继续。
        </legend>
        <label v-for="(file, index) in group.files" :key="index">
          <input
            v-model="conflictChoices[group.key]"
            type="radio"
            :name="`upload-conflict-${groupIndex}`"
            :value="index"
            :disabled="saving || !writable"
          />
          第 {{ index + 1 }} 份：{{ file.name }}（{{ file.size }} 字节）
        </label>
      </fieldset>
      <button class="primary-button" :disabled="!writable || saving">
        {{ saving ? '正在保存资料…' : '上传资料' }}
      </button>
      <p v-if="uploadError" ref="summary" role="alert" tabindex="-1">
        {{ uploadError }}
      </p>
    </form>
    <ul
      v-if="uploadResults.length"
      class="upload-results"
      aria-label="逐项上传结果"
    >
      <li v-for="(item, index) in uploadResults" :key="index">
        <strong>{{ item.submittedName }}</strong
        >：{{
          {
            created: '新增成功',
            replaced: '覆盖成功',
            unchanged: '内容未变',
            failed: '上传失败',
          }[item.outcome]
        }}
        <span v-if="item.error">（{{ item.error.message }}）</span>
        <button
          v-if="item.outcome === 'failed'"
          data-action="retry-upload"
          :disabled="!writable || saving"
          @click="upload(item)"
        >
          单独重试 {{ item.submittedName }}
        </button>
      </li>
    </ul>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div class="dataset-list-heading">
      <h4>全部资料</h4>
      <button :disabled="loading" @click="load()">刷新资料</button>
    </div>
    <p v-if="error" role="alert">
      {{ error }} <button @click="load()">重试资料列表</button>
    </p>
    <p v-if="loading" role="status">正在加载资料…</p>
    <p v-else-if="!rows.length && !error">
      尚无资料，选择纯文本、Markdown 或文字 PDF 开始导入。
    </p>
    <ul v-if="rows.length" class="file-rows">
      <li v-for="item in rows" :key="item.id">
        <button :aria-pressed="selected?.id === item.id" @click="open(item.id)">
          <strong>{{ item.name }}</strong
          ><span
            >{{ item.latestVersion.sizeBytes }} 字节 ·
            {{ item.lastUploadedAt }}</span
          >
        </button>
      </li>
    </ul>
    <div class="dataset-pagination">
      <button :disabled="loading || page === 0" @click="load(page - 1)">
        上一页资料</button
      ><span>第 {{ page + 1 }} 页</span
      ><button :disabled="loading || nextCursor === null" @click="nextPage">
        下一页资料
      </button>
    </div>
    <p v-if="detailLoading" role="status">正在读取资料…</p>
    <p v-if="detailError" role="alert">
      {{ detailError }} <button @click="open(detailId)">重试资料详情</button>
    </p>
    <section v-if="selected" aria-label="资料详情" class="file-detail">
      <h4>{{ selected.name }}</h4>
      <button
        ref="renameButton"
        data-action="rename"
        :disabled="!writable || renameSaving"
        @click="startRename"
      >
        重命名资料
      </button>
      <form
        v-if="renameOpen"
        aria-label="重命名资料"
        @submit.prevent="rename"
        @keydown.esc.stop="closeRename"
      >
        <label for="file-name">完整文件名（不去除首尾空白）</label>
        <input
          id="file-name"
          ref="renameInput"
          v-model="renameName"
          name="file-name"
          :disabled="!writable || renameSaving"
        />
        <button :disabled="!writable || renameSaving">
          {{ renameSaving ? '正在重命名…' : '保存名称' }}
        </button>
        <button type="button" :disabled="renameSaving" @click="closeRename">
          取消重命名
        </button>
        <p v-if="renameError" ref="renameSummary" role="alert" tabindex="-1">
          {{ renameError }}
        </p>
      </form>
      <dl>
        <div>
          <dt>数据集标识</dt>
          <dd>{{ selected.datasetId }}</dd>
        </div>
        <div>
          <dt>创建人</dt>
          <dd>{{ selected.createdBy ?? '未知用户' }}</dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>{{ selected.createdAt }}</dd>
        </div>
        <div>
          <dt>最近内容上传</dt>
          <dd>{{ selected.lastUploadedAt }}</dd>
        </div>
        <div>
          <dt>上传人</dt>
          <dd>{{ selected.latestVersion.uploadedBy ?? '未知用户' }}</dd>
        </div>
        <div>
          <dt>内容版本</dt>
          <dd>{{ selected.latestVersion.id }}</dd>
        </div>
        <div>
          <dt>媒体类型</dt>
          <dd>{{ selected.latestVersion.mediaType }}</dd>
        </div>
        <div>
          <dt>原文件大小</dt>
          <dd>{{ selected.latestVersion.sizeBytes }} 字节</dd>
        </div>
        <div>
          <dt>内容校验值（SHA-256）</dt>
          <dd>{{ selected.latestVersion.contentHash }}</dd>
        </div>
        <div>
          <dt>原副本</dt>
          <dd>
            {{ selected.latestVersion.originalAvailable ? '可用' : '不可用' }}
          </dd>
        </div>
      </dl>
      <button :disabled="versionLoading" @click="loadVersions()">
        查看内容版本
      </button>
      <section v-if="versionsOpen" aria-label="内容版本" class="file-versions">
        <h5>内容版本</h5>
        <p>仅列出内容上传记录；重命名不创建内容版本，索引切换记录不在此列。</p>
        <p v-if="versionLoading" role="status">正在读取内容版本…</p>
        <p v-if="versionError" role="alert">
          {{ versionError }}
          <button @click="loadVersions()">重试版本列表</button>
        </p>
        <p v-if="!versionLoading && !versionError && !versions.length">
          尚无内容版本。
        </p>
        <ul>
          <li v-for="version in versions" :key="version.id">
            <strong>{{ version.uploadedName }}</strong>
            <dl>
              <div>
                <dt>内容版本</dt>
                <dd>{{ version.id }}</dd>
              </div>
              <div>
                <dt>上传人</dt>
                <dd>{{ version.uploadedBy ?? '未知用户' }}</dd>
              </div>
              <div>
                <dt>上传时间</dt>
                <dd>{{ version.uploadedAt }}</dd>
              </div>
              <div>
                <dt>媒体类型</dt>
                <dd>{{ version.mediaType }}</dd>
              </div>
              <div>
                <dt>原文件大小</dt>
                <dd>{{ version.sizeBytes }} 字节</dd>
              </div>
              <div>
                <dt>内容校验值（SHA-256）</dt>
                <dd>{{ version.contentHash }}</dd>
              </div>
              <div>
                <dt>原副本</dt>
                <dd>
                  {{
                    version.originalAvailable
                      ? '完整副本可用'
                      : '完整副本不可用'
                  }}
                </dd>
              </div>
            </dl>
          </li>
        </ul>
        <div class="dataset-pagination">
          <button
            :disabled="versionLoading || versionPage === 0"
            @click="loadVersions(versionPage - 1)"
          >
            上一页版本
          </button>
          <span>第 {{ versionPage + 1 }} 页</span>
          <button
            :disabled="versionLoading || versionNextCursor === null"
            @click="nextVersionPage"
          >
            下一页版本
          </button>
        </div>
      </section>
      <button
        ref="previewButton"
        data-action="preview"
        :disabled="!selected.latestVersion.originalAvailable"
        @click="preview"
      >
        预览最新资料
      </button>
      <button
        data-action="download"
        :disabled="!selected.latestVersion.originalAvailable || downloading"
        @click="download"
      >
        {{ downloading ? '正在下载…' : '下载原文件' }}
      </button>
      <p v-if="contentError && !previewOpen" role="alert">{{ contentError }}</p>
      <section
        v-if="previewOpen"
        ref="previewPanel"
        class="file-preview"
        aria-label="最新资料预览"
        tabindex="-1"
        @keydown.esc.stop="closePreview"
      >
        <div class="dataset-list-heading">
          <h4>最新资料预览</h4>
          <button data-action="close-preview" @click="closePreview">
            关闭预览
          </button>
        </div>
        <p v-if="previewLoading" role="status">正在读取原文件…</p>
        <p v-else-if="contentError" role="alert">
          {{ contentError }}
          <button
            v-if="contentError.startsWith('预览组件加载失败')"
            @click="reloadPage"
          >
            刷新页面
          </button>
        </p>
        <component
          :is="previewRenderer"
          v-else-if="previewContent && previewRenderer"
          v-bind="
            previewContent.mediaType === 'application/pdf'
              ? { bytes: previewContent.bytes }
              : {
                  text: previewText,
                  markdown: previewContent.mediaType === 'text/markdown',
                }
          "
        />
      </section>
    </section>
  </section>
</template>
