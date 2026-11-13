import { exact, uuid, date } from '../system/session';
import { validTask } from '../knowledge-bases/client';
import { validEvidence } from '../knowledge-bases/search-client';
import { validScope } from '../search/client';
const id = (v) => typeof v === 'string' && uuid.test(v);
const text = (v) => typeof v === 'string';
const question = (v) =>
  text(v) && /\S/.test(v) && Array.from(v).length <= 20000;
const ids = (v, min = 0) =>
  Array.isArray(v) &&
  v.length >= min &&
  v.length <= 100 &&
  v.every(id) &&
  new Set(v).size === v.length;
const nullable = (v, check) => v === null || check(v);
const same = (a, b) => a.length === b.length && a.every((v) => b.includes(v));
const task = (v) =>
  validTask(v, 'question') &&
  [
    'knowledgeBaseId',
    'fileId',
    'targetContentVersionId',
    'targetConfigVersionId',
    'retryOfTaskId',
    'progressTotal',
  ].every((k) => v[k] === null) &&
  (v.state === 'succeeded'
    ? v.resultResourceId === v.id
    : v.resultResourceId === null);
function verify(value, valid) {
  if (!valid) throw new Error('问答响应不符合约定，请查询原任务。');
  return value;
}
function path(value) {
  if (!id(value)) throw new Error('问答任务标识无效。');
  return `/question-tasks/${value}`;
}
function answer(v, selected) {
  if (
    !exact(v, [
      'outcome',
      'text',
      'citationEvidenceIds',
      'evidence',
      'scope',
      'historyContext',
      'omittedHistoryTurnCount',
      'uncoveredQuestions',
      'contradictions',
      'warnings',
    ]) ||
    !['answered', 'partial', 'no_evidence', 'needs_clarification'].includes(
      v.outcome,
    ) ||
    !text(v.text) ||
    !Array.isArray(v.evidence) ||
    !validScope(v.scope, selected) ||
    !v.evidence.every(
      (e) =>
        v.scope.usedKnowledgeBaseIds.includes(e.knowledgeBaseId) &&
        validEvidence(e, e.knowledgeBaseId),
    ) ||
    !Array.isArray(v.citationEvidenceIds) ||
    !v.citationEvidenceIds.every(id) ||
    new Set(v.citationEvidenceIds).size !== v.citationEvidenceIds.length ||
    !v.citationEvidenceIds.every((value) =>
      v.evidence.some((e) => e.id === value),
    ) ||
    !['none', 'all_turns', 'recent_complete_turns'].includes(
      v.historyContext,
    ) ||
    !Number.isSafeInteger(v.omittedHistoryTurnCount) ||
    v.omittedHistoryTurnCount < 0 ||
    !Array.isArray(v.uncoveredQuestions) ||
    !v.uncoveredQuestions.every(question) ||
    !Array.isArray(v.contradictions) ||
    !v.contradictions.every(text) ||
    !Array.isArray(v.warnings) ||
    !v.warnings.every(text)
  )
    return false;
  return (
    (v.outcome !== 'no_evidence' ||
      (v.citationEvidenceIds.length === 0 && v.evidence.length === 0)) &&
    (v.outcome !== 'partial' || v.uncoveredQuestions.length > 0)
  );
}
function validQuestion(v, input, conversationId) {
  if (
    !exact(v, [
      'task',
      'conversationId',
      'question',
      'selectedKnowledgeBaseIds',
      'answer',
      'scope',
    ]) ||
    !task(v.task) ||
    !id(v.conversationId) ||
    (conversationId && v.conversationId !== conversationId) ||
    v.question !== input.question ||
    !ids(v.selectedKnowledgeBaseIds, 1) ||
    !same(v.selectedKnowledgeBaseIds, input.knowledgeBaseIds) ||
    !nullable(v.scope, (s) =>
      validScope(s, input.knowledgeBaseIds, v.task.state === 'succeeded'),
    )
  )
    return false;
  if (v.task.state !== 'succeeded') return v.answer === null;
  return (
    answer(v.answer, input.knowledgeBaseIds) &&
    v.scope !== null &&
    same(v.scope.usedKnowledgeBaseIds, v.answer.scope.usedKnowledgeBaseIds) &&
    JSON.stringify(v.scope.omitted) === JSON.stringify(v.answer.scope.omitted)
  );
}
function conversation(v) {
  return (
    exact(v, [
      'id',
      'name',
      'manuallyNamed',
      'knowledgeBaseIds',
      'createdAt',
      'updatedAt',
      'activeTask',
      'lastTask',
    ]) &&
    id(v.id) &&
    text(v.name) &&
    typeof v.manuallyNamed === 'boolean' &&
    ids(v.knowledgeBaseIds) &&
    date(v.createdAt) &&
    date(v.updatedAt) &&
    nullable(v.activeTask, task) &&
    nullable(v.lastTask, task)
  );
}
export function createAnswerClient(session) {
  return {
    async first(input, key) {
      if (
        !input ||
        Object.keys(input).some(
          (k) => !['question', 'knowledgeBaseIds', 'manualName'].includes(k),
        ) ||
        !question(input.question) ||
        !ids(input.knowledgeBaseIds, 1) ||
        !id(key) ||
        ('manualName' in input &&
          (!question(input.manualName) ||
            Array.from(input.manualName).length > 120))
      )
        throw new Error('请填写有效问题、范围和名称。');
      const value = await session.request({
        method: 'post',
        url: '/conversations/first-question',
        headers: { 'Idempotency-Key': key },
        data: input,
      });
      return verify(
        value,
        exact(value, ['conversation', 'questionTask']) &&
          conversation(value.conversation) &&
          validQuestion(value.questionTask, input, value.conversation.id) &&
          value.conversation.lastTask?.id === value.questionTask.task.id &&
          same(value.conversation.knowledgeBaseIds, input.knowledgeBaseIds) &&
          (!value.conversation.activeTask ||
            value.conversation.activeTask.id === value.questionTask.task.id),
      );
    },
    async get(taskId, input, conversationId) {
      const value = await session.request({ method: 'get', url: path(taskId) });
      return verify(
        value,
        validQuestion(value, input, conversationId) && value.task.id === taskId,
      );
    },
    async cancel(taskId, input, conversationId) {
      const value = await session.request({
        method: 'post',
        url: path(taskId) + '/cancel',
      });
      return verify(
        value,
        validQuestion(value, input, conversationId) && value.task.id === taskId,
      );
    },
    async evidence(conversationId, item) {
      if (!id(conversationId) || !id(item?.id))
        throw new Error('引用标识无效。');
      const value = await session.request({
        method: 'get',
        url: `/conversations/${conversationId}/evidence/${item.id}`,
      });
      return verify(
        value,
        validEvidence(value, item.knowledgeBaseId) &&
          [
            'id',
            'chunkId',
            'fileId',
            'contentVersionId',
            'indexVersionId',
            'configVersionId',
          ].every((k) => value[k] === item[k]),
      );
    },
  };
}
