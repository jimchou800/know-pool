import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

export async function checkDocumentIndex(
  page,
  tests,
  { http, base, headers, datasetId, knowledgeBaseId },
) {
  const management = page.getByRole('region', {
    name: '单库检索测试',
    exact: true,
  });
  for (const [name, mediaType, format] of [
    ['source-location.md', 'text/markdown', 'markdown'],
    ['chinese-cross-page.pdf', 'application/pdf', 'pdf'],
  ]) {
    const buffer = await readFile(
      new URL(
        `../../server/src/test/resources/documents/${name}`,
        import.meta.url,
      ),
    );
    const upload = await http.post(
      `${base}/api/v1/datasets/${datasetId}/files:upload`,
      {
        headers,
        multipart: { files: { name, mimeType: mediaType, buffer } },
      },
    );
    assert.equal(upload.status(), 200);
    const uploaded = (await upload.json()).items[0];
    assert.notEqual(uploaded.outcome, 'failed');
    const fileId = uploaded.file.id,
      versionId = uploaded.file.latestVersion.id;
    let indexed;
    for (let attempt = 0; attempt < 120; attempt++) {
      const response = await http.get(
        `${base}/api/v1/knowledge-bases/${knowledgeBaseId}/files?limit=100`,
      );
      assert.equal(response.status(), 200);
      indexed = (await response.json()).items.find(
        (item) => item.fileId === fileId,
      );
      if (indexed?.updateState === 'failed')
        assert.fail(indexed.failure?.message || '固定文档索引失败');
      if (indexed?.effectiveContentVersionId === versionId) break;
      await page.waitForTimeout(250);
    }
    assert.equal(indexed?.effectiveContentVersionId, versionId);
    await management
      .getByLabel('检索内容', { exact: true })
      .fill(format === 'pdf' ? '跨页中文句子' : '使用指南');
    await management
      .getByRole('button', { name: '执行单库检索', exact: true })
      .click();
    const result = management
      .locator('.knowledge-files > li')
      .filter({ has: page.getByText(name, { exact: true }) })
      .first();
    await result
      .getByRole('button', { name: '查看原文依据', exact: true })
      .waitFor();
    await result
      .getByRole('button', { name: '查看原文依据', exact: true })
      .click();
    const evidence = management.getByRole('region', {
      name: '原文依据',
      exact: true,
    });
    await evidence.waitFor();
    assert.ok((await evidence.innerText()).includes(versionId));
    const responsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `/api/v1/files/${fileId}/versions/${versionId}/preview`,
    );
    await evidence
      .getByRole('button', { name: '打开对应版本原文', exact: true })
      .click();
    const response = await responsePromise;
    assert.equal(response.status(), 200);
    assert.equal(response.headers()['cache-control'], 'no-store');
    const preview = evidence.getByRole('region', {
      name: '对应版本原文',
      exact: true,
    });
    if (format === 'pdf') {
      assert.ok((await evidence.innerText()).includes('第1至2页'));
      await preview.getByText('第 1 页，共 2 页', { exact: true }).waitFor();
      assert.equal(
        await preview.getByLabel('页码', { exact: true }).inputValue(),
        '1',
      );
      await preview.locator('[data-action=next-pdf]').click();
      await preview.getByText('第 2 页，共 2 页', { exact: true }).waitFor();
    } else {
      await preview.locator('.located-line').waitFor();
      assert.ok((await preview.innerText()).includes('第一段😀。'));
      assert.ok((await evidence.innerText()).includes('使用指南'));
    }
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await preview.scrollIntoViewIfNeeded();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: `artifacts/live-document-index-${format}-${width}.png`,
      });
    }
    await preview
      .getByRole('button', { name: '关闭原文预览', exact: true })
      .click();
    assert.equal(
      await evidence
        .getByRole('button', { name: '打开对应版本原文', exact: true })
        .evaluate((node) => document.activeElement === node),
      true,
    );
    await evidence
      .getByRole('button', { name: '关闭原文依据', exact: true })
      .click();
  }
  tests.push({
    name: '真实文档索引证据与指定版本原文定位',
    passed: true,
    operationIds: [
      'uploadFiles',
      'listKnowledgeBaseFiles',
      'testKnowledgeBaseSearch',
      'getSearchRequest',
      'getSearchEvidence',
      'previewVersionFile',
    ],
    constraintIds: [
      '#/paths/~1files~1{fileId}~1versions~1{contentVersionId}~1preview/get/description',
    ],
    assertions: [
      '固定Markdown标题源行及两页中文PDF经真实索引、检索、证据和指定版本原文联通，三种宽度保留截图；模型仅本机替身。',
    ],
  });
}
