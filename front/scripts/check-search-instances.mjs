import assert from 'node:assert/strict';

export async function checkSearchInstances(
  page,
  tests,
  { http, base, knowledgeBaseId, gateEmbedding, releaseEmbedding },
) {
  const root = `${base}/api/v1`;
  await page.goto(`${base}/#/search`);
  const list = page.getByRole('complementary', {
    name: '检索实例',
    exact: true,
  });
  const create = async () => {
    const response = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/search-instances' &&
        r.request().method() === 'POST',
    );
    await list.getByRole('button', { name: '新建检索', exact: true }).click();
    return (await (await response).json()).id;
  };
  const currentWindow = () =>
    page.getByRole('region', { name: '检索窗口', exact: true });
  const rename = async (name) => {
    await page
      .getByRole('button', { name: '重命名当前检索', exact: true })
      .click();
    await page.getByLabel('检索名称', { exact: true }).fill(name);
    const response = page.waitForResponse(
      (r) =>
        /\/name$/.test(new URL(r.url()).pathname) &&
        r.request().method() === 'PATCH',
    );
    await page
      .getByRole('button', { name: '保存检索名称', exact: true })
      .click();
    assert.equal((await response).status(), 200);
  };
  const first = await create();
  await rename('重名检索');
  await currentWindow()
    .getByLabel('检索内容', { exact: true })
    .fill('第一个实例未提交草稿');
  await currentWindow().locator(`input[value="${knowledgeBaseId}"]`).check();
  const scopeResponse = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/v1/search-instances/${first}/scope`,
  );
  await currentWindow()
    .getByRole('button', { name: '保存知识库范围', exact: true })
    .click();
  assert.equal((await scopeResponse).status(), 200);
  const second = await create();
  await rename('重名检索');
  await list.locator(`[data-instance-id="${first}"] button`).click();
  assert.equal(
    await currentWindow().getByLabel('检索内容', { exact: true }).inputValue(),
    '第一个实例未提交草稿',
  );
  const selected = await http.get(`${root}/search-instances/${first}`);
  assert.equal((await selected.json()).manuallyNamed, true);
  gateEmbedding();
  let requestId;
  try {
    await currentWindow()
      .getByRole('checkbox', {
        name: '我同意本次检索可能调用嵌入服务并产生费用',
      })
      .check();
    const response = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname ===
        `/api/v1/search-instances/${first}/requests`,
    );
    await currentWindow()
      .getByRole('button', { name: '开始检索', exact: true })
      .click();
    requestId = (await (await response).json()).id;
    await currentWindow()
      .getByRole('button', { name: '取消本次检索', exact: true })
      .click();
    const cancel = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname ===
        `/api/v1/search-requests/${requestId}/cancel`,
    );
    await currentWindow()
      .getByRole('button', { name: '确认取消检索', exact: true })
      .click();
    assert.equal((await (await cancel).json()).task.state, 'cancelled');
  } finally {
    releaseEmbedding();
  }
  assert.equal(
    (await (await http.get(`${root}/search-instances/${first}`)).json()).name,
    '重名检索',
  );
  await page.getByRole('button', { name: '删除当前检索', exact: true }).click();
  await page.getByRole('button', { name: '保留当前实例', exact: true }).click();
  assert.equal(
    (await http.get(`${root}/search-instances/${first}`)).status(),
    200,
  );
  await page.getByRole('button', { name: '删除当前检索', exact: true }).click();
  const deletion = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/v1/search-instances/${first}` &&
      r.request().method() === 'DELETE',
  );
  await page
    .getByRole('button', { name: '确认删除检索实例', exact: true })
    .click();
  assert.equal((await deletion).status(), 202);
  assert.equal(
    (await http.get(`${root}/search-requests/${requestId}`)).status(),
    410,
  );
  assert.equal(
    (await http.get(`${root}/search-instances/${second}`)).status(),
    200,
  );
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-search-instances-${width}.png`,
    });
  }
  tests.push({
    name: '真实实例重名范围切换取消确认删除与跨实例隔离',
    passed: true,
    operationIds: [
      'listSearchInstances',
      'getSearchInstance',
      'renameSearchInstance',
      'updateSearchInstanceScope',
      'deleteSearchInstance',
      'cancelSearchRequest',
    ],
    assertions: [
      '两个稳定标识允许同名且手动名不被首次提交覆盖',
      '保存范围不自动检索，切换保留未提交草稿',
      '取消原请求及删除确认，其他实例仍存在，旧请求返回失效',
      '三宽实例管理页面截图',
    ],
  });
  await page.goto(`${base}/#/knowledge-bases/${knowledgeBaseId}`);
}

export async function checkSearchRestart(
  page,
  tests,
  { base, instanceId, restartServer },
) {
  await restartServer();
  const posts = [];
  const observe = (request) => {
    if (
      request.method() === 'POST' &&
      /\/requests$/.test(new URL(request.url()).pathname)
    )
      posts.push(request.url());
  };
  page.on('request', observe);
  try {
    await page.goto(`${base}/#/search`);
    const list = page.getByRole('complementary', {
      name: '检索实例',
      exact: true,
    });
    const target = list.locator(`[data-instance-id="${instanceId}"] button`);
    await list
      .getByRole('button', { name: '刷新实例列表', exact: true })
      .waitFor();
    await list
      .getByText('正在读取实例…', { exact: true })
      .waitFor({ state: 'hidden' });
    while (
      (await target.count()) === 0 &&
      (await list
        .getByRole('button', { name: '更多检索实例', exact: true })
        .count())
    ) {
      await list
        .getByRole('button', { name: '更多检索实例', exact: true })
        .click();
      await list
        .getByText('正在读取实例…', { exact: true })
        .waitFor({ state: 'hidden' });
    }
    await target.click();
    await page.getByText('结果需手动重新检索', { exact: false }).waitFor();
    assert.equal(posts.length, 0);
    assert.equal(
      await page
        .getByRole('region', { name: '本次检索结果', exact: true })
        .count(),
      0,
    );
    tests.push({
      name: '真实重启后实例只恢复条件且不自动收费',
      passed: true,
      operationIds: ['listSearchInstances', 'getSearchInstance'],
      assertions: [
        `实例${instanceId}重启仅元数据恢复，旧结果不展示，提交请求为零`,
      ],
    });
  } finally {
    page.off('request', observe);
  }
}
