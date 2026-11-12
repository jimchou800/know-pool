<script setup>
defineProps({ name: { type: String, default: 'chat' } });
const paths = {
  home: 'm3 10 9-7 9 7M5 9v12h14V9M9 21v-7h6v7',
  data: 'M4 6c0-4 16-4 16 0s-16 4-16 0Zm0 0v12c0 4 16 4 16 0V6M4 12c0 4 16 4 16 0',
  book: 'M4 4h7v16H4V4Zm9 0h7v16h-7V4ZM7 8h1m8 0h1M7 12h1m8 0h1',
  chat: 'M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-9l-6 4v-4H3V6a2 2 0 0 1 2-2ZM8 9h8M8 13h5',
  search: 'M20 20 15 15M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z',
  settings:
    'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2',
  plus: 'M12 5v14M5 12h14',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  close: 'm6 6 12 12M6 18 18 6',
  file: 'M5 3h9l5 5v13H5V3Zm9 0v6h5M8 13h8m-8 4h6',
  check: 'm5 12 4 4L19 6',
};
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="paths[name] || paths.chat" />
  </svg>
</template>
