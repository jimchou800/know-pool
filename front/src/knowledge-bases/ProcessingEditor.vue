<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { createKnowledgeBaseClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  config: { type: Object, required: true },
  writable: { type: Boolean, default: true },
});
const emit = defineEmits(['completed']);
const api = createKnowledgeBaseClient(props.client);
const open = ref(false),
  busy = ref(false),
  error = ref(''),
  fields = ref({}),
  task = ref(null);
const strategy = ref('structure'),
  overlap = ref(200),
  removeHeaders = ref(false),
  metadata = ref([]);
const panel = ref(null),
  trigger = ref(null),
  summary = ref(null);
const active = computed(
  () => task.value && ['queued', 'running'].includes(task.value.state),
);
const states = {
  queued: '等待重建',
  running: '正在重建',
  succeeded: '重建成功',
  failed: '重建失败',
  cancelled: '重建已取消',
  interrupted: '重建已中断',
};
let sequence = 0,
  timer,
  key,
  payload;
async function edit() {
  const chunking = props.config.input.chunking;
  strategy.value = chunking.strategy;
  overlap.value = chunking.overlapCodePoints;
  removeHeaders.value = chunking.removePageHeadersFooters;
  metadata.value = Object.entries(chunking.customMetadata).map(
    ([key, value]) => ({
      key,
      value,
      embedded: chunking.embeddedMetadataFields.includes(key),
    }),
  );
  open.value = true;
  error.value = '';
  fields.value = {};
  task.value = null;
  key = undefined;
  payload = undefined;
  await nextTick();
  panel.value?.focus();
}
async function close() {
  if (busy.value || active.value) return;
  const current = ++sequence;
  clearTimeout(timer);
  open.value = false;
  await nextTick();
  if (current === sequence && trigger.value?.isConnected) trigger.value.focus();
}
async function read() {
  if (!task.value || busy.value) return;
  const current = sequence;
  busy.value = true;
  error.value = '';
  try {
    const value = await api.task(task.value.id, 'rebuild_knowledge_base');
    if (current !== sequence) return;
    if (value.knowledgeBaseId !== props.config.knowledgeBaseId)
      throw new Error('任务所属知识库不一致，请刷新详情。');
    task.value = value;
    if (['queued', 'running'].includes(value.state))
      timer = setTimeout(read, 1000);
    else emit('completed');
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
async function save() {
  if (busy.value || active.value || !props.writable) return;
  error.value = '';
  fields.value = {};
  if (!key) {
    if (
      !Number.isSafeInteger(overlap.value) ||
      overlap.value < 0 ||
      overlap.value >= 2000
    )
      fields.value['/chunking/overlapCodePoints'] =
        '目标重叠须为0至1999个码点。';
    if (
      metadata.value.length > 20 ||
      metadata.value.some(
        (item) =>
          !/^user_[A-Za-z0-9_]{1,60}$/.test(item.key) ||
          Array.from(item.value).length > 2000,
      ) ||
      new Set(metadata.value.map((item) => item.key)).size !==
        metadata.value.length
    )
      fields.value['/chunking/customMetadata'] =
        '自定义字段须为唯一的user_英文标识，最多20项，每值最多2000个码点。';
    if (Object.keys(fields.value).length) {
      error.value = '请修正切片配置。';
      await nextTick();
      summary.value?.focus();
      return;
    }
    payload = {
      chunking: {
        strategy: strategy.value,
        overlapCodePoints: overlap.value,
        removePageHeadersFooters: removeHeaders.value,
        customMetadata: Object.fromEntries(
          metadata.value.map((item) => [item.key, item.value]),
        ),
        embeddedMetadataFields: metadata.value
          .filter((item) => item.embedded)
          .map((item) => item.key),
      },
      embedding: {
        ...props.config.input.embedding,
        modelSnapshot: { ...props.config.input.embedding.modelSnapshot },
      },
    };
    key = crypto.randomUUID();
  }
  const current = ++sequence;
  busy.value = true;
  try {
    const accepted = await api.updateProcessing(
      props.config.knowledgeBaseId,
      payload,
      key,
    );
    if (current !== sequence) return;
    task.value = accepted.task;
    key = undefined;
    payload = undefined;
    if (['queued', 'running'].includes(task.value.state))
      timer = setTimeout(read, 1000);
    else emit('completed');
  } catch (failure) {
    if (current !== sequence) return;
    error.value = failure.message;
    for (const field of failure.fieldErrors || [])
      fields.value[field.field] = field.message;
    if (failure.code) {
      key = undefined;
      payload = undefined;
    }
    await nextTick();
    summary.value?.focus();
  } finally {
    if (current === sequence) busy.value = false;
  }
}
onBeforeUnmount(() => {
  ++sequence;
  clearTimeout(timer);
});
</script>
<template>
  <section class="knowledge-section" aria-label="切片配置">
    <button ref="trigger" :disabled="open || !writable" @click="edit">
      调整切片配置
    </button>
    <section
      v-if="open"
      ref="panel"
      role="region"
      aria-label="编辑切片配置"
      tabindex="-1"
      @keydown.esc.stop="close"
    >
      <h3>编辑切片配置</h3>
      <button :disabled="busy || !!active" @click="close">关闭切片配置</button>
      <p>
        保存后创建新处理版本并整库重建，期间旧生效索引继续服务；重建失败保留旧版本。可能调用嵌入服务并产生费用。
      </p>
      <p>
        系统长度硬上限为2000个Unicode码点，独立于模型词元限制。重叠初值不代表最优效果；重复标题、表头及选入元信息均计入预算。
      </p>
      <p>
        PDF按页策略只支持PDF，库内其他格式会明确失败；不会跳过这些文件后宣称完整成功。
      </p>
      <form novalidate @submit.prevent="save">
        <div v-if="error" ref="summary" role="alert" tabindex="-1">
          {{ error }}
          <p v-for="(message, field) in fields" :key="field">{{ message }}</p>
        </div>
        <fieldset :disabled="busy || !!active || !!key || !writable">
          <legend>切片规则</legend>
          <label
            >切片策略<select v-model="strategy">
              <option value="structure">结构优先</option>
              <option value="fixed">固定长度</option>
              <option value="page">PDF按页</option>
            </select></label
          >
          <label
            >目标重叠码点数<input
              v-model.number="overlap"
              type="number"
              min="0"
              max="1999"
              :aria-invalid="!!fields['/chunking/overlapCodePoints']"
          /></label>
          <label
            ><input
              v-model="removeHeaders"
              type="checkbox"
            />移除PDF重复页眉页脚</label
          >
          <p>
            自定义元信息只能使用user_前缀，不能覆盖系统字段。选入嵌入的字段计入模型输入预算。
          </p>
          <div
            v-for="(item, index) in metadata"
            :key="index"
            class="knowledge-metadata"
          >
            <label
              >字段名<input
                v-model="item.key"
                :aria-label="`切片元信息字段名${index + 1}`"
            /></label>
            <label
              >字段值<textarea
                v-model="item.value"
                :aria-label="`切片元信息字段值${index + 1}`"
              ></textarea>
            </label>
            <label
              ><input
                v-model="item.embedded"
                type="checkbox"
              />加入嵌入输入</label
            >
            <button type="button" @click="metadata.splice(index, 1)">
              删除字段
            </button>
          </div>
          <button
            type="button"
            :disabled="metadata.length >= 20"
            @click="metadata.push({ key: 'user_', value: '', embedded: false })"
          >
            添加切片元信息
          </button>
        </fieldset>
        <button :disabled="busy || !!active || !writable">
          {{ key ? '重试原配置提交' : '保存配置并重建' }}
        </button>
      </form>
      <p v-if="busy" role="status">正在查询或保存处理配置…</p>
      <div v-if="task" role="status">
        <p>
          {{ states[task.state] }}；任务：{{ task.id }}；目标配置：{{
            task.targetConfigVersionId
          }}
        </p>
        <p v-if="task.failure">{{ task.failure.message }}。旧生效索引保留。</p>
        <p v-for="(warning, index) in task.warnings" :key="index">
          {{ warning }}
        </p>
        <button :disabled="busy" @click="read">查询原重建任务</button>
      </div>
    </section>
  </section>
</template>
