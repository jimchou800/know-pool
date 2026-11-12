import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useWorkspace, sampleQuestion } from './workspace';

beforeEach(() => {
  localStorage.clear();
  setActivePinia(createPinia());
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('问答演示状态', () => {
  it('仅持久保存首次提交后的会话，空白窗口及草稿不保存', () => {
    const store = useWorkspace();
    store.newSession();
    const id = store.active.id;
    store.active.draft = '  ';
    store.submit();
    expect(store.active.messages).toHaveLength(0);
    expect(localStorage.getItem('know-pool-prototype-sessions')).not.toContain(
      id,
    );
    store.active.draft = sampleQuestion;
    store.submit();
    expect(store.active.name).toBe(
      Array.from(sampleQuestion).slice(0, 22).join(''),
    );
    const saved = JSON.parse(
      localStorage.getItem('know-pool-prototype-sessions'),
    );
    expect(saved[0].id).toBe(id);
    expect(saved[0]).not.toHaveProperty('draft');
    expect(saved[0].messages[0].complete).toBe(false);
  });

  it('校验前不产生已完成回答，重复提交不能创建第二个任务', () => {
    const store = useWorkspace();
    store.newSession();
    store.active.draft = sampleQuestion;
    store.submit();
    expect(store.active.stage).toBe('正在检索资料');
    store.active.draft = '重复提问';
    store.submit();
    expect(store.active.messages).toHaveLength(1);
    vi.advanceTimersByTime(650);
    expect(store.active.stage).toBe('正在生成回答');
    vi.advanceTimersByTime(650);
    expect(store.active.stage).toBe('正在校验回答与引用');
    expect(store.active.messages[0].complete).toBe(false);
    vi.advanceTimersByTime(800);
    expect(store.active.messages[0].complete).toBe(true);
    expect(store.active.stage).toBe('');
    store.active.draft = '后续问题';
    store.submit();
    expect(store.active.name).not.toBe('后续问题');
  });

  it('切换实例后原任务继续，草稿和知识库范围彼此独立', () => {
    const store = useWorkspace();
    store.newSession();
    const originalId = store.active.id;
    store.active.scope = '研究资料库';
    store.active.draft = sampleQuestion;
    store.submit();
    store.openSession('sample-1');
    store.active.draft = '未提交的另一条草稿';
    expect(store.active.scope).toBe('产品资料库');
    vi.advanceTimersByTime(2100);
    expect(store.active.messages).toHaveLength(1);
    store.openSession(originalId);
    expect(store.active.scope).toBe('研究资料库');
    expect(store.active.messages[0].complete).toBe(true);
    store.openSession('sample-1');
    expect(store.active.draft).toBe('未提交的另一条草稿');
  });

  it('恢复已提交的本地记录，在途演示标记中断且不启动新计时', () => {
    const first = useWorkspace();
    first.newSession();
    first.active.draft = sampleQuestion;
    first.submit();
    const id = first.active.id;
    vi.clearAllTimers();
    setActivePinia(createPinia());
    const restored = useWorkspace();
    expect(restored.active.id).toBe(id);
    expect(restored.active.messages[0].interrupted).toBe(true);
    expect(restored.active.stage).toBe('');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('损坏或非法本地数据回退到样例，存储失败仍可继续演示', () => {
    localStorage.setItem('know-pool-prototype-sessions', '{');
    expect(useWorkspace().sessions).toHaveLength(3);
    setActivePinia(createPinia());
    localStorage.setItem(
      'know-pool-prototype-sessions',
      JSON.stringify([{ id: 'x', name: 'x', scope: '非法范围', messages: [] }]),
    );
    expect(useWorkspace().sessions).toHaveLength(3);
    setActivePinia(createPinia());
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('存储不可用');
    });
    const store = useWorkspace();
    store.newSession();
    expect(store.notice).toContain('当前页面保留');
  });

  it('范围外入口显示明确提示而不假装完成业务页面', () => {
    const store = useWorkspace();
    store.drawer = true;
    store.go('数据集');
    expect(store.notice).toContain('选定视觉方案后扩展');
    expect(store.drawer).toBe(false);
    store.go('chat');
    expect(store.page).toBe('chat');
    store.go('home');
    expect(store.page).toBe('home');
    expect(store.recent).toHaveLength(3);
  });
});
