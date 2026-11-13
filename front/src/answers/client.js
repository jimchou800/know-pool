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
  input ||= {
    question: v?.question,
    knowledgeBaseIds: v?.selectedKnowledgeBaseIds,
  };
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
    !question(v.question) ||
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
function conversationPath(value) {
  if (!id(value)) throw new Error('问答实例标识无效。');
  return `/conversations/${value}`;
}
function page(value, check) {
  return (
    exact(value, ['items', 'nextCursor']) &&
    Array.isArray(value.items) &&
    value.items.every(check) &&
    nullable(value.nextCursor, text) &&
    new Set(value.items.map((item) => item.id)).size === value.items.length
  );
}
export function createAnswerClient(session) {
  return {
    async list(cursor) {
      const value = await session.request({
        method: 'get',
        url: '/conversations',
        params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
      });
      return verify(value, page(value, conversation));
    },
    async getConversation(conversationId) {
      const value = await session.request({
        method: 'get',
        url: conversationPath(conversationId),
      });
      return verify(value, conversation(value) && value.id === conversationId);
    },
    async rename(conversationId, name) {
      if (!question(name) || Array.from(name).length > 120)
        throw new Error('名称不能为空，最多120个码点。');
      const value = await session.request({
        method: 'patch',
        url: conversationPath(conversationId) + '/name',
        data: { name },
      });
      return verify(
        value,
        conversation(value) &&
          value.id === conversationId &&
          value.name === name &&
          value.manuallyNamed,
      );
    },
    async scope(conversationId, knowledgeBaseIds) {
      if (!ids(knowledgeBaseIds))
        throw new Error('后续范围最多选择一百个知识库。');
      const value = await session.request({
        method: 'put',
        url: conversationPath(conversationId) + '/scope',
        data: { knowledgeBaseIds },
      });
      return verify(
        value,
        conversation(value) &&
          value.id === conversationId &&
          same(value.knowledgeBaseIds, knowledgeBaseIds),
      );
    },
    async turns(conversationId, cursor) {
      const value = await session.request({
        method: 'get',
        url: conversationPath(conversationId) + '/turns',
        params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
      });
      return verify(
        value,
        page(
          value,
          (turn) =>
            exact(turn, ['id', 'questionTask']) &&
            id(turn.id) &&
            validQuestion(turn.questionTask, null, conversationId),
        ),
      );
    },
    async submit(conversationId, input, key) {
      if (
        !exact(input, ['question', 'knowledgeBaseIds']) ||
        !question(input.question) ||
        !ids(input.knowledgeBaseIds, 1) ||
        !id(key)
      )
        throw new Error('请填写有效问题和范围。');
      const value = await session.request({
        method: 'post',
        url: conversationPath(conversationId) + '/questions',
        headers: { 'Idempotency-Key': key },
        data: input,
      });
      return verify(value, validQuestion(value, input, conversationId));
    },
    async remove(conversationId, key) {
      if (!id(key)) throw new Error('删除操作标识无效。');
      const value = await session.request({
        method: 'delete',
        url: conversationPath(conversationId),
        headers: { 'Idempotency-Key': key },
        data: { confirmed: true },
      });
      return verify(
        value,
        exact(value, ['task']) &&
          validTask(value.task, 'cleanup') &&
          value.task.state === 'succeeded' &&
          date(value.task.startedAt) &&
          date(value.task.finishedAt) &&
          value.task.attempt === 1 &&
          value.task.progressCompleted === 1 &&
          value.task.progressTotal === 1 &&
          [
            'knowledgeBaseId',
            'fileId',
            'targetContentVersionId',
            'targetConfigVersionId',
            'retryOfTaskId',
            'resultResourceId',
          ].every((field) => value.task[field] === null),
      );
    },
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
