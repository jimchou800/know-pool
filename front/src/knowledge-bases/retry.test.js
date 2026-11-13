import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mount, flushPromises } from '@vue/test-utils';
import { setup, id, otherId } from '../../test-fixtures/knowledge-bases';
import { createKnowledgeBaseClient } from './client';
import RetryFiles from './RetryFiles.vue';
const observations = [];
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
it('单份及全失败项重试真实请求无正文，逐项结果与同键恢复不冒充成功', async () => {
  const s = await setup(() => null),
    api = createKnowledgeBaseClient(s.client);
  for (const [fileId, operation] of [
    [otherId, 'retryKnowledgeBaseFile'],
    [null, 'retryFailedKnowledgeBaseFiles'],
  ]) {
    for (const outcome of ['enqueued', 'already_active', 'ineligible']) {
      const value = {
        items: [
          {
            fileId: otherId,
            outcome,
            taskId: outcome === 'ineligible' ? null : id,
            reason: outcome === 'enqueued' ? null : '固定资格或已有任务说明',
          },
        ],
      };
      s.reply({ httpStatus: 202, body: value });
      for (let count = 0; count < 2; count++) {
        expect(await api.retry(id, fileId, otherId)).toEqual(value);
        expect(s.requests.at(-1).data).toBeUndefined();
        expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(otherId);
        observe(operation, s.requests.at(-1));
      }
    }
  }
  s.reply({
    items: [{ fileId: id, outcome: 'enqueued', taskId: otherId, reason: null }],
  });
  await expect(api.retry(id, otherId, id)).rejects.toThrow('不符合');
  s.reply({
    items: [
      { fileId: otherId, outcome: 'completed', taskId: null, reason: null },
    ],
  });
  await expect(api.retry(id, otherId, id)).rejects.toThrow('不符合');
});
it('两个重试操作公开错误由真实客户端消费', async () => {
  const s = await setup(() => null),
    api = createKnowledgeBaseClient(s.client);
  for (const [fileId, operation] of [
    [otherId, 'retryKnowledgeBaseFile'],
    [null, 'retryFailedKnowledgeBaseFiles'],
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
      [503, 'DEPENDENCY_UNAVAILABLE'],
    ]) {
      s.reply({
        errorStatus: status,
        body: {
          requestId: id,
          code,
          message: '固定重试错误',
          fieldErrors: [],
          retryable: false,
        },
      });
      await expect(api.retry(id, fileId, otherId)).rejects.toThrow(
        '固定重试错误',
      );
      observe(operation, s.requests.at(-1));
    }
  }
});
it('费用未确认零请求，受理不明同键恢复并分别展示逐项结果', async () => {
  const s = await setup(() => {
      throw new Error('固定断线');
    }),
    w = mount(RetryFiles, { props: { client: s.client, knowledgeBaseId: id } });
  await w.get('button').trigger('click');
  await w.findAll('button')[1].trigger('click');
  expect(s.requests).toHaveLength(0);
  expect(w.text()).toContain('请先确认');
  await w.get('input').setValue(true);
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  const key = s.requests.at(-1).headers.get('Idempotency-Key');
  expect(w.text()).toContain('受理结果尚未确认');
  s.reply({
    httpStatus: 202,
    body: {
      items: [
        {
          fileId: id,
          outcome: 'already_active',
          taskId: otherId,
          reason: '原任务仍在运行',
        },
        {
          fileId: otherId,
          outcome: 'ineligible',
          taskId: null,
          reason: '资料已被排除',
        },
      ],
    },
  });
  await w.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(key);
  expect(w.text()).toContain('已有任务');
  expect(w.text()).toContain('不再符合条件');
  expect(w.text()).toContain('受理不代表处理成功');
  expect(w.emitted('completed')).toHaveLength(1);
  w.unmount();
});
afterAll(() =>
  execFileSync('python', ['scripts/settings-evidence.py', '08'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  }),
);
