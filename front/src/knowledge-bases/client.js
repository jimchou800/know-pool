import { date, exact, uuid } from '../system/session';
import { trimName } from '../datasets/client';

const text = (value, min = 0, max = Number.MAX_SAFE_INTEGER) =>
  typeof value === 'string' &&
  Array.from(value).length >= min &&
  Array.from(value).length <= max;
const identifier = (value) => typeof value === 'string' && uuid.test(value);
const nullable = (value, valid) => value === null || valid(value);
const count = (value) => Number.isSafeInteger(value) && value >= 0;
const integer = (value, min, max) =>
  count(value) && value >= min && value <= max;
const validProcessing = (v) => {
  if (!exact(v, ['chunking', 'embedding'])) return false;
  const c = v.chunking,
    e = v.embedding;
  return (
    exact(c, [
      'strategy',
      'overlapCodePoints',
      'removePageHeadersFooters',
      'customMetadata',
      'embeddedMetadataFields',
    ]) &&
    ['structure', 'fixed', 'page'].includes(c.strategy) &&
    integer(c.overlapCodePoints, 0, 1999) &&
    typeof c.removePageHeadersFooters === 'boolean' &&
    c.customMetadata !== null &&
    typeof c.customMetadata === 'object' &&
    !Array.isArray(c.customMetadata) &&
    Object.keys(c.customMetadata).length <= 20 &&
    Object.entries(c.customMetadata).every(
      ([k, v]) => /^user_[A-Za-z0-9_]{1,60}$/.test(k) && text(v, 0, 2000),
    ) &&
    Array.isArray(c.embeddedMetadataFields) &&
    c.embeddedMetadataFields.length <= 20 &&
    new Set(c.embeddedMetadataFields).size ===
      c.embeddedMetadataFields.length &&
    c.embeddedMetadataFields.every((k) => Object.hasOwn(c.customMetadata, k)) &&
    exact(e, [
      'modelConfigVersionId',
      'dimensions',
      'distance',
      'similarityThreshold',
      'candidatesPerQuery',
      'modelSnapshot',
    ]) &&
    identifier(e.modelConfigVersionId) &&
    integer(e.dimensions, 1, 65536) &&
    ['cosine', 'dot', 'euclidean'].includes(e.distance) &&
    Number.isFinite(e.similarityThreshold) &&
    integer(e.candidatesPerQuery, 1, 1000) &&
    exact(e.modelSnapshot, ['endpoint', 'model']) &&
    text(e.modelSnapshot.model, 1, 200) &&
    validEndpoint(e.modelSnapshot.endpoint)
  );
};
function validEndpoint(value) {
  if (!text(value) || !/^https?:\/\//.test(value)) return false;
  try {
    return !!new URL(value).hostname;
  } catch {
    return false;
  }
}
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
const validFailure = (v) =>
  exact(v, ['code', 'message', 'retryable']) &&
  failureCodes.includes(v.code) &&
  text(v.message) &&
  typeof v.retryable === 'boolean';
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
export function validTask(v, kind = 'knowledge_base_create') {
  if (
    !exact(v, taskFields) ||
    v.kind !== kind ||
    !identifier(v.id) ||
    ![
      'queued',
      'running',
      'succeeded',
      'failed',
      'cancelled',
      'interrupted',
    ].includes(v.state) ||
    ![
      'queued',
      'initializing',
      'parsing',
      'chunking',
      'embedding',
      'vector_writing',
      'validating',
      'switching',
      'retrieving',
      'generating',
      'answer_validating',
      'copying',
      'verifying',
      'saving_config',
      'cleaning',
      'completed',
    ].includes(v.phase) ||
    !date(v.createdAt) ||
    !nullable(v.startedAt, date) ||
    !nullable(v.finishedAt, date) ||
    ![
      'knowledgeBaseId',
      'fileId',
      'targetContentVersionId',
      'targetConfigVersionId',
      'retryOfTaskId',
      'resultResourceId',
    ].every((k) => nullable(v[k], identifier)) ||
    !integer(v.attempt, 0, 3) ||
    !count(v.progressCompleted) ||
    !nullable(v.progressTotal, count) ||
    !Array.isArray(v.warnings) ||
    !v.warnings.every((s) => text(s))
  )
    return false;
  const terminal = !['queued', 'running'].includes(v.state);
  return (
    (terminal ? v.phase === 'completed' && date(v.finishedAt) : true) &&
    (v.state === 'queued' ? v.startedAt === null : true) &&
    (['failed', 'interrupted'].includes(v.state)
      ? validFailure(v.failure)
      : v.failure === null) &&
    (kind === 'knowledge_base_create'
      ? v.state === 'succeeded'
        ? identifier(v.resultResourceId) &&
          identifier(v.knowledgeBaseId) &&
          v.resultResourceId.toLowerCase() === v.knowledgeBaseId.toLowerCase()
        : v.resultResourceId === null
      : ['index_file', 'search'].includes(kind)
        ? v.state === 'succeeded'
          ? identifier(v.resultResourceId)
          : v.resultResourceId === null
        : true)
  );
}
const validBase = (value) =>
  exact(value, [
    'id',
    'name',
    'description',
    'createdAt',
    'updatedAt',
    'publicationState',
    'publishedAt',
    'offlineAt',
    'offlineReason',
    'availability',
    'availabilityReason',
    'lastCheckedAt',
    'processingState',
    'targetConfigVersionId',
    'effectiveConfigVersionId',
    'effectiveIndexVersionId',
    'includedFileCount',
    'searchableFileCount',
  ]) &&
  identifier(value.id) &&
  text(value.name, 1, 120) &&
  trimName(value.name) === value.name &&
  text(value.description, 0, 2000) &&
  date(value.createdAt) &&
  date(value.updatedAt) &&
  ['unpublished', 'published', 'offline'].includes(value.publicationState) &&
  nullable(value.publishedAt, date) &&
  nullable(value.offlineAt, date) &&
  nullable(value.offlineReason, (v) =>
    ['manual', 'no_searchable_files'].includes(v),
  ) &&
  ['available', 'unavailable'].includes(value.availability) &&
  nullable(value.availabilityReason, text) &&
  nullable(value.lastCheckedAt, date) &&
  ['idle', 'queued', 'running', 'failed'].includes(value.processingState) &&
  identifier(value.targetConfigVersionId) &&
  nullable(value.effectiveConfigVersionId, identifier) &&
  nullable(value.effectiveIndexVersionId, identifier) &&
  count(value.includedFileCount) &&
  count(value.searchableFileCount);
function verify(value, valid) {
  if (!valid) throw new Error('知识库响应格式不符合约定，请重新读取。');
  return value;
}
function path(id) {
  if (!identifier(id)) throw new Error('知识库标识无效，请重新选择。');
  return `/knowledge-bases/${id.toLowerCase()}`;
}
function params(cursor) {
  if (cursor !== undefined && !text(cursor, 1, 2048))
    throw new Error('分页位置无效，请返回第一页。');
  return { limit: 20, ...(cursor === undefined ? {} : { cursor }) };
}
const validPage = (value, valid) =>
  exact(value, ['items', 'nextCursor']) &&
  Array.isArray(value.items) &&
  value.items.every(valid) &&
  nullable(value.nextCursor, text);
const validSource = (v) =>
  exact(v, ['id', 'knowledgeBaseId', 'type', 'resourceId', 'createdAt']) &&
  identifier(v.id) &&
  identifier(v.knowledgeBaseId) &&
  ['dataset', 'file'].includes(v.type) &&
  identifier(v.resourceId) &&
  date(v.createdAt);
const validFile = (v) =>
  exact(v, [
    'knowledgeBaseId',
    'fileId',
    'sources',
    'excluded',
    'included',
    'latestContentVersionId',
    'effectiveContentVersionId',
    'effectiveIndexVersionId',
    'targetContentVersionId',
    'targetConfigVersionId',
    'updateState',
    'usingPreviousVersion',
    'task',
    'failure',
    'effectiveConfigVersionId',
  ]) &&
  identifier(v.knowledgeBaseId) &&
  identifier(v.fileId) &&
  Array.isArray(v.sources) &&
  v.sources.every(
    (s) => validSource(s) && s.knowledgeBaseId === v.knowledgeBaseId,
  ) &&
  typeof v.excluded === 'boolean' &&
  typeof v.included === 'boolean' &&
  identifier(v.latestContentVersionId) &&
  [
    'effectiveContentVersionId',
    'effectiveIndexVersionId',
    'targetContentVersionId',
    'targetConfigVersionId',
    'effectiveConfigVersionId',
  ].every((k) => nullable(v[k], identifier)) &&
  ['not_included', 'queued', 'processing', 'ready', 'failed'].includes(
    v.updateState,
  ) &&
  typeof v.usingPreviousVersion === 'boolean' &&
  nullable(
    v.task,
    (t) =>
      validTask(t, 'index_file') &&
      t.knowledgeBaseId === v.knowledgeBaseId &&
      t.fileId === v.fileId,
  ) &&
  nullable(v.failure, validFailure);
const validExclusion = (v) =>
  exact(v, ['knowledgeBaseId', 'fileId', 'createdAt']) &&
  identifier(v.knowledgeBaseId) &&
  identifier(v.fileId) &&
  date(v.createdAt);
export function createKnowledgeBaseClient(session) {
  async function page(id, suffix, valid, cursor) {
    const value = await session.request({
      method: 'get',
      url: path(id) + suffix,
      params: params(cursor),
    });
    return verify(
      value,
      validPage(
        value,
        (v) => valid(v) && v.knowledgeBaseId.toLowerCase() === id.toLowerCase(),
      ),
    );
  }
  return {
    async switches(id, cursor) {
      return page(
        id,
        '/switches',
        (v) =>
          exact(v, [
            'id',
            'knowledgeBaseId',
            'fileId',
            'fromContentVersionId',
            'toContentVersionId',
            'fromIndexVersionId',
            'toIndexVersionId',
            'fromConfigVersionId',
            'toConfigVersionId',
            'taskId',
            'switchedAt',
          ]) &&
          [
            'id',
            'knowledgeBaseId',
            'fileId',
            'toContentVersionId',
            'toIndexVersionId',
            'toConfigVersionId',
            'taskId',
          ].every((key) => identifier(v[key])) &&
          [
            'fromContentVersionId',
            'fromIndexVersionId',
            'fromConfigVersionId',
          ].every((key) => nullable(v[key], identifier)) &&
          date(v.switchedAt),
        cursor,
      );
    },
    async cancel(task) {
      if (
        !task ||
        !identifier(task.id) ||
        ![
          'index_file',
          'rebuild_knowledge_base',
          'search',
          'question',
        ].includes(task.kind)
      )
        throw new Error('该任务不支持取消。');
      const value = await session.request({
        method: 'post',
        url: `/tasks/${task.id.toLowerCase()}/cancel`,
      });
      return verify(
        value,
        validTask(value, task.kind) &&
          value.id.toLowerCase() === task.id.toLowerCase() &&
          value.knowledgeBaseId === task.knowledgeBaseId,
      );
    },
    async removeSource(id, sourceId) {
      if (!identifier(sourceId)) throw new Error('来源标识无效。');
      const response = await session.request({
        method: 'delete',
        url: path(id) + `/sources/${sourceId.toLowerCase()}`,
        rawResponse: true,
      });
      verify(
        response,
        response.status === 204 &&
          (response.data === '' ||
            response.data === null ||
            response.data === undefined),
      );
    },
    exclusions: (id, cursor) => page(id, '/exclusions', validExclusion, cursor),
    async exclude(id, fileId) {
      if (!identifier(fileId)) throw new Error('资料标识无效。');
      const value = await session.request({
        method: 'put',
        url: path(id) + `/exclusions/${fileId.toLowerCase()}`,
      });
      return verify(
        value,
        validExclusion(value) &&
          value.knowledgeBaseId.toLowerCase() === id.toLowerCase() &&
          value.fileId.toLowerCase() === fileId.toLowerCase(),
      );
    },
    async include(id, fileId) {
      if (!identifier(fileId)) throw new Error('资料标识无效。');
      const response = await session.request({
        method: 'delete',
        url: path(id) + `/exclusions/${fileId.toLowerCase()}`,
        rawResponse: true,
      });
      verify(
        response,
        response.status === 204 &&
          (response.data === '' ||
            response.data === null ||
            response.data === undefined),
      );
    },
    async fileKnowledgeBases(fileId, cursor) {
      if (!identifier(fileId)) throw new Error('资料标识无效。');
      const value = await session.request({
        method: 'get',
        url: `/files/${fileId.toLowerCase()}/knowledge-bases`,
        params: params(cursor),
      });
      return verify(
        value,
        validPage(
          value,
          (v) =>
            validFile(v) && v.fileId.toLowerCase() === fileId.toLowerCase(),
        ),
      );
    },
    async list(cursor) {
      const value = await session.request({
        method: 'get',
        url: '/knowledge-bases',
        params: params(cursor),
      });
      return verify(value, validPage(value, validBase));
    },
    async get(id) {
      const value = await session.request({ method: 'get', url: path(id) });
      return verify(
        value,
        validBase(value) && value.id.toLowerCase() === id.toLowerCase(),
      );
    },
    async create(data, key) {
      if (
        !identifier(key) ||
        !data ||
        Object.keys(data).some(
          (k) =>
            !['name', 'description', 'processing', 'datasetIds'].includes(k),
        ) ||
        !text(data.name, 1, 120) ||
        !trimName(data.name) ||
        ('description' in data && !text(data.description, 0, 2000)) ||
        !validProcessing(data.processing) ||
        ('datasetIds' in data &&
          (!Array.isArray(data.datasetIds) ||
            data.datasetIds.length > 100 ||
            !data.datasetIds.every(identifier) ||
            new Set(data.datasetIds).size !== data.datasetIds.length))
      )
        throw new Error('请填写有效的知识库名称、完整处理配置及来源。');
      const value = await session.request({
        method: 'post',
        url: '/knowledge-bases',
        data,
        headers: { 'Idempotency-Key': key },
      });
      return verify(value, exact(value, ['task']) && validTask(value.task));
    },
    async task(id, kind = 'knowledge_base_create') {
      if (
        !identifier(id) ||
        ![
          'knowledge_base_create',
          'index_file',
          'search',
          'rebuild_knowledge_base',
          'cleanup',
        ].includes(kind)
      )
        throw new Error('任务标识或类型无效。');
      const value = await session.request({
        method: 'get',
        url: `/tasks/${id.toLowerCase()}`,
      });
      return verify(
        value,
        validTask(value, kind) && value.id.toLowerCase() === id.toLowerCase(),
      );
    },
    async update(id, data) {
      if (
        !data ||
        !Object.keys(data).length ||
        Object.keys(data).some((k) => !['name', 'description'].includes(k)) ||
        ('name' in data &&
          (!text(data.name, 1, 120) || !trimName(data.name))) ||
        ('description' in data && !text(data.description, 0, 2000))
      )
        throw new Error('请填写有效的名称与说明。');
      const value = await session.request({
        method: 'patch',
        url: path(id),
        data,
      });
      return verify(
        value,
        validBase(value) && value.id.toLowerCase() === id.toLowerCase(),
      );
    },
    async updateProcessing(id, data, key) {
      if (!identifier(key) || !validProcessing(data))
        throw new Error('请填写有效的切片配置与操作标识。');
      const value = await session.request({
        method: 'put',
        url: path(id) + '/processing-config',
        data,
        headers: { 'Idempotency-Key': key },
      });
      return verify(
        value,
        exact(value, ['task']) &&
          validTask(value.task, 'rebuild_knowledge_base') &&
          value.task.knowledgeBaseId.toLowerCase() === id.toLowerCase(),
      );
    },
    async config(id) {
      const value = await session.request({
        method: 'get',
        url: path(id) + '/processing-config',
      });
      return verify(
        value,
        exact(value, [
          'id',
          'knowledgeBaseId',
          'createdAt',
          'parserRuleVersion',
          'chunkRuleVersion',
          'lengthMeterVersion',
          'input',
        ]) &&
          identifier(value.id) &&
          identifier(value.knowledgeBaseId) &&
          value.knowledgeBaseId.toLowerCase() === id.toLowerCase() &&
          date(value.createdAt) &&
          ['parserRuleVersion', 'chunkRuleVersion', 'lengthMeterVersion'].every(
            (k) => text(value[k]),
          ) &&
          validProcessing(value.input),
      );
    },
    async addSource(id, data) {
      if (
        !exact(data, ['type', 'resourceId']) ||
        !['dataset', 'file'].includes(data.type) ||
        !identifier(data.resourceId)
      )
        throw new Error('请选择有效的数据集或资料。');
      const value = await session.request({
        method: 'post',
        url: path(id) + '/sources',
        data,
      });
      return verify(
        value,
        validSource(value) &&
          value.knowledgeBaseId.toLowerCase() === id.toLowerCase() &&
          value.type === data.type &&
          value.resourceId.toLowerCase() === data.resourceId.toLowerCase(),
      );
    },
    sources: (id, cursor) => page(id, '/sources', validSource, cursor),
    files: (id, cursor) => page(id, '/files', validFile, cursor),
    tasks: (id, cursor) =>
      page(
        id,
        '/tasks',
        (value) =>
          ['index_file', 'rebuild_knowledge_base', 'cleanup'].includes(
            value?.kind,
          ) && validTask(value, value.kind),
        cursor,
      ),
  };
}
