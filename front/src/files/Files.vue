<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
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
const chosen = ref(null);
const saving = ref(false);
const uploadError = ref('');
const notice = ref('');
const fileInput = ref(null);
const summary = ref(null);
const previewButton = ref(null);
const previewPanel = ref(null);
const previewText = ref('');
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
  detailId.value = id;
  ++contentSequence;
  previewOpen.value = false;
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
  contentError.value = '';
  await nextTick();
  previewPanel.value?.focus();
  try {
    const result = await api.preview(selected.value.id);
    if (current !== contentSequence) return;
    if (result.mediaType !== 'text/plain')
      throw new Error('此处仅支持纯文本预览，请下载原文件查看。');
    previewText.value = new TextDecoder('utf-8', { fatal: true }).decode(
      result.bytes,
    );
    if (previewText.value.includes('\u0000'))
      throw new Error('资料不是有效的纯文本，请下载原文件查看。');
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
  await nextTick();
  previewButton.value?.focus();
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
  chosen.value = event.target.files?.[0] || null;
  uploadError.value = '';
  notice.value = '';
}
async function upload() {
  if (saving.value || !props.writable) return;
  if (!chosen.value || !/\.txt$/i.test(chosen.value.name)) {
    uploadError.value = '请选择一份扩展名为 .txt 的纯文本资料。';
    await nextTick();
    summary.value?.focus();
    return;
  }
  const current = ++uploadSequence;
  saving.value = true;
  uploadError.value = '';
  notice.value = '';
  try {
    const result = await api.upload(props.datasetId, [chosen.value]);
    if (current !== uploadSequence) return;
    const item = result.items[0];
    if (item.outcome === 'failed') throw new Error(item.error.message);
    notice.value =
      item.outcome === 'unchanged'
        ? '资料内容未变化，已保留原版本。'
        : '资料已保存，尚不代表已完成知识库索引。';
    chosen.value = null;
    fileInput.value.value = '';
    cursors.value = [undefined];
    await Promise.all([load(0), open(item.file.id)]);
    if (current === uploadSequence) emit('changed');
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
    previewOpen.value = false;
    previewLoading.value = false;
    contentError.value = '';
    selected.value = null;
    rows.value = [];
    saving.value = false;
    detailLoading.value = false;
    chosen.value = null;
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
});
</script>

<template>
  <section class="files-panel" aria-label="数据集资料">
    <h3>资料</h3>
    <p>保存资料不代表已完成索引，需在知识库中处理后才可检索。</p>
    <form @submit.prevent="upload">
      <label for="text-file">选择一份纯文本资料</label>
      <input
        id="text-file"
        ref="fileInput"
        class="file-input"
        tabindex="-1"
        type="file"
        accept=".txt,text/plain"
        :disabled="!writable || saving"
        @change="choose"
      />
      <button
        type="button"
        :disabled="!writable || saving"
        @click="fileInput.click()"
      >
        选择文本文件
      </button>
      <span class="chosen-file">{{ chosen?.name || '未选择资料' }}</span>
      <button class="primary-button" :disabled="!writable || saving">
        {{ saving ? '正在保存资料…' : '上传文本资料' }}
      </button>
      <p v-if="uploadError" ref="summary" role="alert" tabindex="-1">
        {{ uploadError }}
      </p>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div class="dataset-list-heading">
      <h4>全部资料</h4>
      <button :disabled="loading" @click="load()">刷新资料</button>
    </div>
    <p v-if="error" role="alert">
      {{ error }} <button @click="load()">重试资料列表</button>
    </p>
    <p v-if="loading" role="status">正在加载资料…</p>
    <p v-else-if="!rows.length && !error">尚无资料，选择文本文件开始导入。</p>
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
      <button
        ref="previewButton"
        data-action="preview"
        :disabled="
          !selected.latestVersion.originalAvailable ||
          selected.latestVersion.fileType !== 'text'
        "
        @click="preview"
      >
        预览最新文本
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
        aria-label="最新文本预览"
        tabindex="-1"
        @keydown.esc.stop="closePreview"
      >
        <div class="dataset-list-heading">
          <h4>最新文本预览</h4>
          <button data-action="close-preview" @click="closePreview">
            关闭预览
          </button>
        </div>
        <p v-if="previewLoading" role="status">正在读取原文件…</p>
        <p v-else-if="contentError" role="alert">{{ contentError }}</p>
        <pre v-else-if="previewText">{{ previewText }}</pre>
        <p v-else>这份文本为空。</p>
      </section>
    </section>
  </section>
</template>
