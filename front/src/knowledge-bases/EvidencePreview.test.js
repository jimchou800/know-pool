import { expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import EvidencePreview from './EvidencePreview.vue';
import { evidence } from '../../test-fixtures/indexing';
const item = {
  ...evidence,
  originalAvailable: true,
  location: {
    ...evidence.location,
    format: 'markdown',
    lineStart: 3,
    lineEnd: 3,
  },
};
function response() {
  return {
    status: 200,
    data: new TextEncoder().encode('# 旧版\n\n证据正文').buffer,
    headers: {
      'content-type': 'text/markdown; charset=UTF-8',
      'content-disposition': "inline; filename*=UTF-8''old.md",
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'content-security-policy': "sandbox; default-src 'none'",
    },
  };
}
it('按证据版本读取原文并定位源行，关闭返回触发点', async () => {
  const requests = [];
  const wrapper = mount(EvidencePreview, {
    props: {
      evidence: item,
      client: {
        request: async (r) => {
          requests.push(r);
          return response();
        },
      },
    },
    attachTo: document.body,
  });
  const open = wrapper.get('button');
  await open.trigger('click');
  await flushPromises();
  await vi.waitFor(() => {
    expect(wrapper.get('[aria-label="对应版本原文"]').text()).toContain(
      '证据正文',
    );
  });
  expect(requests[0].url).toBe(
    `/files/${item.fileId}/versions/${item.contentVersionId}/preview`,
  );
  expect(wrapper.get('[aria-label="对应版本原文"]').text()).toContain(
    '证据正文',
  );
  expect(document.activeElement.textContent).toBe('证据正文');
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '关闭原文预览')
    .trigger('click');
  expect(document.activeElement).toBe(open.element);
  expect(wrapper.find('[aria-label="对应版本原文"]').exists()).toBe(false);
  wrapper.unmount();
});
it('关闭后迟到原文不复活面板也不夺取焦点', async () => {
  let resolve;
  const wrapper = mount(EvidencePreview, {
    props: {
      evidence: item,
      client: {
        request: () =>
          new Promise((done) => {
            resolve = done;
          }),
      },
    },
    attachTo: document.body,
  });
  const open = wrapper.get('button');
  await open.trigger('click');
  await flushPromises();
  await wrapper
    .get('[aria-label="对应版本原文"]')
    .trigger('keydown', { key: 'Escape' });
  resolve(response());
  await flushPromises();
  expect(wrapper.find('[aria-label="对应版本原文"]').exists()).toBe(false);
  expect(document.activeElement).toBe(open.element);
  wrapper.unmount();
});
it.each([
  ['CONTENT_UNAVAILABLE', '已不可用'],
  ['NOT_FOUND', '不存在'],
  ['INTERNAL_ERROR', '固定读取失败'],
])('版本读取失败%s不回退最新且可仅重试原文', async (code, message) => {
  const request = vi
    .fn()
    .mockRejectedValueOnce({ code, message: '固定读取失败' })
    .mockResolvedValueOnce(response());
  const wrapper = mount(EvidencePreview, {
    props: { evidence: item, client: { request } },
  });
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain(message);
  await wrapper.get('[role=alert] button').trigger('click');
  await flushPromises();
  expect(
    request.mock.calls.every(([r]) =>
      r.url.endsWith(`/versions/${item.contentVersionId}/preview`),
    ),
  ).toBe(true);
  wrapper.unmount();
});
it.each([
  [
    '错误媒体',
    () => ({
      ...response(),
      headers: {
        ...response().headers,
        'content-type': 'text/plain; charset=UTF-8',
      },
    }),
    '格式不一致',
  ],
  [
    '无效UTF8',
    () => ({ ...response(), data: new Uint8Array([255]).buffer }),
    'UTF-8',
  ],
  [
    '空字符',
    () => ({ ...response(), data: new Uint8Array([0]).buffer }),
    '有效的纯文本',
  ],
  [
    '超字节预算',
    () => ({ ...response(), data: new ArrayBuffer(800001) }),
    '资源限制',
  ],
  [
    '超行数预算',
    () => ({
      ...response(),
      data: new TextEncoder().encode('\n'.repeat(10001)).buffer,
    }),
    '资源限制',
  ],
  [
    '超文本预算',
    () => ({
      ...response(),
      data: new TextEncoder().encode('甲'.repeat(200001)).buffer,
    }),
    '资源限制',
  ],
])('%s明确报错并保留依据区域', async (_, value, message) => {
  const wrapper = mount(EvidencePreview, {
    props: { evidence: item, client: { request: async () => value() } },
  });
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain(message);
  expect(wrapper.find('pre').exists()).toBe(false);
  wrapper.unmount();
});
it('原副本不可用不出现假原文入口或发出请求', () => {
  const request = vi.fn();
  const wrapper = mount(EvidencePreview, {
    props: { evidence, client: { request } },
  });
  expect(wrapper.find('button').exists()).toBe(false);
  expect(request).not.toHaveBeenCalled();
  wrapper.unmount();
});
