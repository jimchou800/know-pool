<script setup>
defineProps({ location: { type: Object, required: true } });
</script>
<template>
  <div class="evidence-location">
    <p v-if="location.format === 'pdf'">第{{ location.pageStart }}至{{ location.pageEnd }}页</p>
    <p v-else>第{{ location.lineStart }}至{{ location.lineEnd }}行</p>
    <p v-if="location.titlePath.length">标题：{{ location.titlePath.join(' / ') }}</p>
    <p v-if="location.paragraphId !== null">段落：{{ location.paragraphId }}</p>
    <p v-if="location.structureId !== null">结构：{{ location.structureId }}</p>
    <p v-if="location.continuationIndex !== null">{{ location.structureId !== null ? '结构' : '文件' }}第{{ location.continuationIndex + 1 }}片</p>
    <p v-if="location.tableRowStart !== null && location.tableRowEnd !== null">表格第{{ location.tableRowStart + 1 }}至{{ location.tableRowEnd + 1 }}行（表头为第1行）</p>
    <p v-if="location.tableColumnStart !== null && location.tableColumnEnd !== null">表格第{{ location.tableColumnStart + 1 }}至{{ location.tableColumnEnd + 1 }}列</p>
    <p v-if="location.codeBlockId !== null">代码块：{{ location.codeBlockId }}<span v-if="location.codeLanguage !== null">；语言：{{ location.codeLanguage }}</span></p>
  </div>
</template>
