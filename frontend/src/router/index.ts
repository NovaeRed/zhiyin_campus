import { createRouter, createWebHistory } from 'vue-router';
import { useAuth } from '../composables/auth';
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('../pages/Chat.vue'), meta: { authenticated: true } },
    {
      path: '/history',
      component: () => import('../pages/History.vue'),
      meta: { authenticated: true },
    },
    {
      path: '/admin/documents',
      component: () => import('../pages/Documents.vue'),
      meta: { authenticated: true, manager: true },
    },
    { path: '/login', component: () => import('../pages/Auth.vue') },
    { path: '/register', component: () => import('../pages/Auth.vue'), props: { register: true } },
    { path: '/:pathMatch(.*)*', component: () => import('../pages/NotFound.vue') },
  ],
});
router.beforeEach(async (to) => {
  if (to.meta.authenticated) {
    if (!localStorage.getItem('token')) return '/login';
    await useAuth().reload();
    if (!localStorage.getItem('token')) return '/login';
  }
});
window.addEventListener('account-expired', () => {
  if (router.currentRoute.value.meta.authenticated) void router.replace('/login');
});
export default router;
