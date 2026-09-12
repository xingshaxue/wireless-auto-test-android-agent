import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
    {
      path: '/',
      component: () => import('./layout/MainLayout.vue'),
      children: [
        { path: '', name: 'dashboard', component: () => import('./views/DashboardView.vue') },
        { path: 'agents', name: 'agents', component: () => import('./views/AgentsView.vue') },
        { path: 'devices', name: 'devices', component: () => import('./views/DevicesView.vue') },
        { path: 'files', name: 'files', component: () => import('./views/FilesView.vue') },
        { path: 'tests', name: 'tests', component: () => import('./views/TestsView.vue') },
        { path: 'events', name: 'events', component: () => import('./views/EventsView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.isLoggedIn && to.path !== '/login') {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (auth.isLoggedIn && to.path === '/login') {
    return { path: '/' }
  }
  return true
})

export default router
