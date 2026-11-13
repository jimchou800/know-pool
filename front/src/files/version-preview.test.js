import { expect, it, vi } from 'vitest';
import { createFileClient } from './client';
const fileId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const versionId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
it.each([
  'text/plain; charset=UTF-8',
  'text/markdown; charset=UTF-8',
  'application/pdf',
])('指定版本安全预览实际请求目标版本而非最新：%s', async (mediaType) => {
  const response = {
    status: 200,
    data: new ArrayBuffer(4),
    headers: {
      'content-type': mediaType,
      'content-disposition': "inline; filename*=UTF-8''%E6%97%A7%E7%89%88",
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
      'content-security-policy': "sandbox; default-src 'none'",
    },
  };
  const request = vi.fn(async () => response);
  const api = createFileClient({ request });
  expect(
    await api.previewVersion(fileId.toUpperCase(), versionId.toUpperCase()),
  ).toEqual({ bytes: response.data, mediaType: mediaType.split(';')[0] });
  expect(request).toHaveBeenCalledWith({
    method: 'get',
    url: `/files/${fileId}/versions/${versionId}/preview`,
    responseType: 'arraybuffer',
    rawResponse: true,
  });
  await expect(api.previewVersion(fileId, '../错误')).rejects.toThrow(
    '标识无效',
  );
  expect(request).toHaveBeenCalledTimes(1);
});
