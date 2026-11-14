<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { createRuntimeClient, runtimeFields } from './runtime';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const api = createRuntimeClient(props.client),
  draft = ref(null),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  fields = ref([]),
  notice = ref(''),
  uncertain = ref(false);
const originalChunkLimit = ref(null),
  rebuildConfirmed = ref(false);
let alive = true,
  sequence = 0;
async function read() {
  if (busy.value) return;
  const identity = ++sequence;
  loading.value = true;
  error.value = '';
  fields.value = [];
  notice.value = '';
  try {
    const value = await api.get();
    if (alive && identity === sequence) {
      draft.value = JSON.parse(JSON.stringify(value));
      originalChunkLimit.value = value.chunkHardLimitCodePoints;
      rebuildConfirmed.value = false;
      uncertain.value = false;
    }
  } catch (cause) {
    if (alive && identity === sequence) error.value = cause.message;
  } finally {
    if (alive && identity === sequence) loading.value = false;
  }
}
async function save() {
  if (busy.value || loading.value || !props.writable || uncertain.value) return;
  if (
    draft.value.chunkHardLimitCodePoints !== originalChunkLimit.value &&
    !rebuildConfirmed.value
  ) {
    error.value = '请先确认切片参数变更可能触发知识库重建及嵌入费用。';
    return;
  }
  busy.value = true;
  error.value = '';
  fields.value = [];
  notice.value = '';
  try {
    const value = await api.save(JSON.parse(JSON.stringify(draft.value)));
    if (alive) {
      draft.value = JSON.parse(JSON.stringify(value));
      originalChunkLimit.value = value.chunkHardLimitCodePoints;
      rebuildConfirmed.value = false;
      notice.value =
        '运行参数已保存并回读生效值。普通资源限额仅作用新调度；切片硬上限变化请到知识库查看重建任务，旧生效索引保留到成功切换。';
    }
  } catch (cause) {
    if (alive) {
      error.value = cause.message;
      fields.value = cause.fieldErrors ?? [];
      uncertain.value = !cause.code && !cause.fieldErrors;
    }
  } finally {
    if (alive) busy.value = false;
  }
}
onMounted(read);
onBeforeUnmount(() => {
  alive = false;
  sequence++;
});
</script>
<template>
  <section class="embedding-history runtime-settings" aria-label="运行参数">
    <header>
      <h2>运行参数</h2>
      <button type="button" :disabled="loading || busy" @click="read">
        重新读取生效参数
      </button>
    </header>
    <p>
      以本机服务返回的生效值为准，全量保存全部二十二字段。初值不代表性能或真实模型质量已经验收。
    </p>
    <p>
      提高切片硬上限等处理输入变化会生成处理配置并重建知识库，可能产生嵌入费用；普通并发和资源限额只影响新调度，不终止在途调用。保存不发起模型评测。
    </p>
    <p>
      单请求最多一次预处理、一次生成、一次校验，大预算不会放大硬上限。证据、完整历史、系统问题与输出总量须符合当前模型上下文，由服务端权威校验。
    </p>
    <p v-if="loading" role="status">正在读取生效参数…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <ul v-if="fields.length" aria-label="运行参数错误">
      <li v-for="(field, index) in fields" :key="index">{{ field.message }}</li>
    </ul>
    <p v-if="uncertain" role="alert">
      保存结果尚未确认，请重新读取生效参数后再修改，避免盲目重复保存。
    </p>
    <form v-if="draft" @submit.prevent="save">
      <fieldset :disabled="busy || loading || !writable || uncertain">
        <div class="runtime-fields">
          <label v-for="[key, label, max] in runtimeFields" :key="key"
            >{{ label
            }}<input
              v-model.number="draft[key]"
              :name="key"
              type="number"
              :min="key === 'rrfConstant' ? undefined : 1"
              :max="
                key === 'rrfConstant'
                  ? undefined
                  : (max ?? Number.MAX_SAFE_INTEGER)
              "
              :step="key === 'rrfConstant' ? 'any' : 1"
              required
          /></label>
        </div>
        <h3>模型共享限流</h3>
        <p>
          索引、检索、问答共享同端点模型的并发、每分钟请求和词元限额，不按实例倍增。未配置限流的已配置模型不能启动收费任务；供应商实际配额仍须核实。
        </p>
        <div
          v-for="(limit, index) in draft.modelRateLimits"
          :key="index"
          class="runtime-rate"
          :aria-label="`模型限流第${index + 1}项`"
        >
          <label
            >基础地址<input
              v-model="limit.endpoint"
              :name="`rate-${index}-endpoint`"
              type="url"
              required /></label
          ><label
            >模型名<input
              v-model="limit.model"
              :name="`rate-${index}-model`"
              required
          /></label>
          <label
            v-for="[key, label] in [
              ['maxConcurrent', '共享并发数'],
              ['requestsPerMinute', '每分钟请求数'],
              ['tokensPerMinute', '每分钟词元数'],
            ]"
            :key="key"
            >{{ label
            }}<input
              v-model.number="limit[key]"
              :name="`rate-${index}-${key}`"
              type="number"
              min="1"
              :max="Number.MAX_SAFE_INTEGER"
              step="1"
              required
          /></label>
          <button type="button" @click="draft.modelRateLimits.splice(index, 1)">
            删除第{{ index + 1 }}项限流
          </button>
        </div>
        <button
          type="button"
          :disabled="draft.modelRateLimits.length >= 100"
          @click="
            draft.modelRateLimits.push({
              endpoint: '',
              model: '',
              maxConcurrent: '',
              requestsPerMinute: '',
              tokensPerMinute: '',
            })
          "
        >
          添加模型限流
        </button>
        <label v-if="draft.chunkHardLimitCodePoints !== originalChunkLimit"
          ><input
            v-model="rebuildConfirmed"
            type="checkbox"
          />我确认本次切片硬上限变化可能触发知识库重建、嵌入费用及耗时。</label
        >
        <button type="submit">保存全部运行参数</button>
      </fieldset>
    </form>
    <p v-if="busy" role="status">正在保存运行参数…</p>
  </section>
</template>
