import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const first = JSON.parse(
  await readFile('artifacts/contract-consumer-01.json', 'utf8'),
);
const second = JSON.parse(
  await readFile('artifacts/contract-consumer-02.json', 'utf8'),
);
const third = JSON.parse(
  await readFile('artifacts/contract-consumer-03.json', 'utf8'),
);
const sixth = JSON.parse(
  await readFile('artifacts/contract-consumer-06.json', 'utf8'),
);
assert.equal(first.contractSha256, second.contractSha256);
assert.equal(first.implementationSha256, second.implementationSha256);
assert.equal(second.contractSha256, third.contractSha256);
assert.equal(second.implementationSha256, third.implementationSha256);
assert.equal(third.contractSha256, sixth.contractSha256);
assert.equal(third.implementationSha256, sixth.implementationSha256);
await writeFile(
  'artifacts/contract-consumer.json',
  JSON.stringify(
    {
      ...third,
      scope: [...first.scope, ...second.scope, ...third.scope, ...sixth.scope],
      tests: [...first.tests, ...second.tests, ...third.tests, ...sixth.tests],
    },
    null,
    2,
  ),
);
