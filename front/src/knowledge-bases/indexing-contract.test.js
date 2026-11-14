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
import { preflight, config, rebuild } from '../../test-fixtures/preflight';
import { createPreflightClient } from './preflight-client';
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

it('文档结构与跨页定位经三个真实客户端入口完整消费', async () => {
  const s = await setup(() => null),
    api = createManagementSearchClient(s.client);
  const base = evidence.location;
  const locations = [
    {
      ...base,
      format: 'markdown',
      lineStart: 3,
      lineEnd: 4,
      titlePath: ['使用指南'],
      paragraphId: 'paragraph-1',
      structureId: 'paragraph-1',
      continuationIndex: 0,
    },
    {
      ...base,
      format: 'markdown',
      lineStart: 8,
      lineEnd: 9,
      titlePath: ['使用指南', '步骤'],
      structureId: 'list-1',
      continuationIndex: 1,
    },
    {
      ...base,
      format: 'markdown',
      lineStart: 11,
      lineEnd: 13,
      titlePath: ['使用指南', '步骤'],
      structureId: 'table-1',
      tableRowStart: 0,
      tableRowEnd: 1,
      tableColumnStart: 0,
      tableColumnEnd: 1,
    },
    {
      ...base,
      format: 'markdown',
      lineStart: 15,
      lineEnd: 17,
      structureId: 'code-1',
      codeBlockId: 'code-1',
      codeLanguage: 'java',
    },
    {
      ...base,
      format: 'pdf',
      lineStart: null,
      lineEnd: null,
      pageStart: 1,
      pageEnd: 2,
    },
  ];
  for (const location of locations) {
    const item = { ...evidence, location, originalAvailable: true };
    const result = {
      ...search,
      result: { ...search.result, evidence: [item] },
    };
    s.reply(result);
    expect(await api.submit(id, '固定文档定位', otherId)).toEqual(result);
    observed('testKnowledgeBaseSearch', s.requests.at(-1));
    expect(await api.get(id, id)).toEqual(result);
    observed('getSearchRequest', s.requests.at(-1));
    s.reply(item);
    expect(await api.evidence(id, otherId, id)).toEqual(item);
    observed('getSearchEvidence', s.requests.at(-1));
  }
  for (const change of [
    { lineStart: 4, lineEnd: 3 },
    { tableRowStart: 2, tableRowEnd: 1 },
    { tableColumnStart: 2, tableColumnEnd: 1 },
    { format: 'pdf', pageStart: 2, pageEnd: 1 },
  ]) {
    s.reply({ ...evidence, location: { ...base, ...change } });
    await expect(api.evidence(id, otherId, id)).rejects.toThrow('响应格式');
  }
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
it('实际会话请求消费预检全部统计及配置更新原任务，保留版本安全信息', async () => {
  const s = await setup(() => preflight),
    api = createPreflightClient(s.client);
  for (const location of [
    preflight.samples[0].location,
    {
      ...preflight.samples[0].location,
      format: 'pdf',
      pageStart: 1,
      pageEnd: 2,
      lineStart: null,
      lineEnd: null,
    },
    {
      ...preflight.samples[0].location,
      format: 'markdown',
      titlePath: ['指南'],
      paragraphId: 'p-1',
      structureId: 's-1',
      continuationIndex: 0,
      tableRowStart: 0,
      tableRowEnd: 1,
      tableColumnStart: 0,
      tableColumnEnd: 1,
      codeBlockId: 'c-1',
      codeLanguage: 'java',
    },
  ]) {
    const value = {
      ...preflight,
      samples: [
        {
          ...preflight.samples[0],
          location,
          fallbackModes: [
            'sentence',
            'delimiter',
            'hard',
            'table_row',
            'table_cell',
            'code_line',
          ],
        },
      ],
    };
    s.reply(value);
    expect(await api.get(otherId)).toEqual(value);
    observed('getChunkPreflight', s.requests.at(-1));
  }
  const empty = {
    ...preflight,
    samples: [],
    warnings: [],
    fallbackCounts: {},
    chunkCount: 0,
    minimumLength: 0,
    maximumLength: 0,
    medianLength: 0,
    p95Length: 0,
    emptyText: true,
    integrityPassed: false,
  };
  s.reply(empty);
  expect(await api.get(otherId)).toEqual(empty);
  observed('getChunkPreflight', s.requests.at(-1));
  for (const state of [
    'queued',
    'running',
    'succeeded',
    'failed',
    'cancelled',
    'interrupted',
  ]) {
    const terminal = !['queued', 'running'].includes(state);
    const task = {
      ...rebuild,
      state,
      phase: terminal ? 'completed' : 'queued',
      startedAt: state === 'queued' ? null : time,
      finishedAt: terminal ? time : null,
      failure: ['failed', 'interrupted'].includes(state)
        ? {
            code: 'INTEGRITY_FAILED',
            message: '固定完整性失败',
            retryable: false,
          }
        : null,
      warnings: ['固定降级提示'],
    };
    s.reply({ task });
    expect(await s.api.updateProcessing(id, config.input, otherId)).toEqual({
      task,
    });
    expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(otherId);
    observed('updateProcessingConfig', s.requests.at(-1));
    s.reply(task);
    expect(await s.api.task(otherId, 'rebuild_knowledge_base')).toEqual(task);
    observed('getTask', s.requests.at(-1));
    s.reply({ items: [task], nextCursor: null });
    expect((await s.api.tasks(id)).items[0]).toEqual(task);
    observed('listKnowledgeBaseTasks', s.requests.at(-1));
  }
});
it('预检所有公开错误和配置更新字段失败实际消费不重提', async () => {
  const s = await setup(() => null),
    api = createPreflightClient(s.client);
  for (const [status, code] of [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'ORIGIN_REJECTED'],
    [404, 'NOT_FOUND'],
    [409, 'CONFLICT'],
    [422, 'INVALID_REQUEST'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
    [503, 'DEPENDENCY_UNAVAILABLE'],
  ]) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code,
        message: '固定配置错误',
        fieldErrors: [
          {
            field: '/chunking/overlapCodePoints',
            code,
            message: '固定字段错误',
          },
        ],
        retryable: false,
      },
    });
    await expect(
      s.api.updateProcessing(id, config.input, otherId),
    ).rejects.toThrow('固定配置错误');
    observed('updateProcessingConfig', s.requests.at(-1));
    if (![409, 422].includes(status)) {
      await expect(api.get(otherId)).rejects.toThrow('固定配置错误');
      observed('getChunkPreflight', s.requests.at(-1));
    }
  }
});
it('未知字段、非整数计数、非法样例位置与超量样例拒绝，不消费伪统计', async () => {
  const s = await setup(() => null),
    api = createPreflightClient(s.client);
  for (const value of [
    null,
    { ...preflight, extra: true },
    { ...preflight, taskId: id },
    { ...preflight, chunkCount: -1 },
    { ...preflight, p95Length: NaN },
    { ...preflight, fallbackCounts: { hard: -1 } },
    { ...preflight, samples: Array(21).fill(preflight.samples[0]) },
    { ...preflight, warnings: [null] },
    {
      ...preflight,
      samples: [{ ...preflight.samples[0], fallbackModes: ['invalid'] }],
    },
    {
      ...preflight,
      samples: [
        {
          ...preflight.samples[0],
          location: {
            ...preflight.samples[0].location,
            lineStart: 5,
            lineEnd: 1,
          },
        },
      ],
    },
  ]) {
    s.reply(value);
    await expect(api.get(otherId)).rejects.toThrow('预检响应格式');
  }
  for (const taskId of [null, '', 5, '错误'])
    await expect(api.get(taskId)).rejects.toThrow('任务标识');
});

