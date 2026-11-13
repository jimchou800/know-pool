<script setup>
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import Icon from '../Icon.vue';
import { createSystemClient } from './session';
import Datasets from '../datasets/Datasets.vue';
import Settings from '../settings/Settings.vue';
import KnowledgeBases from '../knowledge-bases/KnowledgeBases.vue';
import Search from '../search/SearchInstances.vue';
import Answers from '../answers/Answers.vue';
import RecentAnswers from '../answers/RecentAnswers.vue';

const props = defineProps({
  client: { type: Object, default: () => createSystemClient() },
});
const route = useRoute();
const loading = ref(true);
const error = ref('');
const status = ref(null);
const entries = [
  {
    path: '/datasets',
    title: '数据集',
    detail: '导入与管理原始资料',
    icon: 'data',
  },
  {
    path: '/knowledge-bases',
    title: '知识库',
    detail: '组织可检索的知识',
    icon: 'book',
  },
  {
    path: '/answers',
    title: '新建问答',
    detail: '带着问题，找到依据',
    icon: 'chat',
  },
  {
    path: '/search',
    title: '新建检索',
    detail: '直接查找原文片段',
    icon: 'search',
  },
];
const writeStates = {
  ready: '可正常写入',
  paused: '数据写入已暂停',
  migration_failed: '数据迁移失败，写入已暂停',
  schema_failed: '数据库升级失败，写入已暂停',
};
const pageNames = {
  '/datasets': '数据集',
  '/knowledge-bases': '知识库',
  '/answers': '知识问答',
  '/search': '知识检索',
  '/settings': '设置',
};

async function connect() {
  loading.value = true;
  error.value = '';
  status.value = null;
  try {
    status.value = await props.client.connect();
  } catch (failure) {
    error.value = failure.message;
  } finally {
    loading.value = false;
  }
}
onMounted(connect);
</script>

<template>
  <div class="prototype compact formal">
    <div class="application">
      <aside class="navigation" aria-label="业务导航">
        <RouterLink to="/" class="brand"
          ><span class="brand-mark"><i></i><i></i><i></i></span
          ><span>知池<small>个人知识工作台</small></span></RouterLink
        >
        <nav>
          <RouterLink
            to="/"
            class="nav-item"
            :class="{ selected: route.path === '/' }"
            ><Icon name="home" />首页</RouterLink
          >
          <p class="nav-group">数据接入</p>
          <RouterLink
            v-for="entry in entries.slice(0, 2)"
            :key="entry.path"
            :to="entry.path"
            class="nav-item"
            :class="{ selected: route.path.startsWith(entry.path) }"
            ><Icon :name="entry.icon" />{{ entry.title }}</RouterLink
          >
          <p class="nav-group">知识服务</p>
          <RouterLink
            to="/search"
            class="nav-item"
            :class="{ selected: route.path === '/search' }"
            ><Icon name="search" />知识检索</RouterLink
          >
          <RouterLink
            to="/answers"
            class="nav-item"
            :class="{ selected: route.path === '/answers' }"
            ><Icon name="chat" />知识问答</RouterLink
          >
        </nav>
        <RouterLink
          to="/settings"
          class="nav-item settings-link"
          :class="{ selected: route.path === '/settings' }"
          ><Icon name="settings" />设置</RouterLink
        >
      </aside>
      <main class="formal-main">
        <div class="connection-bar">
          <p v-if="loading" role="status">正在连接本机服务…</p>
          <p v-else-if="error" role="alert">{{ error }}</p>
          <p v-else role="status">
            本机服务已连接 · {{ writeStates[status.writeState]
            }}<span
              v-for="(warning, index) in status.warnings"
              :key="index"
              class="system-warning"
              >{{ warning }}</span
            >
          </p>
          <button
            class="text-button reconnect"
            :disabled="loading"
            @click="connect"
          >
            重新连接
          </button>
        </div>
        <section v-if="route.path === '/'" class="welcome-content">
          <header class="welcome-heading">
            <p class="welcome-kicker">个人知识工作台</p>
            <h1>欢迎回到知池</h1>
            <p class="welcome-description">
              整理资料，让每一个问题都有据可循。
            </p>
          </header>
          <div class="home-sections">
            <section>
              <h2>开始工作</h2>
              <div class="shortcuts">
                <RouterLink
                  v-for="entry in entries"
                  :key="entry.path"
                  :to="entry.path"
                  class="shortcut"
                  ><span class="shortcut-icon"><Icon :name="entry.icon" /></span
                  ><span
                    ><strong>{{ entry.title }}</strong
                    ><small>{{ entry.detail }}</small></span
                  ><Icon name="arrow"
                /></RouterLink>
              </div>
            </section>
            <RecentAnswers v-if="status && !loading" :client="client" />
          </div>
        </section>
        <template
          v-else-if="
            route.path === '/datasets' || route.path.startsWith('/datasets/')
          "
          ><Datasets
            v-if="status && !loading"
            :client="client"
            :writable="status.writeState === 'ready'"
        /></template>
        <Settings
          v-else-if="route.path === '/settings' && status && !loading"
          :client="client"
          :writable="status.writeState === 'ready'"
        />
        <KnowledgeBases
          v-else-if="
            (route.path === '/knowledge-bases' ||
              route.path.startsWith('/knowledge-bases/')) &&
            status &&
            !loading
          "
          :client="client"
          :writable="status.writeState === 'ready'"
        />
        <Search
          v-else-if="route.path === '/search' && status && !loading"
          :client="client"
          :writable="status.writeState === 'ready'"
        />
        <Answers
          v-else-if="route.path === '/answers' && status && !loading"
          :client="client"
          :initial-id="
            typeof route.query.conversation === 'string'
              ? route.query.conversation
              : null
          "
          :writable="status.writeState === 'ready'"
        />
        <section v-else class="welcome-content">
          <h1>{{ pageNames[route.path] || '页面不存在' }}</h1>
          <p>
            {{ pageNames[route.path] || '当前页面' }}功能尚未接通，请返回首页。
          </p>
          <RouterLink to="/" class="text-button">返回首页</RouterLink>
        </section>
      </main>
    </div>
  </div>
</template>
