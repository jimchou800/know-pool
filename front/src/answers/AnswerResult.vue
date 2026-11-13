<script setup>
import EvidenceLocation from '../knowledge-bases/EvidenceLocation.vue';
defineProps({ value: { type: Object, required: true }, evidenceBusy: Boolean });
defineEmits(['evidence']);
const outcomes = {
  answered: '已依据资料回答',
  partial: '部分问题有依据',
  no_evidence: '资料中没有足够依据',
  needs_clarification: '需要补充说明',
};
const failures = {
  ANSWER_VALIDATION_FAILED: '回答未通过引用或事实校验，未展示草稿。',
  ANSWER_VALIDATION_TIMEOUT: '回答校验超时，未展示草稿。',
  MODEL_CONFIG_INVALID: '模型配置或调用预算不满足要求，请修正设置后手动重试。',
  MODEL_UNAVAILABLE: '模型服务不可用，本次任务已结束。',
  GENERATION_FAILED: '回答生成失败，本次任务已结束。',
  SCOPE_UNAVAILABLE: '选定知识库全部不可用，请调整范围或修复依赖。',
};
</script>
<template>
  <section class="knowledge-section" aria-label="问答轮次">
    <p>问题：{{ value.question }}</p>
    <p>提交时间：{{ value.task.createdAt }}</p>
    <p>选定范围：{{ value.selectedKnowledgeBaseIds.join('、') }}</p>
    <p v-if="value.task.state === 'interrupted'" role="status">
      应用重启后任务已中断，未自动重新调用模型；确认费用后可手动提问。
    </p>
    <p v-if="value.task.failure" role="status">
      {{ failures[value.task.failure.code] || value.task.failure.message }}
    </p>
    <p v-if="value.task.state === 'cancelled'" role="status">
      问答已取消，不展示草稿。
    </p>
    <p v-for="warning in value.task.warnings" :key="warning">
      {{ warning }}
    </p>
    <div v-if="value.scope">
      <p v-if="value.scope.incomplete">
        本次范围不完整，回答只覆盖实际参与的知识库。
      </p>
      <p>选定范围：{{ value.scope.selectedKnowledgeBaseIds.join('、') }}</p>
      <p>实际范围：{{ value.scope.usedKnowledgeBaseIds.join('、') || '无' }}</p>
      <p v-for="item in value.scope.omitted" :key="item.knowledgeBaseId">
        遗漏知识库：{{ item.knowledgeBaseId }}；{{ item.message }}
      </p>
    </div>
    <section
      v-if="value.task.state === 'succeeded' && value.answer"
      aria-label="已校验回答"
    >
      <h3>{{ outcomes[value.answer.outcome] }}</h3>
      <p v-if="value.answer.historyContext === 'recent_complete_turns'">
        本轮只使用近期完整历史，省略了{{
          value.answer.omittedHistoryTurnCount
        }}轮历史；没有自动摘要，指代不明时请补充说明。
      </p>
      <p v-else-if="value.answer.historyContext === 'all_turns'">
        本轮使用预算内的全部完整历史，仅辅助理解，不代替本轮原文依据。
      </p>
      <p v-else>本轮未带入历史上下文；历史仅辅助理解，不替代原文依据。</p>
      <p class="source-text">{{ value.answer.text }}</p>
      <p v-for="item in value.answer.uncoveredQuestions" :key="item">
        缺少依据的问题：{{ item }}
      </p>
      <p v-for="item in value.answer.contradictions" :key="item">
        资料矛盾：{{ item }}
      </p>
      <p v-for="warning in value.answer.warnings" :key="warning">
        {{ warning }}
      </p>
      <p>校验通过仍需结合引用原文判断，不代表正确性保证。</p>
      <ul class="knowledge-files">
        <li
          v-for="item in value.answer.evidence.filter((e) =>
            value.answer.citationEvidenceIds.includes(e.id),
          )"
          :key="item.id"
        >
          <strong>{{ item.filenameAtCapture }}</strong>
          <p>内容版本：{{ item.contentVersionId }}</p>
          <EvidenceLocation :location="item.location" /><button
            :disabled="evidenceBusy"
            @click="$emit('evidence', item, $event)"
          >
            查看引用原文
          </button>
        </li>
      </ul>
    </section>
  </section>
</template>
