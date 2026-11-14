<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { uuid } from '../system/session';
import { createMigrationClient } from './client';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
  dataDirectory: { type: String, default: '' },
});
const emit = defineEmits(['status-change']);
const api = createMigrationClient(props.client);
const target = ref(''),
  preflight = ref(null),
  confirmed = ref(false),
  busy = ref(false),
  error = ref(''),
  fieldErrors = ref([]);
const tasks = ref([]),
  cursor = ref(null),
  result = ref(null),
  selectedId = ref(null),
  pending = ref(null);
const storageKey = 'know-pool-migration-pending';
const phases = {
  queued: '等待迁移',
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
      : await api.submit(operation.token, operation.key);
    if (!alive) return;
    remember(null);
    preflight.value = null;
    confirmed.value = false;
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
    !preflight.value?.allowed ||
    Date.parse(preflight.value.expiresAt) <= Date.now()
  ) {
    error.value = '请重新预检并明确确认迁移。';
    return;
  }
  execute({ token: preflight.value.token, key: crypto.randomUUID() });
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
        : uuid.test(value.token))
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
  <section class="embedding-history migration" aria-label="数据目录迁移">
    <header>
      <h2>数据目录迁移</h2>
      <button type="button" :disabled="busy" @click="list()">
        刷新迁移任务
      </button>
    </header>
    <p>
      当前数据目录：<strong>{{ dataDirectory || '尚未读取' }}</strong>
    </p>
    <p>
      迁移会暂停写入并等待在途操作结束，再复制、校验、切换和清理。目标须为空或不存在的合法本地绝对目录；浏览器只提交路径，由后端检查和操作文件。
    </p>
    <p>不会迁移 Qdrant 原生存储；目录迁移成功后仍须核查连接与索引引用。</p>
    <form @submit.prevent="check">
      <label
        >目标本地目录<input
          v-model="target"
          name="migrationTarget"
          minlength="3"
          maxlength="32767"
          required
          :disabled="busy || !!pending || !writable"
          @input="invalidate"
      /></label>
      <button type="submit" :disabled="busy || !!pending || !writable">
        预检迁移目录
      </button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul v-if="fieldErrors.length" aria-label="迁移字段错误">
      <li v-for="(field, index) in fieldErrors" :key="index">
        {{ field.message }}
      </li>
    </ul>
    <section v-if="preflight" aria-label="迁移预检结果">
      <p>规范化目标：{{ preflight.normalizedPath }}</p>
      <p>
        预计复制 {{ preflight.estimatedBytes }} 字节；预检有效期至
        {{ preflight.expiresAt }}。
      </p>
      <p>
        {{
          preflight.allowed
            ? '目录预检允许迁移，提交时将再次检查。'
            : '目录不允许迁移，请调整目标。'
        }}
      </p>
      <ul>
        <li v-for="(reason, index) in preflight.reasons" :key="index">
          {{ reason }}
        </li>
      </ul>
      <p v-if="preflight.containsPlaintextCredentials" class="settings-warning">
        迁移资料包含明文模型凭据，请保护目标目录。
      </p>
      <label v-if="preflight.allowed"
        ><input
          v-model="confirmed"
          type="checkbox"
          :disabled="busy || !!pending || !writable"
        />我确认暂停写入并迁移到上述目录，了解模型凭据随资料迁移且不包含向量存储快照。</label
      >
      <button
        v-if="preflight.allowed"
        type="button"
        :disabled="!confirmed || busy || !!pending || !writable"
        @click="submit"
      >
        确认迁移
      </button>
    </section>
    <p v-if="busy" role="status">正在处理迁移请求…</p>
    <section v-if="pending" aria-label="迁移受理待确认">
      <p>
        受理结果尚未确认。请先刷新任务查找原记录；继续确认将复用原请求和幂等键，不创建另一迁移。
      </p>
      <button type="button" :disabled="busy" @click="execute(pending)">
        继续确认原请求
      </button>
    </section>
    <ul aria-label="迁移任务列表">
      <li v-for="task in tasks" :key="task.id">
        <button type="button" :disabled="busy" @click="read(task.id)">
          {{ states[task.state] }} · {{ task.id }}
        </button>
      </li>
    </ul>
    <p v-if="!tasks.length">尚无迁移任务。</p>
    <button v-if="cursor" type="button" :disabled="busy" @click="list(true)">
      加载更多迁移任务
    </button>
    <p v-if="selectedId && !result">
      正在查看原任务：{{ selectedId }}。读取失败时请继续读取此任务。
    </p>
    <button v-if="selectedId" type="button" :disabled="busy" @click="read()">
      重新读取原任务
    </button>
    <section v-if="result" aria-label="迁移任务详情" aria-live="polite">
      <h3>
        {{ states[result.task.state] }} ·
        {{ phases[result.task.phase] || '迁移处理中' }}
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
        当前使用：{{ result.activeDataDirectory }}；迁移目标：{{
          result.targetPath
        }}
      </p>
      <p>
        {{
          result.configurationSwitched
            ? '目录配置已切换，继续使用新目录。'
            : '尚未切换目录配置，原数据和配置保留。'
        }}
      </p>
      <p v-if="result.oldDirectoryResidue" role="alert">
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
      <p>
        {{
          result.credentialsIncluded
            ? '本次迁移包含明文模型凭据。'
            : '本次迁移未包含模型凭据。'
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
        确认恢复迁移
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
