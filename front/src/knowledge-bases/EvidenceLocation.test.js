import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import EvidenceLocation from './EvidenceLocation.vue';
import { evidence } from '../../test-fixtures/indexing';

it('不适用的结构和半组表格坐标不伪造为第一行或第一列', () => {
  const wrapper = mount(EvidenceLocation, {
    props: {
      location: { ...evidence.location, tableRowStart: 0, tableColumnStart: 0 },
    },
  });
  expect(wrapper.text()).not.toContain('表格');
  expect(wrapper.text()).not.toContain('段落：');
  expect(wrapper.text()).not.toContain('代码块：');
  wrapper.unmount();
});
it('表格零起坐标以一开始展示并说明表头，保留代码及真实结构', () => {
  const wrapper = mount(EvidenceLocation, {
    props: {
      location: {
        ...evidence.location,
        titlePath: ['指南', '表格'],
        paragraphId: 'paragraph-1',
        structureId: 'table-1',
        continuationIndex: 0,
        tableRowStart: 0,
        tableRowEnd: 1,
        tableColumnStart: 0,
        tableColumnEnd: 1,
        codeBlockId: 'code-1',
        codeLanguage: 'java',
      },
    },
  });
  expect(wrapper.text()).toContain('指南 / 表格');
  expect(wrapper.text()).toContain('表格第1至2行（表头为第1行）');
  expect(wrapper.text()).toContain('表格第1至2列');
  expect(wrapper.text()).toContain('结构第1片');
  expect(wrapper.text()).toContain('语言：java');
  wrapper.unmount();
});
