import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import {
  setup,
  id,
  otherId,
  time,
  task,
} from '../../test-fixtures/knowledge-bases';
import { accepted, questionTask, answer } from '../../test-fixtures/answers';
import { createAnswerClient } from './client';
import {
  questionVariants,
  variantInput,
} from '../../test-fixtures/answer-variants';
const observations = [];
const cleanup = {
  task: {
    ...task,
    kind: 'cleanup',
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    knowledgeBaseId: null,
    targetConfigVersionId: null,
    attempt: 1,
    progressCompleted: 1,
    progressTotal: 1,
  },
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
it('历史与同键追问恢复保留每轮部分范围、位置快照及中断信息', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
  for (const value of questionVariants()) {
    s.reply({
      items: [{ id: otherId, questionTask: value }],
      nextCursor: null,
    });
    expect((await api.turns(id)).items[0].questionTask).toEqual(value);
    observe('listConversationTurns', s.requests.at(-1));
    const { question, knowledgeBaseIds } = variantInput(value);
    s.reply({ httpStatus: 202, body: value });
    expect(
      await api.submit(id, { question, knowledgeBaseIds }, otherId),
    ).toEqual(value);
    observe('submitQuestion', s.requests.at(-1));
    const conversation = {
      ...accepted.conversation,
      activeTask: ['queued', 'running'].includes(value.task.state)
        ? value.task
        : null,
      lastTask: value.task,
    };
    s.reply({ items: [conversation], nextCursor: null });
    expect((await api.list()).items[0]).toEqual(conversation);
    observe('listConversations', s.requests.at(-1));
    s.reply(conversation);
    expect(await api.getConversation(id)).toEqual(conversation);
    observe('getConversation', s.requests.at(-1));
    expect(await api.rename(id, conversation.name)).toEqual(conversation);
    observe('renameConversation', s.requests.at(-1));
    expect(await api.scope(id, conversation.knowledgeBaseIds)).toEqual(
      conversation,
    );
    observe('updateConversationScope', s.requests.at(-1));
  }
});
it('会话分页读取手名允许重名，范围保存为空不改写历史，追问复用显式原范围与同键', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client),
    conversation = accepted.conversation;
  s.reply({ items: [conversation], nextCursor: 'opaque-next' });
  expect((await api.list()).items).toEqual([conversation]);
  observe('listConversations', s.requests.at(-1));
  s.reply({ items: [], nextCursor: null });
  expect((await api.list('opaque-next')).nextCursor).toBeNull();
  expect(s.requests.at(-1).params.cursor).toBe('opaque-next');
  observe('listConversations', s.requests.at(-1));
  s.reply(conversation);
  expect(await api.getConversation(id)).toEqual(conversation);
  observe('getConversation', s.requests.at(-1));
  expect(await api.rename(id, conversation.name)).toEqual(conversation);
  observe('renameConversation', s.requests.at(-1));
  s.reply({ ...conversation, knowledgeBaseIds: [] });
  expect((await api.scope(id, [])).knowledgeBaseIds).toEqual([]);
  observe('updateConversationScope', s.requests.at(-1));
  for (const historyContext of ['none', 'all_turns', 'recent_complete_turns']) {
    const value = {
      ...questionTask,
      answer: {
        ...answer,
        historyContext,
        omittedHistoryTurnCount:
          historyContext === 'recent_complete_turns' ? 3 : 0,
      },
    };
    s.reply({
      items: [{ id: otherId, questionTask: value }],
      nextCursor: null,
    });
    expect(
      (await api.turns(id)).items[0].questionTask.answer.historyContext,
    ).toBe(historyContext);
    observe('listConversationTurns', s.requests.at(-1));
  }
  s.reply({ items: [], nextCursor: null });
  expect((await api.turns(id, 'turn-next')).items).toEqual([]);
  observe('listConversationTurns', s.requests.at(-1));
  s.reply({ httpStatus: 202, body: questionTask });
  const input = { question: questionTask.question, knowledgeBaseIds: [id] };
  for (let index = 0; index < 2; index++) {
    expect(await api.submit(id, input, otherId)).toEqual(questionTask);
    expect(JSON.parse(s.requests.at(-1).data)).toEqual(input);
    observe('submitQuestion', s.requests.at(-1));
  }
  s.reply({ httpStatus: 202, body: cleanup });
  expect(await api.remove(id, otherId)).toEqual(cleanup);
  expect(JSON.parse(s.requests.at(-1).data)).toEqual({ confirmed: true });
  observe('deleteConversation', s.requests.at(-1));
});
it('历史及实例拒绝未知字段错会话，删除严格消费已完成清理身份', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
  s.reply({
    items: [{ ...accepted.conversation, draft: '非法' }],
    nextCursor: null,
  });
  await expect(api.list()).rejects.toThrow('不符合');
  s.reply({
    items: [
      {
        id: otherId,
        questionTask: { ...questionTask, conversationId: otherId },
      },
    ],
    nextCursor: null,
  });
  await expect(api.turns(id)).rejects.toThrow('不符合');
  for (const change of [
    { resultResourceId: id },
    { progressTotal: null },
    { knowledgeBaseId: id },
    { state: 'queued', phase: 'queued', finishedAt: null },
  ]) {
    s.reply({
      httpStatus: 202,
      body: { task: { ...cleanup.task, ...change } },
    });
    await expect(api.remove(id, otherId)).rejects.toThrow('不符合');
  }
  await expect(
    api.submit(id, { question: ' ', knowledgeBaseIds: [id] }, otherId),
  ).rejects.toThrow('有效');
});
it('七操作公开错误由真实客户端处理，同会话冲突不创建替代请求', async () => {
  const s = await setup(() => null),
    api = createAnswerClient(s.client);
  const operations = [
    ['listConversations', () => api.list(), false],
    ['getConversation', () => api.getConversation(id), false],
    ['renameConversation', () => api.rename(id, '同名'), true],
    ['updateConversationScope', () => api.scope(id, []), true],
    ['listConversationTurns', () => api.turns(id), false],
    [
      'submitQuestion',
      () =>
        api.submit(
          id,
          { question: questionTask.question, knowledgeBaseIds: [id] },
          otherId,
        ),
      true,
    ],
    ['deleteConversation', () => api.remove(id, otherId), true],
  ];
  for (const [operation, invoke, write] of operations) {
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
          message: '固定会话错误',
          fieldErrors: [{ field: '/question', code, message: '固定字段错误' }],
          retryable: false,
        },
      });
      await expect(invoke()).rejects.toThrow('固定会话错误');
      observe(operation, s.requests.at(-1));
    }
  }
});
afterAll(() =>
  execFileSync(
    'python',
    ['scripts/settings-evidence.py', '08', 'answers-history-client'],
    {
      input: JSON.stringify(observations),
      encoding: 'utf8',
    },
  ),
);
