import { exact, uuid, date } from '../system/session';
import { validTask } from './client';

const identifier = (value) => typeof value === 'string' && uuid.test(value);
const text = (value, min = 0, max = Number.MAX_SAFE_INTEGER) =>
  typeof value === 'string' &&
  Array.from(value).length >= min &&
  Array.from(value).length <= max;
const nullable = (value, check) => value === null || check(value);
const integer = (value, min = 0) => Number.isSafeInteger(value) && value >= min;
const filename = (value) =>
  text(value, 1, 255) &&
  !/[\\/]/.test(value) &&
  Array.from(value).every(
    (char) => char.codePointAt(0) > 31 && char.codePointAt(0) !== 127,
  ) &&
  !['.', '..'].includes(value);
function validScope(value, knowledgeBaseId) {
  return (
    exact(value, [
      'selectedKnowledgeBaseIds',
      'usedKnowledgeBaseIds',
      'omitted',
      'incomplete',
    ]) &&
    Array.isArray(value.selectedKnowledgeBaseIds) &&
    value.selectedKnowledgeBaseIds.length === 1 &&
    value.selectedKnowledgeBaseIds[0] === knowledgeBaseId &&
    Array.isArray(value.usedKnowledgeBaseIds) &&
    value.usedKnowledgeBaseIds.length <= 1 &&
    value.usedKnowledgeBaseIds.every((id) => id === knowledgeBaseId) &&
    Array.isArray(value.omitted) &&
    value.omitted.every(
      (item) =>
        exact(item, ['knowledgeBaseId', 'reason', 'message']) &&
        item.knowledgeBaseId === knowledgeBaseId &&
        [
          'deleted',
          'not_published',
          'unavailable',
          'no_effective_index',
        ].includes(item.reason) &&
        text(item.message),
    ) &&
    typeof value.incomplete === 'boolean'
  );
}
export function validLocation(value) {
  return (
    exact(value, [
      'format',
      'pageStart',
      'pageEnd',
      'lineStart',
      'lineEnd',
      'titlePath',
      'paragraphId',
      'structureId',
      'continuationIndex',
      'tableRowStart',
      'tableRowEnd',
      'tableColumnStart',
      'tableColumnEnd',
      'codeLanguage',
      'codeBlockId',
    ]) &&
    ['text', 'markdown', 'pdf'].includes(value.format) &&
    ['pageStart', 'pageEnd', 'lineStart', 'lineEnd'].every((key) =>
      nullable(value[key], (v) => integer(v, 1)),
    ) &&
    (value.format === 'pdf'
      ? integer(value.pageStart, 1) && value.pageEnd >= value.pageStart
      : integer(value.lineStart, 1) &&
        value.lineEnd >= value.lineStart &&
        integer(value.lineEnd, 1)) &&
    Array.isArray(value.titlePath) &&
    value.titlePath.every((v) => text(v)) &&
    ['paragraphId', 'structureId', 'codeLanguage', 'codeBlockId'].every((key) =>
      nullable(value[key], (v) => text(v)),
    ) &&
    [
      'continuationIndex',
      'tableRowStart',
      'tableRowEnd',
      'tableColumnStart',
      'tableColumnEnd',
    ].every((key) => nullable(value[key], (v) => integer(v))) &&
    [
      ['pageStart', 'pageEnd'],
      ['lineStart', 'lineEnd'],
      ['tableRowStart', 'tableRowEnd'],
      ['tableColumnStart', 'tableColumnEnd'],
    ].every(
      ([start, end]) =>
        value[start] === null ||
        value[end] === null ||
        value[end] >= value[start],
    )
  );
}
function validEvidence(value, knowledgeBaseId) {
  return (
    exact(value, [
      'id',
      'chunkId',
      'knowledgeBaseId',
      'fileId',
      'contentVersionId',
      'indexVersionId',
      'configVersionId',
      'filenameAtCapture',
      'currentFilename',
      'text',
      'location',
      'originalAvailable',
      'usingPreviousVersion',
      'updateState',
      'capturedAt',
    ]) &&
    [
      'id',
      'chunkId',
      'knowledgeBaseId',
      'fileId',
      'contentVersionId',
      'indexVersionId',
      'configVersionId',
    ].every((key) => identifier(value[key])) &&
    value.knowledgeBaseId === knowledgeBaseId &&
    filename(value.filenameAtCapture) &&
    nullable(value.currentFilename, filename) &&
    text(value.text) &&
    validLocation(value.location) &&
    typeof value.originalAvailable === 'boolean' &&
    typeof value.usingPreviousVersion === 'boolean' &&
    ['ready', 'updating', 'failed'].includes(value.updateState) &&
    date(value.capturedAt)
  );
}
function validResult(value, request, knowledgeBaseId) {
  return (
    exact(value, [
      'requestId',
      'instanceId',
      'taskId',
      'query',
      'scope',
      'outcome',
      'evidence',
      'preprocessingState',
      'additionalQueries',
      'warnings',
    ]) &&
    value.requestId === request.id &&
    value.instanceId === null &&
    value.taskId === request.task.id &&
    text(value.query, 1, 20000) &&
    /\S/.test(value.query) &&
    validScope(value.scope, knowledgeBaseId) &&
    ['matches', 'no_matches'].includes(value.outcome) &&
    Array.isArray(value.evidence) &&
    value.evidence.every((item) => validEvidence(item, knowledgeBaseId)) &&
    (value.outcome === 'matches'
      ? value.evidence.length > 0
      : value.evidence.length === 0) &&
    value.scope.usedKnowledgeBaseIds.length === 1 &&
    value.scope.omitted.length === 0 &&
    value.scope.incomplete === false &&
    value.preprocessingState === 'not_used' &&
    Array.isArray(value.additionalQueries) &&
    value.additionalQueries.length === 0 &&
    Array.isArray(value.warnings) &&
    value.warnings.every((item) => text(item))
  );
}
function requestPath(id) {
  if (!identifier(id)) throw new Error('检索请求标识无效。');
  return `/search-requests/${id.toLowerCase()}`;
}
function verify(value, valid) {
  if (!valid) throw new Error('管理检索响应格式不符合约定，请重新查询原请求。');
  return value;
}
function validRequest(value, knowledgeBaseId) {
  return (
    exact(value, ['id', 'instanceId', 'task', 'result', 'scope']) &&
    identifier(value.id) &&
    value.instanceId === null &&
    validTask(value.task, 'search') &&
    value.task.knowledgeBaseId === knowledgeBaseId &&
    nullable(value.scope, (scope) => validScope(scope, knowledgeBaseId)) &&
    (value.task.state === 'succeeded'
      ? value.scope !== null &&
        value.task.resultResourceId === value.id &&
        value.scope.usedKnowledgeBaseIds.length === 1 &&
        value.scope.omitted.length === 0 &&
        value.scope.incomplete === false &&
        validResult(value.result, value, knowledgeBaseId)
      : value.result === null)
  );
}
export function createManagementSearchClient(session) {
  return {
    async submit(knowledgeBaseId, query, key) {
      if (
        !identifier(knowledgeBaseId) ||
        !identifier(key) ||
        !text(query, 1, 20000) ||
        !/\S/.test(query)
      )
        throw new Error('请输入有效的检索内容（最多20000码点）并选择知识库。');
      const value = await session.request({
        method: 'post',
        url: `/knowledge-bases/${knowledgeBaseId.toLowerCase()}/search-test`,
        headers: { 'Idempotency-Key': key },
        data: { query, knowledgeBaseIds: [knowledgeBaseId] },
      });
      return verify(value, validRequest(value, knowledgeBaseId));
    },
    async get(requestId, knowledgeBaseId) {
      const value = await session.request({
        method: 'get',
        url: requestPath(requestId),
      });
      return verify(
        value,
        validRequest(value, knowledgeBaseId) && value.id === requestId,
      );
    },
    async evidence(requestId, evidenceId, knowledgeBaseId) {
      if (!identifier(evidenceId)) throw new Error('原文依据标识无效。');
      const value = await session.request({
        method: 'get',
        url: `${requestPath(requestId)}/evidence/${evidenceId.toLowerCase()}`,
      });
      return verify(
        value,
        validEvidence(value, knowledgeBaseId) && value.id === evidenceId,
      );
    },
  };
}
