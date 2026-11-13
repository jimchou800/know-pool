import { afterAll, afterEach, expect, it, vi } from 'vitest';
import { execFileSync } from 'node:child_process';
import { flushPromises, mount } from '@vue/test-utils';
import {
  setup,
  id,
  otherId,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
import { createMigrationClient } from './client';
import Migration from './Migration.vue';
const observations = [];
const migration = {
  ...task,
  kind: 'migration',
  knowledgeBaseId: null,
  targetConfigVersionId: null,
};
const preflight = {
  token: id,
  expiresAt: '2099-01-01T00:00:00Z',
  normalizedPath: 'D:\\固定迁移目标',
  allowed: true,
  reasons: [],
  containsPlaintextCredentials: true,
  includesVectorSnapshot: false,
  estimatedBytes: 128,
};
const result = {
  task: migration,
  operation: 'migration',
  activeDataDirectory: 'D:\\固定迁移源',
  targetPath: preflight.normalizedPath,
  configurationSwitched: false,
  oldDirectoryResidue: false,
  recoverable: false,
  indexStatus: 'not_checked',
  credentialsIncluded: true,
  vectorSnapshotIncluded: false,
  failureStage: null,
  warnings: ['固定维护提示'],
};
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
      ? { constraintIds: ['#/x-business-constraints/maintenance'], assertions }
      : {}),
  });
}
let wrapper;
afterEach(() => {
  wrapper?.unmount();
  wrapper = null;
  sessionStorage.clear();
  vi.useRealTimers();
});
const button = (text) =>
  wrapper.findAll('button').find((item) => item.text() === text);
