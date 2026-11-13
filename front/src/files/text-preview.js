import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';

const parser = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false,
});
parser.renderer.rules.image = (tokens, index) =>
  parser.utils.escapeHtml(`【图片未加载：${tokens[index].content}】`);
parser.renderer.rules.link_open = () => '';
parser.renderer.rules.link_close = () => '';
export function sourceLines(text) {
  return text
    .replace(/^\ufeff/, '')
    .replace(/\r\n?/g, '\n')
    .split('\n');
}
export function renderMarkdown(text) {
  if (text.length > 200_000)
    throw new Error(
      'Markdown 超过预览资源限制（20万字符），请下载原文件查看。',
    );
  return DOMPurify.sanitize(parser.render(text), {
    ALLOWED_TAGS: [
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'p',
      'br',
      'hr',
      'strong',
      'em',
      's',
      'blockquote',
      'ul',
      'ol',
      'li',
      'pre',
      'code',
      'table',
      'thead',
      'tbody',
      'tr',
      'th',
      'td',
    ],
    ALLOWED_ATTR: ['start', 'colspan', 'rowspan'],
  });
}
