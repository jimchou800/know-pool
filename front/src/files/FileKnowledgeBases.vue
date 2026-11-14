<script setup>
import { ref, watch, onBeforeUnmount } from 'vue';
import { createKnowledgeBaseClient } from '../knowledge-bases/client';
const props = defineProps({
  client: { type: Object, required: true },
  fileId: { type: String, required: true },
});
const api = createKnowledgeBaseClient(props.client);
const open = ref(false),
  rows = ref([]),
  cursor = ref(null),
  busy = ref(false),
  error = ref(''),
  names = ref({});
let sequence = 0;
const states = {
  not_included: '未纳入',
  queued: '等待同步',
  processing: '正在同步',
  ready: '已处理',
  failed: '同步失败',
};
async function load(more = false) {
  if (busy.value) return;
  open.value = true;
  busy.value = true;
  error.value = '';
  const current = ++sequence;
  try {
    const value = await api.fileKnowledgeBases(
      props.fileId,
      more ? cursor.value : undefined,
    );
    if (current !== sequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...value.items.filter(
            (item) =>
              !rows.value.some(
                (row) => row.knowledgeBaseId === item.knowledgeBaseId,
              ),
          ),
        ]
      : value.items;
    cursor.value = value.nextCursor;
    const results = await Promise.allSettled(
      value.items.map(async (item) => [
        item.knowledgeBaseId,
        (await api.get(item.knowledgeBaseId)).name,
      ]),
    );
    if (current !== sequence) return;
    names.value = {
      ...names.value,
      ...Object.fromEntries(
        results
          .filter((item) => item.status === 'fulfilled')
          .map((item) => item.value),
      ),
    };
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
watch(
  () => props.fileId,
  () => {
    ++sequence;
    open.value = false;
    rows.value = [];
    names.value = {};
    cursor.value = null;
    busy.value = false;
    error.value = '';
  },
);
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <section class="file-versions" aria-label="资料关联知识库">
    <button :disabled="busy" @click="load()">
      {{ open ? '刷新关联知识库' : '查看关联知识库' }}
    </button>
    <template v-if="open">
      <h5>关联知识库</h5>
      <p>
        列出仍有有效来源或单文件排除的知识库；关联不代表已生效、已发布或当前可用。
      </p>
      <p v-if="busy" role="status">正在读取关联知识库…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="!rows.length && !busy && !error">暂无关联知识库。</p>
      <ul>
        <li v-for="item in rows" :key="item.knowledgeBaseId">
          <strong>{{
            names[item.knowledgeBaseId] || item.knowledgeBaseId
          }}</strong>
          <p>
            {{ item.excluded ? '已排除' : item.included ? '已纳入' : '未纳入' }}
            · {{ states[item.updateState] }}
          </p>
          <p>
            生效内容版本：{{
              item.effectiveContentVersionId || '尚未生效'
            }}；最新内容版本：{{
              item.latestContentVersionId
            }}；目标内容版本：{{ item.targetContentVersionId || '未排队' }}
          </p>
          <p>
            生效配置版本：{{
              item.effectiveConfigVersionId || '无'
            }}；生效索引版本：{{ item.effectiveIndexVersionId || '无' }}
          </p>
          <p v-if="item.usingPreviousVersion">更新期间仍使用旧生效版本。</p>
          <p v-if="item.failure">{{ item.failure.message }}</p>
          <p v-if="!item.sources.length">无有效来源；排除记录仍保留。</p>
          <ul>
            <li v-for="source in item.sources" :key="source.id">
              {{ source.type === 'dataset' ? '数据集来源' : '单文件来源' }}：{{
                source.resourceId
              }}
            </li>
          </ul>
        </li>
      </ul>
      <button v-if="cursor !== null" :disabled="busy" @click="load(true)">
        更多关联知识库
      </button>
    </template>
  </section>
</template>
