<script setup>
import { ref, nextTick, watch } from 'vue';
import { useWorkspace, evidence, answer, sampleQuestion } from './workspace';
import Icon from './Icon.vue';

const store = useWorkspace();
const themes = [
  { id: 'compact', name: '紧凑高效', note: '让每一项工作触手可及' },
  { id: 'spacious', name: '宽松简洁', note: '给思考留一点空间' },
  { id: 'reading', name: '阅读友好', note: '让答案回到资料本身' },
];
const shortcuts = [
  {
    name: '数据集',
    detail: '导入与管理原始资料',
    icon: 'data',
    target: '数据集',
  },
  {
    name: '知识库',
    detail: '组织可检索的知识',
    icon: 'book',
    target: '知识库',
  },
  {
    name: '新建问答',
    detail: '带着问题，找到依据',
    icon: 'chat',
    target: 'new',
  },
  {
    name: '新建检索',
    detail: '直接查找原文片段',
    icon: 'search',
    target: '知识检索',
  },
];
const composer = ref(null);
const closeButton = ref(null);
const noticeButton = ref(null);
let referenceTrigger;
let noticeTrigger;

watch(
  () => store.notice,
  async (value) => {
    if (value) {
      noticeTrigger = document.activeElement;
      await nextTick();
      noticeButton.value?.focus();
    } else {
      await nextTick();
      noticeTrigger?.focus();
    }
  },
);

function activate(target) {
  if (target === 'new') store.newSession();
  else store.go(target);
}

async function openEvidence(event) {
  referenceTrigger = event.currentTarget;
  store.drawer = true;
  await nextTick();
  closeButton.value.focus();
}

function closeEvidence() {
  store.drawer = false;
  referenceTrigger?.focus();
}

function useExample() {
  store.active.draft = sampleQuestion;
  composer.value.focus();
}
</script>

