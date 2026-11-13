import { expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import Files from './Files.vue';

const id = '00000000-0000-4000-8000-000000000001';
const now = '2026-11-13T00:00:00Z';
const record = {
  id,
  datasetId: id,
  name: '安全资料.txt',
  createdBy: null,
  createdAt: now,
  lastUploadedAt: now,
  updatedAt: now,
  latestVersion: {
    id,
    fileId: id,
    uploadedName: '安全资料.txt',
    fileType: 'text',
    mediaType: 'text/plain',
    sizeBytes: 3,
    hashAlgorithm: 'SHA-256',
    contentHash: 'a'.repeat(64),
    uploadedBy: null,
    uploadedAt: now,
    originalAvailable: true,
  },
};
it('空数据集提供单份文本上传与保存不等于索引提示', async () => {
  const client = {
    request: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
  };
  const wrapper = mount(Files, { props: { client, datasetId: id } });
  await flushPromises();
  expect(
    wrapper.get('input[type=file]').attributes('multiple'),
  ).toBeUndefined();
  expect(wrapper.text()).toContain('保存资料不代表已完成索引');
  expect(wrapper.text()).toContain('尚无资料');
  expect(findButton(wrapper, '选择文本文件').exists()).toBe(true);
});
it('选中文本上传后显示逐项结果、集中元数据与未知上传人', async () => {
  let saved = false;
  const client = {
    request: vi.fn(async (config) => {
      if (config.method === 'post') {
        saved = true;
        return {
          requestId: id,
          items: [
            {
              index: 0,
              submittedName: '安全资料.txt',
              outcome: 'created',
              file: record,
              error: null,
              indexTaskIds: [],
            },
          ],
        };
      }
      if (config.url.startsWith('/files/')) return record;
      return { items: saved ? [record] : [], nextCursor: null };
    }),
  };
  const wrapper = mount(Files, { props: { client, datasetId: id } });
  await flushPromises();
  Object.defineProperty(wrapper.get('input').element, 'files', {
    value: [new File(['内容'], '安全资料.txt', { type: 'text/plain' })],
  });
  await wrapper.get('input').trigger('change');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('资料已保存');
  expect(wrapper.text()).toContain('未知用户');
  expect(wrapper.text()).toContain(record.latestVersion.contentHash);
});
it('危险文本只以正文显示，关闭预览后焦点返回触发按钮', async () => {
  const client = {
    request: vi.fn(async (config) => {
      if (config.rawResponse)
        return {
          data: new TextEncoder().encode('<script>危险内容</script>').buffer,
          status: 200,
          headers: {
            'content-type': 'text/plain;charset=UTF-8',
            'content-disposition': 'inline; filename="安全资料.txt"',
            'x-content-type-options': 'nosniff',
            'cache-control': 'no-store',
            'content-security-policy': "sandbox; default-src 'none'",
          },
        };
      return config.url.startsWith('/files/')
        ? record
        : { items: [record], nextCursor: null };
    }),
  };
  const wrapper = mount(Files, {
    props: { client, datasetId: id },
    attachTo: document.body,
  });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await flushPromises();
  await wrapper.get('[data-action=preview]').trigger('click');
  await flushPromises();
  expect(wrapper.get('pre').text()).toBe('<script>危险内容</script>');
  expect(wrapper.find('script').exists()).toBe(false);
  await wrapper.get('[data-action=close-preview]').trigger('click');
  await flushPromises();
  expect(document.activeElement).toBe(
    wrapper.get('[data-action=preview]').element,
  );
  wrapper.unmount();
});
it('原格式下载使用二进制对象地址并及时释放', async () => {
  const create = vi.fn().mockReturnValue('blob:固定资料');
  const revoke = vi.fn();
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke });
  const click = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(() => {});
  const client = {
    request: vi.fn(async (config) =>
      config.rawResponse
        ? {
            data: new Uint8Array([65]).buffer,
            status: 200,
            headers: {
              'content-type': 'text/plain;charset=UTF-8',
              'content-disposition': 'attachment; filename="安全资料.txt"',
              'x-content-type-options': 'nosniff',
              'cache-control': 'no-store',
            },
          }
        : config.url.startsWith('/files/')
          ? record
          : { items: [record], nextCursor: null },
    ),
  };
  const wrapper = mount(Files, { props: { client, datasetId: id } });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await flushPromises();
  await wrapper.get('[data-action=download]').trigger('click');
  await flushPromises();
  expect(create).toHaveBeenCalledOnce();
  expect(revoke).toHaveBeenCalledWith('blob:固定资料');
  expect(click).toHaveBeenCalledOnce();
  click.mockRestore();
  vi.unstubAllGlobals();
});
it('资料详情读取失败可以重试且不显示旧对象', async () => {
  let broken = true;
  const client = {
    request: vi.fn(async (config) => {
      if (config.url.startsWith('/files/')) {
        if (broken) throw new Error('读取失败');
        return record;
      }
      return { items: [record], nextCursor: null };
    }),
  };
  const wrapper = mount(Files, { props: { client, datasetId: id } });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('读取失败');
  expect(wrapper.find('.file-detail').exists()).toBe(false);
  broken = false;
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试资料详情')
    .trigger('click');
  await flushPromises();
  expect(wrapper.get('.file-detail').text()).toContain('安全资料.txt');
});
const findButton = (wrapper, text) =>
  wrapper.findAll('button').find((button) => button.text() === text);
