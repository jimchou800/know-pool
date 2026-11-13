import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import Datasets from './Datasets.vue';
import { createSystemClient } from '../system/session';

export const record = {
  id: '00000000-0000-4000-8000-000000000001',
  name: '测试资料',
  description: '原有说明',
  createdAt: '2026-11-13T00:00:00Z',
  updatedAt: '2026-11-13T00:00:00Z',
  fileCount: 0,
  totalBytes: 0,
};
const session = {
  sessionId: record.id,
  csrfToken: '固定测试保护'.repeat(10),
  expiresAt: record.createdAt,
};
const status = {
  applicationVersion: '0.1.0',
  startedAt: record.createdAt,
  writeState: 'ready',
  dataDirectory: '测试目录',
  schemaVersion: 1,
  warnings: [],
};
const fileRecord = (size) => ({
  id: record.id,
  datasetId: record.id,
  name: '资料.txt',
  createdBy: null,
  createdAt: record.createdAt,
  lastUploadedAt: record.createdAt,
  updatedAt: record.updatedAt,
  latestVersion: {
    id: record.id,
    fileId: record.id,
    uploadedName: '资料.txt',
    fileType: 'text',
    mediaType: 'text/plain',
    sizeBytes: size,
    hashAlgorithm: 'SHA-256',
    contentHash: 'a'.repeat(64),
    uploadedBy: null,
    uploadedAt: record.createdAt,
    originalAvailable: true,
  },
});
let wrapper;
let requests;
let handler;
afterEach(() => wrapper?.unmount());
async function setup(path = '/datasets', writable = true, respond) {
  requests = [];
  handler = vi.fn((config) =>
    config.url === '/datasets' ? { items: [record], nextCursor: null } : record,
  );
  if (respond) handler.mockImplementation(respond);
  const client = createSystemClient(async (config) => {
    if (config.url === '/session')
      return { data: session, status: 200, config };
    if (config.url === '/system/status')
      return { data: status, status: 200, config };
    requests.push(config);
    return {
      data: await handler(config),
      status: 200,
      config,
      headers: config.rawResponse
        ? {
            'content-type': 'text/plain;charset=UTF-8',
            'content-disposition': 'inline; filename="资料.txt"',
            'x-content-type-options': 'nosniff',
            'cache-control': 'no-store',
            'content-security-policy': "sandbox; default-src 'none'",
          }
        : undefined,
    };
  });
  await client.connect();
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push(path);
  wrapper = mount(Datasets, {
    props: { client, writable },
    attachTo: document.body,
    global: { plugins: [router] },
  });
  await flushPromises();
  return router;
}
const button = (label) =>
  wrapper.findAll('button').find((node) => node.text() === label);
async function create() {
  await button('创建数据集').trigger('click');
}
async function save() {
  await wrapper.get('form').trigger('submit');
  await flushPromises();
}

it('列表分页携带真实游标，失败保留旧页并支持重试与上一页', async () => {
  await setup();
  expect(wrapper.text()).toContain(record.name);
  expect(requests[0].params).toEqual({ limit: 20 });
  handler.mockImplementation(() => ({
    items: [record],
    nextCursor: 'opaque +/游标',
  }));
  await button('刷新列表').trigger('click');
  await flushPromises();
  await button('下一页').trigger('click');
  await flushPromises();
  expect(requests.at(-1).params).toEqual({
    limit: 20,
    cursor: 'opaque +/游标',
  });
  expect(wrapper.text()).toContain('第 2 页');
  handler.mockRejectedValueOnce(new Error('内部细节'));
  await button('上一页').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('请求未完成');
  expect(wrapper.text()).toContain('第 2 页');
  await button('重试列表').trigger('click');
  await flushPromises();
  await button('上一页').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('第 1 页');
});

