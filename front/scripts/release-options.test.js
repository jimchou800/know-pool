import { expect, it } from 'vitest';
import { releaseOptions } from './release-options';
it('发行服务使用实际本机端口且允许两种明确浏览器', () => {
  expect(releaseOptions(['http://127.0.0.1:49221/', 'chrome'])).toEqual({
    baseURL: 'http://127.0.0.1:49221',
    channel: 'chrome',
    origin: 'http://127.0.0.1:49221',
  });
  expect(releaseOptions(['http://localhost:49222']).channel).toBe('msedge');
  expect(releaseOptions(['http://[::1]:49223', 'msedge']).channel).toBe(
    'msedge',
  );
});
it('不隐式连接开发服务，拒绝远程、路径和敏感地址及未知浏览器', () => {
  for (const args of [
    [],
    ['http://127.0.0.1:49221', 'firefox'],
    ['https://example.com'],
    ['http://127.0.0.1/path'],
    ['http://user:secret@localhost'],
    ['http://localhost/?token=secret'],
    ['http://localhost/#/settings'],
    ['file:///index.html'],
  ])
    expect(() => releaseOptions(args)).toThrow();
});
