import { expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import IndexTasks from './IndexTasks.vue';
import {
  setup,
  id,
  otherId,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
it('清理失败可观察且不提供文件预检或未实现的重试入口', async () => {
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
      message: '固定清理失败',
      retryable: false,
    },
  };
  const s = await setup(() => ({ items: [cleanup], nextCursor: null }));
  const wrapper = mount(IndexTasks, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('清理失败不会恢复资料纳入');
  expect(wrapper.text()).toContain('固定清理失败');
  expect(
    wrapper.findAll('button').some((b) => b.text() === '查看切片预检'),
  ).toBe(false);
  expect(
    wrapper.findAll('button').some((b) => b.text().includes('重试清理')),
  ).toBe(false);
  wrapper.unmount();
});
