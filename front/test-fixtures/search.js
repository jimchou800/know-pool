import { id, otherId, time } from './knowledge-bases';
import { search } from './indexing';
export const instanceId = '00000000-0000-4000-8000-000000000073';
export const nextRequestId = '00000000-0000-4000-8000-000000000074';
export const instance = {
  id: instanceId,
  name: '新检索',
  manuallyNamed: false,
  lastSubmittedQuery: null,
  knowledgeBaseIds: [],
  createdAt: time,
  updatedAt: time,
  currentRequestId: null,
  resultState: 'never_submitted',
};
export const input = { query: '版本规则', knowledgeBaseIds: [id, otherId] };
export const scope = {
  selectedKnowledgeBaseIds: [id, otherId],
  usedKnowledgeBaseIds: [id],
  omitted: [
    {
      knowledgeBaseId: otherId,
      reason: 'unavailable',
      message: '固定依赖故障',
    },
  ],
  incomplete: true,
};
export const result = {
  ...search,
  instanceId,
  task: { ...search.task, knowledgeBaseId: null },
  scope,
  result: { ...search.result, instanceId, scope, warnings: ['范围不完整'] },
};
