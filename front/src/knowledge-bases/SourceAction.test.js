import { expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import SourceAction from './SourceAction.vue';
import { id, otherId, time } from '../../test-fixtures/knowledge-bases';

it.each(['remove', 'exclude', 'include'])(
  '范围操作%s必须确认且不自动处理资料',
  async (action) => {
    const request = vi.fn(async () =>
      action === 'exclude'
        ? { knowledgeBaseId: id, fileId: otherId, createdAt: time }
        : { status: 204, data: '' },
    );
    const wrapper = mount(SourceAction, {
      props: {
        action,
        client: { request },
        knowledgeBaseId: id,
        resourceId: otherId,
      },
      attachTo: document.body,
    });
    const trigger = wrapper.get('button');
    await trigger.trigger('click');
    expect(request).not.toHaveBeenCalled();
    if (action === 'include')
      expect(wrapper.text()).toContain('没有有效来源时仍不纳入');
    await wrapper.get('section').trigger('keydown', { key: 'Escape' });
    expect(document.activeElement).toBe(trigger.element);
    await trigger.trigger('click');
    await wrapper
      .findAll('button')
      .find((b) => b.text().startsWith('确认'))
      .trigger('click');
    await flushPromises();
    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.calls[0][0].method).toBe(
      action === 'exclude' ? 'put' : 'delete',
    );
    expect(wrapper.emitted('changed')).toHaveLength(1);
    wrapper.unmount();
  },
);
it('失败不显示范围已更改，修正后须用户再次确认', async () => {
  const request = vi
    .fn()
    .mockRejectedValueOnce({ message: '固定来源失败' })
    .mockResolvedValueOnce({ status: 204, data: '' });
  const wrapper = mount(SourceAction, {
    props: {
      action: 'remove',
      client: { request },
      knowledgeBaseId: id,
      resourceId: otherId,
    },
  });
  await wrapper.get('button').trigger('click');
  await wrapper.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain('固定来源失败');
  expect(wrapper.emitted('changed')).toBeUndefined();
  await wrapper.findAll('button')[1].trigger('click');
  await flushPromises();
  expect(wrapper.emitted('changed')).toHaveLength(1);
  wrapper.unmount();
});
