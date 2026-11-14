import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import Answers from './Answers.vue';
import AnswerWindow from './AnswerWindow.vue';
import RecentAnswers from './RecentAnswers.vue';
import {
  setup,
  id,
  otherId,
  time,
  record,
  task,
} from '../../test-fixtures/knowledge-bases';
import { accepted, questionTask, answer } from '../../test-fixtures/answers';
const button = (w, name) =>
  w.findAll('button').find((item) => item.text() === name);
const first = accepted.conversation;
const secondTask = {
  ...questionTask,
  conversationId: otherId,
  task: { ...questionTask.task, id, resultResourceId: id },
};
const second = {
  ...first,
  id: otherId,
  name: '另一个会话',
  lastTask: secondTask.task,
};
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
afterEach(() => vi.unstubAllGlobals());
async function ready(extra = () => undefined) {
  const s = await setup((r) => {
    const override = extra(r);
    if (override !== undefined) return override;
    if (r.url === '/conversations')
      return { items: [first, second], nextCursor: null };
    if (r.url === '/knowledge-bases')
      return {
        items: [{ ...record, publicationState: 'published' }],
        nextCursor: null,
      };
    if (r.url.endsWith('/turns'))
      return {
        items: [
          {
            id: otherId,
            questionTask: r.url.includes(otherId) ? secondTask : questionTask,
          },
        ],
        nextCursor: null,
      };
    if (r.url.startsWith('/question-tasks/'))
      return r.url.includes(otherId) ? questionTask : secondTask;
    if (r.method === 'delete') return { httpStatus: 202, body: cleanup };
    if (r.url.endsWith('/name'))
      return { ...first, name: JSON.parse(r.data).name };
    return r.url.endsWith(otherId) ? second : first;
  });
  const w = mount(Answers, { props: { client: s.client } });
  await flushPromises();
  return { s, w };
}
it('本地空白不保存，切换保留输入，重名后删除仅卸载对应会话', async () => {
  const { s, w } = await ready();
  await w.find('textarea').setValue('保留第一会话输入');
  await w.get(`[data-conversation-id="${otherId}"] button`).trigger('click');
  await flushPromises();
  await w.get(`[data-conversation-id="${id}"] button`).trigger('click');
  await flushPromises();
  expect(
    w
      .findAllComponents(AnswerWindow)
      .find((window) => window.props('initialConversation')?.id === id)
      .get('textarea').element.value,
  ).toBe('保留第一会话输入');
  await button(w, '重命名当前问答').trigger('click');
  await w.get('#conversation-name').setValue(second.name);
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(
    w
      .findAll('[data-conversation-id] strong')
      .filter((item) => item.text() === second.name),
  ).toHaveLength(2);
  await button(w, '删除当前问答').trigger('click');
  expect(w.text()).toContain('不删除源资料');
  await button(w, '保留当前会话').trigger('click');
  expect(s.requests.some((r) => r.method === 'delete')).toBe(false);
  await button(w, '删除当前问答').trigger('click');
  await button(w, '确认删除问答实例').trigger('click');
  await flushPromises();
  expect(w.find(`[data-conversation-id="${id}"]`).exists()).toBe(false);
  expect(w.find(`[data-conversation-id="${otherId}"]`).exists()).toBe(true);
  await button(w, '新建问答').trigger('click');
  await flushPromises();
  expect(
    s.requests.some((r) => r.url === '/conversations/first-question'),
  ).toBe(false);
  expect(w.text()).toContain('首问前不保存');
  w.unmount();
});
it('历史分页与近期完整上下文提示按各轮固定范围展示，保存后续范围不提交任务', async () => {
  const historical = {
    ...questionTask,
    answer: {
      ...answer,
      historyContext: 'recent_complete_turns',
      omittedHistoryTurnCount: 4,
    },
  };
  const { s, w } = await ready((r) => {
    if (r.url.endsWith('/turns'))
      return {
        items: [{ id: otherId, questionTask: historical }],
        nextCursor: r.params?.cursor ? null : 'history-next',
      };
    if (r.url === `/question-tasks/${otherId}`) return historical;
    if (r.url.endsWith('/scope')) return { ...first, knowledgeBaseIds: [] };
  });
  expect(w.text()).toContain('省略了4轮历史');
  await button(w, '更多问答轮次').trigger('click');
  await flushPromises();
  expect(s.requests.some((r) => r.params?.cursor === 'history-next')).toBe(
    true,
  );
  const window = w.findComponent(AnswerWindow);
  await window.find('input[type=checkbox]').setValue(false);
  await button(window, '保存后续知识库范围').trigger('click');
  await flushPromises();
  expect(window.text()).toContain('历史及在途问题范围不变');
  expect(window.text()).toContain(id);
  expect(s.requests.some((r) => r.url.endsWith('/questions'))).toBe(false);
  w.unmount();
});
it('删除期间旧任务查询迟到成功不得复活记录', async () => {
  let resolve;
  const running = {
    ...questionTask,
    answer: null,
    task: {
      ...questionTask.task,
      state: 'running',
      phase: 'generating',
      finishedAt: null,
      resultResourceId: null,
    },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('通知断线');
    }),
  );
  const { w } = await ready((r) => {
    if (r.method === 'get' && r.url === `/conversations/${id}`)
      return { ...first, activeTask: running.task, lastTask: running.task };
    if (r.url === `/question-tasks/${otherId}`)
      return new Promise((done) => {
        resolve = done;
      });
  });
  await button(w, '删除当前问答').trigger('click');
  await button(w, '确认删除问答实例').trigger('click');
  await flushPromises();
  resolve(questionTask);
  await flushPromises();
  expect(w.find(`[data-conversation-id="${id}"]`).exists()).toBe(false);
  expect(
    w
      .findAllComponents(AnswerWindow)
      .some((window) => window.props('initialConversation')?.id === id),
  ).toBe(false);
  w.unmount();
});
it('首页真实分页最近问答携带稳定标识继续会话，不发起模型任务', async () => {
  const s = await setup((r) => ({
    items: r.params?.cursor ? [second] : [first],
    nextCursor: r.params?.cursor ? null : 'recent-next',
  }));
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/');
  const w = mount(RecentAnswers, {
    props: { client: s.client },
    global: { plugins: [router] },
  });
  await flushPromises();
  await button(w, '更多最近问答').trigger('click');
  await flushPromises();
  expect(w.findAll('a')).toHaveLength(2);
  expect(w.find('a').attributes('href')).toContain(`conversation=${id}`);
  expect(s.requests.every((r) => r.method === 'get')).toBe(true);
  w.unmount();
});
it('追问使用当前显式范围，活动期间禁重交且无首问重建', async () => {
  let resolve;
  const running = {
    ...secondTask,
    conversationId: id,
    question: '后续问题',
    answer: null,
    scope: null,
    task: {
      ...secondTask.task,
      state: 'running',
      phase: 'generating',
      finishedAt: null,
      resultResourceId: null,
    },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(
          new ReadableStream({
            start(controller) {
              resolve = () => controller.close();
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
    ),
  );
  const { s, w } = await ready((r) =>
    r.url.endsWith('/questions')
      ? { httpStatus: 202, body: running }
      : undefined,
  );
  const window = w.findComponent(AnswerWindow);
  await window.get('textarea').setValue('后续问题');
  await window.findAll('input[type=checkbox]').at(-1).setValue(true);
  await window.get('form').trigger('submit');
  await flushPromises();
  const post = s.requests.find((r) => r.url.endsWith('/questions'));
  expect(JSON.parse(post.data)).toEqual({
    question: '后续问题',
    knowledgeBaseIds: [id],
  });
  expect(button(window, '提交追问').attributes('disabled')).toBeDefined();
  expect(
    s.requests.some((r) => r.url === '/conversations/first-question'),
  ).toBe(false);
  w.unmount();
  resolve();
  await flushPromises();
});
