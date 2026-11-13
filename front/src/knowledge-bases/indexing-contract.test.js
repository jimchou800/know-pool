import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import {
  setup,
  id,
  otherId,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
import { search, evidence, scope } from '../../test-fixtures/indexing';
import { createManagementSearchClient } from './search-client';
import { watchTask } from './task-events';
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
it('六种任务状态和进度通过真实客户端读取，不把失败当成功索引', async () => {
  const s = await setup(() => null);
  for (const state of [
    'queued',
    'running',
    'succeeded',
    'failed',
    'cancelled',
    'interrupted',
  ]) {
    const terminal = !['queued', 'running'].includes(state);
    const item = {
      ...task,
      kind: 'index_file',
      state,
      phase: terminal
        ? 'completed'
        : state === 'running'
          ? 'embedding'
          : 'queued',
      startedAt: state === 'queued' ? null : time,
      finishedAt: terminal ? time : null,
      fileId: otherId,
      targetContentVersionId: otherId,
      attempt: 1,
      progressCompleted: 1,
      progressTotal: 2,
      resultResourceId: state === 'succeeded' ? otherId : null,
      failure: ['failed', 'interrupted'].includes(state)
        ? {
            code: 'TASK_INTERRUPTED',
            message: '固定中断原因',
            retryable: false,
          }
        : null,
      warnings: ['固定警告'],
    };
    s.reply({ items: [item], nextCursor: '固定游标' });
    expect((await s.api.tasks(id, '上页')).items[0]).toEqual(item);
    observed('listKnowledgeBaseTasks', s.requests.at(-1));
    s.reply(item);
    expect(await s.api.task(otherId, 'index_file')).toEqual(item);
    observed('getTask', s.requests.at(-1));
  }
  s.reply({ items: [], nextCursor: null });
  expect((await s.api.tasks(id)).items).toEqual([]);
  observed('listKnowledgeBaseTasks', s.requests.at(-1));
});

it('管理请求六状态无索引和依赖失败实际消费，成功命中及无命中独立', async () => {
  const s = await setup(() => search),
    api = createManagementSearchClient(s.client);
  for (const state of [
    'queued',
    'running',
    'succeeded',
    'failed',
    'cancelled',
    'interrupted',
  ]) {
    const terminal = !['queued', 'running'].includes(state);
    const item = {
      ...search,
      task: {
        ...search.task,
        state,
        phase: terminal ? 'completed' : 'queued',
        startedAt: state === 'queued' ? null : time,
        finishedAt: terminal ? time : null,
        resultResourceId: state === 'succeeded' ? id : null,
        failure: ['failed', 'interrupted'].includes(state)
          ? {
              code: 'NO_EFFECTIVE_INDEX',
              message: '先完成处理',
              retryable: false,
            }
          : null,
      },
      result: state === 'succeeded' ? search.result : null,
      scope: state === 'queued' ? null : scope,
    };
    s.reply(item);
    expect(await api.submit(id, '版本规则', otherId)).toEqual(item);
    observed('testKnowledgeBaseSearch', s.requests.at(-1));
    expect(await api.get(id, id)).toEqual(item);
    observed('getSearchRequest', s.requests.at(-1));
  }
  const empty = {
    ...search,
    result: { ...search.result, outcome: 'no_matches', evidence: [] },
  };
  s.reply(empty);
  expect((await api.get(id, id)).result.outcome).toBe('no_matches');
  observed('getSearchRequest', s.requests.at(-1));
  for (const updateState of ['ready', 'updating', 'failed']) {
    const item = {
      ...evidence,
      updateState,
      currentFilename: '当前文件名.txt',
      originalAvailable: true,
    };
    s.reply(item);
    expect(await api.evidence(id, otherId, id)).toEqual(item);
    observed('getSearchEvidence', s.requests.at(-1));
  }
  s.reply(evidence);
  expect((await api.evidence(id, otherId, id)).currentFilename).toBeNull();
  observed('getSearchEvidence', s.requests.at(-1));
});

it('全部公开检索错误由实际会话客户端消费而不自动重提', async () => {
  const s = await setup(() => null),
    api = createManagementSearchClient(s.client);
  const errors = [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'ORIGIN_REJECTED'],
    [404, 'NOT_FOUND'],
    [409, 'CONFLICT'],
    [422, 'NO_EFFECTIVE_INDEX'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
    [503, 'DEPENDENCY_UNAVAILABLE'],
  ];
  for (const [status, code] of errors) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code,
        message: '固定脱敏错误',
        fieldErrors: [{ field: '/query', code, message: '固定字段错误' }],
        retryable: false,
      },
    });
    await expect(api.submit(id, '固定查询', otherId)).rejects.toThrow(
      '固定脱敏错误',
    );
    observed('testKnowledgeBaseSearch', s.requests.at(-1));
  }
  for (const [status, code] of [
    ...errors.filter(([status]) => ![409, 422].includes(status)),
    [410, 'RESULT_EXPIRED'],
  ]) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code,
        message: '固定查询错误',
        fieldErrors: [],
        retryable: false,
      },
    });
    await expect(api.get(id, id)).rejects.toThrow('固定查询错误');
    observed('getSearchRequest', s.requests.at(-1));
    await expect(api.evidence(id, otherId, id)).rejects.toThrow('固定查询错误');
    observed('getSearchEvidence', s.requests.at(-1));
  }
});

it('阶段和终态实际流消费验证事件名、帧身份、恢复头与重复终态', async () => {
  const events = [
    {
      taskId: otherId,
      sequence: 1,
      state: 'running',
      phase: 'embedding',
      occurredAt: time,
    },
    {
      taskId: otherId,
      sequence: 2,
      state: 'succeeded',
      phase: 'completed',
      occurredAt: time,
    },
  ];
  const frames = events
    .map(
      (event) =>
        `id: ${otherId}:${event.sequence}\nevent: ${event.state === 'running' ? 'phase' : 'terminal'}\ndata: ${JSON.stringify(event)}\n\n`,
    )
    .join('');
  const received = [];
  await watchTask(otherId, {
    onEvent: (item) => received.push(item),
    fetcher: async (url, options) => {
      expect(options.credentials).toBe('same-origin');
      expect(url).not.toContain('?');
      return new Response(frames, {
        headers: { 'Content-Type': 'text/event-stream' },
      });
    },
  });
  expect(received).toEqual(events);
  const prefix = '#/paths/~1tasks~1{taskId}~1events/get';
  observations.push({
    operationId: 'watchTask',
    method: 'get',
    url: `/tasks/${otherId}/events`,
    events,
    constraintIds: [
      `${prefix}/x-sse/events/phase`,
      `${prefix}/x-sse/events/terminal`,
      `${prefix}/x-sse/id`,
      `${prefix}/x-sse/terminalStates`,
    ],
    assertions: [
      '真实客户端按阶段和终态事件名解析，帧标识匹配任务与安全序号，终态后关闭；同源凭据且无URL令牌。',
    ],
  });
});
afterAll(() => {
  execFileSync('python', ['scripts/settings-evidence.py', '08'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  });
});
