<script setup>
import { onBeforeUnmount, ref } from 'vue';
import { createAnswerClient } from './client';
const props = defineProps({ client: { type: Object, required: true } });
const api = createAnswerClient(props.client),
  rows = ref([]),
  cursor = ref(null),
  loading = ref(false),
  error = ref('');
let sequence = 0;
async function load(more = false) {
  if (loading.value) return;
  const current = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const page = await api.list(more ? cursor.value : undefined);
    if (current !== sequence) return;
    rows.value = more
      ? [
          ...rows.value,
          ...page.items.filter(
            (item) => !rows.value.some((old) => old.id === item.id),
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
onBeforeUnmount(() => ++sequence);
load();
</script>
<template>
  <section aria-label="最近问答">
    <h2>最近问答</h2>
    <p v-if="loading" role="status">正在读取最近问答…</p>
    <p v-if="error" role="alert">
      {{ error
      }}<button :disabled="loading" @click="load()">重新读取最近问答</button>
    </p>
    <p v-if="!loading && !error && !rows.length" class="empty-history">
      尚无问答记录。创建问答后，可在这里继续交流。
    </p>
    <ul class="dataset-rows">
      <li v-for="item in rows" :key="item.id">
        <RouterLink :to="{ path: '/answers', query: { conversation: item.id } }"
          >{{ item.name }} · {{ item.updatedAt }} · 继续会话</RouterLink
        >
      </li>
    </ul>
    <button v-if="cursor !== null" :disabled="loading" @click="load(true)">
      更多最近问答
    </button>
  </section>
</template>
