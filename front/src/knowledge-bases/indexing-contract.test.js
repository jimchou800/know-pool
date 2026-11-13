import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import {
  setup,
  id,
  otherId,
  task,
  time,
  record,
  file,
} from '../../test-fixtures/knowledge-bases';
import { search, evidence, scope } from '../../test-fixtures/indexing';
import { createManagementSearchClient } from './search-client';
import { watchTask } from './task-events';
import { errorCodes } from '../system/session';
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
  const queued = { ...file.task };
  s.reply({ items: [queued], nextCursor: null });
  expect((await s.api.tasks(id)).items[0].progressTotal).toBeNull();
  observed('listKnowledgeBaseTasks', s.requests.at(-1));
});

it('索引完成与失败回读实际消费生效版本及知识库依赖故障', async () => {
  const s = await setup(() => null);
  for (const failed of [false, true]) {
    const failure = failed
      ? { code: 'MODEL_UNAVAILABLE', message: '固定嵌入故障', retryable: false }
      : null;
    const terminalTask = {
      ...file.task,
      state: failed ? 'failed' : 'succeeded',
      phase: 'completed',
      startedAt: time,
      finishedAt: time,
      progressCompleted: 1,
      progressTotal: 1,
      warnings: ['固定索引提示'],
      attempt: 1,
      failure,
      resultResourceId: failed ? null : otherId,
    };
    const indexed = {
      ...file,
      effectiveContentVersionId: otherId,
      effectiveIndexVersionId: otherId,
      effectiveConfigVersionId: otherId,
      updateState: failed ? 'failed' : 'ready',
      usingPreviousVersion: failed,
      failure,
      task: terminalTask,
    };
    s.reply({ items: [indexed], nextCursor: null });
    expect((await s.api.files(id)).items[0]).toEqual(indexed);
    observed('listKnowledgeBaseFiles', s.requests.at(-1));
    const current = {
      ...record,
      effectiveIndexVersionId: otherId,
      effectiveConfigVersionId: otherId,
      includedFileCount: 1,
      searchableFileCount: 1,
      availability: failed ? 'unavailable' : 'available',
      availabilityReason: failed ? '固定嵌入故障' : null,
      processingState: failed ? 'failed' : 'idle',
    };
    s.reply({ items: [current], nextCursor: null });
    expect((await s.api.list()).items[0]).toEqual(current);
    observed('listKnowledgeBases', s.requests.at(-1));
    s.reply(current);
    expect(await s.api.get(id)).toEqual(current);
    observed('getKnowledgeBase', s.requests.at(-1));
    expect(
      await s.api.update(id, { name: current.name, description: '' }),
    ).toEqual(current);
    observed('updateKnowledgeBase', s.requests.at(-1));
  }
});