async function selectFile(wrapper, name = '安全资料.txt') {
  Object.defineProperty(wrapper.get('input').element, 'files', {
    value: [new File(['内容'], name, { type: 'text/plain' })],
    configurable: true,
  });
  await wrapper.get('input').trigger('change');
}
it('上传无选择、错误类型、暂停和逐项失败均不会冒报保存', async () => {
  const request = vi
    .fn()
    .mockResolvedValue({ items: [record], nextCursor: null });
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
    attachTo: document.body,
  });
  await flushPromises();
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(document.activeElement.getAttribute('role')).toBe('alert');
  await selectFile(wrapper, '资料.pdf');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(request.mock.calls.every(([config]) => config.method === 'get')).toBe(
    true,
  );
  await selectFile(wrapper);
  await wrapper.setProps({ writable: false });
  await wrapper.get('form').trigger('submit');
  expect(request.mock.calls.every(([config]) => config.method === 'get')).toBe(
    true,
  );
  await wrapper.setProps({ writable: true });
  request.mockImplementation(async (config) =>
    config.method === 'post'
      ? {
          requestId: id,
          items: [
            {
              index: 0,
              submittedName: '安全资料.txt',
              outcome: 'failed',
              file: null,
              error: {
                requestId: id,
                code: 'UNSUPPORTED_FILE',
                message: '无法识别文本',
                fieldErrors: [],
                retryable: false,
              },
              indexTaskIds: [],
            },
          ],
        }
      : { items: [record], nextCursor: null },
  );
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.text()).toContain('无法识别文本');
  expect(wrapper.text()).not.toContain('资料已保存');
  expect(wrapper.get('.file-rows').text()).toContain('安全资料.txt');
  wrapper.unmount();
});
it('列表分页失败保留原页，重试及上一页均按游标请求', async () => {
  let fail = false;
  const request = vi.fn(async (config) => {
    if (fail) throw new Error('列表读取失败');
    return {
      items: [record],
      nextCursor: config.params.cursor ? null : '第二页',
    };
  });
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
  });
  await flushPromises();
  await findButton(wrapper, '下一页资料').trigger('click');
  await flushPromises();
  expect(request.mock.calls.at(-1)[0].params.cursor).toBe('第二页');
  expect(wrapper.text()).toContain('第 2 页');
  fail = true;
  await findButton(wrapper, '上一页资料').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('列表读取失败');
  expect(wrapper.text()).toContain('第 2 页');
  fail = false;
  await findButton(wrapper, '重试资料列表').trigger('click');
  await flushPromises();
  await findButton(wrapper, '上一页资料').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('第 1 页');
});
it('切换数据集隔离迟到列表、详情及上传结果', async () => {
  let finishList, finishDetail, finishUpload;
  const other = '00000000-0000-4000-8000-000000000002';
  const request = vi.fn(async (config) => {
    if (config.method === 'post')
      return new Promise((resolve) => {
        finishUpload = resolve;
      });
    if (config.url.startsWith('/files/'))
      return new Promise((resolve) => {
        finishDetail = resolve;
      });
    return {
      items: config.url.includes(other) ? [] : [record],
      nextCursor: null,
    };
  });
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
  });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await selectFile(wrapper);
  await wrapper.get('form').trigger('submit');
  request.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finishList = resolve;
      }),
  );
  await findButton(wrapper, '刷新资料').trigger('click');
  await wrapper.setProps({ datasetId: other });
  await flushPromises();
  finishList({ items: [record], nextCursor: null });
  finishDetail(record);
  finishUpload({
    requestId: id,
    items: [
      {
        index: 0,
        submittedName: '安全资料.txt',
        outcome: 'created',
        file: record,
        error: null,
        indexTaskIds: [],
      },
    ],
  });
  await flushPromises();
  expect(wrapper.text()).toContain('尚无资料');
  expect(wrapper.find('.file-detail').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('资料已保存');
  expect(wrapper.emitted('changed')).toBeUndefined();
});
it.each(['', '\u0000', '非法编码', '副本不可用', '其他媒体'])(
  '预览空态和失败边界%s',
  async (value) => {
    const request = vi.fn(async (config) => {
      if (config.rawResponse) {
        if (value === '副本不可用') throw new Error('副本不可用');
        return {
          data:
            value === '非法编码'
              ? new Uint8Array([255]).buffer
              : new TextEncoder().encode(value).buffer,
          status: 200,
          headers: {
            'content-type':
              value === '其他媒体'
                ? 'text/markdown;charset=UTF-8'
                : 'text/plain;charset=UTF-8',
            'content-disposition': 'inline; filename="安全资料.txt"',
            'x-content-type-options': 'nosniff',
            'cache-control': 'no-store',
            'content-security-policy': "sandbox; default-src 'none'",
          },
        };
      }
      return config.url.startsWith('/files/')
        ? record
        : { items: [record], nextCursor: null };
    });
    const wrapper = mount(Files, {
      props: { client: { request }, datasetId: id },
    });
    await flushPromises();
    await wrapper.get('.file-rows button').trigger('click');
    await flushPromises();
    await wrapper.get('[data-action=preview]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain(
      value === ''
        ? '这份文本为空'
        : value === '\u0000'
          ? '不是有效的纯文本'
          : value === '非法编码'
            ? '不是有效的UTF-8文本'
            : value === '其他媒体'
              ? '仅支持纯文本预览'
              : '副本不可用',
    );
    await wrapper.get('.file-preview').trigger('keydown', { key: 'Escape' });
    expect(wrapper.find('pre').exists()).toBe(false);
  },
);
it('关闭预览隔离迟到正文，重复上传在途只提交一次', async () => {
  let finish;
  let finishUpload;
  const request = vi.fn(async (config) => {
    if (config.rawResponse)
      return new Promise((resolve) => {
        finish = resolve;
      });
    if (config.method === 'post')
      return new Promise((resolve) => {
        finishUpload = resolve;
      });
    return config.url.startsWith('/files/')
      ? record
      : { items: [record], nextCursor: null };
  });
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
  });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await flushPromises();
  await wrapper.get('[data-action=preview]').trigger('click');
  await wrapper.get('[data-action=close-preview]').trigger('click');
  finish({
    data: new TextEncoder().encode('迟到正文').buffer,
    status: 200,
    headers: {
      'content-type': 'text/plain;charset=UTF-8',
      'content-disposition': 'inline; filename="安全资料.txt"',
      'x-content-type-options': 'nosniff',
      'cache-control': 'no-store',
      'content-security-policy': "sandbox; default-src 'none'",
    },
  });
  await flushPromises();
  expect(wrapper.text()).not.toContain('迟到正文');
  await selectFile(wrapper);
  await wrapper.get('form').trigger('submit');
  await wrapper.get('form').trigger('submit');
  expect(
    request.mock.calls.filter(([config]) => config.method === 'post'),
  ).toHaveLength(1);
  finishUpload({
    requestId: id,
    items: [
      {
        index: 0,
        submittedName: '安全资料.txt',
        outcome: 'unchanged',
        file: record,
        error: null,
        indexTaskIds: [],
      },
    ],
  });
  await flushPromises();
  expect(wrapper.text()).toContain('内容未变化');
});
