import { expect, it, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import Answers from './AnswerWindow.vue';
import { setup, record, otherId } from '../../test-fixtures/knowledge-bases';
import { accepted, questionTask, answer } from '../../test-fixtures/answers';
import { evidence } from '../../test-fixtures/indexing';
const button = (w, name) => w.findAll('button').find((b) => b.text() === name);
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
async function ready(reply) {
  const s = await setup((r) =>
    r.url === '/knowledge-bases'
      ? {
          items: [{ ...record, publicationState: 'published' }],
          nextCursor: null,
        }
      : reply(r),
  );
  const w = mount(Answers, { props: { client: s.client } });
  await flushPromises();
  await button(w, '新建问答').trigger('click');
  await w.get('textarea').setValue('版本规则');
  for (const box of w.findAll('input[type=checkbox]')) await box.setValue(true);
  return { s, w };
}
it('空白问答不保存，首问前手名与费用确认后展示终态回答及快照', async () => {
  const { s, w } = await ready((r) =>
    r.url.includes('/evidence/')
      ? evidence
      : { httpStatus: 202, body: accepted },
  );
  expect(s.requests.every((r) => r.method === 'get')).toBe(true);
  await w.get('#name-first').setValue('手动问答名');
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(w.text()).toContain(answer.text);
  expect(w.text()).toContain('手动问答名');
  await button(w, '查看引用原文').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('不是整份旧文件');
  expect(w.find('script').exists()).toBe(false);
  await button(w, '关闭引用原文').trigger('click');
  w.unmount();
});
it('校验失败不渲染草稿，事件断线仅查询原任务不重提', async () => {
  const pending = {
    ...questionTask,
    answer: null,
    scope: null,
    task: {
      ...questionTask.task,
      state: 'running',
      phase: 'answer_validating',
      finishedAt: null,
      resultResourceId: null,
    },
  };
  const failed = {
    ...pending,
    task: {
      ...questionTask.task,
      state: 'failed',
      resultResourceId: null,
      failure: {
        code: 'ANSWER_VALIDATION_FAILED',
        message: '不通过',
        retryable: false,
      },
    },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('通知断线');
    }),
  );
  const { s, w } = await ready((r) =>
    r.method === 'post'
      ? {
          httpStatus: 202,
          body: {
            ...accepted,
            questionTask: pending,
            conversation: {
              ...accepted.conversation,
              activeTask: pending.task,
              lastTask: pending.task,
            },
          },
        }
      : failed,
  );
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(w.text()).toContain('回答未通过');
  expect(w.text()).not.toContain(answer.text);
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(1);
  expect(s.requests.some((r) => r.url === `/question-tasks/${otherId}`)).toBe(
    true,
  );
  w.unmount();
});
it('部分缺口和矛盾分别展示，费用未同意不受理', async () => {
  const value = {
    ...questionTask,
    answer: {
      ...answer,
      outcome: 'partial',
      uncoveredQuestions: ['未覆盖子问题'],
      contradictions: ['来源条件不同'],
    },
  };
  const { s, w } = await ready(() => ({
    httpStatus: 202,
    body: { ...accepted, questionTask: value },
  }));
  await w.findAll('input[type=checkbox]').at(-1).setValue(false);
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(0);
  await w.findAll('input[type=checkbox]').at(-1).setValue(true);
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(w.text()).toContain('缺少依据的问题：未覆盖子问题');
  expect(w.text()).toContain('资料矛盾：来源条件不同');
  w.unmount();
});
it('取消确认隔离旧查询迟到响应，取消后不出现回答', async () => {
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
  const cancelled = {
    ...running,
    task: { ...questionTask.task, state: 'cancelled', resultResourceId: null },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('断线');
    }),
  );
  const { w } = await ready((r) =>
    r.url.endsWith('/cancel')
      ? cancelled
      : r.method === 'post'
        ? { httpStatus: 202, body: { ...accepted, questionTask: running } }
        : new Promise((done) => {
            resolve = done;
          }),
  );
  await w.get('form').trigger('submit');
  await flushPromises();
  await button(w, '取消本次问答').trigger('click');
  await button(w, '确认取消问答').trigger('click');
  await flushPromises();
  resolve(questionTask);
  await flushPromises();
  expect(w.text()).toContain('问答已取消');
  expect(w.text()).not.toContain(answer.text);
  w.unmount();
});
it('无依据终态明确展示且没有引用入口', async () => {
  const value = {
    ...questionTask,
    answer: {
      ...answer,
      outcome: 'no_evidence',
      text: '资料中没有足够依据。',
      citationEvidenceIds: [],
      evidence: [],
    },
  };
  const { w } = await ready(() => ({
    httpStatus: 202,
    body: { ...accepted, questionTask: value },
  }));
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(w.text()).toContain('资料中没有足够依据');
  expect(button(w, '查看引用原文')).toBeUndefined();
  w.unmount();
});
