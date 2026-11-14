<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { uuid } from '../system/session';
import { createMaintenanceClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
  dataDirectory: { type: String, default: '' },
  operation: { type: String, default: 'migration' },
});
const emit = defineEmits(['status-change']);
const api = createMaintenanceClient(props.client, props.operation);
const label = { migration: '迁移', backup: '备份', restore: '恢复' }[
  props.operation
];
const title = {
  migration: '数据目录迁移',
  backup: '应用数据备份',
  restore: '应用数据恢复',
}[props.operation];
const target = ref(''),
  preflight = ref(null),
  confirmed = ref(false),
  credentialsConfirmed = ref(false),
  costConfirmed = ref(false),
  busy = ref(false),
  error = ref(''),
  fieldErrors = ref([]);
const tasks = ref([]),
  cursor = ref(null),
  result = ref(null),
  selectedId = ref(null),
  pending = ref(null);
const storageKey = `know-pool-${props.operation}-pending`;
const phases = {
  queued: `等待${label}`,
  initializing: '检查目录并暂停写入',
  copying: '复制资料',
  verifying: '校验文件与数据库',
  saving_config: '保存目录配置',
  switching: '切换数据目录',
  cleaning: '清理旧目录',
  validating: '核查索引引用',
  completed: '已结束',
};
const states = {
  queued: '等待执行',
  running: '执行中',
  succeeded: '成功',
  failed: '失败',
  interrupted: '已中断',
  cancelled: '已取消',
};
const stages = {
  path_check: '路径检查',
  copy: '资料复制',
  verification: '文件与数据库校验',
  configuration_save: '目录配置保存',
  old_directory_cleanup: '旧目录清理',
  index_check: '索引核查',
};
const indexes = {
  not_checked: '尚未核查索引',
  valid: '索引引用核查有效',
  unavailable: '索引服务不可用，知识服务尚未恢复',
  rebuild_required: '索引需要重建，请到知识库明确重试失败项',
};
const active = computed(
  () => result.value && ['queued', 'running'].includes(result.value.task.state),
);
const resumable = computed(
  () =>
    result.value?.recoverable &&
    !result.value.configurationSwitched &&
    ['failed', 'interrupted'].includes(result.value.task.state),
);
const residue = computed(
  () =>
    props.operation === 'migration' &&
    result.value?.configurationSwitched &&
    result.value.oldDirectoryResidue &&
    !active.value,
);
let alive = true,
  timer,
  reading = 0,
  listing = 0;
