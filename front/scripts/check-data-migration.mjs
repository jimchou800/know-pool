import assert from 'node:assert/strict';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';

export async function checkDataMigration(page, tests, { http, base }) {
  const root = `${base}/api/v1`;
  const initial = await (await http.get(`${root}/system/status`)).json();
  const source = resolve(initial.dataDirectory);
  const testRoot = resolve('../.runtime/live-checks');
  const within = relative(testRoot, source);
  assert.ok(
    within &&
      !within.startsWith(`..${sep}`) &&
      within !== '..' &&
      !resolve(within).startsWith(`${sep}${sep}`),
    '仅允许统一联调创建的固定临时目录',
  );
  assert.equal(dirname(source).startsWith(testRoot + sep), true);
  const target = join(dirname(source), `migrated-${randomUUID()}`);
  await page.goto(`${base}/#/settings`);
  const panel = page.getByRole('region', { name: '数据目录迁移', exact: true });
  await panel
    .getByText(`当前数据目录：${initial.dataDirectory}`, { exact: true })
    .waitFor();
  const preflight = async (path) => {
    await panel
      .getByRole('textbox', { name: '目标本地目录', exact: true })
      .fill(path);
    const response = page.waitForResponse(
      (reply) =>
        new URL(reply.url()).pathname ===
        '/api/v1/maintenance/migration/preflight',
    );
    await panel
      .getByRole('button', { name: '预检迁移目录', exact: true })
      .click();
    const reply = await response;
    assert.equal(reply.status(), 200);
    assert.deepEqual(JSON.parse(reply.request().postData()), {
      sourcePath: initial.dataDirectory,
      targetPath: path,
    });
    assert.equal(!!reply.request().headers()['x-csrf-token'], true);
    assert.equal(reply.request().headers()['idempotency-key'], undefined);
    return reply.json();
  };
  const rejected = await preflight(source);
  assert.equal(rejected.allowed, false);
  assert.ok(rejected.reasons.length);
  await panel
    .getByText('目录不允许迁移，请调整目标。', { exact: true })
    .waitFor();
  assert.equal(
    await panel.getByRole('button', { name: '确认迁移', exact: true }).count(),
    0,
  );
  const checked = await preflight(target);
  assert.equal(checked.allowed, true);
  assert.equal(checked.includesVectorSnapshot, false);
  const confirm = panel.getByRole('button', { name: '确认迁移', exact: true });
  assert.equal(await confirm.isDisabled(), true);
  await panel.getByRole('checkbox').check();
  const response = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname === '/api/v1/maintenance/migration' &&
      reply.request().method() === 'POST',
  );
  await confirm.click();
  const submitted = await response;
  assert.equal(submitted.status(), 202);
  assert.deepEqual(JSON.parse(submitted.request().postData()), {
    preflightToken: checked.token,
    confirmed: true,
  });
  assert.ok(submitted.request().headers()['idempotency-key']);
  const taskId = (await submitted.json()).task.id;
  await panel.getByText(`任务：${taskId}`, { exact: true }).waitFor();
  await page.reload();
  await panel.getByText(`任务：${taskId}`, { exact: true }).waitFor();
  let result;
  for (let attempt = 0; attempt < 80; attempt++) {
    const reply = await http.get(`${root}/maintenance/tasks/${taskId}`);
    assert.equal(reply.status(), 200);
    result = await reply.json();
    if (!['queued', 'running'].includes(result.task.state)) break;
    await page.waitForTimeout(150);
  }
  assert.equal(result.configurationSwitched, true);
  assert.equal(resolve(result.activeDataDirectory), resolve(target));
  assert.equal(result.oldDirectoryResidue, false);
  assert.equal(result.vectorSnapshotIncluded, false);
  assert.ok(
    ['valid', 'unavailable', 'rebuild_required'].includes(result.indexStatus),
  );
  await panel
    .getByRole('button', { name: '重新读取原任务', exact: true })
    .click();
  await panel
    .getByText('目录配置已切换，继续使用新目录。', { exact: true })
    .waitFor();
  if (result.indexStatus === 'unavailable')
    await panel
      .getByText('索引服务不可用，知识服务尚未恢复', { exact: true })
      .waitFor();
  const current = await (await http.get(`${root}/system/status`)).json();
  assert.equal(resolve(current.dataDirectory), resolve(target));
  assert.equal(
    await page.evaluate(() =>
      sessionStorage.getItem('know-pool-migration-pending'),
    ),
    null,
  );
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await panel.scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-data-migration-${width}.png`,
    });
  }
  tests.push({
    name: '固定临时目录真实迁移预检明确确认与原任务恢复',
    passed: true,
    operationIds: [
      'migrationPreflight',
      'submitMigration',
      'getMaintenanceTask',
      'listTasks',
      'getSystemStatus',
    ],
    constraintIds: ['#/x-business-constraints/maintenance'],
    assertions: [
      '仅统一联调固定临时源目录，浏览器不操作文件；同源目录被真实预检拒绝并显示理由',
      '明确确认才提交，幂等键与保护信息齐全；刷新页面由后端列表恢复原任务',
      '实际使用目录切换且无旧目录残留；向量原生存储不迁移，索引不可用时不宣称恢复',
      '三种桌面宽度真实截图且无横向溢出；不删除仍在使用的迁入目录',
    ],
  });
}
