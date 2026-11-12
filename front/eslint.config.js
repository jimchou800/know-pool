import js from '@eslint/js';
import vue from 'eslint-plugin-vue';
import globals from 'globals';
import prettier from 'eslint-config-prettier';

export default [
  { ignores: ['dist/**', 'coverage/**'] },
  js.configs.recommended,
  ...vue.configs['flat/recommended'],
  {
    files: ['**/*.js', '**/*.vue'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: { 'vue/multi-word-component-names': 'off' },
  },
  prettier,
];
