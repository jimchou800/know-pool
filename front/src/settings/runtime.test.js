import { afterAll, afterEach, expect, it } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { execFileSync } from 'node:child_process';
import { setup, id } from '../../test-fixtures/knowledge-bases';
import { createRuntimeClient, runtimeErrors, runtimeFields } from './runtime';
import RuntimeSettings from './RuntimeSettings.vue';
const initial = {
  ...Object.fromEntries(runtimeFields.map(([key]) => [key, 1])),
  rrfConstant: 60,
  maxFileBytes: 1024,
  maxUploadBytes: 2048,
  maxExtractedCodePoints: 4000,
  chunkHardLimitCodePoints: 2000,
  modelRateLimits: [
    {
      endpoint: 'http://127.0.0.1:4567/v1',
      model: '固定模型',
      maxConcurrent: 1,
      requestsPerMinute: 60,
      tokensPerMinute: 10000,
    },
  ],
};
const observations = [];
function observe(operationId, request, assertions = []) {
  observations.push({
    operationId,
    method: request.method,
    url: request.url,
    params: request.params ?? {},
    headers: request.headers.toJSON(),
    body: request.data ?? null,
    status: request.observed.status,
    response: request.observed.data,
    ...(assertions.length
      ? { constraintIds: ['#/x-business-constraints/cost'], assertions }
      : {}),
  });
}
let wrapper;
afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
});
it('真实客户端读取完整初值并全量保存空或非空限流，不带未声明幂等键', async () => {
  const s = await setup(() => initial),
    api = createRuntimeClient(s.client);
  expect(await api.get()).toEqual(initial);
  observe('getRuntimeSettings', s.requests.at(-1));
  for (const modelRateLimits of [[], initial.modelRateLimits]) {
    const value = { ...initial, modelRateLimits };
    s.reply(value);
    expect(await api.save(value)).toEqual(value);
    expect(JSON.parse(s.requests.at(-1).data)).toEqual(value);
    expect(s.requests.at(-1).headers.has('Idempotency-Key')).toBe(false);
    observe('updateRuntimeSettings', s.requests.at(-1));
  }
});
it('完整字段、整数极值、有限数值、组合与重复模型限流严格校验', async () => {
  expect(runtimeErrors(initial)).toEqual([]);
  const cases = [
    { ...initial, unknown: true },
    { ...initial, maxFileBytes: Number.MAX_SAFE_INTEGER + 1 },
    { ...initial, indexConcurrency: 33 },
    { ...initial, queueCapacity: 100001 },
    { ...initial, maxUploadFiles: 1001 },
    { ...initial, maxFileBytes: '1' },
    { ...initial, rrfConstant: Infinity },
    { ...initial, rrfConstant: 0 },
    { ...initial, finalEvidenceLimit: 2 },
    { ...initial, maxUploadBytes: 1 },
    { ...initial, maxExtractedCodePoints: 1 },
    {
      ...initial,
      modelRateLimits: Array(101).fill(initial.modelRateLimits[0]),
    },
    {
      ...initial,
      modelRateLimits: [
        {
          ...initial.modelRateLimits[0],
          endpoint: 'http://user:secret@localhost',
        },
      ],
    },
    {
      ...initial,
      modelRateLimits: [
        initial.modelRateLimits[0],
        {
          ...initial.modelRateLimits[0],
          endpoint: 'http://127.0.0.1:4567/v1/',
        },
      ],
    },
  ];
  const missing = { ...initial };
  delete missing.historyContextTokenBudget;
  cases.push(missing);
  const s = await setup(() => initial),
    api = createRuntimeClient(s.client);
  for (const value of cases) {
    expect(runtimeErrors(value).length).toBeGreaterThan(0);
    await expect(api.save(value)).rejects.toThrow('错误');
  }
  expect(s.requests).toHaveLength(0);
  expect(
    runtimeErrors({
      ...initial,
      requestModelCallBudget: Number.MAX_SAFE_INTEGER,
      rrfConstant: 0.5,
    }),
  ).toEqual([]);
  s.reply({ ...initial, maxUploadFiles: 0 });
  await expect(api.get()).rejects.toThrow('响应不符合');
});
it('两操作全部公开错误与字段错误实际消费', async () => {
  const s = await setup(() => initial),
    api = createRuntimeClient(s.client);
  for (const [operation, invoke] of [
    ['getRuntimeSettings', () => api.get()],
    ['updateRuntimeSettings', () => api.save(initial)],
  ])
    for (const [status, code] of [
      [400, 'INVALID_REQUEST'],
      [401, 'SESSION_EXPIRED'],
      [403, 'CSRF_REJECTED'],
      [404, 'NOT_FOUND'],
      [409, 'CONFLICT'],
      [422, 'INVALID_STATE'],
      [429, 'QUEUE_FULL'],
      [500, 'INTERNAL_ERROR'],
      [503, 'WRITES_PAUSED'],
    ]) {
      if (operation === 'getRuntimeSettings' && [409, 422].includes(status))
        continue;
      s.reply({
        errorStatus: status,
        body: {
          requestId: id,
          code,
          message: '固定运行参数错误',
          fieldErrors:
            status === 400
              ? [
                  {
                    field: '/contextEvidenceTokenBudget',
                    code: 'CONTEXT_BUDGET',
                    message: '证据及历史预算超过当前模型上下文',
                  },
                ]
              : [],
          retryable: status === 429,
        },
      });
      await expect(invoke()).rejects.toThrow('固定运行参数错误');
      observe(operation, s.requests.at(-1));
    }
});
it('读取失败不造默认表单，真实值全量编辑、繁忙及受理不明需重读', async () => {
  const s = await setup(() => {
    throw new Error('固定断线');
  });
  wrapper = mount(RuntimeSettings, { props: { client: s.client } });
  await flushPromises();
  expect(wrapper.find('form').exists()).toBe(false);
  s.reply(initial);
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.findAll('.runtime-fields input')).toHaveLength(21);
  expect(wrapper.get('[name="maxFileBytes"]').element.value).toBe('1024');
  await wrapper.get('[name="indexConcurrency"]').setValue('2');
  s.reply({ ...initial, indexConcurrency: 2 });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('运行参数已保存');
  expect(Object.keys(JSON.parse(s.requests.at(-1).data))).toHaveLength(22);
  s.reply({
    errorStatus: 429,
    body: {
      requestId: id,
      code: 'QUEUE_FULL',
      message: '服务繁忙，请稍后重试',
      fieldErrors: [],
      retryable: true,
    },
  });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('服务繁忙');
  s.reply(() => {
    throw new Error('固定断线');
  });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('保存结果尚未确认');
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();
  s.reply(initial);
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).not.toContain('保存结果尚未确认');
  await wrapper.setProps({ writable: false });
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();
});
it('切片硬上限变化明确确认费用后才外发，服务端模型上下文字段错误保留修改', async () => {
  const s = await setup(() => initial);
  wrapper = mount(RuntimeSettings, { props: { client: s.client } });
  await flushPromises();
  await wrapper.get('[name="chunkHardLimitCodePoints"]').setValue('2100');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(s.requests.filter((request) => request.method === 'put')).toHaveLength(
    0,
  );
  expect(wrapper.text()).toContain('请先确认切片参数变更');
  await wrapper.get('input[type="checkbox"]').setValue(true);
  s.reply({
    errorStatus: 400,
    body: {
      requestId: id,
      code: 'INVALID_REQUEST',
      message: '预算组合不合法',
      fieldErrors: [
        {
          field: '/contextEvidenceTokenBudget',
          code: 'CONTEXT_BUDGET',
          message: '证据及历史预算超过当前模型上下文',
        },
      ],
      retryable: false,
    },
  });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('证据及历史预算超过当前模型上下文');
  expect(wrapper.get('[name="chunkHardLimitCodePoints"]').element.value).toBe(
    '2100',
  );
  expect(
    s.requests.every((request) => request.url === '/settings/runtime'),
  ).toBe(true);
  observe('updateRuntimeSettings', s.requests.at(-1), [
    '切片输入变化必须明确确认嵌入费用才发出运行参数保存；后端字段错误保留编辑，组件没有额外问答、嵌入、检索或重试请求。',
  ]);
});
afterAll(() =>
  execFileSync(
    'python',
    ['scripts/settings-evidence.py', '08', 'settings-runtime'],
    {
      input: JSON.stringify(observations),
      encoding: 'utf8',
    },
  ),
);
