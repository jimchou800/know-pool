<script setup>
import { onBeforeUnmount, ref, watch } from 'vue';
import { createKnowledgeBaseClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  base: { type: Object, required: true },
});
const api = createKnowledgeBaseClient(props.client),
  versions = ref([]),
  busy = ref(false),
  error = ref('');
const strategies = {
    structure: '结构优先',
    fixed: '固定长度',
    page: 'PDF按页',
  },
  distances = { cosine: '余弦', dot: '点积', euclidean: '欧氏距离' };
let sequence = 0;
async function load() {
  const current = ++sequence,
    base = props.base;
  busy.value = true;
  error.value = '';
  versions.value = [];
  const ids = [
    ...new Set(
      [base.targetConfigVersionId, base.effectiveConfigVersionId].filter(
        Boolean,
      ),
    ),
  ];
  try {
    const values = await Promise.all(
      ids.map((id) => api.configVersion(base.id, id)),
    );
    if (current === sequence) versions.value = values;
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
watch(
  () => [
    props.base.id,
    props.base.targetConfigVersionId,
    props.base.effectiveConfigVersionId,
  ],
  load,
  { immediate: true },
);
onBeforeUnmount(() => ++sequence);
</script>
<template>
  <section class="knowledge-section" aria-label="目标与生效配置对照">
    <h3>目标与生效配置对照</h3>
    <p>
      目标配置：{{ base.targetConfigVersionId }}；生效配置：{{
        base.effectiveConfigVersionId || '尚未生效'
      }}
    </p>
    <p
      v-if="
        base.effectiveConfigVersionId &&
        base.targetConfigVersionId !== base.effectiveConfigVersionId
      "
    >
      新配置尚未整库切换，继续使用旧生效配置；失败不移除仍可服务的旧索引。
    </p>
    <p>
      新配置验证成功后自动整库切换，不要求再次发布；主动下线或自动下线后仍保持下线。
    </p>
    <p v-if="busy" role="status">正在读取实际配置版本…</p>
    <p v-if="error" role="alert">
      {{ error }}<button @click="load">重新读取配置版本</button>
    </p>
    <dl v-for="version in versions" :key="version.id">
      <dt>
        {{
          version.id === base.targetConfigVersionId ? '目标配置' : '旧生效配置'
        }}{{
          version.id === base.effectiveConfigVersionId ? '（当前生效）' : ''
        }}：{{ version.id }}
      </dt>
      <dd>
        嵌入模型：{{ version.input.embedding.modelSnapshot.model }}；接口：{{
          version.input.embedding.modelSnapshot.endpoint
        }}；嵌入配置版本：{{ version.input.embedding.modelConfigVersionId }}
      </dd>
      <dd>
        维度：{{ version.input.embedding.dimensions }}；度量：{{
          distances[version.input.embedding.distance]
        }}；切片：{{ strategies[version.input.chunking.strategy] }}；重叠：{{
          version.input.chunking.overlapCodePoints
        }}个码点
      </dd>
      <dd>
        解析、切片和长度计量版本：{{ version.parserRuleVersion }} /
        {{ version.chunkRuleVersion }} / {{ version.lengthMeterVersion }}
      </dd>
    </dl>
  </section>
</template>
