<script setup>
import { ref, watch, onBeforeUnmount } from 'vue';
import { createKnowledgeBaseClient } from './client';
import { createFileClient } from '../files/client';
import SourceAction from './SourceAction.vue';
const props = defineProps({
  client: { type: Object, required: true },
  knowledgeBaseId: { type: String, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['changed']);
const api = createKnowledgeBaseClient(props.client),
  filesApi = createFileClient(props.client);
const rows = ref([]),
  cursor = ref(null),
  loading = ref(false),
  error = ref(''),
  names = ref({});
let sequence = 0;
async function load(more = false) {
  if (loading.value) return;
  const current = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const value = await api.exclusions(
      props.knowledgeBaseId,
      more ? cursor.value : undefined,
    );
    if (current !== sequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...value.items.filter(
            (item) => !rows.value.some((row) => row.fileId === item.fileId),
          ),
        ]
      : value.items;
    cursor.value = value.nextCursor;
    const entries = await Promise.allSettled(
      value.items.map(async (item) => [
        item.fileId,
        (await filesApi.get(item.fileId)).name,
      ]),
    );
    if (current !== sequence) return;
    names.value = {
      ...names.value,
      ...Object.fromEntries(
        entries
          .filter((item) => item.status === 'fulfilled')
          .map((item) => item.value),
      ),
    };
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function changed() {
  emit('changed');
  void load();
}
watch(
  () => props.knowledgeBaseId,
  () => {
    ++sequence;
    rows.value = [];
    names.value = {};
    cursor.value = null;
    loading.value = false;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <section class="knowledge-section" aria-label="单文件排除">
    <h3>单文件排除</h3>
    <p>
      排除优先全部来源；撤销来源不会清除排除记录。取消排除不会凭空建立新来源。
    </p>
    <button :disabled="loading" @click="load()">刷新排除列表</button>
    <p v-if="loading" role="status">正在读取排除列表…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!rows.length && !loading && !error">没有单文件排除。</p>
    <ul class="knowledge-files">
      <li v-for="item in rows" :key="item.fileId">
        <strong>{{ names[item.fileId] || item.fileId }}</strong>
        <p>排除时间：{{ item.createdAt }}</p>
        <SourceAction
          :client="client"
          :knowledge-base-id="knowledgeBaseId"
          :resource-id="item.fileId"
          action="include"
          :writable="writable"
          @changed="changed"
        />
      </li>
    </ul>
    <button v-if="cursor !== null" :disabled="loading" @click="load(true)">
      更多排除记录
    </button>
  </section>
</template>
