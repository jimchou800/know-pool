import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { createSettingsClient } from './client';
import { createSystemClient } from '../system/session';
const id = '00000000-0000-4000-8000-000000000061';
const time = '2026-01-01T00:00:00Z';
const evidence = [];
const captured = new WeakMap();
function observed(name, label, request) {
  const response = captured.get(request);
  evidence.push({
    operationId: name,
    label,
    method: request.method,
    url: request.url,
    params: request.params ?? {},
    headers: request.headers.toJSON(),
    body: request.data ?? null,
    status: response.status,
    response: response.data,
  });
}
const initial = {
  kind: 'generation',
  versionId: id,
  endpoint: null,
  model: null,
  credential: { configured: false, mask: null },
  contextWindowTokens: null,
  maxOutputTokens: null,
  complete: false,
  updatedAt: time,
};
it('读取持久未设置配置并以真实Axios完整保存生成配置', async () => {
  const requests = [];
  const session = createSystemClient(async (config) => {
    requests.push(config);
    const data =
      config.url === '/session'
        ? { sessionId: id, csrfToken: '固定保护'.repeat(12), expiresAt: time }
        : config.url === '/system/status'
          ? {
              applicationVersion: '0.1',
              startedAt: time,
              writeState: 'ready',
              dataDirectory: '测试',
              schemaVersion: 1,
              warnings: [],
            }
          : initial;
    return { data, status: 200, headers: {}, config };
  });
  await session.connect();
  const api = createSettingsClient(session);
  expect(await api.get('generation')).toEqual(initial);
  await api.save('generation', {
    endpoint: 'http://127.0.0.1:4567/v1',
    model: '测试模型',
    contextWindowTokens: 1024,
    maxOutputTokens: 16,
    credential: { action: 'keep' },
  });
  expect(requests.at(-1).method).toBe('put');
  expect(JSON.parse(requests.at(-1).data)).toEqual({
    endpoint: 'http://127.0.0.1:4567/v1',
    model: '测试模型',
    contextWindowTokens: 1024,
    maxOutputTokens: 16,
    credential: { action: 'keep' },
  });
  expect(requests.at(-1).headers.get('X-CSRF-Token')).toBe(
    '固定保护'.repeat(12),
  );
});

