<script setup>
import { onBeforeUnmount, ref } from 'vue';
import { createFileClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  fileId: { type: String, required: true },
  versionId: { type: String, required: true },
  available: { type: Boolean, default: true },
});
const api = createFileClient(props.client);
const busy = ref(false),
  error = ref('');
let sequence = 0;
async function download() {
  if (busy.value || !props.available) return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  try {
    const result = await api.downloadVersion(props.fileId, props.versionId);
    if (current !== sequence) return;
    const url = URL.createObjectURL(
      new Blob([result.bytes], { type: result.mediaType }),
    );
    try {
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = result.filename;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch (failure) {
    if (current === sequence)
      error.value =
        failure.code === 'CONTENT_UNAVAILABLE'
          ? '该内容版本的完整副本已不可用，不能下载；不会改为下载最新正文。'
          : failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <div>
    <button v-if="available" :disabled="busy" @click="download">
      {{ busy ? '正在读取指定版本…' : '下载此内容版本' }}
    </button>
    <p v-else>该内容版本完整副本不可用，不能下载。</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
</template>
