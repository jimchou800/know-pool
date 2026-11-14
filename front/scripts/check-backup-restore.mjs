import assert from 'node:assert/strict';
import { dirname, join, resolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';

export async function checkBackupRestore(page, tests, { http, base }) {
  const root = `${base}/api/v1`,
    initial = await (await http.get(`${root}/system/status`)).json();
  const source = resolve(initial.dataDirectory),
    allowedRoot = resolve('../.runtime/live-checks') + sep;
  assert.ok(
    source.startsWith(allowedRoot),
    '备份恢复仅针对统一联调固定临时目录',
  );
  const backupPath = join(dirname(source), `backup-${randomUUID()}`);
  const datasetsBefore = await (
    await http.get(`${root}/datasets?limit=100`)
  ).json();
  const conversationsBefore = await (
    await http.get(`${root}/conversations?limit=100`)
  ).json();
  await page.goto(`${base}/#/settings`);
  const run = async (kind, title, path) => {
    const panel = page.getByRole('region', { name: title, exact: true });
    await panel.getByRole('textbox').fill(path);
    const preflightResponse = page.waitForResponse(
      (reply) =>
        new URL(reply.url()).pathname ===
        `/api/v1/maintenance/${kind}/preflight`,
    );
    await panel
      .getByRole('button', {
        name: `预检${kind === 'backup' ? '备份' : '恢复'}目录`,
        exact: true,
      })
      .click();
    const checkedResponse = await preflightResponse;
    assert.equal(checkedResponse.status(), 200);
    const checked = await checkedResponse.json();
    assert.equal(checked.allowed, true);
    assert.equal(checked.includesVectorSnapshot, false);
    assert.deepEqual(
      JSON.parse(checkedResponse.request().postData()),
      kind === 'backup' ? { targetPath: path } : { backupPath: path },
    );
    const confirm = panel.getByRole('button', {
      name: kind === 'backup' ? '确认备份' : '确认恢复',
      exact: true,
    });
    assert.equal(await confirm.isDisabled(), true);
    const checks = panel.getByRole('checkbox');
    assert.equal(await checks.count(), kind === 'backup' ? 1 : 3);
    for (let index = 0; index < (await checks.count()); index++)
      await checks.nth(index).check();
    const submitResponse = page.waitForResponse(
      (reply) =>
        new URL(reply.url()).pathname === `/api/v1/maintenance/${kind}` &&
        reply.request().method() === 'POST',
    );
    await confirm.click();
    const acceptedResponse = await submitResponse;
    assert.equal(acceptedResponse.status(), 202);
    assert.deepEqual(JSON.parse(acceptedResponse.request().postData()), {
      preflightToken: checked.token,
      confirmed: true,
      ...(kind === 'restore'
        ? {
            acknowledgePlaintextCredentials: true,
            acknowledgeIndexRebuildCost: true,
          }
        : {}),
    });
    const taskId = (await acceptedResponse.json()).task.id;
    await page.reload();
    await panel.getByText(`任务：${taskId}`, { exact: true }).waitFor();
    let result;
    for (let attempt = 0; attempt < 100; attempt++) {
      result = await (
        await http.get(`${root}/maintenance/tasks/${taskId}`)
      ).json();
      if (!['queued', 'running'].includes(result.task.state)) break;
      await page.waitForTimeout(100);
    }
    assert.equal(result.task.state, 'succeeded');
    assert.equal(result.vectorSnapshotIncluded, false);
    assert.equal(result.oldDirectoryResidue, false);
    await panel
      .getByRole('button', { name: '重新读取原任务', exact: true })
      .click();
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await panel.scrollIntoViewIfNeeded();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({ path: `artifacts/live-${kind}-${width}.png` });
    }
    return result;
  };
  const backup = await run('backup', '应用数据备份', backupPath);
  assert.equal(backup.configurationSwitched, false);
  assert.equal(resolve(backup.activeDataDirectory), source);
  const restored = await run('restore', '应用数据恢复', backupPath);
  assert.equal(restored.configurationSwitched, true);
  assert.notEqual(resolve(restored.activeDataDirectory), source);
  assert.ok(resolve(restored.activeDataDirectory).startsWith(allowedRoot));
  const current = await (await http.get(`${root}/system/status`)).json();
  assert.equal(
    resolve(current.dataDirectory),
    resolve(restored.activeDataDirectory),
  );
  assert.deepEqual(
    (await (await http.get(`${root}/datasets?limit=100`)).json()).items.map(
      (item) => item.id,
    ),
    datasetsBefore.items.map((item) => item.id),
  );
  assert.deepEqual(
    (
      await (await http.get(`${root}/conversations?limit=100`)).json()
    ).items.map((item) => item.id),
    conversationsBefore.items.map((item) => item.id),
  );
  const panel = page.getByRole('region', { name: '应用数据恢复', exact: true });
  if (restored.indexStatus !== 'valid')
    await panel.getByText(/资料已恢复，知识服务仍待核查或重建/).waitFor();
  assert.ok((await panel.innerText()).includes('可能产生嵌入费用'));
  assert.equal(
    await panel.getByRole('button', { name: '确认仅清理旧目录残留' }).count(),
    0,
  );
  tests.push({
    name: '固定临时资料一致性备份及恢复消费',
    passed: true,
    operationIds: [
      'backupPreflight',
      'submitBackup',
      'restorePreflight',
      'submitRestore',
      'getMaintenanceTask',
      'listTasks',
      'getSystemStatus',
    ],
    constraintIds: ['#/x-business-constraints/maintenance'],
    assertions: [
      '真实预检及明确三项恢复确认，刷新后按后端列表读取原任务',
      '备份未切运行目录，恢复采用独有目录且保留原备份与旧目录，不执行浏览器文件操作',
      '恢复后数据集和会话身份保持，索引不完整明确提示重建费用且未自动发起模型调用',
      '三种桌面宽度备份恢复截图无横向溢出',
    ],
  });
}
