import { exact, uuid } from '../system/session';
import { validLocation } from './search-client';

export const fallbackLabels = {
  sentence: '句子边界',
  delimiter: '分隔符',
  hard: '硬长度',
  table_row: '表格行',
  table_cell: '表格单元格',
  code_line: '代码行',
};
const count = (value) => Number.isSafeInteger(value) && value >= 0;
const identifier = (value) => typeof value === 'string' && uuid.test(value);
const text = (value) => typeof value === 'string';
function validSample(value) {
  return (
    exact(value, [
      'chunkId',
      'text',
      'codePointCount',
      'location',
      'fallbackModes',
    ]) &&
    identifier(value.chunkId) &&
    text(value.text) &&
    count(value.codePointCount) &&
    validLocation(value.location) &&
    Array.isArray(value.fallbackModes) &&
    value.fallbackModes.every((mode) => Object.hasOwn(fallbackLabels, mode))
  );
}
export function createPreflightClient(session) {
  return {
    async get(taskId) {
      if (!identifier(taskId)) throw new Error('预检任务标识无效。');
      const value = await session.request({
        method: 'get',
        url: `/tasks/${taskId.toLowerCase()}/chunk-preflight`,
      });
      if (
        !exact(value, [
          'taskId',
          'contentVersionId',
          'configVersionId',
          'lengthMeterVersion',
          'chunkCount',
          'minimumLength',
          'maximumLength',
          'medianLength',
          'p95Length',
          'fallbackCounts',
          'emptyText',
          'integrityPassed',
          'warnings',
          'samples',
        ]) ||
        !identifier(value.taskId) ||
        value.taskId.toLowerCase() !== taskId.toLowerCase() ||
        !identifier(value.contentVersionId) ||
        !identifier(value.configVersionId) ||
        !text(value.lengthMeterVersion) ||
        !['chunkCount', 'minimumLength', 'maximumLength'].every((key) =>
          count(value[key]),
        ) ||
        !['medianLength', 'p95Length'].every(
          (key) => Number.isFinite(value[key]) && value[key] >= 0,
        ) ||
        !value.fallbackCounts ||
        typeof value.fallbackCounts !== 'object' ||
        Array.isArray(value.fallbackCounts) ||
        !Object.values(value.fallbackCounts).every(count) ||
        typeof value.emptyText !== 'boolean' ||
        typeof value.integrityPassed !== 'boolean' ||
        !Array.isArray(value.warnings) ||
        !value.warnings.every(text) ||
        !Array.isArray(value.samples) ||
        value.samples.length > 20 ||
        !value.samples.every(validSample)
      )
        throw new Error('切片预检响应格式不符合约定，请重新读取原任务。');
      return value;
    },
  };
}
