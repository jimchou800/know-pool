import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const first = JSON.parse(
  await readFile('artifacts/contract-consumer-01.json', 'utf8'),
);
const second = JSON.parse(
  await readFile('artifacts/contract-consumer-02.json', 'utf8'),
);
assert.equal(first.contractSha256, second.contractSha256);
assert.equal(first.implementationSha256, second.implementationSha256);
await writeFile(
  'artifacts/contract-consumer.json',
  JSON.stringify(
    {
      ...second,
      scope: [...first.scope, ...second.scope],
      tests: [...first.tests, ...second.tests],
    },
    null,
    2,
  ),
);