it('任务列表与原任务公开错误实际消费字段说明且不另建任务', async () => {
  const s = await setup(() => null);
  for (const [status, code] of [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'ORIGIN_REJECTED'],
    [404, 'NOT_FOUND'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
    [503, 'DEPENDENCY_UNAVAILABLE'],
  ]) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code,
        message: '固定任务查询错误',
        fieldErrors: [{ field: '/cursor', code, message: '固定游标错误' }],
        retryable: false,
      },
    });
    await expect(s.api.tasks(id)).rejects.toThrow('固定任务查询错误');
    observed('listKnowledgeBaseTasks', s.requests.at(-1));
    await expect(s.api.task(otherId, 'index_file')).rejects.toThrow(
      '固定任务查询错误',
    );
    observed('getTask', s.requests.at(-1));
  }
  expect(s.requests.every((item) => item.method === 'get')).toBe(true);
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
  const named = {
    ...search,
    task: {
      ...search.task,
      targetConfigVersionId: null,
      warnings: ['固定任务提示'],
    },
    result: {
      ...search.result,
      evidence: [
        {
          ...evidence,
          currentFilename: '当前资料.txt',
          location: { ...evidence.location, continuationIndex: 0 },
        },
      ],
    },
  };
  s.reply(named);
  expect(await api.submit(id, '版本规则', otherId)).toEqual(named);
  observed('testKnowledgeBaseSearch', s.requests.at(-1));
  expect(await api.get(id, id)).toEqual(named);
  observed('getSearchRequest', s.requests.at(-1));
  s.reply(named.result.evidence[0]);
  expect(await api.evidence(id, otherId, id)).toEqual(named.result.evidence[0]);
  observed('getSearchEvidence', s.requests.at(-1));
  const unavailable = {
    ...search,
    task: {
      ...search.task,
      state: 'failed',
      resultResourceId: null,
      failure: {
        code: 'NO_EFFECTIVE_INDEX',
        message: '当前库尚无生效索引',
        retryable: false,
      },
    },
    scope: {
      selectedKnowledgeBaseIds: [id],
      usedKnowledgeBaseIds: [],
      omitted: [
        {
          knowledgeBaseId: id,
          reason: 'no_effective_index',
          message: '当前库尚无生效索引',
        },
      ],
      incomplete: true,
    },
    result: null,
  };
  s.reply(unavailable);
  expect(await api.submit(id, '固定查询', otherId)).toEqual(unavailable);
  observed('testKnowledgeBaseSearch', s.requests.at(-1));
  expect(await api.get(id, id)).toEqual(unavailable);
  observed('getSearchRequest', s.requests.at(-1));
  const warned = {
    ...search,
    result: { ...search.result, warnings: ['固定版本更新提示'] },
  };
  s.reply(warned);
  expect((await api.submit(id, '版本规则', otherId)).result.warnings).toEqual([
    '固定版本更新提示',
  ]);
  observed('testKnowledgeBaseSearch', s.requests.at(-1));
  expect((await api.get(id, id)).result.warnings).toEqual(['固定版本更新提示']);
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
        fieldErrors: [{ field: '/requestId', code, message: '固定字段错误' }],
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
  const resumed = [];
  await watchTask(otherId, {
    lastEventId: `${otherId}:1`,
    onEvent: (item) => resumed.push(item),
    fetcher: async (_, options) => {
      expect(options.headers['Last-Event-ID']).toBe(`${otherId}:1`);
      return new Response(frames, {
        headers: { 'Content-Type': 'text/event-stream' },
      });
    },
  });
  expect(resumed).toEqual([events[1]]);
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
      `${prefix}/responses/200/content/text~1event-stream/schema/type`,
    ],
    assertions: [
      '真实客户端按阶段和终态事件名解析，帧标识匹配任务与安全序号，终态后关闭；同源凭据且无URL令牌。',
    ],
  });
});
it('事件错误正文由实际订阅客户端校验并保留字段说明', async () => {
  for (const [status, code] of [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'ORIGIN_REJECTED'],
    [404, 'NOT_FOUND'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
  ]) {
    const detail = {
      requestId: id,
      code,
      message: '固定订阅错误',
      retryable: false,
      fieldErrors: [{ field: '/taskId', code, message: '固定任务字段错误' }],
    };
    let sent;
    await expect(
      watchTask(otherId, {
        lastEventId: `${otherId}:1`,
        fetcher: async (url, options) => {
          sent = { url, options };
          return Response.json(detail, { status });
        },
      }),
    ).rejects.toMatchObject({
      message: expect.stringContaining('固定订阅错误'),
      code,
      fieldErrors: detail.fieldErrors,
    });
    expect(sent.options.headers['Last-Event-ID']).toBe(`${otherId}:1`);
    observations.push({
      operationId: 'watchTask',
      method: 'get',
      url: `/tasks/${otherId}/events`,
      params: {},
      headers: sent.options.headers,
      body: null,
      status,
      response: detail,
    });
  }
});
it('事件订阅复用全部正式共享错误码并保留可重试事实', async () => {
  expect(errorCodes).toHaveLength(18);
  for (const code of errorCodes) {
    const detail = {
      requestId: id,
      code,
      message: '固定共享错误',
      retryable: true,
      fieldErrors: [],
    };
    await expect(
      watchTask(otherId, {
        fetcher: async () => Response.json(detail, { status: 503 }),
      }),
    ).rejects.toMatchObject({
      code,
      retryable: true,
      fieldErrors: [],
      message: expect.stringContaining('固定共享错误'),
    });
  }
});
afterAll(() => {
  execFileSync('python', ['scripts/settings-evidence.py', '08'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  });
});
