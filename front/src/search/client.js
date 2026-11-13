import { exact, uuid, date } from '../system/session';
import { validTask } from '../knowledge-bases/client';
import { validEvidence } from '../knowledge-bases/search-client';

const identifier = (v) => typeof v === 'string' && uuid.test(v);
const query = (v) =>
  typeof v === 'string' && /\S/.test(v) && Array.from(v).length <= 20000;
const ids = (v, min = 0) =>
  Array.isArray(v) &&
  v.length >= min &&
  v.length <= 100 &&
  v.every(identifier) &&
  new Set(v).size === v.length;
const nullable = (v, valid) => v === null || valid(v);
const sameIds = (a, b) =>
  a.length === b.length && a.every((v) => b.includes(v));
function verify(value, valid) {
  if (!valid) throw new Error('知识检索响应不符合约定，请查询原请求。');
  return value;
}
function instancePath(id) {
  if (!identifier(id)) throw new Error('检索实例标识无效。');
  return `/search-instances/${id.toLowerCase()}`;
}
function requestPath(id) {
  if (!identifier(id)) throw new Error('检索请求标识无效。');
  return `/search-requests/${id.toLowerCase()}`;
}
function validInstance(v) {
  return (
    exact(v, [
      'id',
      'name',
      'manuallyNamed',
      'lastSubmittedQuery',
      'knowledgeBaseIds',
      'createdAt',
      'updatedAt',
      'currentRequestId',
      'resultState',
    ]) &&
    identifier(v.id) &&
    typeof v.name === 'string' &&
    typeof v.manuallyNamed === 'boolean' &&
    nullable(v.lastSubmittedQuery, query) &&
    ids(v.knowledgeBaseIds) &&
    date(v.createdAt) &&
    date(v.updatedAt) &&
    nullable(v.currentRequestId, identifier) &&
    [
      'never_submitted',
      'running',
      'available',
      'failed',
      'cancelled',
      'rerun_required',
    ].includes(v.resultState)
  );
}
function validScope(v, selected, complete = true) {
  if (
    !exact(v, [
      'selectedKnowledgeBaseIds',
      'usedKnowledgeBaseIds',
      'omitted',
      'incomplete',
    ]) ||
    !ids(v.selectedKnowledgeBaseIds, 1) ||
    !sameIds(v.selectedKnowledgeBaseIds, selected) ||
    !ids(v.usedKnowledgeBaseIds) ||
    !v.usedKnowledgeBaseIds.every((id) => selected.includes(id)) ||
    !Array.isArray(v.omitted) ||
    typeof v.incomplete !== 'boolean'
  )
    return false;
  const omitted = v.omitted.map((item) => item?.knowledgeBaseId);
  return (
    v.omitted.every(
      (item) =>
        exact(item, ['knowledgeBaseId', 'reason', 'message']) &&
        selected.includes(item.knowledgeBaseId) &&
        !v.usedKnowledgeBaseIds.includes(item.knowledgeBaseId) &&
        [
          'deleted',
          'not_published',
          'unavailable',
          'no_effective_index',
        ].includes(item.reason) &&
        typeof item.message === 'string',
    ) &&
    new Set(omitted).size === omitted.length &&
    (!complete ||
      omitted.length + v.usedKnowledgeBaseIds.length === selected.length) &&
    v.incomplete === omitted.length > 0
  );
}
function validRequest(v, instanceId, submitted) {
  if (
    !exact(v, ['id', 'instanceId', 'task', 'result', 'scope']) ||
    !identifier(v.id) ||
    v.instanceId !== instanceId ||
    !validTask(v.task, 'search') ||
    v.task.knowledgeBaseId !== null ||
    !nullable(v.scope, (s) =>
      validScope(s, submitted.knowledgeBaseIds, v.task.state === 'succeeded'),
    )
  )
    return false;
  if (v.task.state !== 'succeeded') return v.result === null;
  const r = v.result;
  return (
    v.task.resultResourceId === v.id &&
    v.scope !== null &&
    v.scope.usedKnowledgeBaseIds.length > 0 &&
    exact(r, [
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
    r.requestId === v.id &&
    r.instanceId === instanceId &&
    r.taskId === v.task.id &&
    r.query === submitted.query &&
    validScope(r.scope, submitted.knowledgeBaseIds) &&
    sameIds(r.scope.usedKnowledgeBaseIds, v.scope.usedKnowledgeBaseIds) &&
    JSON.stringify(r.scope.omitted) === JSON.stringify(v.scope.omitted) &&
    ['matches', 'no_matches'].includes(r.outcome) &&
    Array.isArray(r.evidence) &&
    r.evidence.every(
      (e) =>
        r.scope.usedKnowledgeBaseIds.includes(e.knowledgeBaseId) &&
        validEvidence(e, e.knowledgeBaseId),
    ) &&
    new Set(r.evidence.map((e) => e.id)).size === r.evidence.length &&
    (r.outcome === 'matches'
      ? r.evidence.length > 0
      : r.evidence.length === 0) &&
    ['not_used', 'completed', 'fallback'].includes(r.preprocessingState) &&
    Array.isArray(r.additionalQueries) &&
    r.additionalQueries.length <= 3 &&
    r.additionalQueries.every(query) &&
    Array.isArray(r.warnings) &&
    r.warnings.every((s) => typeof s === 'string')
  );
}
export function createSearchClient(session) {
  return {
    async create() {
      const value = await session.request({
        method: 'post',
        url: '/search-instances',
      });
      return verify(value, validInstance(value));
    },
    async submit(instanceId, input, key) {
      if (
        !exact(input, ['query', 'knowledgeBaseIds']) ||
        !query(input.query) ||
        !ids(input.knowledgeBaseIds, 1) ||
        !identifier(key)
      )
        throw new Error('请填写有效查询，并选择一至一百个知识库。');
      const value = await session.request({
        method: 'post',
        url: instancePath(instanceId) + '/requests',
        data: input,
        headers: { 'Idempotency-Key': key },
      });
      return verify(value, validRequest(value, instanceId, input));
    },
    async get(id, instanceId, input) {
      const value = await session.request({
        method: 'get',
        url: requestPath(id),
      });
      return verify(
        value,
        validRequest(value, instanceId, input) && value.id === id,
      );
    },
    async evidence(requestId, item) {
      if (!identifier(item?.id)) throw new Error('原文依据标识无效。');
      const value = await session.request({
        method: 'get',
        url: requestPath(requestId) + `/evidence/${item.id}`,
      });
      return verify(
        value,
        validEvidence(value, item.knowledgeBaseId) &&
          [
            'id',
            'chunkId',
            'knowledgeBaseId',
            'fileId',
            'contentVersionId',
            'indexVersionId',
            'configVersionId',
          ].every((field) => value[field] === item[field]),
      );
    },
  };
}
