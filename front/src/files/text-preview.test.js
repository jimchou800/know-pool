import { expect, it } from 'vitest';
import { renderMarkdown, sourceLines } from './text-preview';

it('源行去首BOM并统一三种换行且保留末尾空行', () => {
  expect(sourceLines('\ufeff甲\r\n乙\r丙\n')).toEqual(['甲', '乙', '丙', '']);
});
it('Markdown保留结构且不产生脚本、图片或可导航链接', () => {
  const node = document.createElement('div');
  node.innerHTML = renderMarkdown(
    '# 中文标题\n\n|甲|乙|\n|-|-|\n|一|二|\n\n<script>alert(1)</script>\n\n![图片](https://evil.invalid/a)\n\n[链接](javascript:alert(1))\n\n[外链](https://evil.invalid/)\n\n<img src=x onerror=alert(1)>',
  );
  expect(node.querySelector('h1').textContent).toBe('中文标题');
  expect(node.querySelector('table')).not.toBeNull();
  expect(
    node.querySelector('script,img,a,iframe,svg,[href],[src],[onerror]'),
  ).toBeNull();
  expect(node.textContent).toContain('图片未加载');
});
