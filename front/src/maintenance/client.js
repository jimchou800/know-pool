import { exact, uuid, date } from '../system/session';
import { validTask } from '../knowledge-bases/client';
const identifier = (value) => typeof value === 'string' && uuid.test(value);
const texts = (value) =>
  Array.isArray(value) && value.every((item) => typeof item === 'string');
const stages = [
  'path_check',
  'copy',
  'verification',
  'configuration_save',
  'old_directory_cleanup',
  'index_check',
];
function verify(value, valid) {
  if (!valid) throw new Error('维护响应不符合约定，请读取原任务，勿重新提交。');
  return value;
}
const maintenanceTask = (value, kind) =>
  validTask(value, kind) &&
  [
    'knowledgeBaseId',
    'fileId',
    'targetContentVersionId',
    'targetConfigVersionId',
    'resultResourceId',
  ].every((field) => value[field] === null);
function accepted(value, kind) {
  return verify(
    value,
    exact(value, ['task']) && maintenanceTask(value.task, kind),
  );
}
export function createMaintenanceClient(client, kind = 'migration') {
  if (!['migration', 'backup', 'restore'].includes(kind))
    throw new Error('维护类型无效。');
  return {
    async preflight(sourcePath, targetPath) {
      if (
        !(kind === 'migration' ? [sourcePath, targetPath] : [targetPath]).every(
          (value) =>
            typeof value === 'string' &&
            Array.from(value).length >= 3 &&
            Array.from(value).length <= 32767,
        )
      )
        throw new Error('请输入长度为 3 至 32767 字符的本地绝对目录。');
      const value = await client.request({
        method: 'post',
        url: `/maintenance/${kind}/preflight`,
        data:
          kind === 'migration'
            ? { sourcePath, targetPath }
            : kind === 'backup'
              ? { targetPath }
              : { backupPath: targetPath },
      });
      return verify(
        value,
        exact(value, [
          'token',
          'expiresAt',
          'normalizedPath',
          'allowed',
          'reasons',
          'containsPlaintextCredentials',
          'includesVectorSnapshot',
          'estimatedBytes',
        ]) &&
          identifier(value.token) &&
          date(value.expiresAt) &&
          typeof value.normalizedPath === 'string' &&
          typeof value.allowed === 'boolean' &&
          texts(value.reasons) &&
          typeof value.containsPlaintextCredentials === 'boolean' &&
          value.includesVectorSnapshot === false &&
          Number.isSafeInteger(value.estimatedBytes) &&
          value.estimatedBytes >= 0,
      );
    },
    async submit(preflightToken, key, acknowledgements) {
      if (!identifier(preflightToken) || !identifier(key))
        throw new Error('迁移操作标识无效。');
      if (
        kind === 'restore' &&
        (!exact(acknowledgements, [
          'acknowledgePlaintextCredentials',
          'acknowledgeIndexRebuildCost',
        ]) ||
          acknowledgements.acknowledgePlaintextCredentials !== true ||
          acknowledgements.acknowledgeIndexRebuildCost !== true)
      )
        throw new Error('请明确确认明文凭据与索引重建费用提示。');
      const value = accepted(
        await client.request({
          method: 'post',
          url: `/maintenance/${kind}`,
          headers: { 'Idempotency-Key': key },
          data: {
            preflightToken,
            confirmed: true,
            ...(kind === 'restore' ? acknowledgements : {}),
          },
        }),
        kind,
      );
      return verify(value, value.task.retryOfTaskId === null);
    },
    async get(taskId) {
      if (!identifier(taskId)) throw new Error('迁移任务标识无效。');
      const value = await client.request({
        method: 'get',
        url: `/maintenance/tasks/${taskId}`,
      });
      return verify(
        value,
        exact(value, [
          'task',
          'operation',
          'activeDataDirectory',
          'targetPath',
          'configurationSwitched',
          'oldDirectoryResidue',
          'recoverable',
          'indexStatus',
          'credentialsIncluded',
          'vectorSnapshotIncluded',
          'failureStage',
          'warnings',
        ]) &&
          maintenanceTask(value.task, kind) &&
          value.task.id === taskId &&
          value.operation === kind &&
          typeof value.activeDataDirectory === 'string' &&
          typeof value.targetPath === 'string' &&
          [
            'configurationSwitched',
            'oldDirectoryResidue',
            'recoverable',
            'credentialsIncluded',
          ].every((field) => typeof value[field] === 'boolean') &&
          ['not_checked', 'valid', 'unavailable', 'rebuild_required'].includes(
            value.indexStatus,
          ) &&
          value.vectorSnapshotIncluded === false &&
          (value.failureStage === null ||
            stages.includes(value.failureStage)) &&
          texts(value.warnings),
      );
    },
    async list(cursor) {
      const value = await client.request({
        method: 'get',
        url: '/tasks',
        params: {
          kind,
          limit: 20,
          ...(cursor === undefined ? {} : { cursor }),
        },
      });
      return verify(
        value,
        exact(value, ['items', 'nextCursor']) &&
          Array.isArray(value.items) &&
          value.items.every((task) => maintenanceTask(task, kind)) &&
          (value.nextCursor === null || typeof value.nextCursor === 'string'),
      );
    },
    async retry(taskId, action, key) {
      if (
        !identifier(taskId) ||
        !identifier(key) ||
        !['resume', 'cleanup_residue'].includes(action)
      )
        throw new Error('迁移恢复操作无效。');
      const value = accepted(
        await client.request({
          method: 'post',
          url: `/maintenance/tasks/${taskId}/retry`,
          headers: { 'Idempotency-Key': key },
          data: { action },
        }),
        kind,
      );
      return verify(value, value.task.retryOfTaskId === taskId);
    },
  };
}
export const createMigrationClient = (client) =>
  createMaintenanceClient(client);
