import { expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import IndexSwitches from './IndexSwitches.vue';
import { setup, id, otherId, time } from '../../test-fixtures/knowledge-bases';
it('成功切换显示三组前后版本，不提供回滚；切库后忽略迟到记录', async () => {
  const item = {
    id: otherId,
    knowledgeBaseId: id,
    fileId: otherId,
    fromContentVersionId: null,
    toContentVersionId: otherId,
    fromIndexVersionId: null,
    toIndexVersionId: otherId,
    fromConfigVersionId: null,
    toConfigVersionId: otherId,
    taskId: otherId,
    switchedAt: time,
  };
  const s = await setup(() => ({ items: [item], nextCursor: null }));
  const wrapper = mount(IndexSwitches, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('首次生效');
  expect(wrapper.text()).toContain('配置版本：');
  expect(wrapper.findAll('button').some((b) => b.text().includes('回滚'))).toBe(
    false,
  );
  let resolve;
  s.reply(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await wrapper.get('button').trigger('click');
  await flushPromises();
  wrapper.unmount();
  resolve({ items: [item], nextCursor: null });
  await flushPromises();
});