const complete = (kind = 'generation') => ({
  ...initial,
  kind,
  endpoint: 'http://127.0.0.1:4567/v1',
  model: ' 未核实模型 ',
  credential: { configured: true, mask: '【密钥已设置】' },
  contextWindowTokens: kind === 'generation' ? 1024 : null,
  maxOutputTokens: kind === 'generation' ? 16 : null,
  complete: true,
});
const vector = {
  endpoint: 'http://127.0.0.1:4567',
  credential: { configured: false, mask: null },
  configured: true,
  updatedAt: time,
};
const task = {
  id,
  kind: 'model_test',
  state: 'queued',
  phase: 'queued',
  createdAt: time,
  startedAt: null,
  finishedAt: null,
  knowledgeBaseId: null,
  fileId: null,
  targetContentVersionId: null,
  targetConfigVersionId: id,
  retryOfTaskId: null,
  attempt: 0,
  progressCompleted: 0,
  progressTotal: null,
  failure: null,
  warnings: [],
  resultResourceId: null,
};
async function setup(response) {
  const requests = [];
  let value = response;
  let failure;
  const session = createSystemClient(async (config) => {
    if (config.url === '/session')
      return {
        data: {
          sessionId: id,
          csrfToken: '固定保护'.repeat(12),
          expiresAt: time,
        },
        status: 200,
        headers: {},
        config,
      };
    if (config.url === '/system/status')
      return {
        data: {
          applicationVersion: '0.1',
          startedAt: time,
          writeState: 'ready',
          dataDirectory: '测试',
          schemaVersion: 1,
          warnings: [],
        },
        status: 200,
        headers: {},
        config,
      };
    requests.push(config);
    if (failure) {
      captured.set(config, failure.response);
      throw failure;
    }
    const status = config.url.endsWith('/test') ? 202 : 200;
    captured.set(config, { data: value, status });
    return { data: value, status, headers: {}, config };
  });
  await session.connect();
  return {
    api: createSettingsClient(session),
    requests,
    set: (value) => {
      response = value;
    },
    reply(next) {
      value = next;
    },
    fail(next) {
      failure = next;
    },
  };
}
const input = (kind = 'generation', credential = { action: 'keep' }) => ({
  endpoint: 'http://127.0.0.1:4567/v1',
  model: ' 未核实模型 ',
  contextWindowTokens: kind === 'generation' ? 1024 : null,
  maxOutputTokens: kind === 'generation' ? 16 : null,
  credential,
});
const calls = [
  [
    'getGenerationModel',
    'get',
    '/settings/models/generation',
    (api) => api.get('generation'),
    () => complete(),
  ],
  [
    'updateGenerationModel',
    'put',
    '/settings/models/generation',
    (api) => api.save('generation', input()),
    () => complete(),
  ],
  [
    'testGenerationModel',
    'post',
    '/settings/models/generation/test',
    (api) => api.test('generation', id, true),
    () => ({ task }),
  ],
  [
    'getEmbeddingModel',
    'get',
    '/settings/models/embedding',
    (api) => api.get('embedding'),
    () => complete('embedding'),
  ],
  [
    'updateEmbeddingModel',
    'put',
    '/settings/models/embedding',
    (api) => api.save('embedding', input('embedding')),
    () => complete('embedding'),
  ],
  [
    'testEmbeddingModel',
    'post',
    '/settings/models/embedding/test',
    (api) => api.test('embedding', id, true),
    () => ({ task }),
  ],
  [
    'listEmbeddingModelVersions',
    'get',
    '/settings/models/embedding/versions',
    (api) => api.versions(),
    () => ({ items: [complete('embedding')], nextCursor: null }),
  ],
  [
    'getEmbeddingModelVersion',
    'get',
    `/settings/models/embedding/versions/${id}`,
    (api) => api.version(id),
    () => complete('embedding'),
  ],
  [
    'updateEmbeddingVersionCredential',
    'put',
    `/settings/models/embedding/versions/${id}/credential`,
    (api) => api.rotate(id, { action: 'clear' }),
    () => complete('embedding'),
  ],
  [
    'getConnectionTestResult',
    'get',
    `/tasks/${id}/connection-test`,
    (api) => api.result(id),
    () => ({
      available: false,
      checkedAt: time,
      message: '连接不可用',
      mayHaveIncurredCost: true,
    }),
  ],
  [
    'getVectorConfig',
    'get',
    '/settings/vector-store',
    (api) => api.get('vector'),
    () => vector,
  ],
  [
    'updateVectorConfig',
    'put',
    '/settings/vector-store',
    (api) =>
      api.save('vector', {
        endpoint: vector.endpoint,
        credential: { action: 'clear' },
      }),
    () => vector,
  ],
  [
    'testVectorConfig',
    'post',
    '/settings/vector-store/test',
    (api) => api.test('vector', id),
    () => ({
      task: {
        ...task,
        kind: 'vector_connection_test',
        targetConfigVersionId: null,
      },
    }),
  ],
  ['getTask', 'get', `/tasks/${id}`, (api) => api.task(id), () => task],
];
it.each(calls)(
  '%s严格构造方法路径、请求体与会话保护并消费响应',
  async (name, method, url, invoke, response) => {
    const { api, requests } = await setup(response());
    expect(await invoke(api)).toEqual(response());
    const request = requests[0];
    expect(request.method).toBe(method);
    expect(request.url).toBe(url);
    expect(request.withCredentials).toBe(true);
    expect(request.headers.has('X-CSRF-Token')).toBe(
      ['put', 'post'].includes(method),
    );
    if (method === 'post') {
      expect(request.headers.get('Idempotency-Key')).toBe(id);
      expect(request.data).toBe(
        name === 'testVectorConfig'
          ? undefined
          : JSON.stringify({ allowChargedCall: true }),
      );
    }
    if (method === 'get') expect(request.data).toBeUndefined();
    observed(name, name, request);
  },
);
it.each(
  calls.flatMap((call) =>
    [
      400,
      401,
      403,
      404,
      429,
      500,
      503,
      ...(call[1] === 'get'
        ? call[0] === 'getConnectionTestResult'
          ? [409]
          : []
        : [409, 422]),
    ].map((code) => [...call, code]),
  ),
)('%s消费统一错误状态%s', async (name, method, url, invoke, response, code) => {
  const system = await setup(response());
  const field = '/credential/action';
  system.fail({
    response: {
      status: code,
      data: {
        requestId: id,
        code: code === 409 ? 'INVALID_STATE' : 'INVALID_REQUEST',
        message: '请检查配置',
        fieldErrors: [{ field, code: 'INVALID_REQUEST', message: '操作无效' }],
        retryable: false,
      },
    },
  });
  await expect(invoke(system.api)).rejects.toMatchObject({
    message: `请检查配置 请求标识：${id}`,
    fieldErrors: [{ field, code: 'INVALID_REQUEST', message: '操作无效' }],
  });
  expect(system.requests).toHaveLength(1);
  observed(name, `${name}:${code}`, system.requests[0]);
});
it.each(['keep', 'replace', 'clear'])(
  '三种配置与历史凭据精确发送%s动作，无掩码回传',
  async (action) => {
    const credential =
      action === 'replace' ? { action, value: ' *固定测试凭据* ' } : { action };
    for (const kind of ['generation', 'embedding', 'vector']) {
      const system = await setup(kind === 'vector' ? vector : complete(kind));
      await system.api.save(
        kind,
        kind === 'vector'
          ? { endpoint: vector.endpoint, credential }
          : input(kind, credential),
      );
      expect(JSON.parse(system.requests[0].data).credential).toEqual(
        credential,
      );
      observed(
        {
          generation: 'updateGenerationModel',
          embedding: 'updateEmbeddingModel',
          vector: 'updateVectorConfig',
        }[kind],
        action,
        system.requests[0],
      );
    }
    const system = await setup(complete('embedding'));
    await system.api.rotate(id, credential);
    expect(JSON.parse(system.requests[0].data)).toEqual(credential);
    observed('updateEmbeddingVersionCredential', action, system.requests[0]);
  },
);
it.each([
  null,
  {},
  { action: 'keep', value: '多余' },
  { action: 'clear', value: '多余' },
  { action: 'unknown' },
  { action: 'replace' },
  { action: 'replace', value: '' },
  { action: 'replace', value: '【密钥已设置】' },
  { action: 'replace', value: 'a'.repeat(8193) },
  { action: 'replace', value: 5 },
  { action: 'replace', value: '固定', extra: true },
])('非法凭据动作%j在发送前拒绝', async (credential) => {
  const system = await setup(complete('embedding'));
  await expect(system.api.rotate(id, credential)).rejects.toThrow('密钥');
  expect(system.requests).toHaveLength(0);
});
it.each([
  'file:///tmp',
  'https://user:secret@host/v1',
  'https://host/v1?q=1',
  'https://host/#a',
  'http:///',
  'HTTP://host',
  'http://host/a b',
])('拒绝非法基础地址%s', async (endpoint) => {
  const system = await setup(complete());
  expect(() => system.api.save('generation', { ...input(), endpoint })).toThrow(
    '基础地址',
  );
  expect(system.requests).toHaveLength(0);
});
it.each([
  { model: '' },
  { model: '   ' },
  { model: 'a'.repeat(201) },
  { model: 7 },
  { endpoint: 'https://host/v1/chat/completions' },
  { endpoint: 'https://host/v1/embeddings' },
  { contextWindowTokens: 0 },
  { contextWindowTokens: 1.5 },
  { contextWindowTokens: Number.MAX_SAFE_INTEGER + 1 },
  { maxOutputTokens: null },
  { maxOutputTokens: -1 },
  { extra: true },
])('拒绝模型配置非法输入%j', async (changes) => {
  const system = await setup(complete());
  expect(() =>
    system.api.save('generation', { ...input(), ...changes }),
  ).toThrow();
  expect(system.requests).toHaveLength(0);
});
it('预算仅要求正安全整数，不增加上下文与输出大小关系，模型原样保存', async () => {
  const system = await setup(complete());
  await system.api.save('generation', {
    ...input(),
    contextWindowTokens: 1,
    maxOutputTokens: Number.MAX_SAFE_INTEGER,
  });
  expect(JSON.parse(system.requests[0].data)).toMatchObject({
    model: ' 未核实模型 ',
    contextWindowTokens: 1,
    maxOutputTokens: Number.MAX_SAFE_INTEGER,
  });
  expect(() =>
    system.api.save('embedding', { ...input('embedding'), maxOutputTokens: 1 }),
  ).toThrow();
  expect(() =>
    system.api.save('vector', {
      endpoint: vector.endpoint,
      credential: { action: 'keep' },
      model: '额外',
    }),
  ).toThrow();
  expect(() => system.api.get('unknown')).toThrow();
});
it.each([
  null,
  {},
  [],
  { ...complete(), extra: true },
  { ...complete(), kind: 'embedding' },
  { ...complete(), versionId: '坏' },
  { ...complete(), endpoint: 'file:///tmp' },
  { ...complete(), model: 4 },
  { ...complete(), model: '  ' },
  { ...complete(), credential: { configured: true, mask: '真实秘密' } },
  { ...complete(), contextWindowTokens: 0 },
  { ...complete(), maxOutputTokens: 1.5 },
  { ...complete(), complete: false },
  { ...initial, complete: true },
  { ...complete(), updatedAt: '2026-02-30T00:00:00Z' },
])('拒绝非法模型响应%j', async (response) => {
  const { api } = await setup(response);
  await expect(api.get('generation')).rejects.toThrow('响应格式');
});
it.each([
  { ...vector, extra: true },
  { ...vector, configured: false },
  { ...vector, credential: { configured: false, mask: '错误' } },
  { ...vector, endpoint: 3 },
  { ...vector, updatedAt: '坏时间' },
])('拒绝非法向量响应%j', async (response) => {
  const { api } = await setup(response);
  await expect(api.get('vector')).rejects.toThrow('响应格式');
});
it('读取初始向量和历史空预算、分页与大写标识，未知字段及错误身份被拒绝', async () => {
  const system = await setup({ ...vector, endpoint: null, configured: false });
  expect(await system.api.get('vector')).toMatchObject({
    endpoint: null,
    configured: false,
  });
  system.reply({
    items: [{ ...initial, kind: 'embedding' }],
    nextCursor: 'opaque +/游标',
  });
  await system.api.versions('opaque +/游标');
  expect(system.requests.at(-1).params).toEqual({
    limit: 20,
    cursor: 'opaque +/游标',
  });
  system.reply(complete('embedding'));
  await system.api.version(id.toUpperCase());
  await system.api.rotate(id.toUpperCase(), { action: 'keep' });
  for (const response of [
    {
      ...complete('embedding'),
      versionId: '00000000-0000-4000-8000-000000000099',
    },
    { ...complete('embedding'), contextWindowTokens: 1 },
  ]) {
    system.reply(response);
    await expect(system.api.version(id)).rejects.toThrow();
  }
  system.reply({ items: [], nextCursor: 3 });
  await expect(system.api.versions()).rejects.toThrow();
  system.reply({ items: [complete()], nextCursor: null });
  await expect(system.api.versions()).rejects.toThrow();
  for (const cursor of ['', 3, 'a'.repeat(2049)])
    await expect(system.api.versions(cursor)).rejects.toThrow();
  for (const bad of ['坏', null, 42]) {
    await expect(system.api.version(bad)).rejects.toThrow();
    await expect(system.api.rotate(bad, { action: 'keep' })).rejects.toThrow();
    await expect(system.api.task(bad)).rejects.toThrow();
    await expect(system.api.result(bad)).rejects.toThrow();
  }
});
it.each([
  'queued',
  'running',
  'succeeded',
  'failed',
  'interrupted',
  'cancelled',
])('读取测试任务%s和终态失败约束', async (state) => {
  const terminal = ['succeeded', 'failed', 'interrupted', 'cancelled'].includes(
    state,
  );
  const failed = ['failed', 'interrupted'].includes(state);
  const response = {
    ...task,
    state,
    phase: terminal
      ? 'completed'
      : state === 'running'
        ? 'validating'
        : 'queued',
    startedAt: state === 'queued' ? null : time,
    finishedAt: terminal ? time : null,
    failure: failed
      ? { code: 'TASK_INTERRUPTED', message: '连接中断', retryable: false }
      : null,
    warnings: failed ? ['可能已产生费用'] : [],
  };
  const system = await setup(response);
  expect(await system.api.task(id)).toEqual(response);
});
it.each([
  {},
  null,
  [],
  { ...task, extra: true },
  { ...task, id: '坏' },
  { ...task, kind: 'question' },
  { ...task, state: 'unknown' },
  { ...task, phase: 'embedding' },
  { ...task, createdAt: '坏' },
  { ...task, startedAt: 4 },
  { ...task, finishedAt: 4 },
  { ...task, targetConfigVersionId: 3 },
  { ...task, attempt: 4 },
  { ...task, progressCompleted: -1 },
  { ...task, progressTotal: 1.5 },
  { ...task, warnings: [4] },
  { ...task, failure: {} },
  { ...task, state: 'succeeded', finishedAt: time },
  {
    ...task,
    state: 'failed',
    phase: 'completed',
    finishedAt: time,
    failure: null,
  },
  {
    ...task,
    state: 'interrupted',
    phase: 'completed',
    finishedAt: time,
    failure: { code: 'BAD', message: '错误', retryable: false },
  },
])('拒绝非法测试任务响应%j', async (response) => {
  const system = await setup(response);
  await expect(system.api.task(id)).rejects.toThrow();
});
it('模型测试必须显式费用同意，非法任务包装和结果均拒绝', async () => {
  const system = await setup({ task });
  await expect(system.api.test('generation', id, false)).rejects.toThrow(
    '同意',
  );
  await expect(system.api.test('embedding', '坏', true)).rejects.toThrow(
    '标识',
  );
  expect(system.requests).toHaveLength(0);
  for (const response of [
    null,
    {},
    { task, extra: true },
    { task: { ...task, kind: 'vector_connection_test' } },
  ]) {
    system.reply(response);
    await expect(system.api.test('generation', id, true)).rejects.toThrow();
  }
  for (const response of [
    null,
    {},
    {
      available: '是',
      checkedAt: time,
      message: '错误',
      mayHaveIncurredCost: false,
    },
    {
      available: true,
      checkedAt: '坏',
      message: '错误',
      mayHaveIncurredCost: false,
    },
    {
      available: true,
      checkedAt: time,
      message: 3,
      mayHaveIncurredCost: false,
    },
    {
      available: true,
      checkedAt: time,
      message: '错误',
      mayHaveIncurredCost: 3,
    },
  ]) {
    system.reply(response);
    await expect(system.api.result(id)).rejects.toThrow();
  }
});

