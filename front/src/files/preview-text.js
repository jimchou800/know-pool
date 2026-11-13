export function decodePreviewText(bytes, recovery = '请下载原文件查看。') {
  if (bytes.byteLength > 800_000)
    throw new Error(`文本超过预览资源限制，${recovery}`);
  const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  if (text.includes('\u0000'))
    throw new Error(`资料不是有效的纯文本，${recovery}`);
  if (text.length > 200_000 || text.split(/\r\n|\r|\n/).length > 10_000)
    throw new Error(`文本超过预览资源限制（20万字符或1万行），${recovery}`);
  return text;
}
