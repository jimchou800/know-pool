import { expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import FileKnowledgeBases from './FileKnowledgeBases.vue';
import {
  setup,
  id,
  otherId,
  file,
  record,
} from '../../test-fixtures/knowledge-bases';

it('资料关联库独立来源与排除状态分开展示，换资料忽略迟到列表', async () => {
  const s = await setup((r) =>
    r.url.endsWith('/knowledge-bases')
      ? {
          items: [
            {
              ...file,
              sources: [],
              included: false,
              excluded: true,
              task: null,
              targetContentVersionId: null,
              targetConfigVersionId: null,
              updateState: 'not_included',
            },
          ],
          nextCursor: null,
        }
      : record,
  );
  const wrapper = mount(FileKnowledgeBases, {
    props: { client: s.client, fileId: otherId },
  });
  expect(s.requests).toHaveLength(0);
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain(record.name);
  expect(wrapper.text()).toContain('已排除');
  expect(wrapper.text()).toContain('无有效来源');
  expect(wrapper.text()).not.toContain('已纳入 ·');
  let resolve;
  s.reply(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  await wrapper.get('button').trigger('click');
  await flushPromises();
  await wrapper.setProps({ fileId: id });
  resolve({ items: [file], nextCursor: null });
  await flushPromises();
  expect(wrapper.text()).not.toContain(record.name);
  expect(wrapper.text()).toContain('查看关联知识库');
  wrapper.unmount();
});
