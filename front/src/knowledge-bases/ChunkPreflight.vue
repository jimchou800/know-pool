<script setup>
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { createPreflightClient, fallbackLabels } from './preflight-client';
import EvidenceLocation from './EvidenceLocation.vue';
const props = defineProps({
  client: { type: Object, required: true },
  task: { type: Object, required: true },
});
const api = createPreflightClient(props.client);
const open = ref(false),
  loading = ref(false),
  error = ref(''),
  report = ref(null);
const trigger = ref(null),
  panel = ref(null);
let sequence = 0;
async function load() {
  const current = ++sequence;
  open.value = true;
  loading.value = true;
  error.value = '';
  report.value = null;
  await nextTick();
  if (current !== sequence) return;
  panel.value?.focus();
  try {
    const value = await api.get(props.task.id);
    if (current !== sequence) return;
    if (
      (props.task.targetContentVersionId !== null &&
        value.contentVersionId !== props.task.targetContentVersionId) ||
      (props.task.targetConfigVersionId !== null &&
        value.configVersionId !== props.task.targetConfigVersionId)
    )
      throw new Error('预检与任务目标版本不一致，请重新读取原任务。');
    report.value = value;
  } catch (failure) {
    if (current === sequence)
      error.value =
        failure.code === 'NOT_FOUND'
          ? '预检尚未生成或任务不存在，请稍后刷新原预检。'
          : failure.message;
  } finally {
    if (current === sequence) loading.value = false;
  }
}
async function close() {
  const current = ++sequence;
  open.value = false;
  loading.value = false;
  report.value = null;
  await nextTick();
  if (current === sequence && trigger.value?.isConnected) trigger.value.focus();
}
onBeforeUnmount(() => {
  ++sequence;
});
</script>
<template>
  <div class="chunk-preflight">
    <button ref="trigger" :disabled="open" @click="load">查看切片预检</button>
    <section
      v-if="open"
      ref="panel"
      role="region"
      aria-label="切片完整性预检"
      tabindex="-1"
      @keydown.esc.stop="close"
    >
      <h4>切片完整性预检</h4>
      <button @click="close">关闭切片预检</button>
      <button :disabled="loading" @click="load">刷新原预检</button>
      <p v-if="loading" role="status">正在读取切片预检…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <template v-if="report">
        <p>
          内容版本：{{ report.contentVersionId }}；配置版本：{{
            report.configVersionId
          }}；计量器：{{ report.lengthMeterVersion }}
        </p>
        <p :role="report.integrityPassed ? 'status' : 'alert'">
          {{
            report.integrityPassed
              ? '内容完整性检查通过'
              : '内容完整性检查失败，不能发布该索引'
          }}
        </p>
        <p v-if="report.emptyText">原文为空文本，没有可检索切片。</p>
        <p>
          切片数量：{{ report.chunkCount }}；长度单位为 Unicode
          码点，不能视为模型词元数。
        </p>
        <dl class="knowledge-summary">
          <div>
            <dt>最短</dt>
            <dd>{{ report.minimumLength }}</dd>
          </div>
          <div>
            <dt>最长</dt>
            <dd>{{ report.maximumLength }}</dd>
          </div>
          <div>
            <dt>中位数</dt>
            <dd>{{ report.medianLength }}</dd>
          </div>
          <div>
            <dt>第95百分位</dt>
            <dd>{{ report.p95Length }}</dd>
          </div>
        </dl>
        <h5>降级拆分统计</h5>
        <p>
          降级不等于失败；同一片可使用多种降级方式，计数不能相加当总片数。重叠、表头、标题与选入的元信息均占预算。
        </p>
        <ul v-if="Object.keys(report.fallbackCounts).length">
          <li v-for="(count, mode) in report.fallbackCounts" :key="mode">
            {{ fallbackLabels[mode] || mode }}：{{ count }} 片
          </li>
        </ul>
        <p v-else>未发生降级拆分。</p>
        <p v-for="(warning, index) in report.warnings" :key="index">
          {{ warning }}
        </p>
        <h5>原文顺序切片样例（最多20片）</h5>
        <p v-if="!report.samples.length">暂无切片样例。</p>
        <article
          v-for="sample in report.samples"
          :key="sample.chunkId"
          class="knowledge-section"
        >
          <p>切片：{{ sample.chunkId }}；{{ sample.codePointCount }} 个码点</p>
          <p>
            降级方式：{{
              sample.fallbackModes.length
                ? sample.fallbackModes
                    .map((mode) => fallbackLabels[mode])
                    .join('、')
                : '未降级'
            }}
          </p>
          <EvidenceLocation :location="sample.location" />
          <pre class="source-text">{{ sample.text }}</pre>
        </article>
      </template>
    </section>
  </div>
</template>