function remember(value) {
  pending.value = value;
  try {
    if (value) sessionStorage.setItem(storageKey, JSON.stringify(value));
    else sessionStorage.removeItem(storageKey);
  } catch {
    /* 存储不可用时仍保留本页幂等操作。 */
  }
}
function failure(cause) {
  error.value = cause.message;
  fieldErrors.value = cause.fieldErrors ?? [];
}
function invalidate() {
  if (!pending.value) {
    preflight.value = null;
    confirmed.value = false;
    credentialsConfirmed.value = false;
    costConfirmed.value = false;
  }
}
watch(() => props.dataDirectory, invalidate);
async function list(more = false) {
  const identity = ++listing;
  try {
    const value = await api.list(more ? cursor.value : undefined);
    if (!alive || identity !== listing) return;
    tasks.value = more ? [...tasks.value, ...value.items] : value.items;
    cursor.value = value.nextCursor;
    if (!selectedId.value && tasks.value.length) await read(tasks.value[0].id);
  } catch (cause) {
    if (alive && identity === listing) failure(cause);
  }
}
async function read(taskId = selectedId.value) {
  if (!taskId || !alive) return;
  clearTimeout(timer);
  const identity = ++reading;
  if (selectedId.value !== taskId) result.value = null;
  selectedId.value = taskId;
  try {
    const value = await api.get(taskId);
    if (!alive || identity !== reading) return;
    result.value = value;
    error.value = '';
    fieldErrors.value = [];
    emit('status-change');
    if (['queued', 'running'].includes(value.task.state))
      timer = setTimeout(() => read(taskId), 1500);
  } catch (cause) {
    if (alive && identity === reading) failure(cause);
  }
}
async function check() {
  if (!props.writable || busy.value || pending.value) return;
  busy.value = true;
  error.value = '';
  fieldErrors.value = [];
  preflight.value = null;
  confirmed.value = false;
  credentialsConfirmed.value = false;
  costConfirmed.value = false;
  try {
    const value = await api.preflight(props.dataDirectory, target.value);
    if (alive) preflight.value = value;
  } catch (cause) {
    if (alive) failure(cause);
  } finally {
    if (alive) busy.value = false;
  }
}
async function execute(operation) {
  if (busy.value) return;
  remember(operation);
  busy.value = true;
  error.value = '';
  fieldErrors.value = [];
  try {
    const value = operation.action
      ? await api.retry(operation.taskId, operation.action, operation.key)
      : await api.submit(
          operation.token,
          operation.key,
          operation.acknowledgements,
        );
    if (!alive) return;
    remember(null);
    preflight.value = null;
    confirmed.value = false;
    credentialsConfirmed.value = false;
    costConfirmed.value = false;
    await read(value.task.id);
    await list();
  } catch (cause) {
    if (alive) {
      failure(cause);
      if (cause.code) {
        remember(null);
        if (!operation.action) preflight.value = null;
      }
      emit('status-change');
    }
  } finally {
    if (alive) busy.value = false;
  }
}
function submit() {
  if (
    !props.writable ||
    !confirmed.value ||
    (props.operation === 'restore' &&
      (!credentialsConfirmed.value || !costConfirmed.value)) ||
    !preflight.value?.allowed ||
    Date.parse(preflight.value.expiresAt) <= Date.now()
  ) {
    error.value = `请重新预检并明确确认${label}及风险。`;
    return;
  }
  execute({
    token: preflight.value.token,
    key: crypto.randomUUID(),
    ...(props.operation === 'restore'
      ? {
          acknowledgements: {
            acknowledgePlaintextCredentials: true,
            acknowledgeIndexRebuildCost: true,
          },
        }
      : {}),
  });
}
function retry(action) {
  if (pending.value || !(action === 'resume' ? resumable.value : residue.value))
    return;
  execute({ taskId: result.value.task.id, action, key: crypto.randomUUID() });
}
onMounted(() => {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey));
    if (
      value &&
      uuid.test(value.key) &&
      (value.action
        ? uuid.test(value.taskId) &&
          ['resume', 'cleanup_residue'].includes(value.action)
        : uuid.test(value.token) &&
          (props.operation !== 'restore' ||
            (value.acknowledgements?.acknowledgePlaintextCredentials === true &&
              value.acknowledgements?.acknowledgeIndexRebuildCost === true)))
    )
      pending.value = value;
  } catch {
    /* 非法或不可用的浏览器存储不代替后端任务查询。 */
  }
  list();
});
onBeforeUnmount(() => {
  alive = false;
  reading++;
  listing++;
  clearTimeout(timer);
});
</script>
<template>
  <section class="embedding-history migration" :aria-label="title">
    <header>
      <h2>{{ title }}</h2>
      <button type="button" :disabled="busy" @click="list()">
        刷新{{ label }}任务
      </button>
    </header>
    <p>
      当前数据目录：<strong>{{ dataDirectory || '尚未读取' }}</strong>
    </p>
    <p v-if="operation === 'migration'">
      迁移会暂停写入并等待在途操作结束，再复制、校验、切换和清理。目标须为空或不存在的合法本地绝对目录；浏览器只提交路径，由后端检查和操作文件。
    </p>
    <p v-else-if="operation === 'backup'">
      备份会暂停数据变更，生成数据库一致性备份并校验资料、配置与版本。目标须为空或不存在且不与当前目录相互包含，不能递归备份自身；浏览器不操作文件。
    </p>
    <p v-else>
      恢复将暂停写入，校验备份并恢复到后端创建的独有目录。原运行目录和备份均保留，不自动删除；浏览器只提交备份来源，不自行覆盖文件。
    </p>
    <p v-if="operation === 'migration'">
      不会迁移 Qdrant 原生存储；目录迁移成功后仍须核查连接与索引引用。
    </p>
    <p v-else>
      应用备份可能包含明文模型凭据，不包含 Qdrant
      原生存储快照。恢复后的索引须单独核查，缺失或不匹配时需手动重建，可能产生嵌入费用并耗时；确认提示不会自动调用模型。
    </p>
    <form @submit.prevent="check">
      <label
        >{{ operation === 'restore' ? '备份来源目录' : '目标本地目录'
        }}<input
          v-model="target"
          :name="`${operation}Target`"
          minlength="3"
          maxlength="32767"
          required
          :disabled="busy || !!pending || !writable"
          @input="invalidate"
      /></label>
      <button type="submit" :disabled="busy || !!pending || !writable">
        预检{{ label }}目录
      </button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul v-if="fieldErrors.length" :aria-label="`${label}字段错误`">
      <li v-for="(field, index) in fieldErrors" :key="index">
        {{ field.message }}
      </li>
    </ul>
    <section v-if="preflight" :aria-label="`${label}预检结果`">
      <p>
        {{ operation === 'restore' ? '规范化备份来源' : '规范化目标' }}：{{
          preflight.normalizedPath
        }}
      </p>
      <p>
        预计复制 {{ preflight.estimatedBytes }} 字节；预检有效期至
        {{ preflight.expiresAt }}。
      </p>
      <p>
        {{
          preflight.allowed
            ? `目录预检允许${label}，提交时将再次检查。`
            : operation === 'migration'
              ? '目录不允许迁移，请调整目标。'
              : `目录不允许${label}，请检查路径与完整性。`
        }}
      </p>
      <ul>
        <li v-for="(reason, index) in preflight.reasons" :key="index">
          {{ reason }}
        </li>
      </ul>
      <p v-if="preflight.containsPlaintextCredentials" class="settings-warning">
        {{
          operation === 'migration'
            ? '迁移资料包含明文模型凭据，请保护目标目录。'
            : '本次备份资料包含明文模型凭据，请保护备份及恢复目录。'
        }}
      </p>
      <label v-if="preflight.allowed"
        ><input
          v-model="confirmed"
          type="checkbox"
          :disabled="busy || !!pending || !writable"
        />{{
          operation === 'migration'
            ? '我确认暂停写入并迁移到上述目录，了解模型凭据随资料迁移且不包含向量存储快照。'
            : operation === 'backup'
              ? '我确认暂停数据变更并备份到上述目录，了解明文模型凭据可能随备份保存且不包含向量存储快照。'
              : '我确认暂停写入并采用上述备份恢复应用资料，原运行目录和备份均保留。'
        }}</label
      >
      <template v-if="operation === 'restore' && preflight.allowed">
        <label
          ><input
            v-model="credentialsConfirmed"
            type="checkbox"
            :disabled="busy || !!pending || !writable"
          />我已了解备份可能包含明文模型凭据，恢复后将采用备份配置。</label
        >
        <label
          ><input
            v-model="costConfirmed"
            type="checkbox"
            :disabled="busy || !!pending || !writable"
          />我已了解索引缺失时须手动重建，可能产生嵌入费用及耗时，本次确认不自动授权调用模型。</label
        >
      </template>
      <button
        v-if="preflight.allowed"
        type="button"
        :disabled="
          !confirmed ||
          (operation === 'restore' &&
            (!credentialsConfirmed || !costConfirmed)) ||
          busy ||
          !!pending ||
          !writable
        "
        @click="submit"
      >
        确认{{ label }}
      </button>
    </section>
    <p v-if="busy" role="status">正在处理{{ label }}请求…</p>
    <section v-if="pending" :aria-label="`${label}受理待确认`">
      <p>
        受理结果尚未确认。请先刷新任务查找原记录；继续确认将复用原请求和幂等键，不创建另一{{
          label
        }}任务。
      </p>
      <button type="button" :disabled="busy" @click="execute(pending)">
        继续确认原请求
      </button>
    </section>
    <ul :aria-label="`${label}任务列表`">
      <li v-for="task in tasks" :key="task.id">
        <button type="button" :disabled="busy" @click="read(task.id)">
          {{ states[task.state] }} · {{ task.id }}
        </button>
      </li>
    </ul>
    <p v-if="!tasks.length">尚无{{ label }}任务。</p>
    <button v-if="cursor" type="button" :disabled="busy" @click="list(true)">
      加载更多{{ label }}任务
    </button>
    <p v-if="selectedId && !result">
      正在查看原任务：{{ selectedId }}。读取失败时请继续读取此任务。
    </p>
    <button v-if="selectedId" type="button" :disabled="busy" @click="read()">
      重新读取原任务
    </button>
    <section v-if="result" :aria-label="`${label}任务详情`" aria-live="polite">
      <h3>
        {{
          operation === 'restore' &&
          result.task.state === 'succeeded' &&
          result.indexStatus !== 'valid'
            ? '资料已恢复，知识服务仍待核查或重建'
            : states[result.task.state]
        }}
        ·
        {{ phases[result.task.phase] || `${label}处理中` }}
      </h3>
      <p>
        任务：{{ result.task.id
        }}<span v-if="result.task.retryOfTaskId"
          >；恢复自 {{ result.task.retryOfTaskId }}</span
        >
      </p>
      <p>
        进度：{{ result.task.progressCompleted }} /
        {{ result.task.progressTotal ?? '总量待定' }}
      </p>
      <p>
        当前使用：{{ result.activeDataDirectory }}；{{
          operation === 'backup'
            ? '备份目标'
            : operation === 'restore'
              ? '恢复资料目录'
              : '迁移目标'
        }}：{{ result.targetPath }}
      </p>
      <p v-if="operation === 'migration'">
        {{
          result.configurationSwitched
            ? '目录配置已切换，继续使用新目录。'
            : '尚未切换目录配置，原数据和配置保留。'
        }}
      </p>
      <p v-else-if="operation === 'backup'">
        备份不切换当前运行目录，不清理源资料。备份成功不代表知识服务已恢复。
      </p>
      <p v-else>
        {{
          result.configurationSwitched
            ? '已采用恢复资料，请以当前使用目录为准。原运行目录和备份仍保留。'
            : '尚未采用恢复资料，原运行目录和备份仍保留。'
        }}
      </p>
      <p
        v-if="operation === 'migration' && result.oldDirectoryResidue"
        role="alert"
      >
        旧目录仍有残留。请使用清理残留入口，不要删除当前使用的新目录。
      </p>
      <p v-if="result.failureStage" role="alert">
        {{ stages[result.failureStage] }}失败：{{
          result.task.failure?.message || '请查看维护警告并重新读取任务。'
        }}
      </p>
      <p v-else-if="result.task.failure" role="alert">
        {{ result.task.failure.message }}
      </p>
      <p>{{ indexes[result.indexStatus] }}</p>
      <p v-if="operation === 'restore' && result.indexStatus !== 'valid'">
        不能据资料复制成功宣称全部恢复。请到知识库检查状态，缺索引时明确选择失败项重建，可能产生嵌入费用及耗时，不会自动重建。<a
          href="#/knowledge-bases"
          >前往知识库</a
        >
      </p>
      <p>
        {{
          result.credentialsIncluded
            ? `本次${label}包含明文模型凭据。`
            : `本次${label}未包含模型凭据。`
        }}不包含向量存储快照。
      </p>
      <ul>
        <li
          v-for="(warning, index) in [
            ...result.warnings,
            ...result.task.warnings,
          ]"
          :key="index"
        >
          {{ warning }}
        </li>
      </ul>
      <p v-if="error">轮询遇到错误后已暂停，请重新读取原任务恢复查看。</p>
      <button
        v-if="resumable"
        type="button"
        :disabled="busy || !!pending"
        @click="retry('resume')"
      >
        {{ operation === 'migration' ? '确认恢复迁移' : `确认继续${label}` }}
      </button>
      <button
        v-if="residue"
        type="button"
        :disabled="busy || !!pending"
        @click="retry('cleanup_residue')"
      >
        确认仅清理旧目录残留
      </button>
    </section>
  </section>
</template>