afterAll(() => {
  execFileSync('python', ['scripts/settings-evidence.py'], {
    input: JSON.stringify(evidence),
    encoding: 'utf8',
  });
});

it('连接测试拒绝错误关联身份与排队开始时间，不把其他资源任务当本次测试', async () => {
  const system = await setup(task);
  for (const response of [
    { ...task, targetConfigVersionId: null },
    { ...task, knowledgeBaseId: id },
    { ...task, fileId: id },
    { ...task, targetContentVersionId: id },
    { ...task, retryOfTaskId: id },
    { ...task, resultResourceId: id },
    { ...task, startedAt: time },
    { ...task, kind: 'vector_connection_test', targetConfigVersionId: id },
  ]) {
    system.reply(response);
    await expect(system.api.task(id)).rejects.toThrow('响应格式');
  }
});

it('消费持久空配置、历史空版本与无凭据配置的真实可空分支', async () => {
  for (const kind of ['generation', 'embedding']) {
    const system = await setup({ ...initial, kind });
    expect(await system.api.get(kind)).toEqual({ ...initial, kind });
    observed(
      kind === 'generation' ? 'getGenerationModel' : 'getEmbeddingModel',
      '持久未设置',
      system.requests.at(-1),
    );
  }
  const system = await setup({ ...vector, endpoint: null, configured: false });
  expect(await system.api.get('vector')).toMatchObject({
    endpoint: null,
    configured: false,
  });
  observed('getVectorConfig', '未设置端点', system.requests.at(-1));
  system.reply({
    ...vector,
    credential: { configured: true, mask: '【密钥已设置】' },
  });
  await system.api.get('vector');
  observed('getVectorConfig', '已设置密钥', system.requests.at(-1));
  for (const kind of ['generation', 'embedding', 'vector']) {
    system.reply(
      kind === 'vector'
        ? vector
        : {
            ...complete(kind),
            credential: { configured: false, mask: null },
            complete: false,
          },
    );
    await system.api.save(
      kind,
      kind === 'vector'
        ? { endpoint: vector.endpoint, credential: { action: 'clear' } }
        : input(kind, { action: 'clear' }),
    );
    observed(
      {
        generation: 'updateGenerationModel',
        embedding: 'updateEmbeddingModel',
        vector: 'updateVectorConfig',
      }[kind],
      '清除后不完整',
      system.requests.at(-1),
    );
  }
  system.reply({
    ...vector,
    credential: { configured: true, mask: '【密钥已设置】' },
  });
  await system.api.save('vector', {
    endpoint: vector.endpoint,
    credential: { action: 'replace', value: '固定测试凭据' },
  });
  observed('updateVectorConfig', '向量凭据已设置', system.requests.at(-1));
  system.reply({ ...initial, kind: 'embedding' });
  await system.api.version(id);
  observed(
    'getEmbeddingModelVersion',
    '初始历史空版本',
    system.requests.at(-1),
  );
  await system.api.rotate(id, { action: 'clear' });
  observed(
    'updateEmbeddingVersionCredential',
    '初始历史空版本清除',
    system.requests.at(-1),
  );
  system.reply({
    items: [{ ...initial, kind: 'embedding' }],
    nextCursor: null,
  });
  await system.api.versions('opaque +/游标');
  observed(
    'listEmbeddingModelVersions',
    '历史空版本与游标',
    system.requests.at(-1),
  );
});
it.each([
  'queued',
  'running',
  'succeeded',
  'failed',
  'interrupted',
  'cancelled',
])('三种测试同键恢复可消费%s任务与对应字段', async (state) => {
  const terminal = !['queued', 'running'].includes(state);
  const failed = ['failed', 'interrupted'].includes(state);
  for (const kind of ['generation', 'embedding', 'vector']) {
    const returned = {
      ...task,
      kind: kind === 'vector' ? 'vector_connection_test' : 'model_test',
      targetConfigVersionId: kind === 'vector' ? null : id,
      state,
      phase: terminal
        ? 'completed'
        : state === 'running'
          ? 'initializing'
          : 'queued',
      startedAt: state === 'queued' ? null : time,
      finishedAt: terminal ? time : null,
      failure: failed
        ? {
            code: 'TASK_INTERRUPTED',
            message: '固定测试失败',
            retryable: false,
          }
        : null,
      warnings:
        failed && kind !== 'vector'
          ? ['连接测试可能已产生费用，请勿自动重新提交。']
          : [],
    };
    const system = await setup({ task: returned });
    expect(await system.api.test(kind, id, true)).toEqual({ task: returned });
    observed(
      {
        generation: 'testGenerationModel',
        embedding: 'testEmbeddingModel',
        vector: 'testVectorConfig',
      }[kind],
      state,
      system.requests.at(-1),
    );
    system.reply(returned);
    expect(await system.api.task(id)).toEqual(returned);
    observed('getTask', state, system.requests.at(-1));
  }
});

it('生成预算可空共享结构受操作必填正整数约束，客户端拒绝且不发出请求', async () => {
  const system = await setup(complete());
  for (const field of ['contextWindowTokens', 'maxOutputTokens']) {
    const data = { ...input(), [field]: null };
    expect(() => system.api.save('generation', data)).toThrow('预算');
    expect(system.requests).toHaveLength(0);
    evidence.push({
      operationId: 'updateGenerationModel',
      rejectedInput: true,
      field,
      method: 'put',
      url: '/settings/models/generation',
      input: data,
    });
  }
});
