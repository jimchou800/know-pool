import { readFile, writeFile } from 'node:fs/promises';
import { mergeReports } from './consumer-evidence.mjs';

const fragments = [
  '01',
  '02',
  '03',
  '06',
  '07',
  '08-answers-client',
  '08-answers-history-client',
  '08-cleanup-cleanup',
  '08-knowledge-bases-indexing-contract',
  '08-knowledge-bases-rebuild',
  '08-knowledge-bases-retry',
  '08-maintenance-backup-restore',
  '08-maintenance-migration',
  '08-search-client',
  '08-settings-runtime',
];
const reports = await Promise.all(
  fragments.map(async (fragment) =>
    JSON.parse(
      await readFile(`artifacts/contract-consumer-${fragment}.json`, 'utf8'),
    ),
  ),
);
await writeFile(
  'artifacts/contract-consumer.json',
  JSON.stringify(mergeReports(reports), null, 2),
);
