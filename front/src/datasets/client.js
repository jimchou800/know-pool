import { date, exact, uuid } from '../system/session';

export const trimName = (value) =>
  value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '');

const textLength = (value, min, max) =>
  typeof value === 'string' &&
  Array.from(value).length >= min &&
  Array.from(value).length <= max;
const count = (value) => Number.isSafeInteger(value) && value >= 0;
export const validDataset = (value) =>
  exact(value, [
    'id',
    'name',
    'description',
    'createdAt',
    'updatedAt',
    'fileCount',
    'totalBytes',
  ]) &&
  typeof value.id === 'string' &&
  uuid.test(value.id) &&
  textLength(value.name, 1, 120) &&
  trimName(value.name) === value.name &&
  textLength(value.description, 0, 2000) &&
  date(value.createdAt) &&
  date(value.updatedAt) &&
  count(value.fileCount) &&
  count(value.totalBytes);

function verify(value, valid) {
  if (!valid)
    throw new Error('数据集响应格式不符合约定，请重试或检查应用版本。');
  return value;
}
function identifier(id) {
  if (typeof id !== 'string' || !uuid.test(id))
    throw new Error('数据集标识无效，请返回列表重新选择。');
  return `/datasets/${id}`;
}
function input(value, create) {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    !Object.keys(value).length ||
    Object.keys(value).some((key) => !['name', 'description'].includes(key)) ||
    (create && !Object.hasOwn(value, 'name')) ||
    (Object.hasOwn(value, 'name') &&
      (!textLength(value.name, 1, 120) || !trimName(value.name))) ||
    (Object.hasOwn(value, 'description') &&
      !textLength(value.description, 0, 2000))
  )
    throw new Error('请填写有效的名称与说明。');
  return value;
}
export function createDatasetClient(session) {
  return {
    async list(cursor) {
      if (cursor !== undefined && !textLength(cursor, 1, 2048))
        throw new Error('分页位置无效，请返回第一页。');
      const value = await session.request({
        method: 'get',
        url: '/datasets',
        params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
      });
      return verify(
        value,
        exact(value, ['items', 'nextCursor']) &&
          Array.isArray(value.items) &&
          value.items.every(validDataset) &&
          (value.nextCursor === null || typeof value.nextCursor === 'string'),
      );
    },
    async get(id) {
      const value = await session.request({
        method: 'get',
        url: identifier(id),
      });
      return verify(value, validDataset(value) && value.id === id);
    },
    async create(data) {
      const value = await session.request({
        method: 'post',
        url: '/datasets',
        data: input(data, true),
      });
      return verify(value, validDataset(value));
    },
    async update(id, data) {
      const value = await session.request({
        method: 'patch',
        url: identifier(id),
        data: input(data, false),
      });
      return verify(value, validDataset(value) && value.id === id);
    },
  };
}
