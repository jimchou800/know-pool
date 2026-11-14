import assert from 'node:assert/strict';

export function mergeReports(reports) {
  assert.ok(reports.length, '消费证据不能为空');
  const first = reports[0];
  for (const report of reports) {
    assert.equal(report.contractSha256, first.contractSha256);
    assert.equal(report.implementationSha256, first.implementationSha256);
  }
  const tests = reports.flatMap((report) => report.tests);
  assert.equal(
    new Set(tests.map((test) => test.name)).size,
    tests.length,
    '消费证据测试名称必须全局唯一',
  );
  return {
    ...first,
    scope: [...new Set(reports.flatMap((report) => report.scope))],
    tests,
  };
}
