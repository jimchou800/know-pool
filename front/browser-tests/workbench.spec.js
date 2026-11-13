import { test, expect } from '@playwright/test';

const session = {
  sessionId: '00000000-0000-4000-8000-000000000001',
  csrfToken: '固定测试令牌'.repeat(8),
  expiresAt: '2026-11-13T00:00:00Z',
};
const status = {
  applicationVersion: '0.1.0',
  startedAt: '2026-11-12T00:00:00Z',
  writeState: 'ready',
  dataDirectory: '测试数据目录',
  schemaVersion: 1,
  warnings: [],
};

for (const width of [1280, 1440, 1920]) {
  test(`正式首页在${width}宽度展示真实消费状态并保持导航可访问`, async ({
    page,
  }) => {
    await page.route('**/api/v1/session', (route) =>
      route.fulfill({ json: session }),
    );
    await page.route('**/api/v1/system/status', (route) =>
      route.fulfill({ json: status }),
    );
    await page.setViewportSize({ width, height: 1000 });
    await page.goto('/');
    await expect(page.getByRole('status')).toContainText('本机服务已连接');
    await expect(
      page.getByRole('heading', { name: '欢迎回到知池' }),
    ).toBeVisible();
    await expect(
      page.getByText('尚无问答记录。', { exact: false }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.keyboard.press('Tab');
    await expect(page.locator('.brand')).toBeFocused();
    await page.route('**/api/v1/datasets?*', (route) =>
      route.fulfill({ json: { items: [], nextCursor: null } }),
    );
    await page.getByRole('link', { name: '数据集 导入与管理原始资料' }).click();
    await expect(
      page.getByText('尚无数据集。点击“创建数据集”开始整理资料。'),
    ).toBeVisible();
    await page.locator('.brand').click();
    await expect(
      page.getByRole('heading', { name: '欢迎回到知池' }),
    ).toBeVisible();
  });
}

test('连接失败提供恢复操作，原始错误不展示', async ({ page }) => {
  await page.route('**/api/v1/session', (route) =>
    route.abort('connectionrefused'),
  );
  await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('暂时无法连接本机服务');
  await page.route('**/api/v1/session', (route) =>
    route.fulfill({ json: session }),
  );
  await page.route('**/api/v1/system/status', (route) =>
    route.fulfill({ json: status }),
  );
  await page.getByRole('button', { name: '重新连接' }).click();
  await expect(page.getByRole('status')).toContainText('本机服务已连接');
  await expect(page.getByRole('alert')).toHaveCount(0);
});
