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
it('空数据集提供批量资料上传与保存不等于索引提示', async () => {
  const client = {
    request: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
  };
  const wrapper = mount(Files, { props: { client, datasetId: id } });
  await flushPromises();
  expect(wrapper.get('input[type=file]').attributes('multiple')).toBeDefined();
  expect(wrapper.text()).toContain('保存资料不代表已完成索引');
  expect(wrapper.text()).toContain('尚无资料');
  expect(findButton(wrapper, '选择文件').exists()).toBe(true);
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
  await vi.waitFor(() =>
    expect(wrapper.get('pre').text()).toBe('<script>危险内容</script>'),
  );
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
it.each(['success', 'failure'])(
  '切换资料隔离迟到重命名与版本响应：%s',
  async (outcome) => {
    let finishRename, finishVersions;
    const secondId = '00000000-0000-4000-8000-000000000002';
    const second = {
      ...record,
      id: secondId,
      name: '另一份资料.txt',
      latestVersion: { ...record.latestVersion, fileId: secondId },
    };
    const request = vi.fn(async (config) => {
      if (config.method === 'patch')
        return new Promise((resolve, reject) => {
          finishRename = outcome === 'success' ? resolve : reject;
        });
      if (config.url.endsWith('/versions'))
        return new Promise((resolve, reject) => {
          finishVersions = outcome === 'success' ? resolve : reject;
        });
      if (config.url === `/files/${secondId}`) return second;
      return config.url.startsWith('/files/')
        ? record
        : { items: [record, second], nextCursor: null };
    });
    const wrapper = mount(Files, {
      props: { client: { request }, datasetId: id },
    });
    await flushPromises();
    await wrapper.findAll('.file-rows button')[0].trigger('click');
    await flushPromises();
    await findButton(wrapper, '查看内容版本').trigger('click');
    await wrapper.get('[data-action=rename]').trigger('click');
    await wrapper.get('input[name=file-name]').setValue('迟到名称.txt');
    await wrapper.get('[aria-label="重命名资料"]').trigger('submit');
    await wrapper.get('[aria-label="重命名资料"]').trigger('submit');
    expect(
      request.mock.calls.filter(([config]) => config.method === 'patch'),
    ).toHaveLength(1);
    await wrapper.findAll('.file-rows button')[1].trigger('click');
    await flushPromises();
    finishRename(
      outcome === 'success'
        ? { ...record, name: '迟到名称.txt' }
        : new Error('迟到错误'),
    );
    finishVersions(
      outcome === 'success'
        ? { items: [record.latestVersion], nextCursor: null }
        : new Error('迟到版本错误'),
    );
    await flushPromises();
    expect(wrapper.get('.file-detail h4').text()).toBe(second.name);
    expect(wrapper.text()).not.toContain('迟到');
    expect(wrapper.find('[aria-label="内容版本"]').exists()).toBe(false);
    wrapper.unmount();
  },
);
it('重命名冲突保留当前资料，成功可跨扩展名且版本上传快照不变', async () => {
  let conflict = true;
  const request = vi.fn(async (config) => {
    if (config.method === 'patch') {
      if (conflict) throw new Error('名称已存在');
      return { ...record, name: config.data.name };
    }
    return config.url.startsWith('/files/')
      ? record
      : { items: [record], nextCursor: null };
  });
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
    attachTo: document.body,
  });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await flushPromises();
  await wrapper.get('[data-action=rename]').trigger('click');
  await wrapper.get('input[name=file-name]').setValue(' 改名.pdf ');
  await wrapper.get('[aria-label="重命名资料"]').trigger('submit');
  await flushPromises();
  expect(wrapper.get('.file-detail h4').text()).toBe(record.name);
  expect(wrapper.text()).toContain('名称已存在');
  expect(document.activeElement.getAttribute('role')).toBe('alert');
  conflict = false;
  await wrapper.get('[aria-label="重命名资料"]').trigger('submit');
  await flushPromises();
  expect(wrapper.get('.file-detail h4').text()).toBe('改名.pdf');
  expect(
    request.mock.calls.find(([config]) => config.method === 'patch')[0].data,
  ).toEqual({ name: ' 改名.pdf ' });
  expect(wrapper.text()).toContain(record.latestVersion.contentHash);
  expect(wrapper.text()).toContain('text/plain');
  expect(document.activeElement).toBe(
    wrapper.get('[data-action=rename]').element,
  );
  wrapper.unmount();
});
it('版本列表按服务端游标分页，失败保留原页并可重试', async () => {
  let fail = false;
  const request = vi.fn(async (config) => {
    if (config.url.endsWith('/versions')) {
      if (fail) throw new Error('版本读取失败');
      return {
        items: [
          {
            ...record.latestVersion,
            uploadedName: config.params.cursor ? '原始旧名.txt' : record.name,
            originalAvailable: !config.params.cursor,
          },
        ],
        nextCursor: config.params.cursor ? null : '后续版本',
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
  await findButton(wrapper, '查看内容版本').trigger('click');
  await flushPromises();
  expect(wrapper.get('[aria-label="内容版本"]').text()).toContain(
    '完整副本可用',
  );
  fail = true;
  await findButton(wrapper, '下一页版本').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('版本读取失败');
  expect(wrapper.get('[aria-label="内容版本"]').text()).toContain('第 1 页');
  fail = false;
  await findButton(wrapper, '下一页版本').trigger('click');
  await flushPromises();
  expect(wrapper.get('[aria-label="内容版本"]').text()).toContain(
    '原始旧名.txt',
  );
  expect(wrapper.get('[aria-label="内容版本"]').text()).toContain(
    '完整副本不可用',
  );
  expect(
    request.mock.calls.findLast(([config]) =>
      config.url.endsWith('/versions'),
    )[0].params,
  ).toEqual({ limit: 20, cursor: '后续版本' });
});
it('批量四结果独立展示且显式重试只发送失败项', async () => {
  const files = ['新增.txt', '覆盖.txt', '未变.txt', '失败.docx'].map(
    (name) => new File(['内容'], name),
  );
  const batches = [];
  const client = {
    request: vi.fn(async (config) => {
      if (config.method === 'post') {
        const batch = config.data.getAll('files');
        batches.push(batch);
        return {
          requestId: id,
          items: batch.map((file, index) => ({
            index,
            submittedName: file.name,
            outcome:
              batches.length === 1
                ? ['created', 'replaced', 'unchanged', 'failed'][index]
                : 'created',
            file: batches.length === 1 && index === 3 ? null : record,
            error:
              batches.length === 1 && index === 3
                ? {
                    requestId: id,
                    code: 'RESOURCE_LIMIT',
                    message: '资料超限',
                    fieldErrors: [],
                    retryable: true,
                  }
                : null,
            indexTaskIds: [],
          })),
        };
      }
      return config.url.startsWith('/files/')
        ? record
        : { items: [record], nextCursor: null };
    }),
  };
  const wrapper = mount(Files, { props: { client, datasetId: id } });
  await flushPromises();
  Object.defineProperty(wrapper.get('input').element, 'files', {
    value: files,
  });
  await wrapper.get('input').trigger('change');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(batches[0]).toEqual(files);
  for (const text of [
    '新增成功',
    '覆盖成功',
    '内容未变',
    '上传失败',
    '资料超限',
  ])
    expect(wrapper.text()).toContain(text);
  expect(wrapper.findAll('[data-action=retry-upload]')).toHaveLength(1);
  await wrapper.get('[data-action=retry-upload]').trigger('click');
  await flushPromises();
  expect(batches).toEqual([files, [files[3]]]);
  expect(wrapper.findAll('[data-action=retry-upload]')).toHaveLength(0);
  wrapper.unmount();
});
async function selectFile(wrapper, name = '安全资料.txt') {
  Object.defineProperty(wrapper.get('input').element, 'files', {
    value: [new File(['内容'], name, { type: 'text/plain' })],
    configurable: true,
  });
  await wrapper.get('input').trigger('change');
}
it('同批NFC与大小写等价名称由用户选一份，未选组不阻断其他完整名', async () => {
  const files = ['é.TXT', 'e\u0301.txt', ' 独立.txt', '独立.txt'].map(
    (name) => new File(['内容'], name),
  );
  const batches = [];
  const request = vi.fn(async (config) => {
    if (config.method === 'post') {
      const batch = config.data.getAll('files');
      batches.push(batch);
      return {
        requestId: id,
        items: batch.map((file, index) => ({
          index,
          submittedName: file.name,
          outcome: 'created',
          file: record,
          error: null,
          indexTaskIds: [],
        })),
      };
    }
    return config.url.startsWith('/files/')
      ? record
      : { items: [], nextCursor: null };
  });
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
  });
  await flushPromises();
  Object.defineProperty(wrapper.get('input').element, 'files', {
    value: files,
  });
  await wrapper.get('input').trigger('change');
  expect(wrapper.findAll('input[type=radio]')).toHaveLength(2);
  expect(wrapper.findAll('input[type=radio]:checked')).toHaveLength(0);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(batches).toEqual([[files[2], files[3]]]);
  await wrapper.findAll('input[type=radio]')[1].setValue(true);
  expect(wrapper.findAll('input[type=radio]:checked')).toHaveLength(1);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(batches[1]).toEqual([files[1]]);
  expect(wrapper.findAll('input[type=radio]')).toHaveLength(0);
  expect(wrapper.get('[aria-label="逐项上传结果"]').text()).toContain(
    ' 独立.txt',
  );
});
it('上传无选择、服务端拒绝格式、暂停和逐项失败均不会冒报保存', async () => {
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
  await selectFile(wrapper, '资料.docx');
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(
    request.mock.calls.filter(([config]) => config.method === 'post'),
  ).toHaveLength(1);
  expect(wrapper.text()).not.toContain('资料已保存');
  await selectFile(wrapper);
  await wrapper.setProps({ writable: false });
  await wrapper.get('form').trigger('submit');
  expect(
    request.mock.calls.filter(([config]) => config.method === 'post'),
  ).toHaveLength(1);
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
              ? '预览媒体与资料元数据不一致'
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
it.each(['text/plain', 'text/markdown'])(
  '文本预算拒绝且原文件下载仍可用：%s',
  async (mediaType) => {
    const file = {
      ...record,
      latestVersion: {
        ...record.latestVersion,
        fileType: mediaType === 'text/plain' ? 'text' : 'markdown',
        mediaType,
      },
    };
    const request = vi.fn(async (config) =>
      config.rawResponse
        ? {
            data: new TextEncoder().encode('\n'.repeat(10001)).buffer,
            status: 200,
            headers: {
              'content-type': `${mediaType};charset=UTF-8`,
              'content-disposition': 'inline; filename="资料.txt"',
              'x-content-type-options': 'nosniff',
              'cache-control': 'no-store',
              'content-security-policy': "sandbox; default-src 'none'",
            },
          }
        : config.url.startsWith('/files/')
          ? file
          : { items: [file], nextCursor: null },
    );
    const wrapper = mount(Files, {
      props: { client: { request }, datasetId: id },
    });
    await flushPromises();
    await wrapper.get('.file-rows button').trigger('click');
    await flushPromises();
    await wrapper.get('[data-action=preview]').trigger('click');
    await flushPromises();
    expect(wrapper.get('.file-preview [role=alert]').text()).toContain(
      '资源限制',
    );
    expect(
      wrapper.get('[data-action=download]').attributes('disabled'),
    ).toBeUndefined();
    wrapper.unmount();
  },
);
it('双BOM仅移除首个且Markdown呈现与源行保持一致', async () => {
  const file = {
    ...record,
    latestVersion: {
      ...record.latestVersion,
      fileType: 'markdown',
      mediaType: 'text/markdown',
    },
  };
  const request = vi.fn(async (config) =>
    config.rawResponse
      ? {
          data: new TextEncoder().encode('\ufeff\ufeff# 标题\r正文\n').buffer,
          status: 200,
          headers: {
            'content-type': 'text/markdown;charset=UTF-8',
            'content-disposition': 'inline; filename="资料.md"',
            'x-content-type-options': 'nosniff',
            'cache-control': 'no-store',
            'content-security-policy': "sandbox; default-src 'none'",
          },
        }
      : config.url.startsWith('/files/')
        ? file
        : { items: [file], nextCursor: null },
  );
  const wrapper = mount(Files, {
    props: { client: { request }, datasetId: id },
  });
  await flushPromises();
  await wrapper.get('.file-rows button').trigger('click');
  await flushPromises();
  await wrapper.get('[data-action=preview]').trigger('click');
  await vi.waitFor(() =>
    expect(wrapper.get('.markdown-body').element.textContent).toContain(
      '\ufeff# 标题',
    ),
  );
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看源文本')
    .trigger('click');
  expect(wrapper.get('.source-text').element.textContent).toBe(
    '\ufeff# 标题\n正文\n',
  );
  wrapper.unmount();
});
