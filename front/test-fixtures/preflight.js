import { id, otherId, time, processing, task } from './knowledge-bases';
import { evidence } from './indexing';
export const config = {
  id: otherId,
  knowledgeBaseId: id,
  createdAt: time,
  parserRuleVersion: 'parser-1',
  chunkRuleVersion: 'chunk-1',
  lengthMeterVersion: 'unicode-1',
  input: processing,
};
export const rebuild = { ...task, kind: 'rebuild_knowledge_base' };
export const preflight = {
  taskId: otherId,
  contentVersionId: otherId,
  configVersionId: otherId,
  lengthMeterVersion: 'unicode-1',
  chunkCount: 2,
  minimumLength: 4,
  maximumLength: 8,
  medianLength: 6,
  p95Length: 8,
  fallbackCounts: {
    sentence: 1,
    hard: 1,
    table_row: 0,
    table_cell: 0,
    code_line: 0,
    delimiter: 0,
  },
  emptyText: false,
  integrityPassed: true,
  warnings: ['固定降级提示'],
  samples: [
    {
      chunkId: otherId,
      text: '<script>固定样例😀</script>',
      codePointCount: 8,
      location: evidence.location,
      fallbackModes: ['sentence', 'hard'],
    },
  ],
};
