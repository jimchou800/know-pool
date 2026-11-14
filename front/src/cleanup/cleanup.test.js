import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mount, flushPromises } from '@vue/test-utils';
import {
  setup,
  id,
  otherId,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
import { createCleanupClient } from './client';
import DeleteResource from './DeleteResource.vue';
import CleanupTasks from './CleanupTasks.vue';
const observations = [];
const cleanup = {
  ...task,
  kind: 'cleanup',
  knowledgeBaseId: null,
  targetConfigVersionId: null,
  progressTotal: 0,
};
const impact = {
  resourceId: id,
  fileCount: 2,
  knowledgeBaseIds: [otherId],
  activeTaskCount: 1,
  historySnapshotsRetained: true,
  warnings: ['固定影响提醒'],
};
function observe(operationId, r) {
  observations.push({
    operationId,
    method: r.method,
    url: r.url,
    params: r.params ?? {},
    headers: r.headers.toJSON(),
    body: r.data ?? null,
    status: r.observed.status,
    response: r.observed.data,
  });
}
it('三资源删除影响和确认原键恢复真实消费，清理失败分页和新重试关联原任务', async () => {
  const s = await setup(() => null),
    api = createCleanupClient(s.client);
  for (const [kind, prefix] of [
    ['files', 'File'],
    ['datasets', 'Dataset'],
    ['knowledge-bases', 'KnowledgeBase'],
  ]) {
    s.reply(impact);
    expect(await api.impact(kind, id)).toEqual(impact);
    observe(`get${prefix}DeleteImpact`, s.requests.at(-1));
    const deletionTask = {
      ...cleanup,
      knowledgeBaseId: kind === 'knowledge-bases' ? id : null,
      fileId: kind === 'files' ? id : null,
    };
    s.reply({ httpStatus: 202, body: { task: deletionTask } });
    for (let count = 0; count < 2; count++) {
      expect((await api.remove(kind, id, otherId)).task).toEqual(deletionTask);
      expect(JSON.parse(s.requests.at(-1).data)).toEqual({ confirmed: true });
      observe(`delete${prefix}`, s.requests.at(-1));
    }
  }
  const failed = {
    ...cleanup,
    state: 'failed',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    failure: {
      code: 'CLEANUP_FAILED',
      message: '固定安全清理失败',
      retryable: true,
    },
  };
  s.reply({ items: [failed], nextCursor: 'cleanup-next' });
  expect((await api.list()).items).toEqual([failed]);
  observe('listTasks', s.requests.at(-1));
  s.reply({ items: [], nextCursor: null });
  expect((await api.list('cleanup-next', 'failed')).items).toEqual([]);
  observe('listTasks', s.requests.at(-1));
  s.reply({
    httpStatus: 202,
    body: { task: { ...cleanup, id, retryOfTaskId: otherId } },
  });
  expect((await api.retry(otherId, id)).task.retryOfTaskId).toBe(otherId);
  expect(s.requests.at(-1).data).toBeUndefined();
  observe('retryCleanupTask', s.requests.at(-1));
  s.reply({ ...impact, resourceId: otherId });
  await expect(api.impact('files', id)).rejects.toThrow('不符合');
});
it('八操作全部公开错误由实际客户端消费', async () => {
  const s = await setup(() => null),
    api = createCleanupClient(s.client),
    operations = [
      ['listTasks', () => api.list(), false],
      ['retryCleanupTask', () => api.retry(id, otherId), true],
    ];
  for (const [kind, prefix] of [
    ['files', 'File'],
    ['datasets', 'Dataset'],
    ['knowledge-bases', 'KnowledgeBase'],
  ])
    operations.push(
      [`get${prefix}DeleteImpact`, () => api.impact(kind, id), false],
      [`delete${prefix}`, () => api.remove(kind, id, otherId), true],
    );
  for (const [operation, invoke, write] of operations)
    for (const [status, code] of [
      [400, 'INVALID_REQUEST'],
      [401, 'SESSION_EXPIRED'],
      [403, 'CSRF_REJECTED'],
      [404, 'NOT_FOUND'],
      [409, 'CONFLICT'],
      [422, 'INVALID_STATE'],
      [429, 'QUEUE_FULL'],
      [500, 'INTERNAL_ERROR'],
      [503, 'DEPENDENCY_UNAVAILABLE'],
    ]) {
      if (!write && [409, 422].includes(status)) continue;
      s.reply({
        errorStatus: status,
        body: {
          requestId: id,
          code,
          message: '固定删除清理错误',
          fieldErrors: [],
          retryable: false,
        },
      });
      await expect(invoke()).rejects.toThrow('固定删除清理错误');
      observe(operation, s.requests.at(-1));
    }
});
it('删除前真实影响失败禁止确认，网络未知同键恢复即通知逻辑移除不等待清理', async () => {
  const s = await setup(() => {
      throw new Error('固定断线');
    }),
    w = mount(DeleteResource, {
      props: { client: s.client, kind: 'knowledge-bases', resourceId: id },
    });
  await w.get('button').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('请求未完成');
  expect(w.text()).not.toContain('固定断线');
  expect(
    w.findAll('button').some((item) => item.text() === '确认删除知识库'),
  ).toBe(false);
  s.reply(impact);
  await w
    .findAll('button')
    .find((item) => item.text() === '重新读取删除影响')
    .trigger('click');
  await flushPromises();
  expect(w.text()).toContain('涉及资料：2份');
  expect(w.text()).toContain('保留数据集和源资料');
  s.reply(() => {
    throw new Error('固定断线');
  });
  await w
    .findAll('button')
    .find((item) => item.text() === '确认删除知识库')
    .trigger('click');
  await flushPromises();
  const key = s.requests.at(-1).headers.get('Idempotency-Key');
  s.reply({
    httpStatus: 202,
    body: { task: { ...cleanup, knowledgeBaseId: id } },
  });
  await w
    .findAll('button')
    .find((item) => item.text() === '恢复原删除提交')
    .trigger('click');
  await flushPromises();
  expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(key);
  expect(w.emitted('deleted')[0][0]).toMatchObject({
    id,
    task: { state: 'queued' },
  });
  w.unmount();
});
it('全局失败清理显式重试显示新任务不恢复对象，不在页面打开自动外发', async () => {
  const failed = {
    ...cleanup,
    state: 'failed',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    failure: {
      code: 'CLEANUP_FAILED',
      message: '固定清理失败原因',
      retryable: true,
    },
  };
  const s = await setup((r) =>
    r.method === 'get'
      ? { items: [failed], nextCursor: null }
      : {
          httpStatus: 202,
          body: { task: { ...cleanup, id, retryOfTaskId: otherId } },
        },
  );
  const w = mount(CleanupTasks, { props: { client: s.client } });
  await flushPromises();
  expect(s.requests.every((r) => r.method === 'get')).toBe(true);
  expect(w.text()).toContain('固定清理失败原因');
  await w
    .findAll('button')
    .find((item) => item.text() === '重试失败清理')
    .trigger('click');
  await w
    .findAll('button')
    .find((item) => item.text() === '确认重试清理')
    .trigger('click');
  await flushPromises();
  expect(w.text()).toContain('不恢复已删除对象');
  expect(w.text()).toContain(`原失败清理任务：${otherId}`);
  w.unmount();
});
afterAll(() =>
  execFileSync(
    'python',
    ['scripts/settings-evidence.py', '08', 'cleanup-cleanup'],
    {
      input: JSON.stringify(observations),
      encoding: 'utf8',
    },
  ),
);
