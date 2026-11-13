import { expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import SearchInstances from './SearchInstances.vue';
import Search from './Search.vue';
import { setup, id, record } from '../../test-fixtures/knowledge-bases';
import { instance, instanceId, result } from '../../test-fixtures/search';
const secondId = '00000000-0000-4000-8000-000000000079';
const button = (w, name) => w.findAll('button').find((b) => b.text() === name);
async function ready(extra) {
  const rows = [
    { ...instance, name: '第一个检索' },
    {
      ...instance,
      id: secondId,
      name: '第二个检索',
      resultState: 'rerun_required',
      lastSubmittedQuery: '保存的查询',
      knowledgeBaseIds: [id],
    },
  ];
  const s = await setup((r) => {
    if (r.url === '/knowledge-bases')
      return {
        items: [{ ...record, publicationState: 'published' }],
        nextCursor: null,
      };
    if (r.url === '/search-instances' && r.method === 'get')
      return { items: rows, nextCursor: null };
    if (r.url === `/search-instances/${instanceId}` && r.method === 'get')
      return rows[0];
    if (r.url === `/search-instances/${secondId}` && r.method === 'get')
      return rows[1];
    return extra(r, rows);
  });
  const w = mount(SearchInstances, { props: { client: s.client } });
  await flushPromises();
  return { w, s, rows };
}
it('切换保留各自未提交输入范围，恢复实例只还原已提交条件不自动收费', async () => {
  const { w, s } = await ready(() => null);
  const first = w.findAllComponents(Search)[0];
  await first.get('textarea').setValue('未提交草稿');
  await button(w, '第二个检索待重新检索').trigger('click');
  await flushPromises();
  const second = w.findAllComponents(Search)[1];
  expect(second.get('textarea').element.value).toBe('保存的查询');
  expect(second.text()).toContain('结果需手动重新检索');
  await button(w, '第一个检索未提交').trigger('click');
  await flushPromises();
  expect(first.get('textarea').element.value).toBe('未提交草稿');
  expect(s.requests.every((r) => r.method === 'get')).toBe(true);
  w.unmount();
});
it('重名允许且保存范围不提交检索，删除先确认并移除迟到窗口', async () => {
  let deleted = false;
  const { w, s } = await ready((r) => {
    if (r.method === 'patch')
      return { ...instance, name: '第二个检索', manuallyNamed: true };
    if (r.url.endsWith('/scope')) return { ...instance, knowledgeBaseIds: [] };
    if (r.method === 'delete') {
      deleted = true;
      return {
        httpStatus: 202,
        body: {
          task: {
            ...result.task,
            kind: 'cleanup',
            knowledgeBaseId: null,
            targetConfigVersionId: null,
            resultResourceId: null,
          },
        },
      };
    }
    return null;
  });
  await button(w, '重命名当前检索').trigger('click');
  await w.get('#search-name').setValue('第二个检索');
  await w.get('form').trigger('submit');
  await flushPromises();
  expect(w.findAllComponents(Search)[0].get('h2').text()).toBe('第二个检索');
  await w
    .findAllComponents(Search)[0]
    .findAll('button')
    .find((b) => b.text() === '保存知识库范围')
    .trigger('click');
  await flushPromises();
  expect(s.requests.some((r) => r.url.endsWith('/requests'))).toBe(false);
  await button(w, '删除当前检索').trigger('click');
  expect(deleted).toBe(false);
  expect(w.text()).toContain('不影响数据集、知识库或其他实例');
  await button(w, '确认删除检索实例').trigger('click');
  await flushPromises();
  expect(deleted).toBe(true);
  expect(
    w
      .findAllComponents(Search)
      .every((c) => c.props('initialInstance').id !== instanceId),
  ).toBe(true);
  w.unmount();
});
it('实例删除过程中旧异步结果不写回列表或复活实例', async () => {
  let resolve;
  const { w } = await ready((r) => {
    if (r.method === 'delete')
      return {
        httpStatus: 202,
        body: {
          task: {
            ...result.task,
            kind: 'cleanup',
            targetConfigVersionId: null,
            resultResourceId: null,
          },
        },
      };
    return new Promise((done) => {
      resolve = done;
    });
  });
  const first = w.findAllComponents(Search)[0];
  await first.get('textarea').setValue('版本规则');
  for (const box of first.findAll('input[type=checkbox]'))
    await box.setValue(true);
  await first.get('form').trigger('submit');
  await flushPromises();
  await button(w, '删除当前检索').trigger('click');
  await button(w, '确认删除检索实例').trigger('click');
  await flushPromises();
  resolve(result);
  await flushPromises();
  expect(w.text()).not.toContain('资料原文');
  expect(
    w
      .findAllComponents(Search)
      .every((c) => c.props('initialInstance').id !== instanceId),
  ).toBe(true);
  w.unmount();
});
