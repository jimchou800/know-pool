import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import KnowledgeBases from './KnowledgeBases.vue';
import {
  record,
  config,
  task,
  time,
  id,
  otherId,
  source,
  file,
  setup,
} from '../../test-fixtures/knowledge-bases';

let wrapper;
afterEach(() => {
  wrapper?.unmount();
  vi.useRealTimers();
});
const button = (text) =>
  wrapper.findAll('button').find((b) => b.text() === text);
async function open(path = '/knowledge-bases', respond) {
  const s = await setup(
    respond ||
      ((r) =>
        r.url === '/knowledge-bases'
          ? { items: [record], nextCursor: null }
          : r.url.endsWith('/processing-config')
            ? config
            : r.url.endsWith('/sources') || r.url.endsWith('/files')
              ? { items: [], nextCursor: null }
              : record),
  );
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push(path);
  wrapper = mount(KnowledgeBases, {
    props: { client: s.client },
    global: { plugins: [router] },
    attachTo: document.body,
  });
  await flushPromises();
  return { ...s, router };
}
it('选择空库后发布、可用与处理状态分别显示，目标快照只读', async () => {
  await open(`/knowledge-bases/${id}`);
  expect(wrapper.text()).toContain('未发布');
  expect(wrapper.text()).toContain('运行可用');
  expect(wrapper.text()).toContain('暂无处理任务');
  expect(wrapper.text()).toContain('尚无关联来源');
  expect(wrapper.text()).toContain('固定嵌入模型');
  expect(wrapper.text()).toContain('余弦相似度');
  expect(wrapper.text()).toContain('结构优先');
  expect(wrapper.text()).toContain('user_topic：主题（加入嵌入输入）');
  expect(wrapper.text()).toContain('尚无有效文件');
});
const version = {
  kind: 'embedding',
  versionId: id,
  endpoint: 'http://127.0.0.1:4567/v1',
  model: '固定嵌入模型',
  credential: { configured: true, mask: '【密钥已设置】' },
  contextWindowTokens: null,
  maxOutputTokens: null,
  complete: true,
  updatedAt: time,
};
const success = {
  ...task,
  state: 'succeeded',
  phase: 'completed',
  startedAt: time,
  finishedAt: time,
  resultResourceId: id,
};
async function creating(taskReply, postReply = () => ({ task })) {
  const s = await open('/knowledge-bases', (r) => {
    if (r.url === '/settings/models/embedding/versions')
      return { items: [version], nextCursor: null };
    if (r.url === '/datasets') return { items: [], nextCursor: null };
    if (r.method === 'post') return postReply(r);
    if (r.url.startsWith('/tasks/')) return taskReply(r);
    if (r.url === '/knowledge-bases')
      return { items: [record], nextCursor: null };
    if (r.url.endsWith('/processing-config')) return config;
    if (r.url.endsWith('/sources') || r.url.endsWith('/files'))
      return { items: [], nextCursor: null };
    return record;
  });
  await button('创建知识库').trigger('click');
  await flushPromises();
  await wrapper.get('#kb-name').setValue('空知识库');
  await wrapper.get('#kb-threshold').setValue('0.5');
  await wrapper.get('#kb-candidates').setValue('10');
  return s;
}
it('运行中任务持续查询至成功，期间不显示已创建', async () => {
  let complete = false;
  const s = await creating(() => (complete ? success : task));
  vi.useFakeTimers();
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('尚未创建成功');
  complete = true;
  await vi.advanceTimersByTimeAsync(600);
  await flushPromises();
  expect(s.router.currentRoute.value.path).toBe(`/knowledge-bases/${id}`);
  expect(s.requests.filter((r) => r.url.startsWith('/tasks/'))).toHaveLength(2);
});
it('元信息校验关联输入并在修正后写入创建请求', async () => {
  const s = await creating(() => success);
  await button('添加元信息字段').trigger('click');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.get('#kb-meta-key-0').attributes('aria-invalid')).toBe('true');
  await wrapper.get('#kb-meta-key-0').setValue('user_topic');
  await wrapper.get('#kb-meta-value-0').setValue('固定主题');
  await wrapper
    .get('.knowledge-metadata input[type="checkbox"]')
    .setValue(true);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  const payload = JSON.parse(s.requests.find((r) => r.method === 'post').data);
  expect(payload.processing.chunking.customMetadata).toEqual({
    user_topic: '固定主题',
  });
  expect(payload.processing.chunking.embeddedMetadataFields).toEqual([
    'user_topic',
  ]);
});
it('任务读取失败仅继续查询原任务，创建请求不会重复提交', async () => {
  let failure = true;
  const s = await creating(() => {
    if (failure) throw new Error('断线');
    return success;
  });
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('已受理');
  expect(wrapper.text()).not.toContain('已创建，尚未发布');
  expect(button('继续查询原任务')).toBeTruthy();
  failure = false;
  await button('继续查询原任务').trigger('click');
  await flushPromises();
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(1);
  expect(
    s.requests.filter((r) => r.url.startsWith('/tasks/')).map((r) => r.url),
  ).toEqual([`/tasks/${otherId}`, `/tasks/${otherId}`]);
  expect(s.router.currentRoute.value.path).toBe(`/knowledge-bases/${id}`);
});
it('创建网络结果不确定时同键同负载恢复，不能悄悄创建第二份库', async () => {
  let failure = true;
  const s = await creating(
    () => success,
    () => {
      if (failure) throw new Error('网络断线');
      return { task };
    },
  );
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined();
  expect(button('创建知识库').attributes('disabled')).toBeDefined();
  failure = false;
  await button('继续确认本次创建').trigger('click');
  await flushPromises();
  const posts = s.requests.filter((r) => r.method === 'post');
  expect(posts).toHaveLength(2);
  expect(posts[0].headers.get('Idempotency-Key')).toBe(
    posts[1].headers.get('Idempotency-Key'),
  );
  expect(posts[0].data).toBe(posts[1].data);
});
for (const [kind, label, item] of [
  ['sources', '更多来源', source],
  ['files', '更多文件', file],
]) {
  it(`${label}重复点击只请求并追加同一页一次`, async () => {
    const s = await open(`/knowledge-bases/${id}`, (r) => {
      if (r.url === '/knowledge-bases')
        return { items: [record], nextCursor: null };
      if (r.url.endsWith('/processing-config')) return config;
      if (r.url.endsWith(`/${kind}`))
        return { items: [], nextCursor: '下一页' };
      if (r.url.endsWith('/sources') || r.url.endsWith('/files'))
        return { items: [], nextCursor: null };
      return record;
    });
    let resolve;
    const response = new Promise((done) => {
      resolve = done;
    });
    s.reply((r) => (r.url.endsWith(`/${kind}`) ? response : record));
    const more = button(label);
    await more.trigger('click');
    await more.trigger('click');
    await flushPromises();
    expect(
      s.requests.filter(
        (r) => r.url.endsWith(`/${kind}`) && r.params?.cursor === '下一页',
      ),
    ).toHaveLength(1);
    resolve({ items: [item], nextCursor: null });
    await flushPromises();
    expect(
      wrapper.findAll(
        `[aria-label="${kind === 'sources' ? '关联来源' : '知识库文件'}"] > ul > li`,
      ),
    ).toHaveLength(1);
  });
}
it('失败任务保留脱敏原因，显式重新创建使用新键', async () => {
  let failure = true;
  const s = await creating(() =>
    failure
      ? {
          ...task,
          state: 'failed',
          phase: 'completed',
          startedAt: time,
          finishedAt: time,
          failure: {
            code: 'VECTOR_UNAVAILABLE',
            message: '受控集合故障',
            retryable: true,
          },
          warnings: ['等待核查'],
        }
      : success,
  );
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('受控集合故障');
  expect(wrapper.text()).toContain('等待核查');
  await button('修改后重新创建').trigger('click');
  failure = false;
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  const posts = s.requests.filter((r) => r.method === 'post');
  expect(posts).toHaveLength(2);
  expect(posts[0].headers.get('Idempotency-Key')).not.toBe(
    posts[1].headers.get('Idempotency-Key'),
  );
});
it('切换库后旧详情迟到响应不覆盖新库，旧错误也不覆盖', async () => {
  let resolve;
  const slow = new Promise((done) => {
    resolve = done;
  });
  const s = await open('/knowledge-bases', (r) => {
    if (r.url === '/knowledge-bases')
      return {
        items: [record, { ...record, id: otherId, name: '新库' }],
        nextCursor: null,
      };
    if (r.url.endsWith('/processing-config'))
      return {
        ...config,
        knowledgeBaseId: r.url.includes(otherId) ? otherId : id,
      };
    if (r.url.endsWith('/sources') || r.url.endsWith('/files'))
      return { items: [], nextCursor: null };
    if (r.url === `/knowledge-bases/${id}`) return slow;
    return { ...record, id: otherId, name: '新库' };
  });
  await s.router.push(`/knowledge-bases/${id}`);
  await flushPromises();
  await s.router.push(`/knowledge-bases/${otherId}`);
  await flushPromises();
  resolve(record);
  await flushPromises();
  expect(wrapper.find('[aria-label="知识库详情"]').text()).toContain('新库');
  expect(wrapper.find('[aria-label="知识库详情"]').text()).not.toContain(
    '空知识库',
  );
});
it('名称冲突逐字段关联并聚焦错误，修改后保存真实字段', async () => {
  const s = await open(`/knowledge-bases/${id}`);
  await button('修改名称与说明').trigger('click');
  await wrapper.get('#kb-name').setValue('重名');
  s.reply(() => ({
    errorStatus: 409,
    body: {
      requestId: id,
      code: 'NAME_EXISTS',
      message: '名称已存在',
      fieldErrors: [
        { field: '/name', code: 'NAME_EXISTS', message: '请换一个名称' },
      ],
      retryable: false,
    },
  }));
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.get('#kb-name').attributes('aria-invalid')).toBe('true');
  expect(document.activeElement.getAttribute('role')).toBe('alert');
  s.reply({ ...record, name: '新名称' });
  await wrapper.get('#kb-name').setValue('新名称');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(
    JSON.parse(s.requests.findLast((r) => r.method === 'patch').data),
  ).toEqual({ name: '新名称', description: '' });
  expect(wrapper.text()).toContain('名称与说明已保存');
});
it('新增整个数据集来源发送真实标识，成功刷新来源与待处理状态', async () => {
  const s = await open(`/knowledge-bases/${id}`);
  s.reply((r) =>
    r.url === '/datasets'
      ? {
          items: [
            {
              id: otherId,
              name: '项目资料',
              description: '',
              createdAt: time,
              updatedAt: time,
              fileCount: 0,
              totalBytes: 0,
            },
          ],
          nextCursor: null,
        }
      : r.method === 'post'
        ? { ...source, type: 'dataset' }
        : r.url.endsWith('/sources')
          ? { items: [{ ...source, type: 'dataset' }], nextCursor: null }
          : r.url.endsWith('/processing-config')
            ? config
            : r.url.endsWith('/files')
              ? { items: [], nextCursor: null }
              : { ...record, processingState: 'queued' },
  );
  await button('关联资料').trigger('click');
  await flushPromises();
  await wrapper.get('#kb-source-dataset').setValue(otherId);
  await wrapper.get('form[aria-label="关联资料表单"]').trigger('submit');
  await flushPromises();
  expect(
    JSON.parse(s.requests.findLast((r) => r.method === 'post').data),
  ).toEqual({ type: 'dataset', resourceId: otherId });
  expect(wrapper.text()).toContain('来源已关联');
  expect(wrapper.text()).toContain('等待处理');
  await button('关联资料').trigger('click');
  await flushPromises();
  expect(
    wrapper.get('#kb-source-dataset').attributes('disabled'),
  ).toBeUndefined();
});
it('空库表单须显式阈值与候选数，受理后查询原任务再进入详情', async () => {
  let finished = false;
  const s = await open('/knowledge-bases', (r) => {
    if (r.url === '/settings/models/embedding/versions')
      return {
        items: [
          {
            kind: 'embedding',
            versionId: id,
            endpoint: 'http://127.0.0.1:4567/v1',
            model: '固定嵌入模型',
            credential: { configured: true, mask: '【密钥已设置】' },
            contextWindowTokens: null,
            maxOutputTokens: null,
            complete: true,
            updatedAt: time,
          },
        ],
        nextCursor: null,
      };
    if (r.url === '/datasets') return { items: [], nextCursor: null };
    if (r.method === 'post') return { task };
    if (r.url.startsWith('/tasks/')) {
      finished = true;
      return {
        ...task,
        state: 'succeeded',
        phase: 'completed',
        startedAt: time,
        finishedAt: time,
        resultResourceId: id,
      };
    }
    if (r.url === '/knowledge-bases')
      return { items: finished ? [record] : [], nextCursor: null };
    if (r.url.endsWith('/processing-config')) return config;
    if (r.url.endsWith('/sources') || r.url.endsWith('/files'))
      return { items: [], nextCursor: null };
    return record;
  });
  await button('创建知识库').trigger('click');
  await flushPromises();
  await wrapper.get('#kb-name').setValue('空知识库');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.get('#kb-threshold').attributes('aria-invalid')).toBe('true');
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(0);
  await wrapper.get('#kb-threshold').setValue('0.5');
  await wrapper.get('#kb-candidates').setValue('10');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(s.requests.filter((r) => r.method === 'post')).toHaveLength(1);
  expect(s.router.currentRoute.value.path).toBe(`/knowledge-bases/${id}`);
  expect(wrapper.text()).toContain('未发布');
});
