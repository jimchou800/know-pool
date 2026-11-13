import { afterAll, beforeEach, expect, it } from 'vitest';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createSystemClient } from '../system/session';
import { createDatasetClient, trimName } from './client';

const id = '00000000-0000-4000-8000-000000000001';
const record = {
  id,
  name: '数据集',
  description: '',
  createdAt: '2026-11-13T00:00:00Z',
  updatedAt: '2026-11-13T00:00:00Z',
  fileCount: 0,
  totalBytes: 0,
};
const token = '固定保护令牌'.repeat(10);
const operations = [
  'listDatasets',
  'createDataset',
  'getDataset',
  'updateDataset',
];
const methods = ['get', 'post', 'get', 'patch'];
const prefixes = operations.map(
  (_, index) =>
    `#/paths/~1datasets${index >= 2 ? '~1{datasetId}' : ''}/${methods[index]}`,
);
const schema = (
  name,
  properties,
  extras = ['type', 'required', 'additionalProperties'],
) => [
  ...extras.map((item) => `#/components/schemas/${name}/${item}`),
  ...properties.map(
    (item) => `#/components/schemas/${name}/properties/${item}`,
  ),
];
const datasetConstraints = schema('Dataset', [
  'id/type',
  'id/format',
  'name/type',
  'name/minLength',
  'name/maxLength',
  'description/type',
  'description/maxLength',
  'createdAt/type',
  'createdAt/format',
  'createdAt/pattern',
  'updatedAt/type',
  'updatedAt/format',
  'updatedAt/pattern',
  'fileCount/type',
  'fileCount/minimum',
  'fileCount/maximum',
  'totalBytes/type',
  'totalBytes/minimum',
  'totalBytes/maximum',
]);
const errorConstraints = [
  ...schema('Error', [
    'requestId/type',
    'requestId/format',
    'code/type',
    'code/enum',
    'message/type',
    'fieldErrors/type',
    'retryable/type',
  ]),
  ...schema('FieldError', ['field/type', 'code/type', 'message/type']),
];
const evidence = [];
it('Unicode空白采用White_Space精确集合，不将BOM或控制分隔符误当空白', () => {
  expect(trimName('\u0085\u00a0\u3000资料\u202f\u0085')).toBe('资料');
  expect(trimName('\ufeff资料\u001c')).toBe('\ufeff资料\u001c');
});
let response;
let requests;
let system;
let api;
let failure;
beforeEach(async () => {
  response = record;
  requests = [];
  failure = null;
  system = createSystemClient(async (config) => {
    if (config.url === '/session')
      return {
        data: { sessionId: id, csrfToken: token, expiresAt: record.createdAt },
        status: 200,
        config,
      };
    if (config.url === '/system/status')
      return {
        data: {
          applicationVersion: '0.1.0',
          startedAt: record.createdAt,
          writeState: 'ready',
          dataDirectory: '测试目录',
          schemaVersion: 1,
          warnings: [],
        },
        status: 200,
        config,
      };
    requests.push(config);
    if (failure) throw failure;
    return {
      data: response,
      status: config.method === 'post' ? 201 : 200,
      config,
    };
  });
  await system.connect();
  api = createDatasetClient(system);
});
const invoke = (index) =>
  [
    () => api.list(),
    () => api.create({ name: '数据集' }),
    () => api.get(id),
    () => api.update(id, { description: '' }),
  ][index]();
