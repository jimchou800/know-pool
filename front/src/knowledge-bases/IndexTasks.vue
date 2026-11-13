<script setup>
import { ref, watch, onBeforeUnmount } from 'vue';
import { createKnowledgeBaseClient } from './client';
import { watchTask } from './task-events';
import ChunkPreflight from './ChunkPreflight.vue';
import CancelTask from './CancelTask.vue';

const props = defineProps({
  client: { type: Object, required: true },
  knowledgeBaseId: { type: String, required: true },
  writable: { type: Boolean, default: true },
});
const api = createKnowledgeBaseClient(props.client);
const emit = defineEmits(['completed']);
function cancelled(value) {
  rows.value = rows.value.map((item) => (item.id === value.id ? value : item));
  if (following.value === value.id) stop();
  emit('completed');
}
const rows = ref([]),
  cursor = ref(null),
  loading = ref(false),
  error = ref('');
const following = ref(null),
  followError = ref('');
let sequence = 0,
  followSequence = 0,
  controller,
  recoveryTimer,
  lastEventId;
const phases = {
  queued: '排队',
  initializing: '初始化',
  parsing: '解析',
  chunking: '切片',
  embedding: '嵌入',
  vector_writing: '写入向量',
  validating: '验证',
  switching: '切换生效版本',
  cleaning: '清理已下线索引',
  completed: '已结束',
};
const kinds = {
  index_file: '资料索引',
  rebuild_knowledge_base: '整库重建',
  cleanup: '资源清理',
  model_test: '可用性复查',
};
const states = {
  queued: '等待处理',
  running: '处理中',
  succeeded: '处理成功',
  failed: '处理失败',
  cancelled: '已取消',
  interrupted: '已中断',
};
async function load(more = false) {
  if (loading.value) return;
  const current = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const page = await api.tasks(
      props.knowledgeBaseId,
      more ? cursor.value : undefined,
    );
    if (current !== sequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...page.items.filter(
            (item) => !rows.value.some((row) => row.id === item.id),
          ),
        ]
      : page.items;
    cursor.value = page.nextCursor;
  } catch (failure) {
    if (current === sequence) error.value = failure.message;
  } finally {
    if (current === sequence) loading.value = false;
  }
}
function stop() {
  ++followSequence;
  controller?.abort();
  clearTimeout(recoveryTimer);
  following.value = null;
  lastEventId = undefined;
}
async function refreshTask(id, current) {
  const kind = rows.value.find((row) => row.id === id)?.kind;
  const task = await api.task(id, kind);
  if (current !== followSequence || following.value !== id) return null;
  rows.value = rows.value.map((row) => (row.id === id ? task : row));
  if (!['queued', 'running'].includes(task.state)) {
    stop();
    emit('completed');
  }
  return task;
}
async function follow(id, recovering = false) {
  if (!recovering) {
    stop();
    following.value = id;
  }
  const current = followSequence;
  followError.value = '';
  controller = new AbortController();
  try {
    if (recovering) {
      const task = await refreshTask(id, current);
      if (!task || !following.value) return;
    }
    await watchTask(id, {
      signal: controller.signal,
      lastEventId,
      onEvent: async (_, eventId) => {
        if (current !== followSequence) return;
        lastEventId = eventId;
        await refreshTask(id, current);
      },
    });
  } catch (failure) {
    if (
      current !== followSequence ||
      following.value !== id ||
      failure.name === 'AbortError'
    )
      return;
    try {
      const task = await refreshTask(id, current);
      if (task && following.value === id) {
        followError.value = '通知中断，已查询原任务，稍后恢复通知。';
        recoveryTimer = setTimeout(() => follow(id, true), 1500);
      }
    } catch (queryFailure) {
      if (current === followSequence) followError.value = queryFailure.message;
    }
  }
}
watch(
  () => props.knowledgeBaseId,
  () => {
    ++sequence;
    stop();
    rows.value = [];
    cursor.value = null;
    loading.value = false;
    load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++sequence;
  stop();
});
</script>

<template>
  <section class="knowledge-section" aria-label="处理任务">
    <div class="dataset-detail-heading">
      <h3>处理任务</h3>
      <button :disabled="loading" @click="load()">刷新处理任务</button>
    </div>
    <p>
      上传成功仅表示文件已保存；处理成功后仍以当前生效版本为准，不自动发布知识库。
    </p>
    <p v-if="loading" role="status">正在读取处理任务…</p>
    <p v-if="error" role="alert">
      {{ error
      }}<button :disabled="loading" @click="load()">重新读取任务</button>
    </p>
    <p v-if="followError" role="alert">
      {{ followError
      }}<button @click="follow(following, true)">继续查询原任务</button>
    </p>
    <p v-if="!rows.length && !loading && !error">暂无处理任务。</p>
    <ul class="knowledge-files">
      <li v-for="item in rows" :key="item.id">
        <p>类型：{{ kinds[item.kind] }}</p>
        <strong>{{ states[item.state] }} · {{ phases[item.phase] }}</strong>
        <p v-if="item.kind === 'model_test'">
          此任务检查知识库依赖，不处理资料。任务成功仅表示探测完成，运行可用性以知识库当前状态和复查结果为准；不会自动重新发布知识库。
        </p>
        <p v-if="item.kind === 'cleanup'">
          下线后的资源清理任务；清理失败不会恢复资料纳入，清理重试入口尚未提供。
        </p>
        <p>任务：{{ item.id }}；文件：{{ item.fileId || '未绑定文件' }}</p>
        <p>
          目标内容版本：{{
            item.targetContentVersionId || '无'
          }}；目标配置版本：{{ item.targetConfigVersionId || '无' }}
        </p>
        <p>
          已完成 {{ item.progressCompleted }} /
          {{
            item.progressTotal === null ? '总量未知' : item.progressTotal
          }}；尝试 {{ item.attempt }} 次
        </p>
        <p v-if="item.failure" role="status">{{ item.failure.message }}</p>
        <p v-for="warning in item.warnings" :key="warning">{{ warning }}</p>
        <ChunkPreflight
          v-if="item.kind === 'index_file'"
          :client="client"
          :task="item"
        />
        <CancelTask
          v-if="['index_file', 'rebuild_knowledge_base'].includes(item.kind)"
          :client="client"
          :task="item"
          :writable="writable"
          @updated="cancelled"
        />
        <button
          v-if="['queued', 'running'].includes(item.state)"
          :disabled="following === item.id"
          @click="follow(item.id)"
        >
          {{ following === item.id ? '正在跟踪' : '跟踪处理任务' }}
        </button>
      </li>
    </ul>
    <button v-if="cursor !== null" :disabled="loading" @click="load(true)">
      更多处理任务
    </button>
  </section>
</template>