it('来源撤销与排除实际请求严格消费空响应，反向列表保留独立来源及未纳入事实', async () => {
  const s = await setup(() => null);
  const exclusion = { knowledgeBaseId: id, fileId: otherId, createdAt: time };
  s.reply(exclusion);
  expect(await s.api.exclude(id, otherId)).toEqual(exclusion);
  observed('excludeKnowledgeBaseFile', s.requests.at(-1));
  s.reply({ items: [exclusion], nextCursor: '下一页' });
  expect(await s.api.exclusions(id, '上一页')).toEqual({
    items: [exclusion],
    nextCursor: '下一页',
  });
  observed('listKnowledgeBaseExclusions', s.requests.at(-1));
  const excluded = {
    ...file,
    excluded: true,
    included: false,
    sources: [],
    task: null,
    targetContentVersionId: null,
    targetConfigVersionId: null,
    updateState: 'not_included',
  };
  for (const item of [file, excluded]) {
    s.reply({ items: [item], nextCursor: null });
    expect(await s.api.fileKnowledgeBases(otherId)).toEqual({
      items: [item],
      nextCursor: null,
    });
    observed('listFileKnowledgeBases', s.requests.at(-1));
    expect(await s.api.files(id)).toEqual({ items: [item], nextCursor: null });
    observed('listKnowledgeBaseFiles', s.requests.at(-1));
  }
  s.reply({ items: [], nextCursor: null });
  expect(await s.api.exclusions(id)).toEqual({ items: [], nextCursor: null });
  observed('listKnowledgeBaseExclusions', s.requests.at(-1));
  expect(await s.api.fileKnowledgeBases(otherId, '上一页')).toEqual({
    items: [],
    nextCursor: null,
  });
  observed('listFileKnowledgeBases', s.requests.at(-1));
  s.reply({ httpStatus: 204, body: '' });
  await s.api.removeSource(id, otherId);
  observed('removeKnowledgeBaseSource', s.requests.at(-1));
  await s.api.include(id, otherId);
  observed('includeKnowledgeBaseFile', s.requests.at(-1));
  expect(s.requests.at(-1).headers.get('X-CSRF-Token')).toBeTruthy();
});
it('清理任务可被观察且目标配置可空，不将清理失败当重新纳入', async () => {
  const cleanup = {
    ...task,
    kind: 'cleanup',
    fileId: otherId,
    targetConfigVersionId: null,
    state: 'failed',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    failure: {
      code: 'CLEANUP_FAILED',
      message: '固定清理失败，资料仍下线',
      retryable: false,
    },
  };
  const s = await setup(() => ({ items: [cleanup], nextCursor: null }));
  expect((await s.api.tasks(id)).items[0]).toEqual(cleanup);
  observed('listKnowledgeBaseTasks', s.requests.at(-1));
  s.reply(cleanup);
  expect(await s.api.task(otherId, 'cleanup')).toEqual(cleanup);
  observed('getTask', s.requests.at(-1));
});
it('来源及排除五操作公开错误由真实客户端保留字段反馈，不自动重试', async () => {
  const s = await setup(() => null);
  const calls = [
    ['removeKnowledgeBaseSource', () => s.api.removeSource(id, otherId)],
    ['excludeKnowledgeBaseFile', () => s.api.exclude(id, otherId)],
    ['includeKnowledgeBaseFile', () => s.api.include(id, otherId)],
    ['listKnowledgeBaseExclusions', () => s.api.exclusions(id)],
    ['listFileKnowledgeBases', () => s.api.fileKnowledgeBases(otherId)],
  ];
  for (const [operationId, call] of calls) {
    for (const [status, code] of [
      [400, 'INVALID_REQUEST'],
      [401, 'SESSION_EXPIRED'],
      [403, 'CSRF_REJECTED'],
      [404, 'NOT_FOUND'],
      [409, 'CONFLICT'],
      [422, 'INVALID_REQUEST'],
      [429, 'QUEUE_FULL'],
      [500, 'INTERNAL_ERROR'],
      [503, 'DEPENDENCY_UNAVAILABLE'],
    ]) {
      if (operationId.startsWith('list') && [409, 422].includes(status))
        continue;
      s.reply({
        errorStatus: status,
        body: {
          requestId: id,
          code,
          message: '固定来源错误',
          fieldErrors: [{ field: '/fileId', code, message: '固定身份错误' }],
          retryable: false,
        },
      });
      await expect(call()).rejects.toThrow('固定来源错误');
      observed(operationId, s.requests.at(-1));
    }
  }
});
it('来源排除拒绝跨身份响应及伪空正文，非法标识与游标不发送', async () => {
  const s = await setup(() => null);
  for (const invalid of ['', null, 4, '错误']) {
    await expect(s.api.exclude(id, invalid)).rejects.toThrow('资料标识');
    await expect(s.api.include(id, invalid)).rejects.toThrow('资料标识');
    await expect(s.api.removeSource(id, invalid)).rejects.toThrow('来源标识');
    await expect(s.api.fileKnowledgeBases(invalid)).rejects.toThrow('资料标识');
  }
  expect(s.requests).toHaveLength(0);
  for (const cursor of ['', 2, '字'.repeat(2049)])
    await expect(s.api.exclusions(id, cursor)).rejects.toThrow('分页位置');
  s.reply({ httpStatus: 204, body: { result: '伪正文' } });
  await expect(s.api.include(id, otherId)).rejects.toThrow('响应格式');
  s.reply({ knowledgeBaseId: otherId, fileId: otherId, createdAt: time });
  await expect(s.api.exclude(id, otherId)).rejects.toThrow('响应格式');
  s.reply({ items: [{ ...file, fileId: id }], nextCursor: null });
  await expect(s.api.fileKnowledgeBases(otherId)).rejects.toThrow('响应格式');
});

