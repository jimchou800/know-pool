import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { setup, id, otherId, time } from '../../test-fixtures/knowledge-bases';
import {
  instance,
  instanceId,
  input,
  result,
  scope,
} from '../../test-fixtures/search';
import { evidence } from '../../test-fixtures/indexing';
import { createSearchClient } from './client';
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
it('最小实例通过真实客户端创建，未提交不产生查询或收费任务', async () => {
  const s = await setup(() => instance),
    api = createSearchClient(s.client);
  expect(await api.create()).toEqual(instance);
  expect(s.requests[0].url).toBe('/search-instances');
  expect(s.requests[0].data).toBeUndefined();
  observed('createSearchInstance', s.requests[0]);
  s.reply({ ...instance, currentRequestId: '错误标识' });
  await expect(api.create()).rejects.toThrow('不符合');
});
it('实例检索费用提示外无扩展字段，同键恢复消费原请求及多库遗漏范围和版本证据', async () => {
  const s = await setup(() => ({ httpStatus: 202, body: result })),
    api = createSearchClient(s.client);
  expect(await api.submit(instanceId, input, otherId)).toEqual(result);
  const request = s.requests.at(-1);
  expect(request.url).toBe(`/search-instances/${instanceId}/requests`);
  expect(request.headers.get('Idempotency-Key')).toBe(otherId);
  expect(JSON.parse(request.data)).toEqual(input);
  observed('submitSearch', request);
  expect(await api.submit(instanceId, input, otherId)).toEqual(result);
  observed('submitSearch', s.requests.at(-1));
  s.reply(result);
  expect(await api.get(id, instanceId, input)).toEqual(result);
  observed('getSearchRequest', s.requests.at(-1));
  s.reply(evidence);
  expect(await api.evidence(id, evidence)).toEqual(evidence);
  observed('getSearchEvidence', s.requests.at(-1));
  s.reply({ ...evidence, contentVersionId: id });
  await expect(api.evidence(id, evidence)).rejects.toThrow('不符合');
});
it('全部不可用与无结果响应分离，所有遗漏原因和任务终态均实际消费', async () => {
  const s = await setup(() => null),
    api = createSearchClient(s.client);
  for (const reason of [
    'deleted',
    'not_published',
    'unavailable',
    'no_effective_index',
  ]) {
    const unavailable = {
      ...scope,
      usedKnowledgeBaseIds: [],
      omitted: input.knowledgeBaseIds.map((knowledgeBaseId) => ({
        knowledgeBaseId,
        reason,
        message: '固定范围缺失',
      })),
    };
    const value = {
      ...result,
      scope: unavailable,
      result: null,
      task: {
        ...result.task,
        state: 'failed',
        resultResourceId: null,
        failure: {
          code: 'SCOPE_UNAVAILABLE',
          message: '范围全部不可用',
          retryable: false,
        },
      },
    };
    s.reply(value);
    expect(await api.get(id, instanceId, input)).toEqual(value);
    observed('getSearchRequest', s.requests.at(-1));
    s.reply({ httpStatus: 202, body: value });
    expect(await api.submit(instanceId, input, otherId)).toEqual(value);
    observed('submitSearch', s.requests.at(-1));
  }
  const empty = {
    ...result,
    result: { ...result.result, outcome: 'no_matches', evidence: [] },
  };
  s.reply(empty);
  expect((await api.get(id, instanceId, input)).result.outcome).toBe(
    'no_matches',
  );
  observed('getSearchRequest', s.requests.at(-1));
  for (const state of ['queued', 'running', 'cancelled', 'interrupted']) {
    const terminal = ['cancelled', 'interrupted'].includes(state);
    const value = {
      ...result,
      scope: terminal ? scope : null,
      result: null,
      task: {
        ...result.task,
        state,
        phase: terminal
          ? 'completed'
          : state === 'queued'
            ? 'queued'
            : 'retrieving',
        startedAt: state === 'queued' ? null : time,
        finishedAt: terminal ? time : null,
        resultResourceId: null,
        failure:
          state === 'interrupted'
            ? {
                code: 'TASK_INTERRUPTED',
                message: '固定中断',
                retryable: false,
              }
            : null,
      },
    };
    s.reply(value);
    expect(await api.get(id, instanceId, input)).toEqual(value);
    observed('getSearchRequest', s.requests.at(-1));
  }
});
it('拒绝跨实例跨范围非法字段，查询和范围边界在发送前阻断', async () => {
  const s = await setup(() => result),
    api = createSearchClient(s.client);
  for (const value of [
    { ...result, instanceId: otherId },
    { ...result, extra: true },
    { ...result, scope: { ...scope, incomplete: false } },
    { ...result, result: { ...result.result, query: '其他查询' } },
    {
      ...result,
      result: {
        ...result.result,
        evidence: [{ ...evidence, knowledgeBaseId: otherId }],
      },
    },
  ]) {
    s.reply(value);
    await expect(api.get(id, instanceId, input)).rejects.toThrow('不符合');
  }
  for (const invalid of [
    { ...input, query: ' ' },
    { ...input, query: '长'.repeat(20001) },
    { ...input, knowledgeBaseIds: [] },
    { ...input, knowledgeBaseIds: [id, id] },
    { ...input, extra: true },
  ])
    await expect(api.submit(instanceId, invalid, otherId)).rejects.toThrow(
      '有效查询',
    );
});
it('普通检索全部公开错误由真实响应处理，不自动发新请求', async () => {
  const s = await setup(() => null),
    api = createSearchClient(s.client);
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
        message: '固定普通检索错误',
        fieldErrors: [{ field: '/query', code, message: '固定字段错误' }],
        retryable: false,
      },
    });
    await expect(api.create()).rejects.toThrow('固定普通检索错误');
    observed('createSearchInstance', s.requests.at(-1));
    await expect(api.submit(instanceId, input, otherId)).rejects.toThrow(
      '固定普通检索错误',
    );
    observed('submitSearch', s.requests.at(-1));
    if (![409, 422].includes(status)) {
      await expect(api.get(id, instanceId, input)).rejects.toThrow(
        '固定普通检索错误',
      );
      observed('getSearchRequest', s.requests.at(-1));
      await expect(api.evidence(id, evidence)).rejects.toThrow(
        '固定普通检索错误',
      );
      observed('getSearchEvidence', s.requests.at(-1));
    }
  }
  s.reply({
    errorStatus: 410,
    body: {
      requestId: id,
      code: 'RESULT_EXPIRED',
      message: '结果已失效',
      fieldErrors: [],
      retryable: false,
    },
  });
  await expect(api.get(id, instanceId, input)).rejects.toThrow('结果已失效');
  observed('getSearchRequest', s.requests.at(-1));
  await expect(api.evidence(id, evidence)).rejects.toThrow('结果已失效');
  observed('getSearchEvidence', s.requests.at(-1));
});
afterAll(() => {
  execFileSync('python', ['scripts/settings-evidence.py', '08'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  });
});
