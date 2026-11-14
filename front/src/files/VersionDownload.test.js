import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import VersionDownload from './VersionDownload.vue';
import { id, otherId } from '../../test-fixtures/knowledge-bases';
const originalUrl = URL;
beforeEach(() => {
  class TestURL extends originalUrl {}
  TestURL.createObjectURL = vi.fn();
  TestURL.revokeObjectURL = vi.fn();
  vi.stubGlobal('URL', TestURL);
});
afterEach(() => vi.unstubAllGlobals());
const response = () => ({
  status: 200,
  data: new ArrayBuffer(1),
  headers: {
    'content-type': 'text/plain;charset=UTF-8',
    'content-disposition': "attachment; filename*=UTF-8''old.txt",
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  },
});
it('指定版本使用安全响应文件名下载并释放临时地址', async () => {
  const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fixed');
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  const click = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(function () {
      expect(this.download).toBe('old.txt');
    });
  const request = vi.fn(async () => response());
  const wrapper = mount(VersionDownload, {
    props: { client: { request }, fileId: id, versionId: otherId },
  });
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(request.mock.calls[0][0].url).toBe(
    `/files/${id}/versions/${otherId}/download`,
  );
  expect(revoke).toHaveBeenCalledWith('blob:fixed');
  wrapper.unmount();
  create.mockRestore();
  revoke.mockRestore();
  click.mockRestore();
});
it('不可用版本不退回最新，迟到响应不下载', async () => {
  const request = vi.fn().mockRejectedValue({ code: 'CONTENT_UNAVAILABLE' });
  const wrapper = mount(VersionDownload, {
    props: { client: { request }, fileId: id, versionId: otherId },
  });
  await wrapper.get('button').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('不会改为下载最新正文');
  expect(request).toHaveBeenCalledTimes(1);
  wrapper.unmount();
  let resolve;
  const create = vi.spyOn(URL, 'createObjectURL');
  const late = mount(VersionDownload, {
    props: {
      fileId: id,
      versionId: otherId,
      client: {
        request: () =>
          new Promise((done) => {
            resolve = done;
          }),
      },
    },
  });
  await late.get('button').trigger('click');
  late.unmount();
  resolve(response());
  await flushPromises();
  expect(create).not.toHaveBeenCalled();
  create.mockRestore();
});
