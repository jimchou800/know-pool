<script setup>
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
const props = defineProps({
  api: { type: Object, required: true },
  kind: { type: String, required: true },
  writable: { type: Boolean, default: true },
  version: { type: Object, default: null },
  savedConfig: { type: Object, default: null },
});
const emit = defineEmits(['saved']);
const titles = {
  generation: '生成模型',
  embedding: '嵌入模型',
  vector: '向量连接',
  history: '历史嵌入版本',
};
const config = ref(null);
const form = reactive({
  endpoint: '',
  model: '',
  contextWindowTokens: '',
  maxOutputTokens: '',
  action: 'keep',
  secret: '',
});
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  notice = ref(''),
  fieldErrors = ref([]),
  consent = ref(false),
  task = ref(null),
  result = ref(null),
  testing = ref(false);
const root = ref(null);
let alive = true,
  generation = 0,
  testIdentity = 0;
let pendingKey;
const history = computed(() => props.kind === 'history');
const model = computed(() => !['vector', 'history'].includes(props.kind));
const complete = computed(() =>
  props.kind === 'vector' ? config.value?.configured : config.value?.complete,
);
function clearSecret(reset = true) {
  form.secret = '';
  if (reset) form.action = 'keep';
}
function apply(value) {
  config.value = value;
  form.endpoint = value.endpoint ?? '';
  form.model = value.model ?? '';
  form.contextWindowTokens = value.contextWindowTokens ?? '';
  form.maxOutputTokens = value.maxOutputTokens ?? '';
}
function invalidate() {
  generation++;
  testIdentity++;
  result.value = null;
  task.value = null;
  testing.value = false;
  consent.value = false;
  pendingKey = undefined;
}
function fieldError(name) {
  return fieldErrors.value.find(
    (item) => item.field === `/${name}` || item.field === `/credential/${name}`,
  )?.message;
}
async function failure(failure) {
  error.value = failure.message;
  fieldErrors.value = failure.fieldErrors ?? [];
  await nextTick();
  const field = fieldErrors.value[0]?.field.split('/').at(-1);
  root.value
    ?.querySelector(`[name="${field === 'value' ? 'secret' : field}"]`)
    ?.focus();
}
async function load() {
  loading.value = true;
  error.value = '';
  try {
    const value = history.value
      ? props.version
      : await props.api.get(props.kind);
    if (alive) apply(value);
  } catch (cause) {
    if (alive) await failure(cause);
  } finally {
    if (alive) loading.value = false;
  }
}
async function save() {
  if (saving.value) return;
  saving.value = true;
  error.value = '';
  notice.value = '';
  fieldErrors.value = [];
  try {
    const credential =
      form.action === 'replace'
        ? { action: 'replace', value: form.secret }
        : { action: form.action };
    const value = history.value
      ? await props.api.rotate(config.value.versionId, credential)
      : await props.api.save(props.kind, {
          endpoint: form.endpoint,
          ...(model.value
            ? {
                model: form.model,
                contextWindowTokens:
                  props.kind === 'generation'
                    ? Number(form.contextWindowTokens)
                    : null,
                maxOutputTokens:
                  props.kind === 'generation'
                    ? Number(form.maxOutputTokens)
                    : null,
              }
            : {}),
          credential,
        });
    if (alive) {
      invalidate();
      apply(value);
      notice.value = '已保存。当前配置尚未进行新的连接测试。';
      emit('saved', value);
    }
  } catch (cause) {
    clearSecret(false);
    saving.value = false;
    if (alive) await failure(cause);
  } finally {
    clearSecret(!error.value);
    saving.value = false;
  }
}
async function refresh(identity = testIdentity, epoch = generation) {
  if (!task.value || testing.value) return;
  const id = task.value.id;
  testing.value = true;
  error.value = '';
  try {
    const value = await props.api.task(id);
    if (
      !alive ||
      identity !== testIdentity ||
      epoch !== generation ||
      task.value?.id !== id
    )
      return;
    task.value = value;
    if (value.state === 'succeeded') {
      const checked = await props.api.result(id);
      if (
        alive &&
        identity === testIdentity &&
        epoch === generation &&
        task.value?.id === id
      )
        result.value = checked;
    }
  } catch (cause) {
    if (alive && identity === testIdentity && epoch === generation)
      await failure(cause);
  } finally {
    if (alive && identity === testIdentity && epoch === generation)
      testing.value = false;
  }
}
async function test() {
  if (testing.value || task.value) return;
  const identity = ++testIdentity,
    epoch = generation;
  testing.value = true;
  error.value = '';
  result.value = null;
  try {
    pendingKey ??= crypto.randomUUID();
    const accepted = await props.api.test(
      props.kind,
      pendingKey,
      consent.value,
    );
    if (!alive || identity !== testIdentity || epoch !== generation) return;
    task.value = accepted.task;
  } catch (cause) {
    if (alive && identity === testIdentity && epoch === generation)
      await failure(cause);
  } finally {
    if (alive && identity === testIdentity && epoch === generation)
      testing.value = false;
  }
  if (alive && identity === testIdentity && epoch === generation && task.value)
    await refresh(identity, epoch);
}
function newTest() {
  invalidate();
  notice.value = '请再次明确确认后发起新的测试，可能再次产生费用。';
}
onMounted(load);
watch(
  () => props.savedConfig,
  (value) => {
    invalidate();
    if (
      value?.kind === props.kind &&
      config.value?.versionId === value.versionId
    ) {
      config.value = value;
    }
  },
);
onBeforeUnmount(() => {
  alive = false;
  invalidate();
  clearSecret();
});
</script>
<template>
  <section
    ref="root"
    class="connection-settings"
    :data-kind="kind"
    :aria-label="titles[kind]"
  >
    <header>
      <h2>{{ titles[kind] }}</h2>
      <span v-if="config" class="settings-state">{{
        complete ? '配置完整' : '配置未完整'
      }}</span>
    </header>
    <p v-if="loading" role="status">正在读取配置…</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="!config && !loading" type="button" @click="load">
      重新读取
    </button>
    <template v-if="config">
      <p v-if="history" class="settings-help">
        仅更新此版本凭据，不改变模型身份，也不重建索引。
      </p>
      <p v-if="kind === 'embedding'" class="settings-help">
        模型或地址变化形成新身份；已有知识库仍使用绑定版本。仅换密钥不重建。
      </p>
      <p v-if="kind === 'vector'" class="settings-help">
        配置独立 Qdrant 服务连接；不安装或管理进程，测试只读取集合信息。
      </p>
      <form @submit.prevent="save">
        <fieldset :disabled="!writable || saving">
          <label
            >基础地址<input
              v-model="form.endpoint"
              name="endpoint"
              :readonly="history"
              :aria-invalid="!!fieldError('endpoint')"
              :aria-describedby="`${kind}-endpoint-error`"
              placeholder="请填写兼容接口基础地址"
              :required="!history"
            /><small :id="`${kind}-endpoint-error`">{{
              fieldError('endpoint')
            }}</small></label
          >
          <label v-if="kind !== 'vector'"
            >模型标识<input
              v-model="form.model"
              name="model"
              :readonly="history"
              :required="!history"
              :aria-invalid="!!fieldError('model')"
              :aria-describedby="`${kind}-model-error`"
            /><small :id="`${kind}-model-error`">{{
              fieldError('model')
            }}</small></label
          >
          <div v-if="kind === 'generation'" class="settings-budget">
            <label
              >上下文窗口词元数<input
                v-model="form.contextWindowTokens"
                name="contextWindowTokens"
                :aria-invalid="!!fieldError('contextWindowTokens')"
                :aria-describedby="`${kind}-context-error`"
                type="number"
                min="1"
                max="9007199254740991"
                step="1"
                required
              /><small :id="`${kind}-context-error`">{{
                fieldError('contextWindowTokens')
              }}</small></label
            >
            <label
              >最大输出词元数<input
                v-model="form.maxOutputTokens"
                name="maxOutputTokens"
                :aria-invalid="!!fieldError('maxOutputTokens')"
                :aria-describedby="`${kind}-output-error`"
                type="number"
                min="1"
                max="9007199254740991"
                step="1"
                required
              /><small :id="`${kind}-output-error`">{{
                fieldError('maxOutputTokens')
              }}</small></label
            >
          </div>
          <p class="settings-help">
            密钥状态：{{
              config.credential.mask ?? '未设置'
            }}（仅显示，不会回传）
          </p>
          <label
            >密钥操作<select
              v-model="form.action"
              name="action"
              :aria-invalid="!!fieldError('action')"
              :aria-describedby="`${kind}-action-error`"
              @change="form.secret = ''"
            >
              <option value="keep">保持原密钥</option>
              <option value="replace">替换密钥</option>
              <option value="clear">清除密钥</option></select
            ><small :id="`${kind}-action-error`">{{
              fieldError('action')
            }}</small></label
          >
          <label v-if="form.action === 'replace'"
            >新密钥<input
              v-model="form.secret"
              name="secret"
              type="password"
              autocomplete="off"
              maxlength="8192"
              required
              :aria-invalid="!!fieldError('value')"
              :aria-describedby="`${kind}-secret-error`"
            /><small :id="`${kind}-secret-error`">{{
              fieldError('value')
            }}</small></label
          >
          <button type="submit" class="primary-button">
            {{ saving ? '正在保存…' : '保存配置' }}
          </button>
        </fieldset>
      </form>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p class="settings-meta">
        {{ config.versionId ? `版本：${config.versionId} · ` : '' }}更新时间：{{
          config.updatedAt
        }}
      </p>
      <div v-if="!history" class="connection-test">
        <p class="settings-help">
          仅测试已保存配置，不发送个人资料。一次测试不保证未来持续可用。{{
            kind === 'vector'
              ? '未调用收费模型不代表托管服务免费。'
              : '可能产生模型费用；失败、中断或超时仍可能收费。'
          }}
        </p>
        <label v-if="kind !== 'vector'" class="settings-consent"
          ><input
            v-model="consent"
            type="checkbox"
            :disabled="!!task || testing"
          />我同意本次连接测试可能产生模型费用</label
        >
        <button
          type="button"
          :disabled="
            !writable ||
            !complete ||
            testing ||
            !!task ||
            (kind !== 'vector' && !consent)
          "
          @click="test"
        >
          {{ testing ? '正在查询测试…' : '测试已保存连接' }}
        </button>
        <template v-if="task">
          <p role="status">
            测试任务：{{ task.id }} ·
            {{
              {
                queued: '排队中',
                running: '执行中',
                succeeded: '探测完成',
                failed: '执行失败',
                interrupted: '已中断',
                cancelled: '已取消',
              }[task.state]
            }}
          </p>
          <p v-for="warning in task.warnings" :key="warning">{{ warning }}</p>
          <p v-if="task.failure" role="alert">
            {{ task.failure.message }}
            {{
              kind !== 'vector'
                ? '连接测试可能已产生费用，请勿自动重新提交。'
                : ''
            }}
          </p>
          <button type="button" :disabled="testing" @click="refresh()">
            查询原任务
          </button>
          <button
            v-if="
              ['succeeded', 'failed', 'interrupted', 'cancelled'].includes(
                task.state,
              )
            "
            type="button"
            :disabled="testing"
            @click="newTest"
          >
            准备新的测试
          </button>
        </template>
        <p
          v-if="result"
          role="status"
          :class="{ 'settings-available': result.available }"
        >
          {{ result.available ? '本次连接可用' : '本次连接不可用' }} ·
          {{ result.message }} · {{ result.checkedAt }}
          {{ result.mayHaveIncurredCost ? '可能已产生模型费用。' : '' }}
        </p>
      </div>
    </template>
  </section>
</template>