function observed(index, name, constraintIds) {
  evidence.push({
    name: `${operations[index]}：${name}`,
    operationIds: [operations[index]],
    constraintIds,
    passed: true,
    requests: requests.map((config) => ({
      method: config.method.toUpperCase(),
      path: config.baseURL + config.url,
      params: config.params,
      body: config.data ? JSON.parse(config.data) : undefined,
      headers: Object.keys(config.headers.toJSON()),
      withCredentials: config.withCredentials,
    })),
    assertions: [
      '使用真实Axios适配器捕获请求；断言响应消费或拒绝，保护令牌只记录头名称，不记录真实值',
    ],
  });
}
it.each(operations)('%s成功请求完整响应按稳定标识消费', async (operation) => {
  const index = operations.indexOf(operation);
  if (index === 0) response = { items: [record], nextCursor: null };
  await expect(invoke(index)).resolves.toEqual(response);
  const actual = requests[0];
  expect(actual.method).toBe(methods[index]);
  expect(actual.url).toBe(index < 2 ? '/datasets' : `/datasets/${id}`);
  expect(actual.withCredentials).toBe(true);
  if (index === 1 || index === 3) {
    expect(actual.headers.get('X-CSRF-Token')).toBe(token);
    expect(actual.headers.get('Content-Type')).toBe('application/json');
  } else expect(actual.data).toBeUndefined();
  observed(index, '实际请求和完整响应消费', [
    ...datasetConstraints,
    ...[
      'method',
      'path',
      'parameters',
      'security',
      `responses/${index === 1 ? 201 : 200}`,
    ].map((part) => `${prefixes[index]}/${part}`),
    ...(index === 0
      ? schema('DatasetPage', [
          'items/type',
          'nextCursor/anyOf',
          'nextCursor/anyOf/0/type',
          'nextCursor/anyOf/1/type',
        ])
      : []),
    ...(index >= 2
      ? ['required', 'schema/type', 'schema/format'].map(
          (part) => `${prefixes[index]}/parameters/0/${part}`,
        )
      : []),
    ...([1, 3].includes(index)
      ? ['required', 'schema/type', 'schema/minLength'].map(
          (part) =>
            `${prefixes[index]}/parameters/${index === 1 ? 0 : 1}/${part}`,
        )
      : []),
    ...([1, 3].includes(index)
      ? ['requestBody', 'requestBody/required'].map(
          (part) => `${prefixes[index]}/${part}`,
        )
      : []),
  ]);
});
const malformed = [
  null,
  [],
  { ...record, extra: true },
  { ...record, name: '' },
  { ...record, name: '字'.repeat(121) },
  { ...record, name: 2 },
  { ...record, description: null },
  { ...record, description: '字'.repeat(2001) },
  { ...record, id: '无效' },
  { ...record, id: 2 },
  { ...record, createdAt: '2026-02-30T00:00:00Z' },
  { ...record, updatedAt: '2026-11-13T00:00:00+00:00' },
  { ...record, fileCount: -1 },
  { ...record, totalBytes: 9007199254740992 },
  { ...record, totalBytes: '0' },
  { ...record, fileCount: 0.5 },
  Object.fromEntries(Object.entries(record).filter(([key]) => key !== 'name')),
];
it.each(
  operations.flatMap((operation, index) =>
    malformed.map((value, caseIndex) => [operation, index, caseIndex, value]),
  ),
)('%s拒绝非法响应样例%d/%d', async (_, index, _case, value) => {
  response = index === 0 ? { items: [value], nextCursor: null } : value;
  await expect(invoke(index)).rejects.toThrow('响应格式');
});
it('响应安全整数、Unicode码点上限与说明边界可完整消费', async () => {
  response = {
    ...record,
    name: '𠮷'.repeat(120),
    description: '𠮷'.repeat(2000),
    fileCount: Number.MAX_SAFE_INTEGER,
    totalBytes: Number.MAX_SAFE_INTEGER,
  };
  for (let index = 1; index < 4; index++)
    await expect(invoke(index)).resolves.toEqual(response);
  response = { items: [response], nextCursor: '下一页' };
  await expect(api.list()).resolves.toEqual(response);
});
it.each([
  { items: [], nextCursor: 2 },
  { items: {}, nextCursor: null },
  { items: [], nextCursor: null, extra: true },
  { items: [] },
])('分页拒绝非法外层结构%j', async (value) => {
  response = value;
  await expect(api.list()).rejects.toThrow('响应格式');
});
it('分页游标不透明原样传递，空列表与终页区分', async () => {
  response = { items: [], nextCursor: 'opaque +/下一页' };
  await expect(api.list()).resolves.toEqual(response);
  response = { items: [], nextCursor: null };
  await expect(api.list('opaque +/下一页')).resolves.toEqual(response);
  expect(requests[0].params).toEqual({ limit: 20 });
  expect(requests[1].params).toEqual({ limit: 20, cursor: 'opaque +/下一页' });
  for (const value of ['', 2, '字'.repeat(2049)])
    await expect(api.list(value)).rejects.toThrow('分页位置');
  observed(0, '默认分页与不透明游标及非法游标边界', [
    '#/components/parameters/Limit/required',
    ...['type', 'minimum', 'maximum', 'default'].map(
      (part) => `#/components/parameters/Limit/schema/${part}`,
    ),
    '#/components/parameters/Cursor/required',
    ...['type', 'minLength', 'maxLength'].map(
      (part) => `#/components/parameters/Cursor/schema/${part}`,
    ),
  ]);
});
it.each([1, 3])('写入%d请求省略保持清除及名称说明边界', async (index) => {
  const call = (data) =>
    index === 1 ? api.create(data) : api.update(id, data);
  for (const data of [
    { name: ' 资料 ' },
    { name: '𠮷'.repeat(120), description: '𠮷'.repeat(2000) },
    ...(index === 3 ? [{ description: '' }] : []),
  ]) {
    await call(data);
    expect(JSON.parse(requests.at(-1).data)).toEqual(data);
  }
  for (const data of [
    null,
    [],
    {},
    { description: null },
    { name: null },
    { name: 2 },
    { name: '' },
    { name: '   ' },
    { name: '字'.repeat(121) },
    { name: '有效', description: '字'.repeat(2001) },
    { name: '有效', unknown: true },
  ])
    await expect(call(data)).rejects.toThrow('有效');
  const name = index === 1 ? 'NameDescriptionInput' : 'NameDescriptionPatch';
  observed(
    index,
    '写入对象边界与省略保持空串清除',
    schema(
      name,
      [
        'name/type',
        'name/minLength',
        'name/maxLength',
        'description/type',
        'description/maxLength',
      ],
      [
        'type',
        'additionalProperties',
        index === 1 ? 'required' : 'minProperties',
      ],
    ),
  );
});
it('非法标识与错配响应不覆盖所选资源，会话未建立不发送写请求', async () => {
  for (const value of ['', 2, '非标识']) {
    await expect(api.get(value)).rejects.toThrow('标识');
    await expect(api.update(value, { name: '名称' })).rejects.toThrow('标识');
  }
  response = { ...record, id: '00000000-0000-4000-8000-000000000002' };
  await expect(api.get(id)).rejects.toThrow('响应格式');
  await expect(api.update(id, { name: '名称' })).rejects.toThrow('响应格式');
  await expect(
    createDatasetClient(createSystemClient()).create({ name: '名称' }),
  ).rejects.toThrow('会话已失效');
});
it.each(
  operations.flatMap((operation, index) =>
    [
      400,
      401,
      403,
      404,
      429,
      500,
      503,
      ...([1, 3].includes(index) ? [409, 422] : []),
    ].map((code) => [operation, index, code]),
  ),
)('%s统一错误%d/%d有非空字段反馈且不重试', async (_, index, code) => {
  failure = new Error();
  failure.response = {
    status: code,
    data: {
      requestId: id,
      code: code === 409 ? 'NAME_EXISTS' : 'INVALID_REQUEST',
      message: '请检查请求',
      fieldErrors: [
        { field: '/name', code: 'INVALID_REQUEST', message: '名称无效' },
      ],
      retryable: false,
    },
  };
  await expect(invoke(index)).rejects.toMatchObject({
    message: `请检查请求 请求标识：${id}`,
    fieldErrors: failure.response.data.fieldErrors,
  });
  expect(requests).toHaveLength(1);
  observed(index, `统一错误${code}及非空字段反馈`, [
    ...errorConstraints,
    `${prefixes[index]}/responses/${code}`,
  ]);
});
it.each([
  null,
  { requestId: '坏标识' },
  {
    requestId: id,
    code: '原始异常',
    message: '内部路径',
    fieldErrors: [],
    retryable: true,
  },
])('非法错误结构脱敏%j', async (data) => {
  failure = new Error();
  failure.response = { data };
  await expect(api.create({ name: '名称' })).rejects.toThrow(
    '请求未完成，请重新读取记录确认保存结果后重试。',
  );
});
afterAll(() => {
  mkdirSync('artifacts', { recursive: true });
  writeFileSync(
    'artifacts/contract-consumer-02.json',
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        contractSha256: createHash('sha256')
          .update(readFileSync('../docs/agents/api/openapi.yaml'))
          .digest('hex'),
        implementationSha256: execFileSync(
          'python',
          ['../scripts/contract/verify.py', '--snapshot'],
          { encoding: 'utf8' },
        ).trim(),
        scope: operations,
        tests: evidence,
      },
      null,
      2,
    ),
  );
});
