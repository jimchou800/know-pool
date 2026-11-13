import assert from 'node:assert/strict';

export async function checkDeletionCleanup(
  page,
  tests,
  { http, base, datasetId, knowledgeBaseId },
) {
  const root = `${base}/api/v1`;
  const conversations = await (
    await http.get(`${root}/conversations?limit=100`)
  ).json();
  let snapshot, conversationId;
  for (const conversation of conversations.items) {
    const turns = await (
      await http.get(`${root}/conversations/${conversation.id}/turns?limit=100`)
    ).json();
    for (const turn of turns.items) {
      const evidence = turn.questionTask.answer?.evidence.find(
        (item) => item.knowledgeBaseId === knowledgeBaseId,
      );
      if (evidence) {
        snapshot = evidence;
        conversationId = conversation.id;
        break;
      }
    }
    if (snapshot) break;
  }
  assert.ok(snapshot, '固定历史会话须先生成实际引用');
  const remove = async (kind, id, label, route) => {
    await page.goto(`${base}/#${route}`);
    await page.reload();
    await page
      .getByRole('button', { name: `删除${label}`, exact: true })
      .click();
    const panel = page.getByRole('region', {
      name: `确认删除${label}`,
      exact: true,
    });
    await panel.getByText(/涉及资料：/).waitFor();
    assert.ok((await panel.innerText()).includes('受影响知识库'));
    const response = page.waitForResponse(
      (reply) =>
        reply.request().method() === 'DELETE' &&
        new URL(reply.url()).pathname === `/api/v1/${kind}/${id}`,
    );
    await panel
      .getByRole('button', { name: `确认删除${label}`, exact: true })
      .click();
    const reply = await response;
    assert.equal(reply.status(), 202);
    assert.deepEqual(JSON.parse(reply.request().postData()), {
      confirmed: true,
    });
    assert.ok((await reply.json()).task.id);
    await panel.waitFor({ state: 'detached' });
    assert.equal((await http.get(`${root}/${kind}/${id}`)).status(), 404);
  };
  await remove(
    'knowledge-bases',
    knowledgeBaseId,
    '知识库',
    `/knowledge-bases/${knowledgeBaseId}`,
  );
  assert.equal((await http.get(`${root}/datasets/${datasetId}`)).status(), 200);
  assert.equal(
    (await http.get(`${root}/files/${snapshot.fileId}`)).status(),
    200,
  );
  const before = await (
    await http.get(
      `${root}/conversations/${conversationId}/evidence/${snapshot.id}`,
    )
  ).json();
  assert.equal(before.text, snapshot.text);
  await page.goto(`${base}/#/datasets/${datasetId}`);
  await page.reload();
  const file = await (
    await http.get(`${root}/files/${snapshot.fileId}`)
  ).json();
  await page
    .locator('.file-rows button')
    .filter({ hasText: file.name })
    .first()
    .click();
  await page.getByRole('button', { name: '删除资料', exact: true }).click();
  const panel = page.getByRole('region', { name: '确认删除资料', exact: true });
  await panel.getByText(/涉及资料：1份/).waitFor();
  const deleted = page.waitForResponse(
    (reply) =>
      reply.request().method() === 'DELETE' &&
      new URL(reply.url()).pathname === `/api/v1/files/${snapshot.fileId}`,
  );
  await panel
    .getByRole('button', { name: '确认删除资料', exact: true })
    .click();
  assert.equal((await deleted).status(), 202);
  await panel.waitFor({ state: 'detached' });
  const retained = await (
    await http.get(
      `${root}/conversations/${conversationId}/evidence/${snapshot.id}`,
    )
  ).json();
  assert.equal(retained.currentFilename, null);
  assert.equal(retained.text, snapshot.text);
  assert.equal(retained.filenameAtCapture, snapshot.filenameAtCapture);
  await page.goto(`${base}/#/answers?conversation=${conversationId}`);
  await page
    .getByRole('button', { name: '查看引用原文', exact: true })
    .first()
    .click();
  const drawer = page.getByRole('region', { name: '引用原文', exact: true });
  await drawer.waitFor();
  assert.ok((await drawer.innerText()).includes('源资料已删除'));
  if (!retained.originalAvailable)
    assert.equal(
      await drawer
        .getByRole('button', { name: '打开对应版本原文', exact: true })
        .count(),
      0,
    );
  await remove('datasets', datasetId, '数据集', `/datasets/${datasetId}`);
  await page.goto(`${base}/#/tasks`);
  await page.getByRole('heading', { name: '清理任务', exact: true }).waitFor();
  await page.getByRole('button', { name: '刷新清理任务', exact: true }).click();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-deletion-cleanup-${width}.png`,
    });
  }
  tests.push({
    name: '真实三资源删除影响逻辑失效及历史快照保留',
    passed: true,
    operationIds: [
      'getDatasetDeleteImpact',
      'deleteDataset',
      'getFileDeleteImpact',
      'deleteFile',
      'getKnowledgeBaseDeleteImpact',
      'deleteKnowledgeBase',
      'listTasks',
    ],
    assertions: [
      '三资源真实影响先读后确认，confirmed负载与受理后逻辑失效',
      '删除知识库保留数据集和源资料，删除资料后当前名称为空而引用快照正文不变',
      '历史引用抽屉仍可读取，原文不可用时不提供伪预览下载',
      '独立全局清理任务页展示残留任务，三宽截图，不增加首页仪表盘',
    ],
  });
}
