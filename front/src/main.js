import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createWebHashHistory } from 'vue-router';
import Workbench from './system/Workbench.vue';
import './style.css';
import './system/workbench.css';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
});
createApp(Workbench).use(createPinia()).use(router).mount('#app');
