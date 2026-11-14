import { afterAll, expect, it, vi } from 'vitest';
import { execFileSync } from 'node:child_process';
import { setup, id, otherId, time } from '../../test-fixtures/knowledge-bases';
import { evidence } from '../../test-fixtures/indexing';
import {
  input,
  accepted,
  questionTask,
  answer,
} from '../../test-fixtures/answers';
import { createAnswerClient } from './client';
import { watchTask } from '../knowledge-bases/task-events';
import {
  evidenceVariants,
  questionVariants,
  variantInput,
} from '../../test-fixtures/answer-variants';
const observations = [];
function observed(operationId, r) {
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
it('同键恢复消费部分回答的完整位置、遗漏范围及无正文任务状态', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
  for (const value of questionVariants()) {
    const request = variantInput(value);
    const firstValue = value.answer
      ? {
          ...value,
          task: { ...value.task, warnings: [value.answer.warnings[0]] },
          answer: {
            ...value.answer,
            historyContext: 'none',
            omittedHistoryTurnCount: 0,
            warnings: [value.answer.warnings[0]],
          },
        }
      : value;
    const conversation = {
      ...accepted.conversation,
      knowledgeBaseIds: value.selectedKnowledgeBaseIds,
      lastTask: firstValue.task,
      activeTask: ['queued', 'running'].includes(firstValue.task.state)
        ? firstValue.task
        : null,
    };
    const replay = { conversation, questionTask: firstValue };
    s.reply({ httpStatus: 202, body: replay });
    expect(await api.first(request, id)).toEqual(replay);
    observed('submitFirstQuestion', s.requests.at(-1));
    s.reply(value);
    expect(await api.get(otherId, request, id)).toEqual(value);
    observed('getQuestionTask', s.requests.at(-1));
    if (!['queued', 'running'].includes(value.task.state)) {
      expect(await api.cancel(otherId, request, id)).toEqual(value);
      observed('cancelQuestionTask', s.requests.at(-1));
    }
    if (value.answer) {
      expect(value.answer.scope.omitted).toHaveLength(1);
      expect(value.answer.evidence[0].contentVersionId).toBe(
        evidence.contentVersionId,
      );
      expect(value.answer.warnings).toHaveLength(2);
    } else expect(value.answer).toBeNull();
  }
});
it('引用消费保留 PDF 页码及 Markdown 段落、表格、代码位置和重命名快照', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
  for (const item of evidenceVariants()) {
    s.reply(item);
    const result = await api.evidence(id, item);
    expect(result).toEqual(item);
    expect(result.filenameAtCapture).not.toBe(result.currentFilename);
    expect(result.originalAvailable).toBe(false);
    expect(result.location).toEqual(item.location);
    observed('getConversationEvidence', s.requests.at(-1));
  }
});
it('首问原子受理及同键恢复消费会话任务，最终引用绑定实际内容版本', async () => {
  const s = await setup(() => ({ httpStatus: 202, body: accepted })),
    api = createAnswerClient(s.client);
  for (let attempt = 0; attempt < 2; attempt++) {
    expect(await api.first(input, id)).toEqual(accepted);
    expect(JSON.parse(s.requests.at(-1).data)).toEqual(input);
    observed('submitFirstQuestion', s.requests.at(-1));
  }
  s.reply(questionTask);
  expect(await api.get(otherId, input, id)).toEqual(questionTask);
  observed('getQuestionTask', s.requests.at(-1));
  expect(await api.cancel(otherId, input, id)).toEqual(questionTask);
  observed('cancelQuestionTask', s.requests.at(-1));
  s.reply(evidence);
  expect(await api.evidence(id, evidence)).toEqual(evidence);
  observed('getConversationEvidence', s.requests.at(-1));
  s.reply({ ...evidence, contentVersionId: id });
  await expect(api.evidence(id, evidence)).rejects.toThrow('不符合');
});
it('问答状态失败校验取消均无草稿，成功消费无依据部分缺口与矛盾', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
  for (const outcome of [
    'answered',
    'partial',
    'no_evidence',
    'needs_clarification',
  ]) {
    const value = {
      ...questionTask,
      answer: {
        ...answer,
        outcome,
        ...(outcome === 'no_evidence'
          ? { evidence: [], citationEvidenceIds: [] }
          : {}),
        uncoveredQuestions: outcome === 'partial' ? ['其余条件缺少依据'] : [],
        contradictions: outcome === 'partial' ? ['两个来源条件不同'] : [],
      },
    };
    s.reply(value);
    expect(await api.get(otherId, input, id)).toEqual(value);
    observed('getQuestionTask', s.requests.at(-1));
  }
  for (const state of [
    'queued',
    'running',
    'failed',
    'cancelled',
    'interrupted',
  ]) {
    const terminal = !['queued', 'running'].includes(state);
    const value = {
      ...questionTask,
      answer: null,
      task: {
        ...questionTask.task,
        state,
        phase: terminal
          ? 'completed'
          : state === 'queued'
            ? 'queued'
            : 'answer_validating',
        startedAt: state === 'queued' ? null : time,
        finishedAt: terminal ? time : null,
        resultResourceId: null,
        failure: ['failed', 'interrupted'].includes(state)
          ? {
              code:
                state === 'failed'
                  ? 'ANSWER_VALIDATION_FAILED'
                  : 'TASK_INTERRUPTED',
              message: '固定校验或中断原因',
              retryable: false,
            }
          : null,
      },
    };
    s.reply(value);
    expect(await api.get(otherId, input, id)).toEqual(value);
    observed('getQuestionTask', s.requests.at(-1));
    if (state === 'cancelled') {
      expect(await api.cancel(otherId, input, id)).toEqual(value);
      observed('cancelQuestionTask', s.requests.at(-1));
    }
    s.reply({ ...value, answer });
    await expect(api.get(otherId, input, id)).rejects.toThrow('不符合');
  }
  s.reply({
    ...questionTask,
    answer: { ...answer, citationEvidenceIds: [id] },
  });
  await expect(api.get(otherId, input, id)).rejects.toThrow('不符合');
  s.reply({ ...questionTask, draft: '不允许的草稿' });
  await expect(api.get(otherId, input, id)).rejects.toThrow('不符合');
});
it('问答公开错误真实处理，查询失败不重交首问', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
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
        message: '固定问答接口错误',
        fieldErrors: [{ field: '/question', code, message: '固定字段错误' }],
        retryable: false,
      },
    });
    await expect(api.first(input, id)).rejects.toThrow('固定问答接口错误');
    observed('submitFirstQuestion', s.requests.at(-1));
    await expect(api.cancel(otherId, input, id)).rejects.toThrow(
      '固定问答接口错误',
    );
    observed('cancelQuestionTask', s.requests.at(-1));
    if (![409, 422].includes(status)) {
      await expect(api.get(otherId, input, id)).rejects.toThrow(
        '固定问答接口错误',
      );
      observed('getQuestionTask', s.requests.at(-1));
      await expect(api.evidence(id, evidence)).rejects.toThrow(
        '固定问答接口错误',
      );
      observed('getConversationEvidence', s.requests.at(-1));
    }
  }
});
it('问答专用事件路由同源恢复头仅通知阶段，无正文且拒绝草稿字段', async () => {
  const phase = {
      taskId: otherId,
      sequence: 1,
      state: 'running',
      phase: 'answer_validating',
      occurredAt: time,
    },
    terminal = {
      ...phase,
      sequence: 2,
      state: 'succeeded',
      phase: 'completed',
    };
  const frame = (event, name) =>
    `id: ${otherId}:${event.sequence}\nevent: ${name}\ndata: ${JSON.stringify(event)}\n\n`;
  const fetcher = vi.fn(
      async () =>
        new Response(
          ':心跳\n\n' + frame(phase, 'phase') + frame(terminal, 'terminal'),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
    ),
    receive = vi.fn();
  await watchTask(otherId, {
    question: true,
    fetcher,
    lastEventId: `${otherId}:1`,
    onEvent: receive,
  });
  expect(fetcher.mock.calls[0][0]).toBe(
    `/api/v1/question-tasks/${otherId}/events`,
  );
  expect(fetcher.mock.calls[0][1]).toMatchObject({
    credentials: 'same-origin',
    headers: { 'Last-Event-ID': `${otherId}:1` },
  });
  expect(receive).toHaveBeenCalledTimes(1);
  const prefix = '#/paths/~1question-tasks~1{taskId}~1events/get';
  observations.push({
    operationId: 'watchQuestionTask',
    method: 'get',
    url: `/question-tasks/${otherId}/events`,
    headers: fetcher.mock.calls[0][1].headers,
    events: [phase, terminal],
    constraintIds: [
      `${prefix}/x-sse/events/phase`,
      `${prefix}/x-sse/events/terminal`,
      `${prefix}/x-sse/id`,
      `${prefix}/x-sse/lastEventIdHeader`,
      `${prefix}/x-sse/terminalStates`,
      `${prefix}/x-sse/reconnect`,
      `${prefix}/responses/200/content/text~1event-stream/schema/type`,
    ],
    assertions: [
      '问答专用路由消费心跳、阶段及终态，携带恢复位置且不重新提交收费问题。',
    ],
  });
  await expect(
    watchTask(otherId, {
      question: true,
      onEvent: receive,
      fetcher: async () =>
        new Response(frame({ ...terminal, draft: '非法草稿' }, 'terminal'), {
          headers: { 'Content-Type': 'text/event-stream' },
        }),
    }),
  ).rejects.toThrow('格式');
});
it('问答专用事件接口声明的公开错误均由订阅客户端消费', async () => {
  for (const [status, code] of [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'ORIGIN_REJECTED'],
    [404, 'NOT_FOUND'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
    [503, 'DEPENDENCY_UNAVAILABLE'],
  ]) {
    const detail = {
      requestId: id,
      code,
      message: '固定问答订阅错误',
      fieldErrors: [],
      retryable: false,
    };
    let sent;
    await expect(
      watchTask(otherId, {
        question: true,
        lastEventId: `${otherId}:1`,
        fetcher: async (url, options) => {
          sent = { url, options };
          return Response.json(detail, { status });
        },
      }),
    ).rejects.toMatchObject({ code });
    observations.push({
      operationId: 'watchQuestionTask',
      method: 'get',
      url: sent.url.replace('/api/v1', ''),
      params: {},
      headers: sent.options.headers,
      body: null,
      status,
      response: detail,
    });
  }
});
afterAll(() =>
  execFileSync(
    'python',
    ['scripts/settings-evidence.py', '08', 'answers-client'],
    {
      input: JSON.stringify(observations),
      encoding: 'utf8',
    },
  ),
);