it('成功切换关系实际消费首次空旧版及后续完整版本，取消只接受原任务事实', async () => {
  const s = await setup(() => null);
  for (const first of [true, false]) {
    const item = {
      id: otherId,
      knowledgeBaseId: id,
      fileId: otherId,
      fromContentVersionId: first ? null : id,
      toContentVersionId: otherId,
      fromIndexVersionId: first ? null : id,
      toIndexVersionId: otherId,
      fromConfigVersionId: first ? null : id,
      toConfigVersionId: otherId,
      taskId: otherId,
      switchedAt: time,
    };
    s.reply({ items: [item], nextCursor: first ? '下一页' : null });
    expect(
      (await s.api.switches(id, first ? undefined : '上一页')).items[0],
    ).toEqual(item);
    observed('listIndexSwitches', s.requests.at(-1));
  }
  s.reply({ items: [], nextCursor: null });
  expect((await s.api.switches(id)).items).toEqual([]);
  observed('listIndexSwitches', s.requests.at(-1));
  for (const kind of ['index_file', 'rebuild_knowledge_base', 'search']) {
    for (const state of ['cancelled', 'succeeded', 'failed', 'interrupted']) {
      const value = {
        ...file.task,
        kind,
        state,
        phase: 'completed',
        startedAt: time,
        finishedAt: time,
        resultResourceId:
          state === 'succeeded' && kind !== 'rebuild_knowledge_base'
            ? otherId
            : null,
        failure: ['failed', 'interrupted'].includes(state)
          ? {
              code: 'TASK_INTERRUPTED',
              message: '固定任务失败',
              retryable: false,
            }
          : null,
      };
      s.reply(value);
      expect(await s.api.cancel(value)).toEqual(value);
      observed('cancelTask', s.requests.at(-1));
    }
  }
});
it('取消与切换公开错误实际消费不自动发出新任务', async () => {
  const s = await setup(() => null);
  for (const [status, code] of [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'CSRF_REJECTED'],
    [404, 'NOT_FOUND'],
    [409, 'CONFLICT'],
    [422, 'INVALID_REQUEST'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
    [503, 'DEPENDENCY_UNAVAILABLE'],
  ]) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code,
        message: '固定版本操作失败',
        fieldErrors: [{ field: '/taskId', code, message: '固定任务说明' }],
        retryable: false,
      },
    });
    await expect(s.api.cancel(file.task)).rejects.toThrow('固定版本操作失败');
    observed('cancelTask', s.requests.at(-1));
    if (![409, 422].includes(status)) {
      await expect(s.api.switches(id)).rejects.toThrow('固定版本操作失败');
      observed('listIndexSwitches', s.requests.at(-1));
    }
  }
});

