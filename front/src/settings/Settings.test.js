import { afterEach, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import Settings from './Settings.vue';
import { createSystemClient } from '../system/session';
const time = '2026-01-01T00:00:00Z';
const id = '00000000-0000-4000-8000-000000000061';
const model = (kind) => ({
  kind,
  versionId: id,
  endpoint: null,
  model: null,
  credential: { configured: false, mask: null },
  contextWindowTokens: null,
  maxOutputTokens: null,
  complete: false,
  updatedAt: time,
});
let wrapper;
afterEach(() => wrapper?.unmount());
it('设置展示三独立配置初值，保存后清理秘密且不自动发起收费测试', async () => {
  const requests = [];
  const client = createSystemClient(async (config) => {
    requests.push(config);
    let data;
    if (config.url === '/session')
      data = {
        sessionId: id,
        csrfToken: '固定保护'.repeat(12),
        expiresAt: time,
      };
    else if (config.url === '/system/status')
      data = {
        applicationVersion: '0.1',
        startedAt: time,
        writeState: 'ready',
        dataDirectory: '测试',
        schemaVersion: 1,
        warnings: [],
      };
    else if (config.url.endsWith('/versions'))
      data = { items: [model('embedding')], nextCursor: null };
    else if (config.url.includes('vector-store'))
      data = {
        endpoint: null,
        credential: { configured: false, mask: null },
        configured: false,
        updatedAt: time,
      };
    else
      data = model(
        config.url.endsWith('generation') ? 'generation' : 'embedding',
      );
    return { data, status: 200, headers: {}, config };
  });
  await client.connect();
  wrapper = mount(Settings, {
    props: { client, writable: true },
    attachTo: document.body,
  });
  await flushPromises();
  expect(wrapper.text()).toContain('生成模型');
  expect(wrapper.text()).toContain('嵌入模型');
  expect(wrapper.text()).toContain('向量连接');
  const form = wrapper.get('[data-kind="generation"]');
  await form.get('[name="endpoint"]').setValue('http://127.0.0.1:4567/v1');
  await form.get('[name="model"]').setValue('测试模型');
  await form.get('[name="contextWindowTokens"]').setValue('1024');
  await form.get('[name="maxOutputTokens"]').setValue('16');
  await form.get('[name="action"]').setValue('replace');
  await form.get('[name="secret"]').setValue('固定测试凭据');
  await form.get('form').trigger('submit');
  await flushPromises();
  expect(form.find('[name="secret"]').exists()).toBe(false);
  expect(form.text()).toContain('已保存');
  expect(requests.some((config) => config.url.endsWith('/test'))).toBe(false);
  expect(localStorage.length + sessionStorage.length).toBe(0);
});
