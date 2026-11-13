import { exact, uuid } from '../system/session';
import { validTask } from '../knowledge-bases/client';
const id = (value) => typeof value === 'string' && uuid.test(value);
const count = (value) => Number.isSafeInteger(value) && value >= 0;
function verify(value, valid) {
  if (!valid) throw new Error('删除或清理响应不符合约定，请查询原任务。');
  return value;
}
function path(kind, value) {
  if (!['files', 'datasets', 'knowledge-bases'].includes(kind) || !id(value))
    throw new Error('删除对象标识无效。');
  return `/${kind}/${value}`;
}
function accepted(value) {
  return (
    exact(value, ['task']) &&
    validTask(value.task, 'cleanup') &&
    count(value.task.progressTotal) &&
    [
      'targetContentVersionId',
      'targetConfigVersionId',
      'resultResourceId',
    ].every((field) => value.task[field] === null)
  );
}
export function createCleanupClient(session) {
  return {
    async impact(kind, resourceId) {
      const value = await session.request({
        method: 'get',
        url: path(kind, resourceId) + '/delete-impact',
      });
      return verify(
        value,
        exact(value, [
          'resourceId',
          'fileCount',
          'knowledgeBaseIds',
          'activeTaskCount',
          'historySnapshotsRetained',
          'warnings',
        ]) &&
          value.resourceId === resourceId &&
          count(value.fileCount) &&
          count(value.activeTaskCount) &&
          Array.isArray(value.knowledgeBaseIds) &&
          value.knowledgeBaseIds.every(id) &&
          value.historySnapshotsRetained === true &&
          Array.isArray(value.warnings) &&
          value.warnings.every((item) => typeof item === 'string'),
      );
    },
    async remove(kind, resourceId, key) {
      if (!id(key)) throw new Error('删除操作标识无效。');
      const value = await session.request({
        method: 'delete',
        url: path(kind, resourceId),
        headers: { 'Idempotency-Key': key },
        data: { confirmed: true },
      });
      return verify(
        value,
        accepted(value) &&
          value.task.retryOfTaskId === null &&
          value.task.knowledgeBaseId ===
            (kind === 'knowledge-bases' ? resourceId : null) &&
          value.task.fileId === (kind === 'files' ? resourceId : null),
      );
    },
    async list(cursor, state) {
      const value = await session.request({
        method: 'get',
        url: '/tasks',
        params: {
          limit: 20,
          kind: 'cleanup',
          ...(cursor === undefined ? {} : { cursor }),
          ...(state ? { state } : {}),
        },
      });
      return verify(
        value,
        exact(value, ['items', 'nextCursor']) &&
          Array.isArray(value.items) &&
          value.items.every(
            (task) =>
              validTask(task, 'cleanup') && (!state || task.state === state),
          ) &&
          (value.nextCursor === null || typeof value.nextCursor === 'string'),
      );
    },
    async retry(taskId, key) {
      if (!id(taskId) || !id(key)) throw new Error('清理重试标识无效。');
      const value = await session.request({
        method: 'post',
        url: `/tasks/${taskId}/retry-cleanup`,
        headers: { 'Idempotency-Key': key },
      });
      return verify(
        value,
        accepted(value) && value.task.retryOfTaskId === taskId,
      );
    },
  };
}
