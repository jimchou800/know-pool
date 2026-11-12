import { defineStore } from 'pinia';
import { ref, computed, watch } from 'vue';

export const sampleQuestion = '资料更新后，问答中的引用会跟着变化吗？';
export const evidence = {
  file: '知识管理使用约定.pdf',
  version: '内容版本 3',
  position: '第 12 页，第 2 段',
  text: '问答引用绑定实际参与回答的内容版本，保存引用的完整切片与原文位置。资料更新后，已有回答仍展示当时的原文依据，不使用最新正文替代。',
};
export const answer = {
  lead: '不会。已经生成的回答，会保留当时使用的原文依据。',
  details: [
    '资料上传新版本后，数据集预览和下载展示最新文件。',
    '知识库完成新版本处理前，仍可使用旧生效版本；更新失败时会明确提示。',
    '历史回答中的引用绑定实际内容版本。如果完整旧文件已清理，仍可查看保存的引用片段。',
  ],
};

export const useWorkspace = defineStore('workspace', () => {
  const theme = ref('compact');
  const page = ref('home');
  const samples = [
    {
      id: 'sample-1',
      name: sampleQuestion,
      draft: '',
      messages: [{ question: sampleQuestion, complete: true }],
      stage: '',
      scope: '产品资料库',
    },
    {
      id: 'sample-2',
      name: '历史引用与最新文件的版本关系',
      draft: '',
      messages: [
        {
          question: '历史回答引用的资料，会随新文件上传而改变吗？',
          complete: true,
        },
      ],
      stage: '',
      scope: '产品资料库',
    },
    {
      id: 'sample-3',
      name: '旧文件清理后还能核对历史引用吗？',
      draft: '',
      messages: [
        {
          question: '旧文件副本清理后，历史引用还会跟随新版本变化吗？',
          complete: true,
        },
      ],
      stage: '',
      scope: '产品资料库',
    },
  ];
  const storageKey = 'know-pool-prototype-sessions';
  let initial = samples;
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey));
    if (
      Array.isArray(saved) &&
      saved.length &&
      saved.every(
        (item) =>
          typeof item.id === 'string' &&
          typeof item.name === 'string' &&
          ['产品资料库', '研究资料库'].includes(item.scope) &&
          Array.isArray(item.messages) &&
          item.messages.length &&
          item.messages.every(
            (message) =>
              typeof message.question === 'string' &&
              typeof message.complete === 'boolean',
          ),
      )
    ) {
      initial = saved.map((item) => ({
        id: item.id,
        name: item.name,
        scope: item.scope,
        draft: '',
        stage: '',
        messages: item.messages.map((message) => ({
          question: message.question,
          complete: message.complete,
          interrupted: !message.complete,
        })),
      }));
    }
  } catch {
    /* 本地演示记录损坏或存储受限时，使用固定样例继续评审。 */
  }
  const sessions = ref(initial);
  watch(
    sessions,
    () => {
      try {
        localStorage.setItem(
          storageKey,
          JSON.stringify(
            sessions.value
              .filter((item) => item.messages.length)
              .map((item) => ({
                id: item.id,
                name: item.name,
                scope: item.scope,
                messages: item.messages.map((message) => ({
                  question: message.question,
                  complete: message.complete,
                })),
              })),
          ),
        );
      } catch {
        notice.value = '浏览器存储不可用，演示会话仅在当前页面保留。';
      }
    },
    { deep: true, flush: 'sync' },
  );
  const selectedId = ref(initial[0].id);
  const notice = ref('');
  const drawer = ref(false);
  const active = computed(() =>
    sessions.value.find((item) => item.id === selectedId.value),
  );
  const recent = computed(() =>
    sessions.value.filter((item) => item.messages.length).slice(0, 4),
  );

  function openSession(id) {
    selectedId.value = id;
    page.value = 'chat';
    drawer.value = false;
  }

  function newSession() {
    const item = {
      id: crypto.randomUUID(),
      name: '新问答',
      draft: '',
      messages: [],
      stage: '',
      scope: '产品资料库',
    };
    sessions.value.unshift(item);
    openSession(item.id);
  }

  function go(target) {
    drawer.value = false;
    if (target === 'home' || target === 'chat') page.value = target;
    else
      notice.value = `${target}将在选定视觉方案后扩展。本轮仅演示首页与知识问答。`;
  }

  function submit() {
    const item = active.value;
    const question = item.draft.trim();
    if (!question || item.stage) return;
    if (!item.messages.length)
      item.name = Array.from(question).slice(0, 22).join('');
    item.messages.push({ question, complete: false });
    const message = item.messages[item.messages.length - 1];
    item.draft = '';
    item.stage = '正在检索资料';
    window.setTimeout(() => {
      item.stage = '正在生成回答';
    }, 650);
    window.setTimeout(() => {
      item.stage = '正在校验回答与引用';
    }, 1300);
    window.setTimeout(() => {
      message.complete = true;
      item.stage = '';
    }, 2100);
  }

  return {
    theme,
    page,
    sessions,
    selectedId,
    notice,
    drawer,
    active,
    recent,
    openSession,
    newSession,
    go,
    submit,
  };
});
