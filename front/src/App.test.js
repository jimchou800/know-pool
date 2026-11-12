import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import App from './App.vue';
import Icon from './Icon.vue';
import { useWorkspace, sampleQuestion } from './workspace';

let wrapper;
beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
});
afterEach(() => {
  wrapper?.unmount();
  vi.useRealTimers();
});
function setup() {
  const pinia = createPinia();
  wrapper = mount(App, {
    attachTo: document.body,
    global: { plugins: [pinia] },
  });
  return useWorkspace(pinia);
}

it('切换三套主题，欢迎页入口与会话可以打开，范围提示可关闭', async () => {
  const store = setup();
  const buttons = wrapper.findAll('.theme-switch button');
  for (const [index, button] of buttons.entries()) {
    await button.trigger('click');
    expect(button.attributes('aria-pressed')).toBe('true');
    expect(store.theme).toBe(['compact', 'spacious', 'reading'][index]);
  }
  await wrapper.findAll('.shortcut')[0].trigger('click');
  expect(wrapper.get('[role="dialog"]').text()).toContain('本轮原型范围');
  await wrapper.get('.notice .send-button').trigger('click');
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
  await wrapper.findAll('.shortcut')[1].trigger('click');
  await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' });
  await wrapper.findAll('.shortcut')[3].trigger('click');
  await wrapper.get('.notice-backdrop').trigger('click');
  await wrapper.findAll('.recent-item')[0].trigger('click');
  expect(wrapper.find('.conversation').exists()).toBe(true);
  await wrapper.findAll('.nav-item')[0].trigger('click');
  await wrapper.get('.text-button').trigger('click');
  await wrapper.get('.brand').trigger('click');
  await wrapper.findAll('.shortcut')[2].trigger('click');
  expect(store.active.messages).toHaveLength(0);
});

it('新问答示例填充、提交、进度和完成回答均可操作，输入文本不会解析成网页', async () => {
  const store = setup();
  await wrapper.findAll('.nav-item')[4].trigger('click');
  await wrapper.get('.new-chat').trigger('click');
  await wrapper.get('.example-question').trigger('click');
  expect(wrapper.get('textarea').element.value).toBe(sampleQuestion);
  await wrapper.get('textarea').setValue('<img src=x onerror=alert(1)>');
  await wrapper.get('form').trigger('submit');
  expect(wrapper.find('.answer').exists()).toBe(false);
  expect(wrapper.get('[role="status"]').text()).toContain('正在检索资料');
  expect(wrapper.find('.question img').exists()).toBe(false);
  expect(wrapper.get('.question').text()).toContain('<img');
  await vi.advanceTimersByTimeAsync(2100);
  expect(wrapper.get('.answer').text()).toContain('固定样例回答');
  expect(store.active.messages[0].complete).toBe(true);
  await wrapper.findAll('.session-item')[1].trigger('click');
  expect(wrapper.get('h1').text()).toBe(sampleQuestion);
});

it('原文抽屉显示实际版本位置，关闭后焦点返回引用按钮', async () => {
  setup();
  await wrapper.findAll('.nav-item')[4].trigger('click');
  const citation = wrapper.get('.citation');
  await citation.trigger('click');
  await flushPromises();
  expect(wrapper.get('.evidence-drawer').text()).toContain('内容版本 3');
  expect(wrapper.get('.evidence-drawer').text()).toContain('第 12 页，第 2 段');
  expect(document.activeElement.getAttribute('aria-label')).toBe(
    '关闭原文依据',
  );
  await wrapper.get('.icon-button').trigger('click');
  expect(document.activeElement).toBe(citation.element);
  await citation.trigger('click');
  await wrapper.get('.evidence-drawer').trigger('keydown', { key: 'Escape' });
  expect(wrapper.find('.evidence-drawer').exists()).toBe(false);
});

it('刷新恢复的未完成演示提示中断，不显示未校验回答', async () => {
  const store = setup();
  store.newSession();
  store.active.messages.push({
    question: sampleQuestion,
    complete: false,
    interrupted: true,
  });
  await wrapper.vm.$nextTick();
  expect(wrapper.text()).toContain('演示已中断');
  expect(wrapper.find('.answer').exists()).toBe(false);
});

it('图标缺省名称和未知名称都有安全的回退图形', () => {
  const icon = mount(Icon);
  expect(icon.get('path').attributes('d')).toContain('M5 4');
  icon.setProps({ name: '未知' });
  expect(icon.get('svg').attributes('aria-hidden')).toBe('true');
});
