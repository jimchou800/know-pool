export function releaseOptions(args) {
  const baseURL = args[0]?.replace(/\/$/, '');
  const channel = args[1] || 'msedge';
  if (!baseURL || !['msedge', 'chrome'].includes(channel))
    throw new Error('请提供实际发行服务地址及 msedge 或 chrome 浏览器名称。');
  let releaseURL;
  try {
    releaseURL = new URL(baseURL);
  } catch {
    throw new Error('发行验证服务地址不是有效网址。');
  }
  if (
    !['http:', 'https:'].includes(releaseURL.protocol) ||
    !['127.0.0.1', 'localhost', '[::1]'].includes(releaseURL.hostname) ||
    releaseURL.pathname !== '/' ||
    releaseURL.search ||
    releaseURL.hash ||
    releaseURL.username ||
    releaseURL.password
  )
    throw new Error('发行验证须使用明确的本机根服务地址。');
  return { baseURL, channel, origin: releaseURL.origin };
}
