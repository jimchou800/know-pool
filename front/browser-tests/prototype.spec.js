import { test, expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';

const themes = ['紧凑高效', '宽松简洁', '阅读友好'];
for (const [index, theme] of themes.entries()) {
  for (const width of [1280, 1440, 1920]) {
    test(`${theme}在${width}宽度下首页及引用抽屉可用`, async ({ page }) => {
      const errors = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await page.setViewportSize({ width, height: 1000 });
      await page.goto('/');
      await page.getByRole('button', { name: `${index + 1}${theme}` }).click();
      await expect(
        page.getByRole('heading', { name: '欢迎回到知池' }),
      ).toBeVisible();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      await mkdir('artifacts/previews', { recursive: true });
      if (width === 1440)
        await page.screenshot({
          path: `artifacts/previews/${index + 1}-home.png`,
        });
      await page.locator('.recent-item').first().click();
      await page.locator('.citation').click();
      await expect(
        page.getByRole('complementary', { name: '原文依据' }),
      ).toContainText('内容版本 3');
      await expect(
        page.getByRole('button', { name: '关闭原文依据' }),
      ).toBeFocused();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
      ).toBe(true);
      if (width === 1440)
        await page.screenshot({
          path: `artifacts/previews/${index + 1}-chat.png`,
        });
      await page.keyboard.press('Escape');
      await expect(page.locator('.citation')).toBeFocused();
      expect(errors).toEqual([]);
    });
  }
}

test('新建问答校验前不展示回答，切换后后台继续并保存提交记录', async ({
  page,
}) => {
  await page.goto('/');
  await page
    .getByRole('button', { name: '新建问答 带着问题，找到依据' })
    .click();
  await page.reload();
  await expect(page.locator('.recent-item')).toHaveCount(3);
  await page
    .getByRole('button', { name: '新建问答 带着问题，找到依据' })
    .click();
  await page
    .getByRole('textbox', { name: '输入问题' })
    .fill('<img src=x onerror=alert(1)>');
  await page.getByRole('button', { name: '发送问题' }).click();
  await expect(page.locator('.answer')).toHaveCount(0);
  await expect(page.getByRole('status')).toContainText('正在');
  await page.locator('.session-item').nth(1).click();
  await expect(page.locator('.session-item').first()).toContainText('已完成', {
    timeout: 5000,
  });
  await page.locator('.session-item').first().click();
  await expect(page.locator('.answer')).toBeVisible();
  await expect(page.locator('.question img')).toHaveCount(0);
  await page.reload();
  await expect(page.locator('.recent-item')).toHaveCount(4);
  await page.locator('.recent-item').first().click();
  await expect(page.locator('.question')).toContainText('<img');
});

test('范围提示将焦点留在弹窗内，退出后回到触发按钮', async ({ page }) => {
  await page.goto('/');
  const trigger = page.getByRole('button', {
    name: '数据集 导入与管理原始资料',
  });
  await trigger.click();
  const close = page.getByRole('button', { name: '知道了' });
  await expect(close).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(close).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(close).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
});
