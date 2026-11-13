import assert from 'node:assert/strict';

export async function checkDatasets(page, baseURL, tests) {
  const observed = [];
  const responses = [];
  async function sample(operationId, method, action) {
    const waiting = page.waitForResponse((response) => {
      const path = new URL(response.url()).pathname;
      return (
        response.request().method() === method &&
        response.ok() &&
        (['getDataset', 'updateDataset'].includes(operationId)
          ? /^\/api\/v1\/datasets\/[0-9a-f-]+$/.test(path)
          : path === '/api/v1/datasets')
      );
    });
    await action();
    const response = await waiting;
    responses.push({
      operationId,
      status: response.status(),
      body: await response.json(),
      requestBody: response.request().postDataJSON() ?? undefined,
    });
  }
  const listener = (response) => {
    const outgoing = response.request();
    const url = new URL(response.url());
    if (!url.pathname.startsWith('/api/v1/datasets')) return;
    observed.push({
      method: outgoing.method(),
      path: url.pathname,
      status: response.status(),
      hasProtection: !!outgoing.headers()['x-csrf-token'],
    });
    assert.ok(
      [...url.searchParams.keys()].every((key) =>
        ['limit', 'cursor'].includes(key),
      ),
    );
  };
  page.on('response', listener);
  await sample('listDatasets', 'GET', () =>
    page.getByRole('link', { name: '数据集 导入与管理原始资料' }).click(),
  );
  await page.getByText('尚无数据集。点击“创建数据集”开始整理资料。').waitFor();
  await page.getByRole('button', { name: '创建数据集', exact: true }).click();
  await page.getByRole('button', { name: '保存数据集' }).click();
  assert.equal(
    await page.locator('#dataset-name-error').textContent(),
    '名称不能为空。',
  );
  assert.equal(
    await page
      .locator('[role=alert]')
      .evaluate((node) => node === document.activeElement),
    true,
  );
  await page.getByLabel('名称 必填').fill('  浏览器数据集  ');
  await page.getByLabel('说明 选填').fill('真实界面创建的固定验收说明');
  await sample('createDataset', 'POST', () =>
    page.getByRole('button', { name: '保存数据集' }).click(),
  );
  await page
    .locator('.dataset-detail h2')
    .filter({ hasText: '浏览器数据集' })
    .waitFor();
  const firstPath = new URL(page.url()).hash.slice(1);
  assert.match(firstPath, /^\/datasets\/[0-9a-f-]{36}$/);
  assert.equal(
    await page.locator('.dataset-detail h2').textContent(),
    '浏览器数据集',
  );
  await page.getByText('还没有资料', { exact: true }).waitFor();
  await page.getByRole('button', { name: '修改名称与说明' }).click();
  await page.getByLabel('名称 必填').fill('浏览器已修改数据集');
  await sample('updateDataset', 'PATCH', () =>
    page.getByRole('button', { name: '保存数据集' }).click(),
  );
  await page
    .locator('.dataset-detail h2')
    .filter({ hasText: '浏览器已修改数据集' })
    .waitFor();
  assert.equal(
    await page.locator('.dataset-description').textContent(),
    '真实界面创建的固定验收说明',
  );
  await page.getByRole('button', { name: '创建数据集', exact: true }).click();
  await page.getByLabel('名称 必填').fill('另一个数据集');
  await page.getByRole('button', { name: '保存数据集' }).click();
  await page
    .locator('.dataset-detail h2')
    .filter({ hasText: '另一个数据集' })
    .waitFor();
  await page.getByRole('button', { name: '修改名称与说明' }).click();
  await page.getByLabel('名称 必填').fill('浏览器已修改数据集');
  await page.getByRole('button', { name: '保存数据集' }).click();
  await page
    .locator('#dataset-name-error')
    .filter({ hasText: /名称|存在|重复/ })
    .waitFor();
  assert.equal(
    await page.getByLabel('名称 必填').inputValue(),
    '浏览器已修改数据集',
  );
  await page.getByRole('button', { name: '取消', exact: true }).click();
  assert.equal(
    await page.locator('.dataset-detail h2').textContent(),
    '另一个数据集',
  );
  assert.equal(
    await page
      .getByRole('button', { name: '修改名称与说明' })
      .evaluate((node) => node === document.activeElement),
    true,
  );
  await page.waitForLoadState('networkidle');
  await sample('getDataset', 'GET', () => page.reload());
  await page
    .locator('.dataset-detail h2')
    .filter({ hasText: '另一个数据集' })
    .waitFor();
  await page.waitForLoadState('networkidle');
  await page.goto(baseURL + '/#' + firstPath);
  await page
    .locator('.dataset-detail h2')
    .filter({ hasText: '浏览器已修改数据集' })
    .waitFor();
  await page.getByRole('button', { name: '修改名称与说明' }).click();
  await page.getByLabel('说明 选填').fill('');
  await page.getByRole('button', { name: '保存数据集' }).click();
  await page
    .locator('.dataset-description')
    .filter({ hasText: '暂无说明' })
    .waitFor();
  await page.waitForLoadState('networkidle');
  await page.reload();
  await page
    .locator('.dataset-description')
    .filter({ hasText: '暂无说明' })
    .waitFor();
  assert.ok(
    observed.some(
      (item) =>
        item.method === 'POST' && item.status === 201 && item.hasProtection,
    ),
  );
  assert.ok(
    observed.some(
      (item) =>
        item.method === 'PATCH' && item.status === 200 && item.hasProtection,
    ),
  );
  assert.ok(
    observed.some(
      (item) =>
        item.method === 'PATCH' && item.status === 409 && item.hasProtection,
    ),
  );
  assert.equal(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
    0,
  );
  const initial = await page.context().request.get(baseURL + '/api/v1/session');
  const csrf = (await initial.json()).csrfToken;
  for (let index = 0; index < 20; index++) {
    const response = await page
      .context()
      .request.post(baseURL + '/api/v1/datasets', {
        headers: { 'X-CSRF-Token': csrf },
        data: {
          name: `分页验收${String(index).padStart(2, '0')}${index === 19 ? '长名称资料'.repeat(20) : ''}`,
        },
      });
    assert.equal(response.status(), 201);
  }
  await page.getByRole('button', { name: '刷新列表' }).click();
  await page.getByRole('link', { name: /分页验收19/ }).waitFor();
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  await page.getByText('第 2 页', { exact: true }).waitFor();
  await page.getByRole('link', { name: /浏览器已修改数据集/ }).waitFor();
  await page.getByRole('button', { name: '上一页', exact: true }).click();
  await page.getByText('第 1 页', { exact: true }).waitFor();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    assert.equal(
      await page
        .locator('.datasets-page')
        .evaluate((node) => node.scrollWidth <= node.clientWidth),
      true,
    );
    await page.screenshot({ path: `artifacts/live-datasets-${width}.png` });
    await page.getByRole('button', { name: '修改名称与说明' }).click();
    await page.getByLabel('名称 必填').fill('很长的数据集名称'.repeat(15));
    await page.getByLabel('说明 选填').fill('固定的长说明'.repeat(150));
    await page.screenshot({
      path: `artifacts/live-datasets-form-${width}.png`,
    });
    await page.getByRole('button', { name: '取消', exact: true }).click();
  }
  page.off('response', listener);
  tests.push({
    name: '真实数据集列表分页创建修改冲突及重新读取',
    passed: true,
    operationIds: [
      'listDatasets',
      'createDataset',
      'getDataset',
      'updateDataset',
    ],
    constraintIds: ['security.6', 'security.7'],
    requests: observed,
    assertions: [
      '真实Axios写请求附内存保护头',
      '空名字段错误聚焦摘要',
      '创建trim回显稳定UUID路由',
      '修改省略说明保持与空串清除',
      '重名409字段反馈且原记录不变',
      '刷新与详情重新读取',
      '真实20条分页与游标前后翻页',
      '三宽度列表与长表单截图',
      '浏览器无凭据持久化',
    ],
    samples: responses,
  });
}
