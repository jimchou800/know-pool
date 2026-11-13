import { expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ProcessingEditor from './ProcessingEditor.vue';
import { setup, id } from '../../test-fixtures/knowledge-bases';
import { config, rebuild } from '../../test-fixtures/preflight';

it('编辑原切片配置保留嵌入快照，重复网络提交复用同键同负载', async () => {
  const s = await setup(() => {
    throw new Error('固定断线');
  });
  const wrapper = mount(ProcessingEditor, {
    props: { client: s.client, config },
  });
  await wrapper.get('button').trigger('click');
  await wrapper.get('select').setValue('fixed');
  await wrapper.get('input[type=number]').setValue(0);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  const first = s.requests.at(-1);
  expect(first.method).toBe('put');
  expect(first.url).toBe(`/knowledge-bases/${id}/processing-config`);
  const data = JSON.parse(first.data);
  expect(data.chunking).toMatchObject({
    strategy: 'fixed',
    overlapCodePoints: 0,
  });
  expect(data.embedding).toEqual(config.input.embedding);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(
    first.headers.get('Idempotency-Key'),
  );
  expect(s.requests.at(-1).data).toBe(first.data);
  wrapper.unmount();
});
it('重建只查询原任务，失败保留旧版且不另交模型任务', async () => {
  vi.useFakeTimers();
  const s = await setup((r) =>
    r.method === 'put'
      ? { task: rebuild }
      : {
          ...rebuild,
          state: 'failed',
          phase: 'completed',
          startedAt: rebuild.createdAt,
          finishedAt: rebuild.createdAt,
          failure: {
            code: 'MODEL_UNAVAILABLE',
            message: '固定模型失败',
            retryable: false,
          },
        },
  );
  const wrapper = mount(ProcessingEditor, {
    props: { client: s.client, config },
  });
  await wrapper.get('button').trigger('click');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  await vi.advanceTimersByTimeAsync(1000);
  await flushPromises();
  expect(wrapper.text()).toContain('旧生效索引保留');
  expect(s.requests.map((r) => r.method)).toEqual(['put', 'get']);
  expect(wrapper.emitted('completed')).toHaveLength(1);
  wrapper.unmount();
  vi.useRealTimers();
});
it('非法重叠和系统字段名不发送请求，可修正且关闭返回按钮', async () => {
  const s = await setup(() => null);
  const wrapper = mount(ProcessingEditor, {
    props: { client: s.client, config },
    attachTo: document.body,
  });
  const trigger = wrapper.get('button');
  await trigger.trigger('click');
  await wrapper.get('input[type=number]').setValue(2000);
  await wrapper.get('[aria-label="切片元信息字段名1"]').setValue('fileId');
  await wrapper.get('form').trigger('submit');
  expect(wrapper.text()).toContain('请修正切片配置');
  expect(s.requests).toHaveLength(0);
  await wrapper
    .get('[aria-label="编辑切片配置"]')
    .trigger('keydown', { key: 'Escape' });
  expect(document.activeElement).toBe(trigger.element);
  wrapper.unmount();
});
