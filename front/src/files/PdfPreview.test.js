import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import PdfPreview from './PdfPreview.vue';
it('初始证据页从实际起始页渲染，越界不显示第一页', async () => {
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({}));
  const wrapper = mount(PdfPreview, {
    props: { bytes: new ArrayBuffer(1), initialPage: 2 },
  });
  await flushPromises();
  expect(wrapper.get('input').element.value).toBe('2');
  expect(wrapper.text()).toContain('第 2 页');
  wrapper.unmount();
  const invalid = mount(PdfPreview, {
    props: { bytes: new ArrayBuffer(1), initialPage: 3 },
  });
  await flushPromises();
  expect(invalid.get('[role=alert]').text()).toContain('1至2');
  expect(invalid.get('canvas').element.width).toBe(0);
  invalid.unmount();
});
it('证据末页越界不显示不完整原文且不承诺历史下载', async () => {
  const wrapper = mount(PdfPreview, {
    props: {
      bytes: new ArrayBuffer(1),
      initialPage: 1,
      endPage: 3,
      downloadAvailable: false,
    },
  });
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain('1至2');
  expect(wrapper.text()).not.toContain('下载原文件');
  expect(wrapper.get('canvas').element.width).toBe(0);
  wrapper.unmount();
});
const state = vi.hoisted(() => ({
  destroy: vi.fn(),
  cancel: vi.fn(),
  pages: 2,
  width: 600,
  height: 800,
  fail: false,
  items: [{ str: '中文正文' }],
  pending: null,
  renderFailure: '',
  pagePending: null,
  failureName: 'Error',
}));
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: {},
  getDocument: vi.fn(() => {
    const document = {
      numPages: state.pages,
      getPage: async () =>
        state.pagePending || {
          getViewport: ({ scale }) => ({
            width: state.width * scale,
            height: state.height * scale,
            scale,
          }),
          getTextContent: async () => ({ items: state.items }),
          render: () => ({
            promise: state.renderFailure
              ? Promise.reject(new Error(state.renderFailure))
              : Promise.resolve(),
            cancel: state.cancel,
          }),
          cleanup: vi.fn(),
        },
    };
    return {
      destroy: state.destroy,
      promise:
        state.pending ||
        (state.fail
          ? Promise.reject(
              Object.assign(new Error('固定引擎错误'), {
                name: state.failureName,
              }),
            )
          : Promise.resolve(document)),
    };
  }),
  TextLayer: class {
    render() {
      return Promise.resolve();
    }
    cancel() {}
  },
}));
afterEach(() => {
  state.fail = false;
  state.failureName = 'Error';
  state.pending = null;
  state.pagePending = null;
  state.renderFailure = '';
  state.width = 600;
  state.height = 800;
  state.items = [{ str: '中文正文' }];
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
it('文字PDF单页显示可翻页定位并在关闭时释放文档', async () => {
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({}));
  const wrapper = mount(PdfPreview, {
    props: { bytes: new Uint8Array([1]).buffer },
  });
  await flushPromises();
  expect(wrapper.text()).toContain('共 2 页');
  expect(wrapper.findAll('canvas')).toHaveLength(1);
  await wrapper.get('[data-action=next-pdf]').trigger('click');
  await flushPromises();
  expect(wrapper.get('input').element.value).toBe('2');
  await wrapper.findAll('button')[0].trigger('click');
  await flushPromises();
  await wrapper.get('input').setValue(2);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  await wrapper.get('input').setValue(3);
  await wrapper.get('form').trigger('submit');
  expect(wrapper.text()).toContain('1至2');
  await wrapper.get('input').setValue(1);
  await wrapper.get('form').trigger('submit');
  await flushPromises();
  expect(wrapper.find('[role=alert]').exists()).toBe(false);
  wrapper.unmount();
  expect(state.destroy).toHaveBeenCalled();
});
it.each(['损坏', 'Image exceeded maximum allowed size and was removed.'])(
  'PDF引擎失败有明确中文原因：%s',
  async (message) => {
    state.renderFailure = message;
    const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
    await flushPromises();
    expect(wrapper.get('[role=alert]').text()).toContain(
      message === '损坏' ? '预览失败，请重试' : '资源限制',
    );
    wrapper.unmount();
  },
);
it.each([
  ['InvalidPDFException', '文件损坏'],
  ['PasswordException', '不支持加密'],
  ['Error', '预览失败，请重试'],
])('PDF加载类型%s分别提示且清理资源', async (name, expected) => {
  state.fail = true;
  state.failureName = name;
  const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain(expected);
  wrapper.unmount();
});
it('观察器初次及高度变化不覆盖用户输入或清除页码错误', async () => {
  let observe;
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback) {
        observe = callback;
      }
      observe() {}
      disconnect() {}
    },
  );
  const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
  await flushPromises();
  await wrapper.get('input').setValue(3);
  observe([{ contentRect: { width: 0, height: 100 } }]);
  await flushPromises();
  expect(wrapper.get('input').element.value).toBe('3');
  await wrapper.get('form').trigger('submit');
  observe([{ contentRect: { width: 0, height: 130 } }]);
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain('1至2');
  wrapper.unmount();
});
it.each(['画布', '文字'])(
  'PDF超过%s预算明确失败且不保留半成品',
  async (kind) => {
    if (kind === '画布') state.height = 100_000_000;
    else state.items = [{ str: '甲'.repeat(200001) }];
    const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
    await flushPromises();
    expect(wrapper.get('[role=alert]').text()).toContain('资源限制');
    expect(wrapper.get('canvas').element.width).toBe(0);
    wrapper.unmount();
  },
);
it('补充平面字符按码点计算而不是UTF16单元', async () => {
  state.items = [{ str: '𠀀'.repeat(100001) }];
  const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
  await flushPromises();
  expect(wrapper.find('[role=alert]').exists()).toBe(false);
  wrapper.unmount();
});
it('加载超时保持资源原因且销毁后拒绝不会覆盖错误', async () => {
  vi.useFakeTimers();
  let reject;
  state.pending = new Promise((resolve, fail) => {
    reject = fail;
  });
  const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
  await vi.advanceTimersByTimeAsync(15000);
  reject(new Error('已销毁'));
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain('处理时间限制');
  wrapper.unmount();
});
it('关闭后迟到页面及加载失败不重新显示或遗留任务', async () => {
  let reject;
  state.pending = new Promise((resolve, fail) => {
    reject = fail;
  });
  const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
  wrapper.unmount();
  reject(new Error('迟到错误'));
  await flushPromises();
  expect(state.destroy).toHaveBeenCalled();
});
it('过多文字对象明确拒绝，避免文字层静默截断', async () => {
  state.items = Array.from({ length: 100001 }, () => ({ str: '甲' }));
  const wrapper = mount(PdfPreview, { props: { bytes: new ArrayBuffer(1) } });
  await flushPromises();
  expect(wrapper.get('[role=alert]').text()).toContain('资源限制');
  wrapper.unmount();
  state.items = [{ str: '中文正文' }];
});
