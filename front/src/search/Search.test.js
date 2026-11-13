import { expect, it, vi, afterEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import Search from './Search.vue';
import {
  setup,
  record,
  id,
  otherId,
} from '../../test-fixtures/knowledge-bases';
import { instance, result, nextRequestId } from '../../test-fixtures/search';
import { evidence } from '../../test-fixtures/indexing';
const button = (w, name) => w.findAll('button').find((b) => b.text() === name);
afterEach(() => vi.useRealTimers());
async function ready(reply) {
  const s = await setup((r) => {
    if (r.url === '/knowledge-bases')
      return {
        items: [
          { ...record, publicationState: 'published' },
          {
            ...record,
            id: otherId,
            name: '故障资料库',
            publicationState: 'published',
            availability: 'unavailable',
          },
        ],
        nextCursor: null,
      };
    if (r.url === '/search-instances') return instance;
    return reply(r);
  });
  const w = mount(Search, { props: { client: s.client } });
  await flushPromises();
  await button(w, '新建检索').trigger('click');
  await flushPromises();
  await w.get('textarea').setValue('版本规则');
  for (const box of w.findAll('input[type=checkbox]')) await box.setValue(true);
  return { s, w };
}
it('最小实例费用确认后多库命中显示选定实际遗漏范围，原文按版本安全展示', async () => {
  const { s, w } = await ready((r) =>
    r.url.includes('/evidence/') ? evidence : { httpStatus: 202, body: result },
  );
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('检索范围不完整');
  expect(w.text()).toContain('遗漏：故障资料库');
  expect(w.text()).toContain('选定范围：');
  expect(w.text()).not.toContain('统一可信度');
  expect(w.find('script').exists()).toBe(false);
  await button(w, '查看原文依据').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('完整切片证据快照');
  expect(s.requests.at(-1).url).toBe(
    `/search-requests/${id}/evidence/${otherId}`,
  );
  await button(w, '关闭原文依据').trigger('click');
  const posts = s.requests.filter((r) => r.method === 'post');
  expect(posts).toHaveLength(2);
  w.unmount();
});
it('无结果和范围全失败显示分别状态，缺费用确认不外发', async () => {
  let value = {
    ...result,
    result: { ...result.result, outcome: 'no_matches', evidence: [] },
  };
  const { s, w } = await ready(() => ({ httpStatus: 202, body: value }));
  await w.findAll('input[type=checkbox]').at(-1).setValue(false);
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('请确认本次检索可能产生');
  expect(s.requests.filter((r) => r.url.endsWith('/requests'))).toHaveLength(0);
  await w.findAll('input[type=checkbox]').at(-1).setValue(true);
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('未找到相关内容');
  value = {
    ...result,
    result: null,
    scope: {
      ...result.scope,
      usedKnowledgeBaseIds: [],
      omitted: [id, otherId].map((knowledgeBaseId) => ({
        knowledgeBaseId,
        reason: 'unavailable',
        message: '依赖故障',
      })),
    },
    task: {
      ...result.task,
      state: 'failed',
      resultResourceId: null,
      failure: {
        code: 'SCOPE_UNAVAILABLE',
        message: '全部范围不可用',
        retryable: false,
      },
    },
  };
  await w.findAll('input[type=checkbox]').at(-1).setValue(true);
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('选定范围全部不可用');
  expect(w.text()).not.toContain('未找到相关内容');
  w.unmount();
});
it('同实例新提交清空旧结果并隔离迟到原请求，即使新请求失败也不恢复旧结果', async () => {
  vi.useFakeTimers();
  let resolve,
    submissions = 0;
  const queued = {
    ...result,
    scope: null,
    result: null,
    task: {
      ...result.task,
      state: 'queued',
      phase: 'queued',
      startedAt: null,
      finishedAt: null,
      resultResourceId: null,
    },
  };
  const { w } = await ready((r) => {
    if (r.method === 'get')
      return new Promise((done) => {
        resolve = done;
      });
    submissions++;
    if (submissions === 1) return { httpStatus: 202, body: queued };
    return {
      httpStatus: 202,
      body: {
        ...queued,
        id: nextRequestId,
        task: {
          ...result.task,
          state: 'failed',
          resultResourceId: null,
          failure: {
            code: 'SCOPE_UNAVAILABLE',
            message: '新请求失败',
            retryable: false,
          },
        },
      },
    };
  });
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  await vi.advanceTimersByTimeAsync(750);
  await flushPromises();
  await w.findAll('input[type=checkbox]').at(-1).setValue(true);
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  resolve(result);
  await flushPromises();
  expect(w.text()).toContain('新请求失败');
  expect(w.text()).not.toContain('资料原文');
  w.unmount();
});
it('受理结果不明恢复同键原负载，不暗中改变查询或范围', async () => {
  let fail = true;
  const { s, w } = await ready(() => {
    if (fail) throw new Error('连接中断');
    return { httpStatus: 202, body: result };
  });
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  const first = s.requests.at(-1);
  expect(w.text()).toContain('受理结果尚未确认');
  fail = false;
  await button(w, '恢复本次提交').trigger('click');
  await flushPromises();
  expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(
    first.headers.get('Idempotency-Key'),
  );
  expect(s.requests.at(-1).data).toBe(first.data);
  w.unmount();
});
it('运行中范围尚未完成检查时继续查询原请求，结束后才显示完整范围', async () => {
  vi.useFakeTimers();
  const running = {
    ...result,
    scope: null,
    result: null,
    task: {
      ...result.task,
      state: 'running',
      phase: 'retrieving',
      finishedAt: null,
      resultResourceId: null,
    },
  };
  let queries = 0;
  const { s, w } = await ready((request) => {
    if (request.method === 'post') return { httpStatus: 202, body: running };
    queries++;
    return queries === 1 ? running : result;
  });
  await button(w, '开始检索').trigger('click');
  await flushPromises();
  expect(w.text()).toContain('正在检索');
  expect(w.text()).not.toContain('实际检索：');
  await vi.advanceTimersByTimeAsync(750);
  await flushPromises();
  expect(w.text()).not.toContain('响应不符合');
  await vi.advanceTimersByTimeAsync(750);
  await flushPromises();
  expect(w.text()).toContain('检索范围不完整');
  expect(w.text()).toContain('实际检索：');
  expect(
    s.requests.filter((r) => r.url === `/search-requests/${id}`),
  ).toHaveLength(2);
  w.unmount();
});