it('发布下线消费完整历史与独立可用状态，复查携带费用确认和原幂等键', async () => {
  const s = await setup(() => null);
  for (const publicationState of ['unpublished', 'published', 'offline']) {
    const value = {
      ...record,
      publicationState,
      publishedAt: publicationState === 'unpublished' ? null : time,
      offlineAt: publicationState === 'offline' ? time : null,
      offlineReason: publicationState === 'offline' ? 'manual' : null,
      availability: 'unavailable',
      availabilityReason: '受控依赖故障',
    };
    for (const [method, operation] of [
      ['publish', 'publishKnowledgeBase'],
      ['offline', 'offlineKnowledgeBase'],
    ]) {
      s.reply(value);
      expect(await s.api[method](id.toUpperCase())).toEqual(value);
      expect(s.requests.at(-1).data).toBeUndefined();
      observed(operation, s.requests.at(-1));
      s.reply({ ...value, id: otherId });
      await expect(s.api[method](id)).rejects.toThrow('响应格式');
    }
  }
  const check = { ...task, kind: 'model_test' };
  s.reply({ items: [check], nextCursor: null });
  expect((await s.api.tasks(id)).items).toEqual([check]);
  observed('listKnowledgeBaseTasks', s.requests.at(-1));
  s.reply({
    items: [{ ...check, knowledgeBaseId: otherId }],
    nextCursor: null,
  });
  await expect(s.api.tasks(id)).rejects.toThrow('响应格式');
  s.reply({ task: check });
  for (let attempt = 0; attempt < 2; attempt++) {
    expect(await s.api.check(id, otherId, true)).toEqual({ task: check });
    expect(JSON.parse(s.requests.at(-1).data)).toEqual({
      allowChargedCall: true,
    });
    expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(otherId);
    observed('checkKnowledgeBase', s.requests.at(-1));
  }
  s.reply({ task: { ...check, knowledgeBaseId: otherId } });
  await expect(s.api.check(id, otherId, true)).rejects.toThrow('响应格式');
  await expect(s.api.check(id, otherId, false)).rejects.toThrow('费用');
  await expect(s.api.check(id, '无效', true)).rejects.toThrow('费用');
});
it('发布下线复查公开错误实际消费，保留原状态且不自动创建新任务', async () => {
  const s = await setup(() => null);
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
        message: '固定发布操作失败',
        fieldErrors: [
          { field: '/knowledgeBaseId', code, message: '固定知识库说明' },
        ],
        retryable: false,
      },
    });
    for (const [method, operation] of [
      ['publish', 'publishKnowledgeBase'],
      ['offline', 'offlineKnowledgeBase'],
      ['check', 'checkKnowledgeBase'],
    ]) {
      await expect(s.api[method](id, otherId, true)).rejects.toThrow(
        '固定发布操作失败',
      );
      observed(operation, s.requests.at(-1));
    }
  }
});
afterAll(() => {
  execFileSync(
    'python',
    ['scripts/settings-evidence.py', '08', 'knowledge-bases-indexing-contract'],
    {
      input: JSON.stringify(observations),
      encoding: 'utf8',
    },
  );
});
