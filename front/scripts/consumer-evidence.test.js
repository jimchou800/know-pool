import { execFile } from 'node:child_process';
import { readFile, rm } from 'node:fs/promises';
import { expect, it } from 'vitest';
import { task, otherId } from '../test-fixtures/knowledge-bases';
import { mergeReports } from './consumer-evidence.mjs';

it('并行套件独立写入并完整合并，拒绝重名或快照不一致', async () => {
  const suites = ['selfcheck-a', 'selfcheck-b'];
  const observations = JSON.stringify([
    {
      operationId: 'getTask',
      method: 'get',
      url: `/tasks/${otherId}`,
      params: {},
      headers: {},
      body: null,
      status: 200,
      response: task,
    },
  ]);
  const paths = suites.map(
    (suite) => `artifacts/contract-consumer-08-${suite}.json`,
  );
  try {
    await Promise.all(
      suites.map(
        (suite) =>
          new Promise((resolve, reject) => {
            const child = execFile(
              'python',
              ['scripts/settings-evidence.py', '08', suite],
              (error) => (error ? reject(error) : resolve()),
            );
            child.stdin.end(observations);
          }),
      ),
    );
    const reports = await Promise.all(
      paths.map(async (path) => JSON.parse(await readFile(path, 'utf8'))),
    );
    const merged = mergeReports(reports);
    expect(merged.tests).toHaveLength(2);
    expect(merged.scope).toEqual(['getTask']);
    expect(merged.tests.map((test) => test.name)).toEqual(
      expect.arrayContaining([
        expect.stringContaining('selfcheck-a'),
        expect.stringContaining('selfcheck-b'),
      ]),
    );
    expect(() => mergeReports([reports[0], reports[0]])).toThrow('全局唯一');
    expect(() =>
      mergeReports([
        reports[0],
        { ...reports[1], implementationSha256: '旧快照' },
      ]),
    ).toThrow();
    expect(() => mergeReports([])).toThrow('不能为空');
  } finally {
    await Promise.all(paths.map((path) => rm(path, { force: true })));
  }
});
