import { expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ManagementSearch from './ManagementSearch.vue';
import { setup, id, otherId, task } from '../../test-fixtures/knowledge-bases';
import { search, evidence } from '../../test-fixtures/indexing';

it('用户显式单库检索后查看实际证据，正文不执行脚本且关闭返回焦点', async () => {
  const s = await setup((r) =>
    r.url.includes('/evidence/') ? evidence : search,
  );
  const wrapper = mount(ManagementSearch, {
    props: { client: s.client, knowledgeBaseId: id },
    attachTo: document.body,
  });
  expect(s.requests).toHaveLength(0);
  expect(wrapper.text()).toContain('可能调用嵌入服务并产生费用');
  await wrapper.find('textarea').setValue('版本规则');
  await wrapper.find('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('仍使用旧生效版本');
  const open = wrapper
    .findAll('button')
    .find((b) => b.text() === '查看原文依据');
  await open.trigger('click');
  await flushPromises();
  expect(s.requests.at(-1).url).toBe(
    `/search-requests/${id}/evidence/${otherId}`,
  );
  expect(wrapper.find('[role="region"]').text()).toContain('第2至3行');
  expect(wrapper.find('script').exists()).toBe(false);
  expect(wrapper.text()).toContain('完整原文件不可用');
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '关闭原文依据')
    .trigger('click');
  expect(document.activeElement).toBe(open.element);
  wrapper.unmount();
});

it('在途请求失效后结束本地等待，只有用户手动重新检索才再提交', async () => {
  vi.useFakeTimers();
  const pending = {
    id,
    instanceId: null,
    task: { ...task, kind: 'search' },
    result: null,
    scope: null,
  };
  const s = await setup(() => pending);
  const wrapper = mount(ManagementSearch, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await wrapper.find('textarea').setValue('原查询');
  await wrapper.find('form').trigger('submit');
  await flushPromises();
  s.reply(() => {
    throw {
      response: {
        status: 410,
        data: {
          requestId: id,
          code: 'RESULT_EXPIRED',
          message: '结果失效',
          fieldErrors: [],
          retryable: false,
        },
      },
    };
  });
  await vi.advanceTimersByTimeAsync(750);
  await flushPromises();
  expect(wrapper.text()).toContain('结果已失效');
  expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined();
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(1);
  wrapper.unmount();
  vi.useRealTimers();
});

it('空白查询不发出请求，修正后可提交且不同失败不冒充无命中', async () => {
  const s = await setup(() => ({
    ...search,
    result: { ...search.result, outcome: 'no_matches', evidence: [] },
  }));
  const wrapper = mount(ManagementSearch, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await wrapper.find('form').trigger('submit');
  await flushPromises();
  expect(s.requests).toHaveLength(0);
  expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined();
  await wrapper.find('textarea').setValue('新查询');
  await wrapper.find('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('未找到相关内容');
  expect(s.requests).toHaveLength(1);
  wrapper.unmount();
});
