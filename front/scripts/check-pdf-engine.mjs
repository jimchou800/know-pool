import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname } from 'node:path';
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs';
const require = createRequire(import.meta.url);
const pdfRoot = dirname(require.resolve('pdfjs-dist/package.json'));
const { createCanvas } = createRequire(
  require.resolve('pdfjs-dist/package.json'),
)('@napi-rs/canvas');
const bytes = new Uint8Array(
  await readFile('../server/src/test/resources/documents/oversized-image.pdf'),
);
for (const action of ['operators', 'render']) {
  const task = getDocument({
    data: bytes.slice(),
    maxImageSize: 4_000_000,
    stopAtErrors: true,
    cMapUrl: `${pdfRoot}/cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${pdfRoot}/standard_fonts/`,
  });
  try {
    const document = await task.promise;
    const page = await document.getPage(1);
    const viewport = page.getViewport({ scale: 1 });
    const operation =
      action === 'operators'
        ? page.getOperatorList()
        : page.render({
            canvas: createCanvas(viewport.width, viewport.height),
            viewport,
          }).promise;
    await assert.rejects(operation, /Image exceeded maximum allowed size/);
  } finally {
    await task.destroy();
  }
}
console.log('PDF 真实引擎资源拒绝已通过：操作列表与画布渲染均保留原错误。');
