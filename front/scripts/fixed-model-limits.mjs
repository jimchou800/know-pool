import assert from 'node:assert/strict';

export async function configureFixedModelLimits(http, base, entries, headers) {
  for (const entry of entries) {
    const endpoint = new URL(entry.endpoint);
    assert.ok(
      ['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname) &&
        endpoint.port,
      '只能为本次已创建的本机模型替身显式配置固定配额',
    );
  }
  if (!headers) {
    const session = await (await http.get(`${base}/api/v1/session`)).json();
    headers = { 'X-CSRF-Token': session.csrfToken };
  }
  const response = await http.get(`${base}/api/v1/settings/runtime`);
  assert.equal(response.status(), 200);
  const value = await response.json();
  for (const entry of entries) {
    value.modelRateLimits = value.modelRateLimits.filter(
      (item) =>
        item.endpoint.replace(/\/+$/, '') !==
          entry.endpoint.replace(/\/+$/, '') || item.model !== entry.model,
    );
    value.modelRateLimits.push({
      ...entry,
      maxConcurrent: 4,
      requestsPerMinute: 10000,
      tokensPerMinute: 10000000,
    });
  }
  const saved = await http.put(`${base}/api/v1/settings/runtime`, {
    headers,
    data: value,
  });
  assert.equal(saved.status(), 200);
}
