import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import TextPreview from './TextPreview.vue';
it('证据初始源行直接打开规范化源文本并聚焦，越界不伪造首行', async () => {
  const wrapper = mount(TextPreview, {
    props: {
      text: '\ufeff# 标题\r\n正文\r末行\n',
      markdown: true,
      initialLine: 3,
    },
    attachTo: document.body,
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(document.activeElement.textContent).toBe('末行');
  wrapper.unmount();
  const invalid = mount(TextPreview, {
    props: { text: '仅一行', initialLine: 2 },
  });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(invalid.get('[role=alert]').text()).toContain('1至1');
  expect(invalid.find('.located-line').exists()).toBe(false);
  invalid.unmount();
});
it('定位源行显示原始Markdown并以焦点指向真实行，越界明确报错', async () => {
  const wrapper = mount(TextPreview, {
    props: { text: '# 标题\r\n正文\r尾行\n', markdown: true },
    attachTo: document.body,
  });
  expect(wrapper.get('h1').text()).toBe('标题');
  await wrapper.get('input').setValue(3);
  await wrapper.get('form').trigger('submit');
  expect(document.activeElement.textContent).toBe('尾行');
  await wrapper.get('input').setValue(5);
  await wrapper.get('form').trigger('submit');
  expect(wrapper.get('[role=alert]').text()).toContain('1至4');
  await wrapper.get('[aria-expanded]').trigger('click');
  expect(wrapper.find('pre').exists()).toBe(false);
  wrapper.unmount();
});
it('超过Markdown预算明确拒绝而不显示截断内容', async () => {
  const { renderMarkdown } = await import('./text-preview');
  expect(() => renderMarkdown('甲'.repeat(200001))).toThrow('资源限制');
});
it('起始行存在但范围末行越界仍拒绝定位', async () => {
  const wrapper = mount(TextPreview, { props: { text: '首行\n次行', initialLine: 1, endLine: 3 } });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(wrapper.get('[role=alert]').text()).toContain('源行范围越界');
  expect(wrapper.find('.located-line').exists()).toBe(false);
  wrapper.unmount();
});
it('空文件定位首行仍显示空态且不抛出错误', async () => {
  const wrapper = mount(TextPreview, { props: { text: '' } });
  await wrapper.get('form').trigger('submit');
  expect(wrapper.text()).toContain('这份文本为空');
  wrapper.unmount();
});
