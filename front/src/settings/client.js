import { date, exact, uuid } from '../system/session';

const mask = '【密钥已设置】';
const integer = (value, minimum = 1, maximum = Number.MAX_SAFE_INTEGER) =>
  Number.isSafeInteger(value) && value >= minimum && value <= maximum;
const nullable = (value, valid) => value === null || valid(value);
const identifier = (value) => typeof value === 'string' && uuid.test(value);
const endpoint = (value) => {
  if (
    typeof value !== 'string' ||
    !/^https?:\/\//.test(value) ||
    /[\s?#]/.test(value)
  )
    return false;
  try {
    const url = new URL(value);
    return !!url.hostname && !url.username && !url.password;
  } catch {
    return false;
  }
};
const credential = (value) =>
  exact(value, ['configured', 'mask']) &&
  typeof value.configured === 'boolean' &&
  value.mask === (value.configured ? mask : null);
const positiveOrNull = (value) => nullable(value, integer);
function validConfig(value, kind) {
  if (kind === 'vector')
    return (
      exact(value, ['endpoint', 'credential', 'configured', 'updatedAt']) &&
      nullable(value.endpoint, endpoint) &&
      credential(value.credential) &&
      typeof value.configured === 'boolean' &&
      value.configured === (value.endpoint !== null) &&
      date(value.updatedAt)
    );
  if (
    !exact(value, [
      'kind',
      'versionId',
      'endpoint',
      'model',
      'credential',
      'contextWindowTokens',
      'maxOutputTokens',
      'complete',
      'updatedAt',
    ]) ||
    value.kind !== kind ||
    !identifier(value.versionId) ||
    !nullable(value.endpoint, endpoint) ||
    !nullable(
      value.model,
      (model) => typeof model === 'string' && /\S/.test(model),
    ) ||
    !credential(value.credential) ||
    !positiveOrNull(value.contextWindowTokens) ||
    !positiveOrNull(value.maxOutputTokens) ||
    typeof value.complete !== 'boolean' ||
    !date(value.updatedAt)
  )
    return false;
  const budgets =
    kind === 'generation'
      ? integer(value.contextWindowTokens) && integer(value.maxOutputTokens)
      : value.contextWindowTokens === null && value.maxOutputTokens === null;
  return (
    (kind !== 'embedding' || budgets) &&
    value.complete ===
      !!(
        value.endpoint &&
        value.model &&
        value.credential.configured &&
        budgets
      )
  );
}
function verify(value, valid) {
  if (!valid) throw new Error('设置响应格式不符合约定，请重新读取配置。');
  return value;
}
function change(value) {
  const valid =
    value &&
    (['keep', 'clear'].includes(value.action)
      ? exact(value, ['action'])
      : value.action === 'replace' &&
        exact(value, ['action', 'value']) &&
        typeof value.value === 'string' &&
        Array.from(value.value).length >= 1 &&
        Array.from(value.value).length <= 8192 &&
        value.value !== mask);
  if (!valid)
    throw new Error('请选择有效的密钥操作；显示掩码不能作为密钥保存。');
  return value;
}
function path(kind) {
  if (!['generation', 'embedding', 'vector'].includes(kind))
    throw new Error('配置类型无效。');
  return kind === 'vector'
    ? '/settings/vector-store'
    : `/settings/models/${kind}`;
}
function idPath(id, prefix) {
  if (!identifier(id)) throw new Error('记录标识无效，请重新选择。');
  return `${prefix}/${id.toLowerCase()}`;
}
const taskFields = [
  'id',
  'kind',
  'state',
  'phase',
  'createdAt',
  'startedAt',
  'finishedAt',
  'knowledgeBaseId',
  'fileId',
  'targetContentVersionId',
  'targetConfigVersionId',
  'retryOfTaskId',
  'attempt',
  'progressCompleted',
  'progressTotal',
  'failure',
  'warnings',
  'resultResourceId',
];
const failureCodes = [
  'INVALID_FILE',
  'RESOURCE_LIMIT',
  'MODEL_CONFIG_INVALID',
  'MODEL_UNAVAILABLE',
  'VECTOR_UNAVAILABLE',
  'PARSING_FAILED',
  'INTEGRITY_FAILED',
  'GENERATION_FAILED',
  'ANSWER_VALIDATION_FAILED',
  'ANSWER_VALIDATION_TIMEOUT',
  'PREPROCESSING_FAILED',
  'SCOPE_UNAVAILABLE',
  'NO_EFFECTIVE_INDEX',
  'INDEX_REBUILD_REQUIRED',
  'TASK_INTERRUPTED',
  'TASK_CANCELLED',
  'CLEANUP_FAILED',
  'PATH_INVALID',
  'COPY_FAILED',
  'VERIFICATION_FAILED',
  'CONFIGURATION_SAVE_FAILED',
  'OLD_DIRECTORY_CLEANUP_FAILED',
  'INTERNAL_ERROR',
];
function validTask(value) {
  if (
    !exact(value, taskFields) ||
    !identifier(value.id) ||
    !['model_test', 'vector_connection_test'].includes(value.kind) ||
    ![
      'queued',
      'running',
      'succeeded',
      'failed',
      'cancelled',
      'interrupted',
    ].includes(value.state) ||
    !['queued', 'initializing', 'validating', 'completed'].includes(
      value.phase,
    ) ||
    !date(value.createdAt) ||
    !nullable(value.startedAt, date) ||
    !nullable(value.finishedAt, date) ||
    ![
      'knowledgeBaseId',
      'fileId',
      'targetContentVersionId',
      'targetConfigVersionId',
      'retryOfTaskId',
      'resultResourceId',
    ].every((key) => nullable(value[key], identifier)) ||
    !integer(value.attempt, 0, 3) ||
    !integer(value.progressCompleted, 0) ||
    !nullable(value.progressTotal, (n) => integer(n, 0)) ||
    !Array.isArray(value.warnings) ||
    !value.warnings.every((w) => typeof w === 'string')
  )
    return false;
  const failed = ['failed', 'interrupted'].includes(value.state);
  const terminal = ['succeeded', 'failed', 'cancelled', 'interrupted'].includes(
    value.state,
  );
  return (
    (terminal
      ? value.phase === 'completed' && value.finishedAt !== null
      : value.finishedAt === null) &&
    (failed
      ? exact(value.failure, ['code', 'message', 'retryable']) &&
        failureCodes.includes(value.failure.code) &&
        typeof value.failure.message === 'string' &&
        typeof value.failure.retryable === 'boolean'
      : value.failure === null)
  );
}
export function createSettingsClient(session) {
  const requestConfig = async (kind, config) => {
    const value = await session.request(config);
    return verify(value, validConfig(value, kind));
  };
  return {
    get: (kind) => requestConfig(kind, { method: 'get', url: path(kind) }),
    save(kind, data) {
      const fields =
        kind === 'vector'
          ? ['endpoint', 'credential']
          : [
              'endpoint',
              'model',
              'credential',
              'contextWindowTokens',
              'maxOutputTokens',
            ];
      if (
        !exact(data, fields) ||
        !endpoint(data.endpoint) ||
        (kind !== 'vector' &&
          (/\/(chat\/completions|embeddings)\/?$/.test(data.endpoint) ||
            typeof data.model !== 'string' ||
            !/\S/.test(data.model) ||
            Array.from(data.model).length > 200 ||
            (kind === 'generation'
              ? !integer(data.contextWindowTokens) ||
                !integer(data.maxOutputTokens)
              : data.contextWindowTokens !== null ||
                data.maxOutputTokens !== null)))
      )
        throw new Error('请填写合法基础地址、模型标识及对应预算。');
      change(data.credential);
      return requestConfig(kind, { method: 'put', url: path(kind), data });
    },
    async versions(cursor) {
      if (
        cursor !== undefined &&
        (typeof cursor !== 'string' ||
          !cursor.length ||
          Array.from(cursor).length > 2048)
      )
        throw new Error('分页位置无效，请重新读取。');
      const value = await session.request({
        method: 'get',
        url: '/settings/models/embedding/versions',
        params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
      });
      return verify(
        value,
        exact(value, ['items', 'nextCursor']) &&
          Array.isArray(value.items) &&
          value.items.every((item) => validConfig(item, 'embedding')) &&
          nullable(value.nextCursor, (c) => typeof c === 'string'),
      );
    },
    async version(id) {
      const value = await requestConfig('embedding', {
        method: 'get',
        url: idPath(id, '/settings/models/embedding/versions'),
      });
      return verify(value, value.versionId.toLowerCase() === id.toLowerCase());
    },
    async rotate(id, data) {
      const value = await requestConfig('embedding', {
        method: 'put',
        url: `${idPath(id, '/settings/models/embedding/versions')}/credential`,
        data: change(data),
      });
      return verify(value, value.versionId.toLowerCase() === id.toLowerCase());
    },
    async test(kind, key, consent) {
      if (!identifier(key)) throw new Error('测试请求标识无效。');
      if (kind !== 'vector' && consent !== true)
        throw new Error('请先同意连接测试可能产生模型费用。');
      const value = await session.request({
        method: 'post',
        url: `${path(kind)}/test`,
        headers: { 'Idempotency-Key': key },
        ...(kind === 'vector' ? {} : { data: { allowChargedCall: true } }),
      });
      return verify(
        value,
        exact(value, ['task']) &&
          validTask(value.task) &&
          value.task.kind ===
            (kind === 'vector' ? 'vector_connection_test' : 'model_test'),
      );
    },
    async task(id) {
      const value = await session.request({
        method: 'get',
        url: idPath(id, '/tasks'),
      });
      return verify(
        value,
        validTask(value) && value.id.toLowerCase() === id.toLowerCase(),
      );
    },
    async result(id) {
      const value = await session.request({
        method: 'get',
        url: `${idPath(id, '/tasks')}/connection-test`,
      });
      return verify(
        value,
        exact(value, [
          'available',
          'checkedAt',
          'message',
          'mayHaveIncurredCost',
        ]) &&
          typeof value.available === 'boolean' &&
          date(value.checkedAt) &&
          typeof value.message === 'string' &&
          typeof value.mayHaveIncurredCost === 'boolean',
      );
    },
  };
}
