import { expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import Exclusions from './Exclusions.vue';
import { id, otherId, time } from '../../test-fixtures/knowledge-bases';
it('无来源排除仍可取消，名称读取失败保留身份与记录', async () => {
  const wrapper = mount(Exclusions, {
    props: {
      knowledgeBaseId: id,
      client: {
        request: async (r) => {
          if (r.url.startsWith('/files/')) throw new Error('固定名称失败');
          return {
            items: [{ knowledgeBaseId: id, fileId: otherId, createdAt: time }],
            nextCursor: null,
          };
        },
      },
    },
  });
  await flushPromises();
  expect(wrapper.text()).toContain(otherId);
  expect(wrapper.text()).toContain('取消排除不会凭空建立新来源');
  expect(wrapper.findAll('button').some((b) => b.text() === '取消排除')).toBe(
    true,
  );
  wrapper.unmount();
});
