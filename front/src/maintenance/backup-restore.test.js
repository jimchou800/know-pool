import { afterAll, afterEach, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mount, flushPromises } from '@vue/test-utils';
import {
  setup,
  task,
  id,
  otherId,
  time,
} from '../../test-fixtures/knowledge-bases';
import { createMaintenanceClient } from './client';
import Maintenance from './Migration.vue';
const observations = [];
const acknowledgements = {
  acknowledgePlaintextCredentials: true,
  acknowledgeIndexRebuildCost: true,
};
const preflight = {
  token: id,
  expiresAt: '2099-01-01T00:00:00Z',
  normalizedPath: 'D:\\固定备份',
  allowed: true,
  reasons: [],
  containsPlaintextCredentials: true,
  includesVectorSnapshot: false,
  estimatedBytes: 128,
};
const maintenanceTask = (kind, state = 'queued') => ({
  ...task,
  kind,
  knowledgeBaseId: null,
  targetConfigVersionId: null,
  state,
  phase: state === 'queued' ? 'queued' : 'completed',
  startedAt: state === 'queued' ? null : time,
  finishedAt: state === 'queued' ? null : time,
  failure: ['failed', 'interrupted'].includes(state)
    ? {
        code: 'VERIFICATION_FAILED',
        message: '固定备份恢复故障',
        retryable: true,
      }
    : null,
});
const result = (kind, indexStatus = 'not_checked') => ({
  task: maintenanceTask(kind),
  operation: kind,
  activeDataDirectory: 'D:\\固定运行目录',
  targetPath: kind === 'backup' ? preflight.normalizedPath : 'D:\\固定恢复目录',
  configurationSwitched: false,
  oldDirectoryResidue: false,
  recoverable: false,
  indexStatus,
  credentialsIncluded: true,
  vectorSnapshotIncluded: false,
  failureStage: null,
  warnings: ['原备份和恢复前目录保留'],
});
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
});
const button = (text) =>
  wrapper.findAll('button').find((item) => item.text() === text);
