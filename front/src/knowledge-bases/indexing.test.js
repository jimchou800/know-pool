import { expect, it, vi } from 'vitest';
import {
  setup,
  id,
  otherId,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
import { mount, flushPromises } from '@vue/test-utils';
import IndexTasks from './IndexTasks.vue';

it('用户读取索引任务并区分未知总量和真实阶段，空库不冒充完成', async () => {
  const s = await setup(() => ({
    items: [
      {
        ...task,
        kind: 'index_file',
        phase: 'embedding',
        state: 'running',
        startedAt: time,
      },
    ],
    nextCursor: null,
  }));
  const wrapper = mount(IndexTasks, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('嵌入');
  expect(wrapper.text()).toContain('总量未知');
  expect(wrapper.text()).not.toContain('100%');
  wrapper.unmount();
});

it('实际读取当前库索引任务分页并保持目标版本与进度', async () => {
  const indexed = {
    ...task,
    kind: 'index_file',
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    fileId: otherId,
    targetContentVersionId: otherId,
    progressCompleted: 2,
    progressTotal: 2,
    resultResourceId: otherId,
  };
  const s = await setup(() => ({ items: [indexed], nextCursor: '下一页' }));
  expect(await s.api.tasks(id, '上页')).toEqual({
    items: [indexed],
    nextCursor: '下一页',
  });
  expect(s.requests.at(-1).url).toBe(`/knowledge-bases/${id}/tasks`);
  expect(s.requests.at(-1).params).toEqual({ limit: 20, cursor: '上页' });
  s.reply({
    items: [{ ...indexed, resultResourceId: null }],
    nextCursor: null,
  });
  await expect(s.api.tasks(id)).rejects.toThrow('响应格式');
});

it('用户跟踪任务遇断线先查询原任务，不重新创建索引', async () => {
  const pending = { ...task, kind: 'index_file' };
  const finished = {
    ...pending,
    state: 'succeeded',
    phase: 'completed',
    finishedAt: time,
    resultResourceId: otherId,
  };
  const s = await setup((r) =>
    r.url.endsWith('/tasks')
      ? { items: [pending], nextCursor: null }
      : finished,
  );
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      throw new Error('连接中断');
    }),
  );
  const wrapper = mount(IndexTasks, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '跟踪处理任务')
    .trigger('click');
  await flushPromises();
  expect(s.requests.filter((r) => r.url === `/tasks/${otherId}`)).toHaveLength(
    1,
  );
  expect(wrapper.text()).toContain('处理成功');
  expect(s.requests.every((r) => r.method === 'get')).toBe(true);
  wrapper.unmount();
  vi.unstubAllGlobals();
});
