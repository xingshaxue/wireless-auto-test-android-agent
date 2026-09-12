import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
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

export default router
