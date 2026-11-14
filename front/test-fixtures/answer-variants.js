import { id, time } from './knowledge-bases';
import { evidence } from './indexing';
import { answer, input, questionTask } from './answers';

export const omittedId = '00000000-0000-4000-8000-000000000073';

export function evidenceVariants() {
  return [
    {
      ...evidence.location,
      format: 'pdf',
      pageStart: 2,
      pageEnd: 3,
      lineStart: null,
      lineEnd: null,
    },
    {
      ...evidence.location,
      format: 'markdown',
      titlePath: ['版本规则', '更新条件'],
      paragraphId: '固定段落标识',
      structureId: '固定段落标识',
      continuationIndex: 1,
    },
    {
      ...evidence.location,
      format: 'markdown',
      titlePath: ['版本规则', '条件表格'],
      structureId: '固定表格标识',
      continuationIndex: 0,
      tableRowStart: 0,
      tableRowEnd: 1,
      tableColumnStart: 0,
      tableColumnEnd: 2,
    },
    {
      ...evidence.location,
      format: 'markdown',
      titlePath: ['版本规则', '调用样例'],
      structureId: '固定代码标识',
      continuationIndex: 0,
      codeLanguage: 'java',
      codeBlockId: '固定代码标识',
    },
  ].map((location) => ({
    ...evidence,
    filenameAtCapture:
      location.format === 'pdf' ? '版本规则.pdf' : '版本规则.md',
    currentFilename:
      location.format === 'pdf'
        ? '更新后的资料名称.pdf'
        : '更新后的资料名称.md',
    location,
  }));
}

export function questionVariants() {
  const omissions = [
    'deleted',
    'not_published',
    'unavailable',
    'no_effective_index',
  ];
  const completed = evidenceVariants().map((item, index) => {
    const scope = {
      selectedKnowledgeBaseIds: [id, omittedId],
      usedKnowledgeBaseIds: [id],
      omitted: [
        {
          knowledgeBaseId: omittedId,
          reason: omissions[index],
          message: '该知识库未参与本轮检索。',
        },
      ],
      incomplete: true,
    };
    const warnings = [
      '部分知识库未参与，本轮依据范围不完整。',
      '本轮仅使用预算内近期完整历史，未带入更早轮次；指代不明时请补充说明。',
    ];
    return {
      ...questionTask,
      selectedKnowledgeBaseIds: scope.selectedKnowledgeBaseIds,
      scope,
      task: { ...questionTask.task, warnings },
      answer: {
        ...answer,
        outcome: 'partial',
        text: `本轮资料仅能说明已检索部分的规则【${item.id}】，其他条件尚无有效依据。`,
        evidence: [item],
        scope,
        historyContext: 'recent_complete_turns',
        omittedHistoryTurnCount: 2,
        uncoveredQuestions: ['其他版本的规则尚无有效依据。'],
        contradictions: ['资料对生效日期的条件存在差异，仍需核实。'],
        warnings,
      },
    };
  });
  const pending = [
    'queued',
    'running',
    'failed',
    'cancelled',
    'interrupted',
  ].map((state) => {
    const terminal = !['queued', 'running'].includes(state);
    return {
      ...questionTask,
      answer: null,
      scope: null,
      task: {
        ...questionTask.task,
        state,
        attempt: state === 'queued' ? 0 : 1,
        phase: terminal
          ? 'completed'
          : state === 'queued'
            ? 'queued'
            : 'retrieving',
        startedAt: state === 'queued' ? null : time,
        finishedAt: terminal ? time : null,
        resultResourceId: null,
        failure: ['failed', 'interrupted'].includes(state)
          ? {
              code:
                state === 'failed'
                  ? 'MODEL_CONFIG_INVALID'
                  : 'TASK_INTERRUPTED',
              message: '固定模型配置无效或重启中断。',
              retryable: false,
            }
          : null,
        warnings:
          state === 'cancelled'
            ? ['外部请求可能已经计费，取消不保证撤回费用。']
            : state === 'interrupted'
              ? ['重启不会自动重新调用模型。']
              : [],
      },
    };
  });
  return [...completed, ...pending];
}

export function variantInput(value) {
  return { ...input, knowledgeBaseIds: value.selectedKnowledgeBaseIds };
}
