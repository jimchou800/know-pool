import { chromium, request } from '@playwright/test';
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { checkDatasets } from './check-datasets.mjs';
import { checkFiles } from './check-files.mjs';
import { checkDocumentPreviews } from './check-document-previews.mjs';

const baseURL = process.argv[2] || 'http://127.0.0.1:4173';
const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext();
const page = await context.newPage();
const requests = [];
const requestURLs = [];
const tests = [];
try {
  page.on('request', (outgoing) => {
    const path = new URL(outgoing.url()).pathname;
    if (path.startsWith('/api/')) {
      requestURLs.push(outgoing.url());
      requests.push({ method: outgoing.method(), path });
    }
  });
  await page.goto(baseURL);
  await page
    .getByRole('status')
    .filter({ hasText: '本机服务已连接' })
    .waitFor();
  assert.deepEqual(requests.slice(0, 2), [
    { method: 'GET', path: '/api/v1/session' },
    { method: 'GET', path: '/api/v1/system/status' },
  ]);
  assert.equal(await page.evaluate(() => location.search), '');
  assert.ok(requestURLs.every((url) => new URL(url).search === ''));
  const cookie = (await context.cookies()).find(
    (item) => item.name === 'KNOW_POOL_SESSION',
  );
  assert.ok(cookie);
  assert.equal(cookie.httpOnly, true);
  assert.equal(cookie.sameSite, 'Strict');
  assert.equal(cookie.path, '/');
  assert.equal(
    await page.evaluate(() => document.cookie.includes('KNOW_POOL_SESSION')),
    false,
  );
  assert.equal(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
    0,
  );
  tests.push({
    name: '真实浏览器会话与状态消费',
    passed: true,
    operationIds: ['initializeSession', 'getSystemStatus'],
    requests,
    assertions: [
      '真实后端同源链路',
      'Cookie仅HTTP访问且严格同站与根路径',
      '前端无持久化会话',
    ],
    constraintIds: [
      '#/paths/~1session/get/description',
      '#/paths/~1session/get/responses/200/headers/Set-Cookie/schema/type',
      'security.3',
    ],
  });
  const initialization = await context.request.get(baseURL + '/api/v1/session');
  assert.equal(initialization.status(), 200);
  const protection = (await initialization.json()).csrfToken;
  const blockedWrite = await context.request.post(
    baseURL + '/api/v1/system/status',
  );
  assert.equal(blockedWrite.status(), 403);
  assert.equal((await blockedWrite.json()).code, 'CSRF_REJECTED');
  const allowedProtection = await context.request.post(
    baseURL + '/api/v1/system/status',
    { headers: { 'X-CSRF-Token': protection } },
  );
  assert.equal(allowedProtection.status(), 404);
  assert.equal((await allowedProtection.json()).code, 'NOT_FOUND');
  tests.push({
    name: '真实会话写请求保护先于未实现业务路由',
    passed: true,
    operationIds: ['initializeSession', 'getSystemStatus'],
    assertions: [
      '有会话而无保护令牌返回CSRF_REJECTED',
      '正确保护令牌经过安全校验后返回未实现路由',
      '不记录令牌值',
    ],
    constraintIds: ['security.6'],
  });
  const anonymous = await request.newContext({ baseURL });
  try {
    const unauthorized = await anonymous.get('/api/v1/system/status');
    assert.equal(unauthorized.status(), 401);
    const rejected = await anonymous.get('/api/v1/session', {
      headers: { Origin: 'http://untrusted.invalid' },
    });
    assert.equal(rejected.status(), 403);
    tests.push({
      name: '真实后端拒绝无会话和非法来源',
      passed: true,
      operationIds: ['initializeSession', 'getSystemStatus'],
      assertions: ['无会话读取状态返回401', '非法Origin初始化返回403'],
      constraintIds: [
        '#/paths/~1session/get/responses/403',
        '#/paths/~1system~1status/get/responses/401',
        'security.2',
        'security.5',
      ],
    });
  } finally {
    await anonymous.dispose();
  }
  const reviewedSources = [
    '../server/src/main/resources/application.properties',
    '../server/src/main/java/cn/knowpool/system/api/LocalSessionFilter.java',
    '../server/src/test/java/cn/knowpool/LocalApplicationTest.java',
    '../server/src/test/java/cn/knowpool/system/api/LocalSessionFilterTest.java',
  ];
  const originalReports = [
    '../server/target/surefire-reports/TEST-cn.knowpool.LocalApplicationTest.xml',
    '../server/target/surefire-reports/TEST-cn.knowpool.system.api.LocalSessionFilterTest.xml',
  ];
  for (const path of originalReports) {
    const report = await readFile(path, 'utf8');
    const suite = report.match(/<testsuite\s[^>]*>/)?.[0];
    assert.ok(suite, '缺少后端原始测试套件');
    for (const attribute of ['failures', 'errors', 'skipped']) {
      assert.match(suite, new RegExp(`${attribute}="0"`));
    }
    assert.match(suite, /tests="[1-9]\d*"/);
  }
  const integrationReport = await readFile(originalReports[0], 'utf8');
  const filterReport = await readFile(originalReports[1], 'utf8');
  assert.ok(
    integrationReport.includes(
      'rejectUntrustedOriginsAndHostsAndAllowConfiguredOrigins',
    ),
  );
  assert.ok(
    integrationReport.includes('sessionAndPersistentStatusWorkOverHttp'),
  );
  assert.ok(
    filterReport.includes('renewExpiredSessionsAndBoundSessionCapacity'),
  );
  const sources = await Promise.all(
    reviewedSources.map(async (path) => ({
      path,
      sha256: createHash('sha256')
        .update(await readFile(path))
        .digest('hex'),
    })),
  );
  tests.push({
    name: '前端独立审阅后端回环主机、安全Cookie与日志保护证据',
    passed: true,
    operationIds: ['initializeSession', 'getSystemStatus'],
    constraintIds: ['security.1', 'security.4', 'security.7'],
    source: {
      actor: '/root/frontend04',
      files: sources,
      originalReports,
    },
    assertions: [
      '前端代理已独立审阅server.address回环配置及真实Socket伪Host拒绝断言',
      '前端代理已独立审阅secure请求过滤器测试；未冒称真实TLS浏览器部署',
      '前端代理已独立审阅实际会话令牌和标识不进入测试日志的断言',
      '关联后端原始测试结果零失败、零错误、零跳过；源码摘要绑定当前实现快照',
      '浏览器实际请求不含令牌URL且浏览器持久化为空；报告不输出真实令牌',
    ],
  });
  await mkdir('artifacts', { recursive: true });
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({ path: `artifacts/live-home-${width}.png` });
  }
  await checkDatasets(page, baseURL, tests);
  await checkFiles(page, baseURL, tests);
  await checkDocumentPreviews(page, tests);
  await writeFile(
    'artifacts/contract-browser.json',
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        contractSha256: createHash('sha256')
          .update(await readFile('../docs/agents/api/openapi.yaml'))
          .digest('hex'),
        implementationSha256: execFileSync(
          'python',
          ['../scripts/contract/verify.py', '--snapshot'],
          { encoding: 'utf8' },
        ).trim(),
        tests,
      },
      null,
      2,
    ),
  );
  console.log('真实浏览器联调通过；截图与脱敏证据已写入 artifacts。');
} finally {
  await context.close();
  await browser.close();
}
