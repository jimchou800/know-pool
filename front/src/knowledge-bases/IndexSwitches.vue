<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import { createKnowledgeBaseClient } from './client';
import VersionDownload from '../files/VersionDownload.vue';
const props = defineProps({
  client: { type: Object, required: true },
  knowledgeBaseId: { type: String, required: true },
});
const api = createKnowledgeBaseClient(props.client);
const rows = ref([]),
  cursor = ref(null),
  busy = ref(false),
  error = ref('');
let sequence = 0;
async function load(more = false) {
  if (busy.value) return;
  const current = ++sequence;
  busy.value = true;
  error.value = '';
  try {
    const value = await api.switches(
      props.knowledgeBaseId,
      more ? cursor.value : undefined,
    );
    if (current !== sequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...value.items.filter(
            (item) => !rows.value.some((row) => row.id === item.id),
          ),
        ]
      : value.items;
    cursor.value = value.nextCursor;
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
watch(
  () => props.knowledgeBaseId,
  () => {
    ++sequence;
    rows.value = [];
    cursor.value = null;
    busy.value = false;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <section class="knowledge-section" aria-label="索引切换记录">
    <h3>索引切换记录</h3>
    <p>
      只记录成功生效的前后关系；上传成功不代表索引已切换。不提供回滚或完整旧文件恢复，已清理副本无法读取。
    </p>
    <button :disabled="busy" @click="load()">刷新切换记录</button>
    <p v-if="busy" role="status">正在读取切换记录…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!rows.length && !busy && !error">暂无成功切换记录。</p>
    <ol>
      <li v-for="item in rows" :key="item.id">
        <p>
          文件：{{ item.fileId }}；切换时间：{{ item.switchedAt }}；原任务：{{
            item.taskId
          }}
        </p>
        <p>
          内容版本：{{ item.fromContentVersionId || '首次生效' }} →
          {{ item.toContentVersionId }}
        </p>
        <p>
          索引版本：{{ item.fromIndexVersionId || '首次生效' }} →
          {{ item.toIndexVersionId }}
        </p>
        <p>
          配置版本：{{ item.fromConfigVersionId || '首次生效' }} →
          {{ item.toConfigVersionId }}
        </p>
        <VersionDownload
          :client="client"
          :file-id="item.fileId"
          :version-id="item.toContentVersionId"
        />
      </li>
    </ol>
    <button v-if="cursor !== null" :disabled="busy" @click="load(true)">
      更多切换记录
    </button>
  </section>
</template>