<template>
  <div class="prototype" :class="store.theme">
    <header class="review-bar" :inert="!!store.notice">
      <div class="review-label">
        <span class="demo-dot"></span> 界面探索
        <span class="review-secondary">三套浅色方案</span>
      </div>
      <div class="theme-switch" role="group" aria-label="选择视觉方案">
        <button
          v-for="(theme, index) in themes"
          :key="theme.id"
          :aria-pressed="store.theme === theme.id"
          @click="store.theme = theme.id"
        >
          <span>{{ index + 1 }}</span
          >{{ theme.name }}
        </button>
      </div>
      <span class="review-disclaimer">模拟数据，仅供原型评审</span>
    </header>

    <div class="application" :inert="!!store.notice">
      <aside class="navigation" aria-label="业务导航">
        <a href="#" class="brand" @click.prevent="store.go('home')"
          ><span class="brand-mark"><i></i><i></i><i></i></span
          ><span>知池<small>个人知识工作台</small></span></a
        >
        <nav>
          <button
            class="nav-item"
            :class="{ selected: store.page === 'home' }"
            @click="store.go('home')"
          >
            <Icon name="home" />首页
          </button>
          <p class="nav-group">数据接入</p>
          <button class="nav-item" @click="store.go('数据集')">
            <Icon name="data" />数据集
          </button>
          <button class="nav-item" @click="store.go('知识库')">
            <Icon name="book" />知识库
          </button>
          <p class="nav-group">知识服务</p>
          <button class="nav-item" @click="store.go('知识检索')">
            <Icon name="search" />知识检索
          </button>
          <button
            class="nav-item"
            :class="{ selected: store.page === 'chat' }"
            @click="store.go('chat')"
          >
            <Icon name="chat" />知识问答
          </button>
        </nav>
        <div class="nav-bottom">
          <button class="nav-item" @click="store.go('设置')">
            <Icon name="settings" />设置
          </button>
          <div class="local-tag"><span></span> 本机工作空间</div>
        </div>
      </aside>

      <main v-if="store.page === 'home'" class="home">
        <div class="page-crumb">工作空间 <span>/</span> 首页</div>
        <div class="welcome-content">
          <section class="welcome-heading">
            <div class="welcome-symbol" aria-hidden="true">
              <div></div>
              <div></div>
              <div></div>
            </div>
            <p class="welcome-kicker">你的资料，你的知识</p>
            <h1>欢迎回到知池</h1>
            <p class="welcome-description">
              从一份资料出发，让每一个问题都有据可循。
            </p>
          </section>
          <div class="home-sections">
            <section class="start-section">
              <div class="section-heading">
                <h2>开始工作</h2>
                <span>从这里出发</span>
              </div>
              <div class="shortcuts">
                <button
                  v-for="shortcut in shortcuts"
                  :key="shortcut.name"
                  class="shortcut"
                  @click="activate(shortcut.target)"
                >
                  <span class="shortcut-icon"
                    ><Icon :name="shortcut.icon" /></span
                  ><span
                    ><strong>{{ shortcut.name }}</strong
                    ><small>{{ shortcut.detail }}</small></span
                  ><Icon name="arrow" />
                </button>
              </div>
            </section>
            <section class="recent-section">
              <div class="section-heading">
                <h2>最近问答</h2>
                <button class="text-button" @click="store.go('chat')">
                  查看全部
                </button>
              </div>
              <div class="recent-list">
                <button
                  v-for="session in store.recent"
                  :key="session.id"
                  class="recent-item"
                  @click="store.openSession(session.id)"
                >
                  <Icon name="chat" /><span
                    ><strong>{{ session.name }}</strong
                    ><small>{{ session.scope }}</small></span
                  ><span class="recent-time">{{
                    session.stage ? '处理中' : '最近使用'
                  }}</span>
                </button>
              </div>
            </section>
          </div>
          <footer class="home-footer">
            <span class="footer-rule"></span
            ><span>资料有来处，回答有依据。</span
            ><span class="footer-rule"></span>
          </footer>
        </div>
        <div class="scheme-note">
          <span>{{ themes.find((item) => item.id === store.theme).name }}</span
          >{{ themes.find((item) => item.id === store.theme).note }}
        </div>
      </main>

      <div v-else class="chat-layout">
        <aside class="sessions" aria-label="问答会话列表">
          <div class="session-heading">
            <h2>知识问答</h2>
            <span>{{ store.sessions.length }}</span>
          </div>
          <button class="new-chat" @click="store.newSession()">
            <Icon name="plus" />新建问答
          </button>
          <div class="list-label">会话</div>
          <div class="session-items">
            <button
              v-for="session in store.sessions"
              :key="session.id"
              class="session-item"
              :class="{ active: store.selectedId === session.id }"
              @click="store.openSession(session.id)"
            >
              <strong>{{ session.name }}</strong
              ><small
                ><span :class="{ working: session.stage }"></span
                >{{
                  session.stage ||
                  (session.messages.length ? '已完成' : '尚未提问')
                }}</small
              >
            </button>
          </div>
          <p class="session-footnote">切换会话，正在进行的任务仍会继续。</p>
        </aside>
        <main class="conversation">
          <header class="conversation-header">
            <div>
              <h1>{{ store.active.name }}</h1>
              <p>基于所选知识库回答，并提供原文依据</p>
            </div>
            <span class="sample-badge">样例会话</span>
          </header>
          <div class="scope-bar">
            <Icon name="book" /><label for="scope">知识库范围</label
            ><select
              id="scope"
              v-model="store.active.scope"
              :disabled="!!store.active.stage"
            >
              <option>产品资料库</option>
              <option>研究资料库</option></select
            ><span class="published"><span></span> 已发布</span>
          </div>
          <div class="conversation-body" aria-live="polite">
            <div
              v-if="!store.active.messages.length"
              class="empty-conversation"
            >
              <span class="empty-icon"><Icon name="chat" /></span>
              <h2>从一个问题开始</h2>
              <p>选择知识库，提出问题。回答中的引用会带你回到原文。</p>
              <button class="example-question" @click="useExample">
                {{ sampleQuestion }}<Icon name="arrow" />
              </button>
            </div>
            <article
              v-for="(message, index) in store.active.messages"
              :key="index"
              class="exchange"
            >
              <div class="question">
                <span class="speaker">你</span>
                <p>{{ message.question }}</p>
              </div>
              <div v-if="message.complete" class="answer">
                <div class="answer-author">
                  <span class="answer-mark">知</span><strong>知池</strong
                  ><span>固定样例回答</span>
                </div>
                <p class="answer-lead">{{ answer.lead }}</p>
                <ol>
                  <li v-for="detail in answer.details" :key="detail">
                    {{ detail }}
                  </li>
                </ol>
                <p>
                  因此，查看当前资料与核对历史回答时，看到的版本可能不同。这是为了让每一次引用都可以追溯。
                </p>
                <button class="citation" @click="openEvidence">
                  <Icon name="file" /><span
                    ><strong>1</strong> {{ evidence.file }}</span
                  ><small>第 12 页</small>
                </button>
                <div class="answer-foot">
                  <Icon name="check" />模拟校验完成<span
                    >回答与原文引用一致</span
                  >
                </div>
              </div>
              <p v-else-if="message.interrupted" class="progress">
                演示已中断，请重新提问；不会自动恢复模拟任务。
              </p>
              <div v-else class="progress" role="status">
                <span class="progress-ring"></span>
                <div>
                  <strong>{{ store.active.stage }}</strong>
                  <p>校验完成后展示回答正文</p>
                </div>
              </div>
            </article>
          </div>
          <form class="composer" @submit.prevent="store.submit()">
            <label class="sr-only" for="question">输入问题</label
            ><textarea
              id="question"
              ref="composer"
              v-model="store.active.draft"
              rows="2"
              placeholder="向你的知识库提问…"
              :disabled="!!store.active.stage"
              @keydown.ctrl.enter.prevent="store.submit()"
            ></textarea>
            <div class="composer-bottom">
              <span>仅基于所选知识库回答</span
              ><button
                class="send-button"
                type="submit"
                :disabled="!store.active.draft.trim() || !!store.active.stage"
              >
                {{ store.active.stage ? '处理中' : '发送问题'
                }}<Icon name="arrow" />
              </button>
            </div>
          </form>
          <p class="composer-note">
            演示回答为固定样例，不代表真实模型对当前问题的回答。按 Ctrl＋Enter
            发送。
          </p>
        </main>
        <aside
          v-if="store.drawer"
          class="evidence-drawer"
          aria-label="原文依据"
          @keydown.esc="closeEvidence"
        >
          <header>
            <div>
              <span class="drawer-kicker">引用 1</span>
              <h2>原文依据</h2>
            </div>
            <button
              ref="closeButton"
              class="icon-button"
              aria-label="关闭原文依据"
              @click="closeEvidence"
            >
              <Icon name="close" />
            </button>
          </header>
          <div class="evidence-document">
            <span class="document-icon"><Icon name="file" /></span>
            <h3>{{ evidence.file }}</h3>
            <p>产品资料库</p>
          </div>
          <dl>
            <div>
              <dt>引用版本</dt>
              <dd>{{ evidence.version }}</dd>
            </div>
            <div>
              <dt>原文位置</dt>
              <dd>{{ evidence.position }}</dd>
            </div>
          </dl>
          <div class="source-label">引用时的原文片段</div>
          <blockquote>{{ evidence.text }}</blockquote>
          <p class="evidence-note">
            此处展示回答实际引用的版本快照。资料后续更新，不会改写这份依据。
          </p>
          <span class="snapshot-label"
            ><Icon name="check" />已保留引用快照</span
          >
        </aside>
      </div>
    </div>
    <div
      v-if="store.notice"
      class="notice-backdrop"
      @click.self="store.notice = ''"
    >
      <section
        class="notice"
        role="dialog"
        aria-modal="true"
        aria-labelledby="notice-title"
        @keydown.esc="store.notice = ''"
        @keydown.tab.prevent="noticeButton.focus()"
      >
        <Icon name="book" />
        <h2 id="notice-title">本轮原型范围</h2>
        <p>{{ store.notice }}</p>
        <button
          ref="noticeButton"
          class="send-button"
          @click="store.notice = ''"
        >
          知道了
        </button>
      </section>
    </div>
  </div>
</template>
