<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { createSettingsClient } from './client';
import ConnectionSettings from './ConnectionSettings.vue';
import Migration from '../maintenance/Migration.vue';
import RuntimeSettings from './RuntimeSettings.vue';
import './settings.css';
const props = defineProps({
  client: { type: Object, required: true },
  writable: { type: Boolean, default: true },
  dataDirectory: { type: String, default: '' },
});
const emit = defineEmits(['status-change']);
const api = createSettingsClient(props.client);
const savedConfig = ref(null);
const versions = ref([]),
  nextCursor = ref(null),
  selected = ref(null),
  loading = ref(false),
  error = ref('');
let alive = true,
  selection = 0,
  listing = 0;
async function list(cursor) {
  const identity = ++listing;
  loading.value = true;
  error.value = '';
  try {
    const value = await api.versions(cursor);
    if (alive && identity === listing) {
      versions.value = cursor
        ? [...versions.value, ...value.items]
        : value.items;
      nextCursor.value = value.nextCursor;
    }
  } catch (cause) {
    if (alive && identity === listing) error.value = cause.message;
  } finally {
    if (alive && identity === listing) loading.value = false;
  }
}
async function choose(id) {
  const identity = ++selection;
  selected.value = null;
  error.value = '';
  try {
    const value = await api.version(id);
    if (alive && identity === selection) selected.value = value;
  } catch (cause) {
    if (alive && identity === selection) error.value = cause.message;
  }
}
function saved(value) {
  savedConfig.value = value;
  if (selected.value?.versionId === value.versionId) selected.value = null;
  list();
}
onMounted(() => list());
onBeforeUnmount(() => {
  alive = false;
  selection++;
});
</script>
<template>
  <div class="settings-page">
    <header class="settings-heading">
      <h1>设置</h1>
      <p>显式保存连接，分别验证可用性。</p>
    </header>
    <p class="settings-warning">
      模型凭据明文保存在后端数据目录，会随迁移和备份带走；页面只读取掩码。未完整配置时不会使用环境变量兜底。
    </p>
    <p v-if="!writable" role="alert">
      当前写入已暂停，不能保存或发起连接测试。
    </p>
    <div class="settings-grid">
      <ConnectionSettings
        v-for="kind in ['generation', 'embedding', 'vector']"
        :key="kind"
        :api="api"
        :kind="kind"
        :writable="writable"
        :saved-config="savedConfig"
        @saved="saved"
      />
    </div>
    <RuntimeSettings
      :client="client"
      :writable="writable"
      :key="dataDirectory"
    />
    <Migration
      v-for="operation in ['migration', 'backup', 'restore']"
      :key="operation"
      :client="client"
      :operation="operation"
      :writable="writable"
      :data-directory="dataDirectory"
      @status-change="emit('status-change')"
    />
    <section class="embedding-history" aria-label="已保存嵌入版本">
      <header>
        <h2>已保存嵌入版本</h2>
        <button type="button" :disabled="loading" @click="list()">
          刷新版本
        </button>
      </header>
      <p v-if="loading" role="status">正在读取版本…</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="!versions.length && !loading">尚无可展示版本。</p>
      <ul>
        <li v-for="version in versions" :key="version.versionId">
          <button type="button" @click="choose(version.versionId)">
            <strong>{{ version.model ?? '未设置模型' }}</strong
            ><span>{{ version.endpoint ?? '未设置地址' }}</span
            ><small
              >{{ version.versionId }} ·
              {{ version.complete ? '配置完整' : '配置未完整' }}</small
            >
          </button>
        </li>
      </ul>
      <button
        v-if="nextCursor"
        :disabled="loading"
        type="button"
        @click="list(nextCursor)"
      >
        加载更多版本
      </button>
      <ConnectionSettings
        v-if="selected"
        :key="selected.versionId"
        :api="api"
        kind="history"
        :version="selected"
        :writable="writable"
        @saved="saved"
      />
    </section>
  </div>
</template>
