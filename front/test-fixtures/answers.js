import { id, otherId, time, task } from './knowledge-bases';
import { evidence, scope } from './indexing';
export const input = {
  question: '版本规则',
  knowledgeBaseIds: [id],
  manualName: '手动问答名',
};
export const answer = {
  outcome: 'answered',
  text: '根据资料，版本更新后切换生效。',
  citationEvidenceIds: [evidence.id],
  evidence: [evidence],
  scope,
  historyContext: 'none',
  omittedHistoryTurnCount: 0,
  uncoveredQuestions: [],
  contradictions: [],
  warnings: [],
};
export const questionTask = {
  task: {
    ...task,
    kind: 'question',
    knowledgeBaseId: null,
    targetConfigVersionId: null,
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    resultResourceId: otherId,
  },
  conversationId: id,
  question: input.question,
  selectedKnowledgeBaseIds: [id],
  answer,
  scope,
};
export const accepted = {
  conversation: {
    id,
    name: input.manualName,
    manuallyNamed: true,
    knowledgeBaseIds: [id],
    createdAt: time,
    updatedAt: time,
    activeTask: null,
    lastTask: questionTask.task,
  },
  questionTask,
};
