import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';

import {
  id,
  otherId,
  time,
  record,
  processing,
  task,
  config,
  source,
  file,
  setup,
} from '../../test-fixtures/knowledge-bases';
const observations = [];
function observed(operationId, request) {
  observations.push({
    operationId,
    method: request.method,
    url: request.url,
    params: request.params ?? {},
    headers: request.headers.toJSON(),
    body: request.data ?? null,
    status: request.observed.status,
    response: request.observed.data,
  });
}
it('列表及详情消费三个独立状态，真实请求携带分页与规范标识', async () => {
  const s = await setup(() => ({ items: [record], nextCursor: null }));
  expect(await s.api.list('下一页 +/')).toEqual({
    items: [record],
    nextCursor: null,
  });
  expect(s.requests[0].params).toEqual({ limit: 20, cursor: '下一页 +/' });
  observed('listKnowledgeBases', s.requests[0]);
  s.reply(record);
  expect(await s.api.get(id.toUpperCase())).toEqual(record);
  expect(s.requests.at(-1).url).toBe(`/knowledge-bases/${id}`);
  observed('getKnowledgeBase', s.requests.at(-1));
});
it.each([
  'queued',
  'running',
  'succeeded',
  'failed',
  'cancelled',
  'interrupted',
])('同键重放与原任务查询消费创建%s，失败不产生成功资源', async (state) => {
  const value = {
    ...task,
    state,
    phase: ['queued', 'running'].includes(state)
      ? state === 'queued'
        ? 'queued'
        : 'initializing'
      : 'completed',
    startedAt: state === 'queued' ? null : time,
    finishedAt: ['queued', 'running'].includes(state) ? null : time,
    resultResourceId: state === 'succeeded' ? id : null,
    failure: ['failed', 'interrupted'].includes(state)
      ? { code: 'VECTOR_UNAVAILABLE', message: '受控向量故障', retryable: true }
      : null,
    warnings: ['脱敏核查提示'],
  };
  const s = await setup(() => ({ task: value }));
  expect(
    await s.api.create(
      { name: '知识库', processing, datasetIds: [otherId] },
      id,
    ),
  ).toEqual({ task: value });
  observed('createKnowledgeBase', s.requests.at(-1));
  s.reply(value);
  expect(await s.api.task(otherId)).toEqual(value);
  observed('getTask', s.requests.at(-1));
  if (state !== 'succeeded') {
    s.reply({ ...value, resultResourceId: id });
    await expect(s.api.task(otherId)).rejects.toThrow('响应格式');
  }
});
const badBase = [
  ['id', null],
  ['name', ''],
  ['name', '长'.repeat(121)],
  ['name', ' 名称 '],
  ['description', null],
  ['description', '长'.repeat(2001)],
  ['createdAt', '无效'],
  ['updatedAt', '2026-01-01T00:00:00+00:00'],
  ['publicationState', 'invalid'],
  ['publishedAt', 2],
  ['offlineAt', '无效'],
  ['offlineReason', 'invalid'],
  ['availability', 'invalid'],
  ['availabilityReason', 1],
  ['lastCheckedAt', '无效'],
  ['processingState', 'invalid'],
  ['targetConfigVersionId', null],
  ['effectiveConfigVersionId', 'bad'],
  ['effectiveIndexVersionId', 'bad'],
  ['includedFileCount', -1],
  ['searchableFileCount', Number.MAX_SAFE_INTEGER + 1],
];
it.each(badBase)('拒绝知识库响应非法字段%s', async (key, value) => {
  const s = await setup(() => ({ ...record, [key]: value }));
  await expect(s.api.get(id)).rejects.toThrow('响应格式');
  s.reply({ items: [{ ...record, [key]: value }], nextCursor: null });
  await expect(s.api.list()).rejects.toThrow('响应格式');
});
it('拒绝缺失字段、未知字段、跨库资源与非法分页，错误不冒充空态', async () => {
  const s = await setup(() => ({ ...record, extra: true }));
  await expect(s.api.get(id)).rejects.toThrow('响应格式');
  const missing = { ...record };
  delete missing.name;
  s.reply(missing);
  await expect(s.api.get(id)).rejects.toThrow('响应格式');
  s.reply({ ...record, id: otherId });
  await expect(s.api.get(id)).rejects.toThrow('响应格式');
  for (const bad of ['', null, 1, '长'.repeat(2049)])
    await expect(s.api.list(bad)).rejects.toThrow('分页');
  for (const method of ['get', 'config', 'sources', 'files'])
    await expect(s.api[method]('bad')).rejects.toThrow('标识');
  await expect(s.api.task('bad')).rejects.toThrow('标识');
  for (const reply of [
    { items: [record] },
    { items: [record], nextCursor: 1 },
    { items: 'bad', nextCursor: null },
    null,
  ]) {
    s.reply(reply);
    await expect(s.api.list()).rejects.toThrow('响应格式');
  }
});
const malformedInputs = [
  null,
  {},
  { name: '', processing },
  { name: ' ', processing },
  { name: '长'.repeat(121), processing },
  { name: '库', processing, extra: 1 },
  { name: '库', description: null, processing },
  { name: '库', description: '长'.repeat(2001), processing },
  { name: '库', processing, datasetIds: [id, id] },
  { name: '库', processing, datasetIds: 'bad' },
  { name: '库', processing, datasetIds: ['bad'] },
  { name: '库', processing, datasetIds: Array(101).fill(id) },
];
it.each(malformedInputs)('非法创建输入在发出请求前拒绝 %#', async (data) => {
  const s = await setup(() => ({ task }));
  await expect(s.api.create(data, id)).rejects.toThrow('请填写');
  expect(s.requests).toHaveLength(0);
});
const badProcessing = [
  ['chunking', null],
  ['chunking.strategy', 'bad'],
  ['chunking.overlapCodePoints', 2000],
  ['chunking.overlapCodePoints', -1],
  ['chunking.overlapCodePoints', 0.1],
  ['chunking.removePageHeadersFooters', null],
  ['chunking.customMetadata', null],
  ['chunking.customMetadata', []],
  ['chunking.customMetadata', { bad: '内容' }],
  ['chunking.customMetadata', { user_a: '长'.repeat(2001) }],
  [
    'chunking.customMetadata',
    Object.fromEntries(
      Array.from({ length: 21 }, (_, i) => ['user_' + i, '值']),
    ),
  ],
  ['chunking.embeddedMetadataFields', ['user_missing']],
  ['chunking.embeddedMetadataFields', ['user_topic', 'user_topic']],
  ['embedding.modelConfigVersionId', 'bad'],
  ['embedding.dimensions', 0],
  ['embedding.dimensions', 65537],
  ['embedding.distance', 'bad'],
  ['embedding.similarityThreshold', NaN],
  ['embedding.candidatesPerQuery', 0],
  ['embedding.candidatesPerQuery', 1001],
  ['embedding.modelSnapshot.model', ''],
  ['embedding.modelSnapshot.model', '长'.repeat(201)],
  ['embedding.modelSnapshot.endpoint', 'file:///data'],
  ['embedding.modelSnapshot.endpoint', 'http://'],
  ['embedding.extra', 1],
];
function changed(value, path, replacement) {
  const copy = structuredClone(value);
  const parts = path.split('.');
  const key = parts.pop();
  let current = copy;
  for (const p of parts) current = current[p];
  current[key] = replacement;
  return copy;
}
it.each(badProcessing)(
  '非法处理配置%s拒绝且不发出创建请求',
  async (path, value) => {
    const s = await setup(() => ({ task }));
    await expect(
      s.api.create(
        { name: '库', processing: changed(processing, path, value) },
        id,
      ),
    ).rejects.toThrow('完整处理配置');
    expect(s.requests).toHaveLength(0);
  },
);
it.each([
  ['kind', 'model_test'],
  ['id', 'bad'],
  ['state', 'bad'],
  ['phase', 'bad'],
  ['createdAt', 'bad'],
  ['startedAt', 'bad'],
  ['finishedAt', 'bad'],
  ['fileId', 'bad'],
  ['targetContentVersionId', 'bad'],
  ['targetConfigVersionId', 'bad'],
  ['retryOfTaskId', 'bad'],
  ['attempt', 4],
  ['progressCompleted', -1],
  ['progressTotal', 'bad'],
  ['warnings', [1]],
  ['failure', {}],
])('原任务查询拒绝%s非法响应', async (key, value) => {
  const s = await setup(() => ({ ...task, [key]: value }));
  await expect(s.api.task(otherId)).rejects.toThrow('响应格式');
});
it('配置快照与来源防止跨资源覆盖，非法更新与关联不外发', async () => {
  const s = await setup(() => ({ ...config, knowledgeBaseId: otherId }));
  await expect(s.api.config(id)).rejects.toThrow('响应格式');
  for (const data of [
    null,
    {},
    { extra: 1 },
    { name: '' },
    { name: ' ' },
    { description: null },
  ])
    await expect(s.api.update(id, data)).rejects.toThrow('名称');
  for (const data of [
    null,
    {},
    { type: 'invalid', resourceId: id },
    { type: 'file', resourceId: 'bad' },
    { type: 'dataset', resourceId: id, extra: 1 },
  ])
    await expect(s.api.addSource(id, data)).rejects.toThrow('请选择');
  s.reply({ ...source, knowledgeBaseId: otherId });
  await expect(
    s.api.addSource(id, { type: 'file', resourceId: otherId }),
  ).rejects.toThrow('响应格式');
  s.reply({ items: [{ ...file, fileId: 'bad' }], nextCursor: null });
  await expect(s.api.files(id)).rejects.toThrow('响应格式');
});
it('原任务查询显式读取索引排队意图，空标识配置响应按协议错误拒绝', async () => {
  const s = await setup(() => file.task);
  expect(await s.api.task(otherId, 'index_file')).toEqual(file.task);
  observed('getTask', s.requests.at(-1));
  s.reply({ ...config, knowledgeBaseId: null });
  await expect(s.api.config(id)).rejects.toThrow('知识库响应格式');
});
it('完整配置保留显式边界值与元信息，三种策略及度量均可消费', async () => {
  const s = await setup(() => ({ task }));
  for (const [strategy, distance] of [
    ['structure', 'cosine'],
    ['fixed', 'dot'],
    ['page', 'euclidean'],
  ]) {
    const input = structuredClone(processing);
    input.chunking.strategy = strategy;
    input.embedding.distance = distance;
    input.chunking.overlapCodePoints = 0;
    input.embedding.dimensions = 65536;
    input.embedding.candidatesPerQuery = 1000;
    input.embedding.similarityThreshold = -1;
    await s.api.create({ name: '库', description: '', processing: input }, id);
    observed('createKnowledgeBase', s.requests.at(-1));
    s.reply({ ...config, input });
    await s.api.config(id);
    observed('getProcessingConfig', s.requests.at(-1));
    s.reply({ task });
  }
});
const calls = [
  ['listKnowledgeBases', (a) => a.list()],
  ['createKnowledgeBase', (a) => a.create({ name: '库', processing }, id)],
  ['getKnowledgeBase', (a) => a.get(id)],
  ['updateKnowledgeBase', (a) => a.update(id, { description: '' })],
  ['getProcessingConfig', (a) => a.config(id)],
  [
    'addKnowledgeBaseSource',
    (a) => a.addSource(id, { type: 'file', resourceId: otherId }),
  ],
  ['listKnowledgeBaseSources', (a) => a.sources(id, '游标')],
  ['listKnowledgeBaseFiles', (a) => a.files(id, '游标')],
  ['getTask', (a) => a.task(otherId)],
];
it.each(calls)('%s真实请求统一错误消费及请求标识', async (name, call) => {
  const s = await setup(() => null);
  for (const status of [400, 401, 403, 404, 409, 422, 429, 500, 503]) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code: status === 409 ? 'CONFLICT' : 'INVALID_REQUEST',
        message: '受控错误',
        fieldErrors: [
          { field: '/name', code: 'INVALID_REQUEST', message: '字段错误' },
        ],
        retryable: false,
      },
    });
    await expect(call(s.api)).rejects.toThrow('受控错误');
    observed(name, s.requests.at(-1));
  }
});
afterAll(() => {
  execFileSync('python', ['scripts/settings-evidence.py', '07'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  });
});
it('创建空库真实提交完整配置及幂等键，受理后查询同一任务至成功', async () => {
  const s = await setup(() => ({ task }));
  expect(
    await s.api.create({ name: '空知识库', processing, datasetIds: [] }, id),
  ).toEqual({ task });
  expect(JSON.parse(s.requests[0].data)).toEqual({
    name: '空知识库',
    processing,
    datasetIds: [],
  });
  expect(s.requests[0].headers.get('Idempotency-Key')).toBe(id);
  expect(s.requests[0].headers.get('X-CSRF-Token')).toBeTruthy();
  observed('createKnowledgeBase', s.requests[0]);
  const done = {
    ...task,
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    resultResourceId: id,
  };
  s.reply(done);
  expect(await s.api.task(otherId)).toEqual(done);
  observed('getTask', s.requests.at(-1));
  s.reply({ ...done, resultResourceId: otherId });
  await expect(s.api.task(otherId)).rejects.toThrow('响应格式');
});
it('名称说明维护、目标快照及两类来源消费，文件保持待处理与未生效', async () => {
  const s = await setup(() => ({
    ...record,
    name: '新名称',
    description: '说明',
  }));
  expect(
    (await s.api.update(id, { name: '新名称', description: '说明' })).name,
  ).toBe('新名称');
  expect(JSON.parse(s.requests.at(-1).data)).toEqual({
    name: '新名称',
    description: '说明',
  });
  observed('updateKnowledgeBase', s.requests.at(-1));
  s.reply(config);
  expect(await s.api.config(id)).toEqual(config);
  observed('getProcessingConfig', s.requests.at(-1));
  for (const type of ['file', 'dataset']) {
    s.reply({ ...source, type });
    expect(await s.api.addSource(id, { type, resourceId: otherId })).toEqual({
      ...source,
      type,
    });
    observed('addKnowledgeBaseSource', s.requests.at(-1));
  }
  s.reply({ items: [source], nextCursor: '游标' });
  expect((await s.api.sources(id)).items).toEqual([source]);
  observed('listKnowledgeBaseSources', s.requests.at(-1));
  s.reply({ items: [file], nextCursor: null });
  expect((await s.api.files(id, '游标')).items[0].updateState).toBe('queued');
  observed('listKnowledgeBaseFiles', s.requests.at(-1));
});
