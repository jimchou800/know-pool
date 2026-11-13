<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { getDocument, GlobalWorkerOptions, TextLayer } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import 'pdfjs-dist/web/pdf_viewer.css';
const props = defineProps({ bytes: { type: ArrayBuffer, required: true } });
GlobalWorkerOptions.workerSrc = workerUrl;
const host = ref(null);
const canvas = ref(null);
const layer = ref(null);
const total = ref(0);
const pageNumber = ref(1);
const pageInput = ref(1);
const busy = ref(true);
const error = ref('');
const fieldError = ref('');
let loadingTask;
let document;
let renderTask;
let textLayer;
let currentPage;
let sequence = 0;
let disposed = false;
let timedOut = false;
let timer;
let observer;
function cancelPage() {
  renderTask?.cancel();
  textLayer?.cancel();
  renderTask = null;
  textLayer = null;
}
function release() {
  cancelPage();
  currentPage?.cleanup();
  currentPage = null;
  void loadingTask?.destroy();
  if (canvas.value) canvas.value.width = canvas.value.height = 0;
  layer.value?.replaceChildren();
}
function deadline() {
  clearTimeout(timer);
  timer = setTimeout(() => {
    timedOut = true;
    ++sequence;
    busy.value = false;
    error.value = 'PDF 预览超过处理时间限制，请下载原文件查看。';
    release();
  }, 15_000);
}
async function showPage(number) {
  const current = ++sequence;
  cancelPage();
  busy.value = true;
  error.value = '';
  fieldError.value = '';
  deadline();
  try {
    const page = await document.getPage(number);
    if (disposed || current !== sequence) {
      page.cleanup();
      return;
    }
    currentPage?.cleanup();
    currentPage = page;
    const original = page.getViewport({ scale: 1 });
    const scale = Math.min(
      2,
      Math.max(200, host.value.clientWidth) / original.width,
    );
    const viewport = page.getViewport({ scale });
    if (
      !Number.isFinite(viewport.width * viewport.height) ||
      viewport.width <= 0 ||
      viewport.height <= 0 ||
      viewport.width * viewport.height > 4_000_000
    )
      throw new Error('PDF 页面超过画布资源限制，请下载原文件查看。');
    const text = await page.getTextContent();
    if (disposed || current !== sequence) return;
    if (
      text.items.length > 100_000 ||
      text.items.reduce(
        (size, item) => size + Array.from(item.str || '').length,
        0,
      ) > 200_000
    )
      throw new Error('PDF 页面文字超过资源限制，请下载原文件查看。');
    canvas.value.width = Math.ceil(viewport.width);
    canvas.value.height = Math.ceil(viewport.height);
    layer.value.replaceChildren();
    layer.value.parentElement.style.width = `${viewport.width}px`;
    layer.value.parentElement.style.height = `${viewport.height}px`;
    layer.value.style.setProperty('--scale-factor', scale);
    layer.value.style.setProperty('--total-scale-factor', scale);
    textLayer = new TextLayer({
      textContentSource: text,
      container: layer.value,
      viewport,
    });
    renderTask = page.render({
      canvasContext: canvas.value.getContext('2d'),
      viewport,
    });
    await Promise.all([renderTask.promise, textLayer.render()]);
    if (disposed || current !== sequence) return;
    pageNumber.value = number;
    pageInput.value = number;
  } catch (failure) {
    if (!disposed && current === sequence) {
      error.value = failure.message.includes('maximum allowed size')
        ? 'PDF 图像超过资源限制，请下载原文件查看。'
        : failure.message.includes('资源限制')
          ? failure.message
          : 'PDF 预览失败：文件损坏或格式不支持，请下载原文件查看。';
      cancelPage();
      layer.value?.replaceChildren();
      if (canvas.value) canvas.value.width = canvas.value.height = 0;
    }
  } finally {
    if (current === sequence) {
      clearTimeout(timer);
      busy.value = false;
    }
  }
}
function locate() {
  const value = Number(pageInput.value);
  if (!Number.isInteger(value) || value < 1 || value > total.value) {
    fieldError.value = `请输入1至${total.value}的页码。`;
    return;
  }
  fieldError.value = '';
  void showPage(value);
}
onMounted(async () => {
  deadline();
  const assets = new URL(
    `${import.meta.env.BASE_URL}assets/pdfjs/`,
    window.location.href,
  ).href;
  loadingTask = getDocument({
    data: new Uint8Array(props.bytes.slice(0)),
    cMapUrl: `${assets}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${assets}standard_fonts/`,
    wasmUrl: `${assets}wasm/`,
    useSystemFonts: true,
    stopAtErrors: true,
    maxImageSize: 4_000_000,
    isEvalSupported: false,
    enableXfa: false,
    canvasMaxAreaInBytes: 16_000_000,
    isImageDecoderSupported: false,
  });
  try {
    document = await loadingTask.promise;
    if (disposed || timedOut) return;
    total.value = document.numPages;
    await nextTick();
    await showPage(1);
    if (!disposed && typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(() => {
        if (!busy.value && !error.value) void showPage(pageNumber.value);
      });
      observer.observe(host.value);
    }
  } catch {
    if (!disposed && !timedOut) {
      clearTimeout(timer);
      error.value = 'PDF 预览失败：文件损坏或格式不支持，请下载原文件查看。';
      busy.value = false;
      release();
    }
  }
});
onBeforeUnmount(() => {
  disposed = true;
  ++sequence;
  clearTimeout(timer);
  observer?.disconnect();
  release();
});
</script>
<template>
  <div ref="host" class="pdf-preview">
    <form novalidate class="preview-navigation" @submit.prevent="locate">
      <button
        type="button"
        :disabled="busy || pageNumber <= 1 || !!error"
        @click="showPage(pageNumber - 1)"
      >
        上一页
      </button>
      <label
        >页码
        <input
          v-model="pageInput"
          aria-label="页码"
          type="number"
          min="1"
          :max="total"
          :disabled="busy || !!error"
          :aria-invalid="!!fieldError"
      /></label>
      <button :disabled="busy || !!error">跳转页码</button>
      <span>第 {{ pageNumber }} 页，共 {{ total }} 页</span>
      <button
        data-action="next-pdf"
        type="button"
        :disabled="busy || pageNumber >= total || !!error"
        @click="showPage(pageNumber + 1)"
      >
        下一页
      </button>
      <p v-if="fieldError" role="alert">{{ fieldError }}</p>
    </form>
    <p v-if="busy" role="status">正在渲染页面…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div
      v-show="!busy && !error"
      class="pdf-page"
      :aria-label="`第${pageNumber}页正文`"
    >
      <canvas ref="canvas" aria-hidden="true"></canvas>
      <div ref="layer" class="textLayer"></div>
    </div>
    <p>
      文字 PDF
      单页预览；支持选择正文，不执行链接、表单或附件，不支持扫描文字识别。
    </p>
  </div>
</template>
