import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import Workbench from './Workbench.vue';
import { createSystemClient } from './session';

let wrapper;
afterEach(() => {
  wrapper?.unmount();
  vi.useRealTimers();
});
const session = {
  sessionId: '00000000-0000-4000-8000-000000000001',
  csrfToken: '固定测试令牌'.repeat(8),
  expiresAt: '2026-11-13T00:00:00Z',
};
const status = {
  applicationVersion: '0.1.0',
  startedAt: '2026-11-12T00:00:00Z',
  writeState: 'ready',
  dataDirectory: '测试数据目录',
  schemaVersion: 1,
  warnings: [],
};

it('持续读取真实维护状态暂停跨页写入口，状态读取失败保守暂停且恢复后更新目录', async () => {
  vi.useFakeTimers();
  let current = status,
    fails = false;
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/datasets');
  const client = createSystemClient(async (config) => {
    if (fails) throw new Error('固定状态断线');
    return {
      data: config.url === '/session' ? session : current,
      status: 200,
      headers: {},
      config,
    };
  });
  wrapper = mount(Workbench, {
    props: { client },
    global: {
      plugins: [router],
      stubs: {
        Datasets: {
          props: ['writable'],
          template: '<button :disabled="!writable">固定写入入口</button>',
        },
      },
    },
  });
  await flushPromises();
  const write = () =>
    wrapper.findAll('button').find((item) => item.text() === '固定写入入口');
  expect(write().attributes('disabled')).toBeUndefined();
  current = { ...status, writeState: 'paused' };
  await vi.advanceTimersByTimeAsync(2000);
  await flushPromises();
  expect(write().attributes('disabled')).toBeDefined();
  expect(wrapper.text()).toContain('数据写入已暂停');
  current = { ...status, dataDirectory: 'D:\\固定迁移后目录' };
  fails = true;
  await vi.advanceTimersByTimeAsync(2000);
  await flushPromises();
  expect(wrapper.text()).toContain('当前无法确认写入状态');
  expect(write().attributes('disabled')).toBeDefined();
  fails = false;
  await vi.advanceTimersByTimeAsync(2000);
  await flushPromises();
  expect(write().attributes('disabled')).toBeUndefined();
  expect(wrapper.text()).not.toContain('当前无法确认写入状态');
});

async function setup(adapter) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  });
  await router.push('/');
  wrapper = mount(Workbench, {
    props: {
      client: createSystemClient((config) =>
        config.url === '/conversations'
          ? {
              data: { items: [], nextCursor: null },
              status: 200,
              headers: {},
              config,
            }
          : adapter(config),
      ),
    },
    global: { plugins: [router] },
  });
  await flushPromises();
}

it('首页显示真实连接结果，不显示演示问答或假历史', async () => {
  await setup(async (config) => ({
    data: config.url === '/session' ? session : status,
    status: 200,
    config,
    headers: {},
  }));
  expect(wrapper.get('h1').text()).toBe('欢迎回到知池');
  expect(wrapper.get('[role="status"]').text()).toContain('本机服务已连接');
  expect(wrapper.text()).toContain('尚无问答记录');
  expect(wrapper.text()).not.toContain('固定样例');
  expect(wrapper.findAll('.shortcut')).toHaveLength(4);
  await wrapper.findAll('.shortcut')[0].trigger('click');
  await flushPromises();
  expect(wrapper.get('h1').text()).toBe('数据集');
  await wrapper.get('.brand').trigger('click');
  await flushPromises();
  expect(wrapper.get('h1').text()).toBe('欢迎回到知池');
});

it.each(['paused', 'migration_failed', 'schema_failed'])(
  '写入状态%s和服务端警告有中文反馈',
  async (writeState) => {
    await setup(async (config) => ({
      data:
        config.url === '/session'
          ? session
          : { ...status, writeState, warnings: ['测试警告'] },
      status: 200,
      config,
      headers: {},
    }));
    expect(wrapper.get('[role="status"]').text()).toContain('测试警告');
    expect(wrapper.get('[role="status"]').text()).not.toContain(writeState);
  },
);

it('连接失败显示可重试错误，用户重试成功后清除旧错误', async () => {
  let fails = true;
  await setup(async (config) => {
    if (fails) throw new Error('不可显示的内部错误');
    return {
      data: config.url === '/session' ? session : status,
      status: 200,
      config,
      headers: {},
    };
  });
  expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法连接');
  expect(wrapper.text()).not.toContain('不可显示的内部错误');
  fails = false;
  await wrapper.get('.reconnect').trigger('click');
  await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  expect(wrapper.get('[role="status"]').text()).toContain('本机服务已连接');
});

it('设置导航消费正式配置并在离开时卸载秘密表单', async () => {
  await setup(async (config) => {
    let data;
    if (config.url === '/session') data = session;
    else if (config.url === '/system/status') data = status;
    else if (config.url.endsWith('/versions'))
      data = { items: [], nextCursor: null };
    else if (config.url.endsWith('vector-store'))
      data = {
        endpoint: null,
        credential: { configured: false, mask: null },
        configured: false,
        updatedAt: status.startedAt,
      };
    else
      data = {
        kind: config.url.endsWith('generation') ? 'generation' : 'embedding',
        versionId: session.sessionId,
        endpoint: null,
        model: null,
        credential: { configured: false, mask: null },
        contextWindowTokens: null,
        maxOutputTokens: null,
        complete: false,
        updatedAt: status.startedAt,
      };
    return { data, status: 200, config, headers: {} };
  });
  await wrapper.get('.settings-link').trigger('click');
  await flushPromises();
  expect(wrapper.get('h1').text()).toBe('设置');
  const form = wrapper.get('[data-kind="generation"]');
  await form.get('[name="action"]').setValue('replace');
  await form.get('[name="secret"]').setValue('固定导航清理测试凭据');
  await wrapper.get('.brand').trigger('click');
  await flushPromises();
  expect(wrapper.find('[name="secret"]').exists()).toBe(false);
  expect(wrapper.get('h1').text()).toBe('欢迎回到知池');
});
