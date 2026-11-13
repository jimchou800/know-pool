<script setup>
import { computed, nextTick, onMounted, ref } from 'vue';
import { renderMarkdown, sourceLines } from './text-preview';
const props = defineProps({
  text: { type: String, required: true },
  markdown: { type: Boolean, default: false },
  initialLine: { type: Number, default: null },
});
const lines = computed(() => sourceLines(props.text));
const html = computed(() =>
  props.markdown ? renderMarkdown(lines.value.join('\n')) : '',
);
const sourceOpen = ref(false);
const line = ref(1);
const activeLine = ref(0);
const error = ref('');
const source = ref(null);
onMounted(() => {
  if (props.initialLine !== null) {
    line.value = props.initialLine;
    void locate();
  }
});
async function locate() {
  const value = Number(line.value);
  if (!Number.isInteger(value) || value < 1 || value > lines.value.length) {
    error.value = `请输入1至${lines.value.length}的源行号。`;
    return;
  }
  error.value = '';
  sourceOpen.value = true;
  activeLine.value = value;
  await nextTick();
  const target = source.value?.querySelector(`[data-line="${value}"]`);
  target?.scrollIntoView?.({ block: 'nearest' });
  target?.focus();
}
</script>
<template>
  <div class="text-preview">
    <form novalidate class="preview-navigation" @submit.prevent="locate">
      <label
        >源行号
        <input
          v-model="line"
          aria-label="源行号"
          type="number"
          min="1"
          :max="lines.length"
          :aria-invalid="!!error"
      /></label>
      <button>定位源行</button
      ><span>共 {{ lines.length }} 行（源文件行号）</span>
      <p v-if="error" role="alert">{{ error }}</p>
    </form>
    <!-- 仅插入经过允许列表净化且无链接与图片的 Markdown。 -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="markdown" class="markdown-body" v-html="html"></div>
    <button
      v-if="markdown"
      :aria-expanded="sourceOpen"
      @click="sourceOpen = !sourceOpen"
    >
      {{ sourceOpen ? '隐藏源文本' : '查看源文本' }}
    </button>
    <pre
      v-if="(!markdown || sourceOpen) && text"
      ref="source"
      class="source-text"
    ><template v-for="(item, index) in lines" :key="index"><span :data-line="index + 1" :class="{ 'located-line': activeLine === index + 1 }" tabindex="-1">{{ item }}</span>{{ index < lines.length - 1 ? '\n' : '' }}</template></pre>
    <p v-if="!text">这份文本为空。</p>
  </div>
</template>
