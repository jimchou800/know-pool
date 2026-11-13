import { expect, it, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import PublicationControl from './PublicationControl.vue';
import {
  setup,
  record,
  id,
  otherId,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
const button = (wrapper, name) =>
  wrapper.findAll('button').find((item) => item.text() === name);
afterEach(() => vi.useRealTimers());
it('空库仍由服务端裁定发布，拒绝保留原状态与明确原因', async () => {
  const s = await setup(() => ({
    errorStatus: 422,
    body: {
      requestId: id,
      code: 'INVALID_STATE',
      message: '没有有效可检索资料，不能发布。',
      fieldErrors: [],
      retryable: false,
    },
  }));
  const w = mount(PublicationControl, {
    props: { client: s.client, base: record },
  });
  expect(w.text()).toContain('当前无可检索资料');
  await button(w, '发布知识库').trigger('click');
  await button(w, '确认发布知识库').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('没有有效可检索资料');
  expect(w.emitted('updated')).toBeUndefined();
  expect(s.requests.at(-1).url).toBe(`/knowledge-bases/${id}/publish`);
  w.unmount();
});
it('主动下线需确认并消费实际状态，资料恢复不自动重新发布', async () => {
  const offline = {
    ...record,
    publicationState: 'offline',
    publishedAt: time,
    offlineAt: time,
    offlineReason: 'manual',
    includedFileCount: 1,
    searchableFileCount: 1,
  };
  const s = await setup(() => offline);
  const w = mount(PublicationControl, {
    props: {
      client: s.client,
      base: { ...record, publicationState: 'published', publishedAt: time },
    },
  });
  await button(w, '主动下线知识库').trigger('click');
  expect(w.text()).toContain('不取消处理任务');
  await button(w, '保留当前状态').trigger('click');
  expect(s.requests).toHaveLength(0);
  await button(w, '主动下线知识库').trigger('click');
  await button(w, '确认主动下线知识库').trigger('click');
  await flushPromises();
  expect(w.emitted('updated')[0][0]).toEqual(offline);
  await w.setProps({
    base: { ...offline, offlineReason: 'no_searchable_files' },
  });
  expect(w.text()).toContain('无可检索资料');
  expect(button(w, '重新发布知识库')).toBeTruthy();
  expect(s.requests).toHaveLength(1);
  w.unmount();
});
it('显式复查确认费用，受理不明复用键，任务结束消费不可用结果而不持续探测', async () => {
  vi.useFakeTimers();
  const queued = { ...task, kind: 'model_test' };
  const terminal = {
    ...queued,
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    resultResourceId: null,
  };
  let fail = true;
  const s = await setup((r) => {
    if (r.url.endsWith('/check')) {
      if (fail) throw new Error('受理连接中断');
      return { task: queued };
    }
    if (r.url.endsWith('/connection-test'))
      return {
        available: false,
        checkedAt: time,
        message: '向量服务不可用',
        mayHaveIncurredCost: true,
      };
    if (r.url.startsWith('/tasks/')) return terminal;
    return {
      ...record,
      availability: 'unavailable',
      availabilityReason: '向量服务不可用',
    };
  });
  const w = mount(PublicationControl, {
    props: { client: s.client, base: record },
  });
  await button(w, '复查可用性').trigger('click');
  expect(button(w, '确认复查可用性').element.disabled).toBe(true);
  await w.get('input[type=checkbox]').setValue(true);
  await button(w, '确认复查可用性').trigger('click');
  await flushPromises();
  const first = s.requests.at(-1).headers.get('Idempotency-Key');
  fail = false;
  await button(w, '确认复查可用性').trigger('click');
  await flushPromises();
  const posts = s.requests.filter((r) => r.method === 'post');
  expect(posts).toHaveLength(2);
  expect(posts[1].headers.get('Idempotency-Key')).toBe(first);
  expect(JSON.parse(posts[1].data)).toEqual({ allowChargedCall: true });
  expect(w.text()).toContain('运行不可用');
  expect(w.emitted('updated')[0][0].publicationState).toBe('unpublished');
  await vi.advanceTimersByTimeAsync(5000);
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(2);
  w.unmount();
});
it('切换知识库后忽略旧发布响应，写入暂停禁用操作', async () => {
  let resolve;
  const s = await setup(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  const w = mount(PublicationControl, {
    props: { client: s.client, base: record },
  });
  await button(w, '发布知识库').trigger('click');
  await button(w, '确认发布知识库').trigger('click');
  await flushPromises();
  await w.setProps({ base: { ...record, id: otherId }, writable: false });
  resolve({ ...record, publicationState: 'published', publishedAt: time });
  await flushPromises();
  expect(w.emitted('updated')).toBeUndefined();
  expect(button(w, '发布知识库').element.disabled).toBe(true);
  w.unmount();
});
