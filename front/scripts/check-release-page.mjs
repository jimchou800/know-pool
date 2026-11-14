import assert from 'node:assert/strict';

export async function checkReleasePage(page, tests, baseURL, channel) {
  const origin = new URL(baseURL).origin;
  const response = await page.context().request.get(baseURL);
  assert.equal(response.status(), 200);
  const html = await response.text();
  assert.equal(
    /@vite\/client|\/src\/main\.|localhost:(?:5173|4173)|127\.0\.0\.1:(?:5173|4173)/.test(
      html,
    ),
    false,
    '发行页面不能依赖开发客户端或源码入口',
  );
  const assets = await page
    .locator('script[src],link[rel="stylesheet"][href]')
    .evaluateAll((elements) =>
      elements.map((element) => element.src || element.href),
    );
  assert.ok(assets.length >= 2);
  for (const asset of assets) {
    const url = new URL(asset);
    assert.equal(url.origin, origin);
    assert.ok(url.pathname.startsWith('/assets/'));
    const result = await page.context().request.get(asset);
    assert.equal(result.status(), 200);
    assert.ok((await result.body()).length > 0);
    assert.equal(
      (await result.text()).startsWith('<!doctype html>'),
      false,
      '静态资源不能返回首页冒充成功',
    );
  }
  for (const [route, title] of [
    ['/datasets', '数据集'],
    ['/knowledge-bases', '知识库'],
    ['/settings', '设置'],
    ['/search', '知识检索'],
    ['/answers', '知识问答'],
  ]) {
    await page.goto(`${baseURL}/#${route}`);
    await page.reload();
    await page
      .getByRole('status')
      .filter({ hasText: '本机服务已连接' })
      .waitFor();
    assert.equal(new URL(page.url()).origin, origin);
    assert.equal(new URL(page.url()).hash, `#${route}`);
    assert.equal(await page.locator('.navigation').isVisible(), true);
    await page.getByRole('heading', { name: title, exact: true }).waitFor();
  }
  await page.goto(`${baseURL}/#/settings`);
  await page.getByRole('heading', { name: '设置', exact: true }).waitFor();
  await page.getByRole('link', { name: '数据集', exact: true }).click();
  await page.getByRole('heading', { name: '数据集', exact: true }).waitFor();
  await page.goBack();
  await page.getByRole('heading', { name: '设置', exact: true }).waitFor();
  await page.goForward();
  await page.getByRole('heading', { name: '数据集', exact: true }).waitFor();
  await page.goto(`${baseURL}/`);
  await page
    .getByRole('heading', { name: '欢迎回到知池', exact: true })
    .waitFor();
  await page.locator('.brand').focus();
  await page.keyboard.press('Tab');
  assert.equal(
    await page
      .getByRole('link', { name: '首页', exact: true })
      .evaluate((node) => node === document.activeElement),
    true,
  );
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await page.getByRole('heading', { name: '数据集', exact: true }).waitFor();
  await page.locator('.settings-link').focus();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    assert.equal(
      await page.locator('.settings-link').evaluate((node) => {
        const style = getComputedStyle(node);
        return (
          node === document.activeElement &&
          style.outlineStyle !== 'none' &&
          parseFloat(style.outlineWidth) > 0
        );
      }),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-release-navigation-${width}.png`,
    });
  }
  tests.push({
    name: `正式发行页面资源导航键盘及三宽：${channel}`,
    passed: true,
    operationIds: ['initializeSession', 'getSystemStatus'],
    assertions: [
      '已打包静态脚本及样式真实同源读取，无开发客户端和源码入口',
      '正式hash深层页面直接打开刷新可用，返回前进保留路由；真实业务流程继续复用同一发行页面',
      '键盘Tab及Enter导航可用且焦点可见，三宽截图无横向溢出',
    ],
  });
  await page.goto(baseURL);
  await page
    .getByRole('heading', { name: '欢迎回到知池', exact: true })
    .waitFor();
}