it.each(['backup', 'restore'])(
  '%s四新增实际请求、终态幂等与共用维护恢复完整消费',
  async (kind) => {
    const s = await setup(() => ({ httpStatus: 200, body: preflight })),
      api = createMaintenanceClient(s.client, kind),
      prefix = kind === 'backup' ? 'Backup' : 'Restore';
    expect(await api.preflight('', preflight.normalizedPath)).toEqual(
      preflight,
    );
    expect(JSON.parse(s.requests.at(-1).data)).toEqual(
      kind === 'backup'
        ? { targetPath: preflight.normalizedPath }
        : { backupPath: preflight.normalizedPath },
    );
    expect(s.requests.at(-1).headers.has('Idempotency-Key')).toBe(false);
    observe(`${kind}Preflight`, s.requests.at(-1));
    s.reply({
      httpStatus: 200,
      body: {
        ...preflight,
        allowed: false,
        containsPlaintextCredentials: false,
        estimatedBytes: 0,
        reasons: ['目录非空或备份校验不完整'],
      },
    });
    expect((await api.preflight('', preflight.normalizedPath)).allowed).toBe(
      false,
    );
    observe(`${kind}Preflight`, s.requests.at(-1));
    for (const state of ['queued', 'succeeded']) {
      s.reply({
        httpStatus: 202,
        body: { task: maintenanceTask(kind, state) },
      });
      await api.submit(id, otherId, acknowledgements);
      expect(JSON.parse(s.requests.at(-1).data)).toEqual({
        preflightToken: id,
        confirmed: true,
        ...(kind === 'restore' ? acknowledgements : {}),
      });
      observe(`submit${prefix}`, s.requests.at(-1));
    }
    for (const state of ['queued', 'failed', 'interrupted', 'succeeded']) {
      const value = {
        ...result(kind),
        task: maintenanceTask(kind, state),
        recoverable: ['failed', 'interrupted'].includes(state),
        failureStage: ['failed', 'interrupted'].includes(state)
          ? 'verification'
          : null,
      };
      s.reply(value);
      expect(await api.get(otherId)).toEqual(value);
      observe('getMaintenanceTask', s.requests.at(-1));
    }
    s.reply({ items: [maintenanceTask(kind)], nextCursor: '下一页' });
    await api.list();
    expect(s.requests.at(-1).params.kind).toBe(kind);
    observe('listTasks', s.requests.at(-1));
    s.reply({ items: [], nextCursor: null });
    await api.list('下一页');
    observe('listTasks', s.requests.at(-1));
    s.reply({
      httpStatus: 202,
      body: { task: { ...maintenanceTask(kind), id, retryOfTaskId: otherId } },
    });
    await api.retry(otherId, 'resume', id);
    expect(JSON.parse(s.requests.at(-1).data)).toEqual({ action: 'resume' });
    observe('retryMaintenanceTask', s.requests.at(-1));
  },
);
it.each(['backup', 'restore'])(
  '%s四新增全部公开错误由真实客户端消费',
  async (kind) => {
    const s = await setup(() => null),
      api = createMaintenanceClient(s.client, kind);
    for (const [operation, invoke] of [
      [`${kind}Preflight`, () => api.preflight('', preflight.normalizedPath)],
      [
        `submit${kind === 'backup' ? 'Backup' : 'Restore'}`,
        () => api.submit(id, otherId, acknowledgements),
      ],
    ])
      for (const [status, code] of [
        [400, 'INVALID_REQUEST'],
        [401, 'SESSION_EXPIRED'],
        [403, 'CSRF_REJECTED'],
        [404, 'NOT_FOUND'],
        [409, 'CONFLICT'],
        [422, 'INVALID_STATE'],
        [429, 'RESOURCE_LIMIT'],
        [500, 'INTERNAL_ERROR'],
        [503, 'WRITES_PAUSED'],
      ]) {
        s.reply({
          errorStatus: status,
          body: {
            requestId: id,
            code,
            message: '固定备份恢复错误',
            fieldErrors:
              status === 400
                ? [
                    {
                      field: kind === 'backup' ? 'targetPath' : 'backupPath',
                      code: 'INVALID_PATH',
                      message: '目录不合法',
                    },
                  ]
                : [],
            retryable: status === 429,
          },
        });
        await expect(invoke()).rejects.toThrow('固定备份恢复错误');
        observe(operation, s.requests.at(-1));
      }
  },
);
it('恢复必须三项明确确认，断线后原键与风险确认原样恢复且不自动模型调用', async () => {
  const s = await setup((request) =>
    request.url === '/tasks'
      ? { items: [], nextCursor: null }
      : { httpStatus: 200, body: preflight },
  );
  wrapper = mount(Maintenance, {
    props: {
      client: s.client,
      operation: 'restore',
      dataDirectory: 'D:\\固定运行目录',
    },
  });
  await flushPromises();
  await wrapper
    .get('input[name="restoreTarget"]')
    .setValue(preflight.normalizedPath);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  const checks = wrapper.findAll('input[type="checkbox"]');
  expect(checks).toHaveLength(3);
  for (const check of checks.slice(0, 2)) await check.setValue(true);
  expect(button('确认恢复').attributes('disabled')).toBeDefined();
  await checks[2].setValue(true);
  s.reply(() => {
    throw new Error('固定断线');
  });
  await button('确认恢复').trigger('click');
  await flushPromises();
  const original = s.requests.at(-1),
    key = original.headers.get('Idempotency-Key');
  expect(JSON.parse(original.data)).toEqual({
    preflightToken: id,
    confirmed: true,
    ...acknowledgements,
  });
  const saved = JSON.parse(sessionStorage.getItem('know-pool-restore-pending'));
  expect(saved.acknowledgements).toEqual(acknowledgements);
  expect(Object.keys(saved).sort()).toEqual([
    'acknowledgements',
    'key',
    'token',
  ]);
  const value = {
    ...result('restore', 'rebuild_required'),
    task: maintenanceTask('restore', 'succeeded'),
    configurationSwitched: true,
  };
  s.reply((request) =>
    request.method === 'post'
      ? { httpStatus: 202, body: { task: value.task } }
      : request.url === '/tasks'
        ? { items: [value.task], nextCursor: null }
        : value,
  );
  await button('继续确认原请求').trigger('click');
  await flushPromises();
  const submits = s.requests.filter(
    (request) => request.url === '/maintenance/restore',
  );
  expect(submits).toHaveLength(2);
  expect(submits[1].headers.get('Idempotency-Key')).toBe(key);
  expect(wrapper.text()).toContain('资料已恢复，知识服务仍待核查或重建');
  expect(wrapper.text()).toContain('可能产生嵌入费用');
  expect(button('确认仅清理旧目录残留')).toBeUndefined();
  expect(wrapper.get('a').attributes('href')).toBe('#/knowledge-bases');
  expect(
    s.requests.every(
      (request) => !/retry-index|questions|search-requests/.test(request.url),
    ),
  ).toBe(true);
  observe(
    'getMaintenanceTask',
    s.requests.find(
      (request) => request.url === `/maintenance/tasks/${otherId}`,
    ),
    [
      '恢复资料成功但索引缺失不宣称全部恢复，提示显式重建费用，原备份和旧运行目录保留，不自动调用模型。',
    ],
  );
});
it.each(['backup', 'restore'])(
  '%s失败原任务发现与显式继续仅恢复可恢复任务',
  async (kind) => {
    const value = {
      ...result(kind),
      task: maintenanceTask(kind, 'failed'),
      recoverable: true,
      failureStage: 'copy',
    };
    const s = await setup((request) =>
      request.url === '/tasks'
        ? { items: [value.task], nextCursor: null }
        : value,
    );
    wrapper = mount(Maintenance, {
      props: { client: s.client, operation: kind, writable: false },
    });
    await flushPromises();
    expect(s.requests.every((request) => request.method === 'get')).toBe(true);
    expect(
      button(kind === 'backup' ? '确认继续备份' : '确认继续恢复'),
    ).toBeDefined();
    expect(button('确认仅清理旧目录残留')).toBeUndefined();
    s.reply((request) =>
      request.method === 'post'
        ? {
            httpStatus: 202,
            body: { task: { ...value.task, id, retryOfTaskId: otherId } },
          }
        : request.url === '/tasks'
          ? { items: [], nextCursor: null }
          : { ...value, task: { ...value.task, id, retryOfTaskId: otherId } },
    );
    await button(kind === 'backup' ? '确认继续备份' : '确认继续恢复').trigger(
      'click',
    );
    await flushPromises();
    expect(
      JSON.parse(s.requests.find((request) => request.method === 'post').data),
    ).toEqual({ action: 'resume' });
  },
);
it('恢复接口拒绝缺少风险确认与串类型响应', async () => {
  const s = await setup(() => null),
    api = createMaintenanceClient(s.client, 'restore');
  await expect(api.submit(id, otherId)).rejects.toThrow('明确确认');
  expect(s.requests).toHaveLength(0);
  s.reply(result('backup'));
  await expect(api.get(otherId)).rejects.toThrow('不符合');
  s.reply({ items: [maintenanceTask('backup')], nextCursor: null });
  await expect(api.list()).rejects.toThrow('不符合');
});
afterAll(() =>
  execFileSync(
    'python',
    ['scripts/settings-evidence.py', '08', 'maintenance-backup-restore'],
    {
      input: JSON.stringify(observations),
      encoding: 'utf8',
    },
  ),
);