const terminal = (state = 'failed') => ({
  ...migration,
  state,
  phase: 'completed',
  startedAt: time,
  finishedAt: time,
  failure:
    state === 'succeeded'
      ? null
      : { code: 'MIGRATION_FAILED', message: '固定阶段失败', retryable: true },
});
it('四操作实际请求与完整预检维护响应、同键终态和显式恢复消费', async () => {
  const s = await setup(() => ({ httpStatus: 200, body: preflight })),
    api = createMigrationClient(s.client);
  expect(
    await api.preflight(result.activeDataDirectory, result.targetPath),
  ).toEqual(preflight);
  expect(JSON.parse(s.requests.at(-1).data)).toEqual({
    sourcePath: result.activeDataDirectory,
    targetPath: result.targetPath,
  });
  expect(s.requests.at(-1).headers.has('Idempotency-Key')).toBe(false);
  observe('migrationPreflight', s.requests.at(-1));
  s.reply({
    httpStatus: 200,
    body: {
      ...preflight,
      allowed: false,
      containsPlaintextCredentials: false,
      reasons: ['目标非空或源目标相互包含'],
      estimatedBytes: 0,
    },
  });
  expect(
    (await api.preflight(result.activeDataDirectory, result.targetPath))
      .allowed,
  ).toBe(false);
  observe('migrationPreflight', s.requests.at(-1));
  for (const value of [migration, terminal('succeeded')]) {
    s.reply({ httpStatus: 202, body: { task: value } });
    expect((await api.submit(id, otherId)).task).toEqual(value);
    expect(JSON.parse(s.requests.at(-1).data)).toEqual({
      preflightToken: id,
      confirmed: true,
    });
    observe('submitMigration', s.requests.at(-1));
  }
  for (const phase of [
    'queued',
    'initializing',
    'copying',
    'verifying',
    'saving_config',
    'cleaning',
    'validating',
  ]) {
    const value = {
      ...result,
      task: {
        ...migration,
        state: phase === 'queued' ? 'queued' : 'running',
        phase,
        startedAt: phase === 'queued' ? null : time,
        progressTotal: 128,
        progressCompleted: 20,
      },
    };
    s.reply(value);
    expect(await api.get(otherId)).toEqual(value);
    observe('getMaintenanceTask', s.requests.at(-1));
  }
  for (const [index, stage] of [
    'path_check',
    'copy',
    'verification',
    'configuration_save',
    'old_directory_cleanup',
    'index_check',
  ].entries()) {
    const value = {
      ...result,
      task: terminal(index === 0 ? 'interrupted' : 'failed'),
      failureStage: stage,
      configurationSwitched: index >= 4,
      oldDirectoryResidue: index === 4,
      recoverable: index < 5,
      credentialsIncluded: index !== 0,
      indexStatus: index === 5 ? 'unavailable' : 'not_checked',
    };
    s.reply(value);
    expect((await api.get(otherId)).failureStage).toBe(stage);
    observe('getMaintenanceTask', s.requests.at(-1));
  }
  for (const indexStatus of ['valid', 'rebuild_required']) {
    s.reply({
      ...result,
      task: terminal('succeeded'),
      indexStatus,
      configurationSwitched: true,
      activeDataDirectory: result.targetPath,
    });
    expect((await api.get(otherId)).indexStatus).toBe(indexStatus);
    observe('getMaintenanceTask', s.requests.at(-1));
  }
  for (const action of ['resume', 'cleanup_residue']) {
    s.reply({
      httpStatus: 202,
      body: { task: { ...migration, id, retryOfTaskId: otherId } },
    });
    expect((await api.retry(otherId, action, id)).task.retryOfTaskId).toBe(
      otherId,
    );
    expect(JSON.parse(s.requests.at(-1).data)).toEqual({ action });
    observe('retryMaintenanceTask', s.requests.at(-1));
  }
  s.reply({ items: [migration], nextCursor: '下一页' });
  expect((await api.list()).items).toEqual([migration]);
  observe('listTasks', s.requests.at(-1));
  s.reply({ items: [], nextCursor: null });
  await api.list('下一页');
  observe('listTasks', s.requests.at(-1));
});
it('四操作全部公开错误和字段错误沿真实会话客户端显示', async () => {
  const s = await setup(() => null),
    api = createMigrationClient(s.client);
  for (const [operation, invoke] of [
    [
      'migrationPreflight',
      () => api.preflight(result.activeDataDirectory, result.targetPath),
    ],
    ['submitMigration', () => api.submit(id, otherId)],
    ['getMaintenanceTask', () => api.get(otherId)],
    ['retryMaintenanceTask', () => api.retry(otherId, 'resume', id)],
  ]) {
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
      if (operation === 'getMaintenanceTask' && [409, 422].includes(status))
        continue;
      s.reply({
        errorStatus: status,
        body: {
          requestId: id,
          code,
          message: '固定维护错误',
          fieldErrors:
            status === 400
              ? [
                  {
                    field: 'targetPath',
                    code: 'INVALID_PATH',
                    message: '固定非法目录',
                  },
                ]
              : [],
          retryable: status === 503,
        },
      });
      await expect(invoke()).rejects.toThrow('固定维护错误');
      observe(operation, s.requests.at(-1));
    }
  }
});
it('禁止未确认与拒绝目录，受理未知原键恢复，轮询断线仅重读原任务', async () => {
  const s = await setup((request) =>
    request.url === '/tasks' ? { items: [], nextCursor: null } : preflight,
  );
  wrapper = mount(Migration, {
    props: { client: s.client, dataDirectory: result.activeDataDirectory },
  });
  await flushPromises();
  await wrapper.get('[name="migrationTarget"]').setValue(result.targetPath);
  s.reply({
    httpStatus: 200,
    body: { ...preflight, allowed: false, reasons: ['目录非空，禁止迁移'] },
  });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('目录非空，禁止迁移');
  expect(button('确认迁移')).toBeUndefined();
  s.reply({ httpStatus: 200, body: preflight });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(button('确认迁移').attributes('disabled')).toBeDefined();
  await wrapper.get('input[type="checkbox"]').setValue(true);
  s.reply(() => {
    throw new Error('固定断线');
  });
  await button('确认迁移').trigger('click');
  await flushPromises();
  const key = s.requests.at(-1).headers.get('Idempotency-Key');
  expect(wrapper.text()).toContain('受理结果尚未确认');
  s.reply((request) => {
    if (request.url === '/maintenance/migration')
      return { httpStatus: 202, body: { task: migration } };
    if (request.url === '/tasks')
      return { items: [migration], nextCursor: null };
    throw new Error('固定断线');
  });
  await button('继续确认原请求').trigger('click');
  await flushPromises();
  const submits = s.requests.filter(
    (request) => request.url === '/maintenance/migration',
  );
  expect(submits).toHaveLength(2);
  expect(submits[1].headers.get('Idempotency-Key')).toBe(key);
  s.reply({
    ...result,
    task: terminal('succeeded'),
    indexStatus: 'valid',
    configurationSwitched: true,
  });
  await button('重新读取原任务').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('目录配置已切换');
  expect(sessionStorage.length).toBe(0);
});
it.each([
  'copy',
  'verification',
  'configuration_save',
  'old_directory_cleanup',
  'index_check',
])('原任务%s失败按真实检查点决定恢复入口且切换后不回滚', async (stage) => {
  const switched = ['old_directory_cleanup', 'index_check'].includes(stage),
    value = {
      ...result,
      task: terminal(),
      failureStage: stage,
      configurationSwitched: switched,
      activeDataDirectory: switched
        ? result.targetPath
        : result.activeDataDirectory,
      oldDirectoryResidue: stage === 'old_directory_cleanup',
      recoverable: stage !== 'index_check',
      indexStatus: stage === 'index_check' ? 'unavailable' : 'not_checked',
    };
  const s = await setup((request) =>
    request.url === '/tasks'
      ? { items: [value.task], nextCursor: null }
      : value,
  );
  wrapper = mount(Migration, {
    props: {
      client: s.client,
      writable: false,
      dataDirectory: value.activeDataDirectory,
    },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('失败');
  expect(!!button('确认恢复迁移')).toBe(!switched);
  expect(!!button('确认仅清理旧目录残留')).toBe(
    stage === 'old_directory_cleanup',
  );
  expect(s.requests.every((request) => request.method === 'get')).toBe(true);
  if (switched) expect(wrapper.text()).toContain('继续使用新目录');
  if (stage === 'index_check')
    expect(wrapper.text()).toContain('知识服务尚未恢复');
  const action = switched ? 'cleanup_residue' : 'resume';
  if (stage !== 'index_check') {
    s.reply((request) =>
      request.method === 'post'
        ? {
            httpStatus: 202,
            body: { task: { ...migration, id, retryOfTaskId: otherId } },
          }
        : request.url === '/tasks'
          ? { items: [], nextCursor: null }
          : { ...value, task: { ...value.task, id, retryOfTaskId: otherId } },
    );
    await button(switched ? '确认仅清理旧目录残留' : '确认恢复迁移').trigger(
      'click',
    );
    await flushPromises();
    expect(
      JSON.parse(s.requests.find((request) => request.method === 'post').data),
    ).toEqual({ action });
  }
  observe(
    'getMaintenanceTask',
    s.requests.find(
      (request) => request.url === `/maintenance/tasks/${otherId}`,
    ),
    [
      '组件按真实失败阶段显示恢复入口；切换后继续使用新目录，索引不可用不宣称服务恢复。',
    ],
  );
});
it('预检令牌容量已满显示错误且旧预检不能继续确认', async () => {
  const s = await setup((request) =>
    request.url === '/tasks'
      ? { items: [], nextCursor: null }
      : { httpStatus: 200, body: preflight },
  );
  wrapper = mount(Migration, {
    props: { client: s.client, dataDirectory: result.activeDataDirectory },
  });
  await flushPromises();
  await wrapper.get('[name="migrationTarget"]').setValue(result.targetPath);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(button('确认迁移')).toBeDefined();
  s.reply({
    errorStatus: 429,
    body: {
      requestId: id,
      code: 'RESOURCE_LIMIT',
      message: '预检令牌容量已满，请稍后重新预检',
      fieldErrors: [],
      retryable: true,
    },
  });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('预检令牌容量已满');
  expect(button('确认迁移')).toBeUndefined();
  expect(
    s.requests.some((request) => request.url === '/maintenance/migration'),
  ).toBe(false);
  observe('migrationPreflight', s.requests.at(-1));
});
it('切换任务先清旧详情，迟到响应不能串用恢复对象', async () => {
  let release;
  const old = { ...result, task: terminal(), recoverable: true };
  const next = { ...old, task: { ...old.task, id } };
  const s = await setup((request) =>
    request.url === '/tasks'
      ? { items: [old.task, next.task], nextCursor: null }
      : request.url === `/maintenance/tasks/${id}`
        ? new Promise((resolve) => {
            release = resolve;
          })
        : old,
  );
  wrapper = mount(Migration, {
    props: { client: s.client, dataDirectory: result.activeDataDirectory },
  });
  await flushPromises();
  expect(button('确认恢复迁移')).toBeDefined();
  await button(`失败 · ${id}`).trigger('click');
  await flushPromises();
  expect(button('确认恢复迁移')).toBeUndefined();
  expect(wrapper.find('[aria-label="迁移任务详情"]').exists()).toBe(false);
  await button(`失败 · ${otherId}`).trigger('click');
  await flushPromises();
  release(next);
  await flushPromises();
  expect(wrapper.get('[aria-label="迁移任务详情"]').text()).toContain(otherId);
  expect(wrapper.get('[aria-label="迁移任务详情"]').text()).not.toContain(
    `任务：${id}`,
  );
  expect(s.requests.every((request) => request.method === 'get')).toBe(true);
});
it('拒绝畸形响应、错误任务身份与非法调用参数', async () => {
  const s = await setup(() => null),
    api = createMigrationClient(s.client);
  await expect(api.preflight('a', result.targetPath)).rejects.toThrow('长度');
  await expect(api.submit('bad', id)).rejects.toThrow('标识');
  await expect(api.get('bad')).rejects.toThrow('标识');
  await expect(api.retry(id, 'bad', otherId)).rejects.toThrow('无效');
  s.reply({ ...preflight, includesVectorSnapshot: true });
  await expect(
    api.preflight(result.activeDataDirectory, result.targetPath),
  ).rejects.toThrow('不符合');
  s.reply({ ...result, task: { ...migration, id } });
  await expect(api.get(otherId)).rejects.toThrow('不符合');
  s.reply({
    httpStatus: 202,
    body: { task: { ...migration, retryOfTaskId: null } },
  });
  await expect(api.retry(otherId, 'resume', id)).rejects.toThrow('不符合');
  s.reply({ items: [task], nextCursor: null });
  await expect(api.list()).rejects.toThrow('不符合');
});
afterAll(() =>
  execFileSync('python', ['scripts/settings-evidence.py', '08'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  }),
);
