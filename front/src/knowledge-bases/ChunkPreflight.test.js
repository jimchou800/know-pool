import { expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ChunkPreflight from './ChunkPreflight.vue';
import { preflight } from '../../test-fixtures/preflight';
import { file } from '../../test-fixtures/knowledge-bases';

it('完整统计降级及真实位置样例安全展示，关闭返回触发点', async () => {
  const request = vi.fn(async () => preflight);
  const wrapper = mount(ChunkPreflight, {
    props: { client: { request }, task: file.task },
    attachTo: document.body,
  });
  const trigger = wrapper.get('button');
  expect(request).not.toHaveBeenCalled();
  await trigger.trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('内容完整性检查通过');
  expect(wrapper.text()).toContain('第95百分位');
  expect(wrapper.text()).toContain('句子边界：1 片');
  expect(wrapper.text()).toContain('第2至3行');
  expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.text()).toContain('<script>固定样例😀</script>');
  await wrapper
    .get('[aria-label="切片完整性预检"]')
    .trigger('keydown', { key: 'Escape' });
  expect(document.activeElement).toBe(trigger.element);
  wrapper.unmount();
});
it('尚无预检显式刷新同一任务，不触发重建；关闭忽略迟到结果', async () => {
  let resolve;
  const request = vi
    .fn()
    .mockRejectedValueOnce({ code: 'NOT_FOUND' })
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
  const wrapper = mount(ChunkPreflight, {
    props: { client: { request }, task: file.task },
  });
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('预检尚未生成');
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '刷新原预检')
    .trigger('click');
  await flushPromises();
  await wrapper
    .get('[aria-label="切片完整性预检"]')
    .trigger('keydown', { key: 'Escape' });
  resolve(preflight);
  await flushPromises();
  expect(wrapper.find('[aria-label="切片完整性预检"]').exists()).toBe(false);
  expect(
    request.mock.calls.every(
      ([r]) =>
        r.method === 'get' &&
        r.url.endsWith(`/${file.task.id}/chunk-preflight`),
    ),
  ).toBe(true);
  wrapper.unmount();
});
it('空文本和完整性失败独立显示，不用降级冒充失败', async () => {
  const wrapper = mount(ChunkPreflight, {
    props: {
      task: file.task,
      client: {
        request: async () => ({
          ...preflight,
          emptyText: true,
          integrityPassed: false,
          samples: [],
          chunkCount: 0,
          fallbackCounts: {},
        }),
      },
    },
  });
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('原文为空文本');
  expect(wrapper.get('[role=alert]').text()).toContain('不能发布该索引');
  expect(wrapper.text()).toContain('未发生降级拆分');
  wrapper.unmount();
});
