import { expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import CancelTask from './CancelTask.vue';
import { file, time } from '../../test-fixtures/knowledge-bases';
it('取消须确认并展示抢先完成的实际终态，不伪造取消成功', async () => {
  const completed = {
    ...file.task,
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    resultResourceId: file.task.id,
  };
  const request = vi.fn(async () => completed);
  const wrapper = mount(CancelTask, {
    props: { client: { request }, task: file.task },
    attachTo: document.body,
  });
  const trigger = wrapper.get('button');
  await trigger.trigger('click');
  expect(request).not.toHaveBeenCalled();
  await wrapper.get('section').trigger('keydown', { key: 'Escape' });
  expect(document.activeElement).toBe(trigger.element);
  await trigger.trigger('click');
  await wrapper.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(request.mock.calls[0][0]).toEqual({
    method: 'post',
    url: `/tasks/${file.task.id}/cancel`,
  });
  expect(wrapper.emitted('updated')[0][0]).toEqual(completed);
  wrapper.unmount();
});
it('清理与终态任务无取消入口；取消失败保留原任务', async () => {
  for (const task of [
    { ...file.task, kind: 'cleanup' },
    { ...file.task, state: 'cancelled' },
  ]) {
    const wrapper = mount(CancelTask, { props: { client: {}, task } });
    expect(wrapper.find('button').exists()).toBe(false);
    wrapper.unmount();
  }
  const wrapper = mount(CancelTask, {
    props: {
      task: file.task,
      client: {
        request: async () => {
          throw new Error('固定取消失败');
        },
      },
    },
  });
  await wrapper.get('button').trigger('click');
  await wrapper.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain('固定取消失败');
  expect(wrapper.emitted('updated')).toBeUndefined();
  wrapper.unmount();
});