it('新增和覆盖上传后真实重读集中统计且保留资料详情与保存结果', async () => {
  let size = 0;
  const file = () => fileRecord(size);
  await setup(`/datasets/${record.id}`, true, (config) => {
    const dataset = () => ({
      ...record,
      fileCount: size ? 1 : 0,
      totalBytes: size,
    });
    if (config.url.endsWith('/files:upload')) {
      const outcome = size ? 'replaced' : 'created';
      size = outcome === 'created' ? 3 : 8;
      return {
        requestId: record.id,
        items: [
          {
            index: 0,
            submittedName: '资料.txt',
            outcome,
            file: file(),
            error: null,
            indexTaskIds: [],
          },
        ],
      };
    }
    if (config.url.endsWith('/files'))
      return { items: size ? [file()] : [], nextCursor: null };
    if (config.url.startsWith('/files/')) return file();
    return config.url === '/datasets'
      ? { items: [dataset()], nextCursor: null }
      : dataset();
  });
  for (const expected of [3, 8]) {
    Object.defineProperty(wrapper.get('input[type=file]').element, 'files', {
      value: [new File(['固定内容'], '资料.txt', { type: 'text/plain' })],
      configurable: true,
    });
    await wrapper.get('input[type=file]').trigger('change');
    await wrapper.get('.files-panel form').trigger('submit');
    await flushPromises();
    const summary = wrapper.get('.dataset-detail > dl');
    expect(summary.text()).toContain('1 份');
    expect(summary.text()).toContain(`${expected} 字节`);
    expect(wrapper.get('.dataset-rows').text()).toContain(
      `1 份资料 · ${expected} 字节`,
    );
    expect(wrapper.get('.file-detail').text()).toContain('资料.txt');
    expect(wrapper.get('.files-panel [role=status]').text()).toContain(
      '资料已保存',
    );
  }
});
it('上传后的统计刷新失败保留已保存资料及结果，重试成功不卸载资料面板', async () => {
  let saved = false;
  let fail = true;
  await setup(`/datasets/${record.id}`, true, (config) => {
    if (config.rawResponse)
      return new TextEncoder().encode('固定预览正文').buffer;
    if (config.url.endsWith('/files:upload')) {
      saved = true;
      return {
        requestId: record.id,
        items: [
          {
            index: 0,
            submittedName: '资料.txt',
            outcome: 'created',
            file: fileRecord(3),
            error: null,
            indexTaskIds: [],
          },
        ],
      };
    }
    if (config.url.endsWith('/files'))
      return { items: saved ? [fileRecord(3)] : [], nextCursor: null };
    if (config.url.startsWith('/files/')) return fileRecord(3);
    const dataset = {
      ...record,
      fileCount: saved ? 1 : 0,
      totalBytes: saved ? 3 : 0,
    };
    if (config.url === '/datasets')
      return { items: [dataset], nextCursor: null };
    if (saved && fail) throw new Error('固定读取故障');
    return dataset;
  });
  const panel = wrapper.get('.files-panel').element;
  Object.defineProperty(wrapper.get('input[type=file]').element, 'files', {
    value: [new File(['固定内容'], '资料.txt', { type: 'text/plain' })],
  });
  await wrapper.get('input[type=file]').trigger('change');
  await wrapper.get('.files-panel form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('资料统计刷新失败');
  expect(wrapper.get('.file-detail').text()).toContain('资料.txt');
  expect(wrapper.get('.files-panel [role=status]').text()).toContain(
    '资料已保存',
  );
  expect(wrapper.get('.files-panel').element).toBe(panel);
  await button('预览最新资料').trigger('click');
  await flushPromises();
  await vi.waitFor(() =>
    expect(wrapper.get('.file-preview pre').text()).toBe('固定预览正文'),
  );
  fail = false;
  await button('重试资料统计').trigger('click');
  await flushPromises();
  expect(wrapper.get('.dataset-detail > dl').text()).toContain('3 字节');
  expect(wrapper.text()).not.toContain('资料统计刷新失败');
  expect(wrapper.get('.files-panel').element).toBe(panel);
  expect(wrapper.get('.file-detail').text()).toContain('资料.txt');
  expect(wrapper.get('.file-preview pre').text()).toBe('固定预览正文');
});
it.each([false, true])(
  '统计刷新迟到成功或失败不能覆盖新数据集：%s',
  async (reject) => {
    let saved = false;
    let finish;
    const otherId = '00000000-0000-4000-8000-000000000002';
    const router = await setup(`/datasets/${record.id}`, true, (config) => {
      if (config.url.endsWith('/files:upload')) {
        saved = true;
        return {
          requestId: record.id,
          items: [
            {
              index: 0,
              submittedName: '资料.txt',
              outcome: 'created',
              file: fileRecord(3),
              error: null,
              indexTaskIds: [],
            },
          ],
        };
      }
      if (config.url.endsWith('/files')) return { items: [], nextCursor: null };
      if (config.url.startsWith('/files/')) return fileRecord(3);
      if (config.url === '/datasets')
        return { items: [record], nextCursor: null };
      if (config.url.endsWith(otherId))
        return {
          ...record,
          id: otherId,
          name: '新数据集',
          fileCount: 2,
          totalBytes: 12,
        };
      if (saved)
        return new Promise((resolve, fail) => {
          finish = reject ? fail : resolve;
        });
      return record;
    });
    Object.defineProperty(wrapper.get('input[type=file]').element, 'files', {
      value: [new File(['内容'], '资料.txt')],
    });
    await wrapper.get('input[type=file]').trigger('change');
    await wrapper.get('.files-panel form').trigger('submit');
    await flushPromises();
    await router.push(`/datasets/${otherId}`);
    await flushPromises();
    finish(
      reject
        ? new Error('迟到故障')
        : { ...record, fileCount: 1, totalBytes: 3 },
    );
    await flushPromises();
    expect(wrapper.get('.dataset-detail h2').text()).toBe('新数据集');
    expect(wrapper.get('.dataset-detail > dl').text()).toContain('12 字节');
    expect(wrapper.text()).not.toContain('资料统计刷新失败');
  },
);
it('无资料空态、稳定标识详情导航与读失败恢复', async () => {
  const router = await setup();
  await wrapper.get('.dataset-rows a').trigger('click');
  await flushPromises();
  expect(router.currentRoute.value.path).toBe(`/datasets/${record.id}`);
  expect(wrapper.get('.dataset-detail').text()).toContain('上传资料');
  expect(wrapper.text()).toContain(record.createdAt);
  handler.mockRejectedValueOnce(new Error('不显示的内部路径'));
  await router.push('/datasets/00000000-0000-4000-8000-000000000002');
  await flushPromises();
  expect(wrapper.get('.dataset-detail').text()).not.toContain(record.name);
  expect(wrapper.text()).toContain('请求未完成');
  handler.mockResolvedValue({
    ...record,
    id: '00000000-0000-4000-8000-000000000002',
    description: '',
    fileCount: 2,
  });
  await button('重试详情').trigger('click');
  await flushPromises();
  expect(wrapper.get('.dataset-detail').text()).toContain('暂无说明');
  expect(wrapper.get('.dataset-detail').text()).toContain('上传资料');
});
it('创建空说明省略，请求保护头来自共享会话；成功重新读取详情', async () => {
  await setup();
  handler.mockImplementation((config) =>
    config.method === 'post'
      ? { ...record, name: '新数据集', description: '' }
      : config.url === '/datasets'
        ? { items: [], nextCursor: null }
        : { ...record, name: '新数据集', description: '' },
  );
  await create();
  expect(document.activeElement.id).toBe('dataset-name');
  await wrapper.get('input').setValue('  新数据集  ');
  await save();
  const post = requests.find((config) => config.method === 'post');
  expect(JSON.parse(post.data)).toEqual({ name: '  新数据集  ' });
  expect(post.headers.get('X-CSRF-Token')).toBe(session.csrfToken);
  expect(post.headers.get('Content-Type')).toBe('application/json');
  expect(wrapper.text()).toContain('数据集已创建');
  expect(wrapper.get('.dataset-detail h2').text()).toBe('新数据集');
  expect(wrapper.text()).toContain('尚无数据集');
});
it('本地字段校验并聚焦错误摘要，取消返回创建按钮', async () => {
  await setup();
  await create();
  await save();
  expect(wrapper.get('#dataset-name-error').text()).toContain('名称不能为空');
  expect(document.activeElement.getAttribute('role')).toBe('alert');
  await wrapper.get('input').setValue('字'.repeat(121));
  await wrapper.get('textarea').setValue('字'.repeat(2001));
  await save();
  expect(wrapper.text()).toContain('名称不能超过 120 字');
  expect(wrapper.text()).toContain('说明不能超过 2000 字');
  expect(requests.some((config) => config.method === 'post')).toBe(false);
  await wrapper.get('form').trigger('keydown', { key: 'Escape' });
  expect(document.activeElement.textContent.trim()).toBe('创建数据集');
});
it('修改只发送改变字段，省略保持及空串清除；失败不覆盖原记录', async () => {
  await setup(`/datasets/${record.id}`);
  await button('修改名称与说明').trigger('click');
  await wrapper.get('input').setValue('重命名');
  handler.mockImplementation((config) => {
    if (config.method === 'patch') {
      const failure = new Error();
      failure.response = {
        data: {
          requestId: record.id,
          code: 'NAME_EXISTS',
          message: '名称已存在',
          fieldErrors: [
            { field: '/name', code: 'NAME_EXISTS', message: '请使用其他名称' },
          ],
          retryable: false,
        },
      };
      throw failure;
    }
    return config.url === '/datasets'
      ? { items: [record], nextCursor: null }
      : record;
  });
  await save();
  expect(JSON.parse(requests.at(-1).data)).toEqual({ name: '重命名' });
  expect(wrapper.get('#dataset-name-error').text()).toBe('请使用其他名称');
  expect(wrapper.get('.dataset-rows').text()).toContain(record.name);
  await button('取消').trigger('click');
  expect(wrapper.get('.dataset-detail h2').text()).toBe(record.name);
  expect(document.activeElement.textContent.trim()).toBe('修改名称与说明');
  await button('修改名称与说明').trigger('click');
  await wrapper.get('textarea').setValue('');
  handler.mockImplementation((config) =>
    config.url === '/datasets'
      ? { items: [{ ...record, description: '' }], nextCursor: null }
      : { ...record, description: '' },
  );
  await save();
  expect(
    JSON.parse(requests.findLast((config) => config.method === 'patch').data),
  ).toEqual({ description: '' });
  expect(wrapper.get('.dataset-detail').text()).toContain('暂无说明');
});
it('无变更不发送写请求，暂停状态禁写', async () => {
  await setup(`/datasets/${record.id}`);
  await button('修改名称与说明').trigger('click');
  await save();
  expect(requests.some((config) => config.method === 'patch')).toBe(false);
  await wrapper.setProps({ writable: false });
  expect(button('修改名称与说明').attributes('disabled')).toBeDefined();
  expect(button('创建数据集').attributes('disabled')).toBeDefined();
});
it('重复提交不重复写入，迟到详情不覆盖当前选择', async () => {
  const router = await setup(`/datasets/${record.id}`);
  await button('修改名称与说明').trigger('click');
  await wrapper.get('input').setValue('修改');
  let finish;
  handler.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  await wrapper.get('form').trigger('submit');
  await wrapper.get('form').trigger('submit');
  expect(requests.filter((config) => config.method === 'patch')).toHaveLength(
    1,
  );
  finish(record);
  await flushPromises();
  let old;
  handler.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        old = resolve;
      }),
  );
  await router.push('/datasets/00000000-0000-4000-8000-000000000002');
  await flushPromises();
  await router.push('/datasets');
  await flushPromises();
  old({ ...record, id: '00000000-0000-4000-8000-000000000002' });
  await flushPromises();
  expect(wrapper.get('.dataset-detail').text()).toContain('从左侧选择');
});

it.each([false, true])(
  '离开资源后迟到写入结果或错误不覆盖新选择：%s',
  async (reject) => {
    const router = await setup(`/datasets/${record.id}`);
    await button('修改名称与说明').trigger('click');
    await wrapper.get('input').setValue('迟到修改');
    let finish;
    handler.mockImplementationOnce(
      () =>
        new Promise((resolve, fail) => {
          finish = reject ? fail : resolve;
        }),
    );
    await wrapper.get('form').trigger('submit');
    await router.push('/datasets');
    await flushPromises();
    finish(reject ? new Error('内部故障') : { ...record, name: '迟到修改' });
    await flushPromises();
    expect(wrapper.get('.dataset-detail').text()).toContain('从左侧选择');
    expect(wrapper.text()).not.toContain('迟到修改');
    expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  },
);
