<script setup>
import { computed, nextTick, onBeforeUnmount, ref, shallowRef } from 'vue';
import { createFileClient } from '../files/client';
import { decodePreviewText } from '../files/preview-text';

const props = defineProps({
  client: { type: Object, required: true },
  evidence: { type: Object, required: true },
});
const api = createFileClient(props.client);
const open = ref(false),
  busy = ref(false),
  error = ref(''),
  text = ref('');
const content = shallowRef(null),
  renderer = shallowRef(null);
const trigger = ref(null),
  panel = ref(null);
const rendererProps = computed(() =>
  props.evidence.location.format === 'pdf'
    ? {
        bytes: content.value?.bytes,
        initialPage: props.evidence.location.pageStart,
        endPage: props.evidence.location.pageEnd,
        downloadAvailable: false,
      }
    : {
        text: text.value,
        markdown: props.evidence.location.format === 'markdown',
        initialLine: props.evidence.location.lineStart,
        endLine: props.evidence.location.lineEnd,
      },
);
let sequence = 0;
async function preview() {
  const current = ++sequence;
  const item = props.evidence;
  open.value = true;
  busy.value = true;
  error.value = '';
  content.value = null;
  renderer.value = null;
  text.value = '';
  await nextTick();
  if (current !== sequence) return;
  panel.value?.focus();
  try {
    const result = await api.previewVersion(item.fileId, item.contentVersionId);
    if (current !== sequence) return;
    const expected = {
      text: 'text/plain',
      markdown: 'text/markdown',
      pdf: 'application/pdf',
    }[item.location.format];
    if (result.mediaType !== expected)
      throw new Error('原文媒体与证据格式不一致，请重新读取证据。');
    let component,
      decoded = '';
    if (item.location.format === 'pdf')
      component = (await import('../files/PdfPreview.vue')).default;
    else {
      decoded = decodePreviewText(result.bytes, '当前仅显示完整切片证据。');
      component = (await import('../files/TextPreview.vue')).default;
    }
    if (current !== sequence) return;
    text.value = decoded;
    renderer.value = component;
    content.value = result;
  } catch (failure) {
    if (current !== sequence) return;
    error.value =
      failure.code === 'CONTENT_UNAVAILABLE'
        ? '该版本完整原文已不可用，仍可核对完整切片证据。'
        : failure.code === 'NOT_FOUND'
          ? '资料或内容版本不存在，仍可核对完整切片证据。'
          : failure instanceof TypeError
            ? '原文不是有效UTF-8文本，当前仅显示完整切片证据。'
            : failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function close() {
  const current = ++sequence;
  open.value = false;
  busy.value = false;
  error.value = '';
  content.value = null;
  renderer.value = null;
  text.value = '';
  await nextTick();
  if (current === sequence && trigger.value?.isConnected) trigger.value.focus();
}
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <div v-if="evidence.originalAvailable" class="evidence-preview">
    <button ref="trigger" :disabled="open" @click="preview">
      打开对应版本原文
    </button>
    <section
      v-if="open"
      ref="panel"
      tabindex="-1"
      role="region"
      aria-label="对应版本原文"
      @keydown.esc.stop="close"
    >
      <h5>对应版本原文</h5>
      <button @click="close">关闭原文预览</button>
      <p>
        内容版本：{{
          evidence.contentVersionId
        }}。仅查看该版本，不自动切换最新原文。
      </p>
      <p v-if="busy" role="status">正在读取对应版本原文…</p>
      <p v-if="error" role="alert">
        {{ error }}<button @click="preview">重试原文预览</button>
      </p>
      <component
        v-if="content && renderer"
        :is="renderer"
        v-bind="rendererProps"
      />
    </section>
  </div>
</template>
