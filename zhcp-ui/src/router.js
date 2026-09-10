import { createRouter, createWebHashHistory } from 'vue-router'
import Login from './views/Login.vue'
import Layout from './views/Layout.vue'
import Table from './views/Table.vue'
import Review from './views/Review.vue'
import Mine from './views/Mine.vue'
import Rules from './views/Rules.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: Login },
    {
      path: '/',
      component: Layout,
      redirect: '/table',
      children: [
        { path: 'table', component: Table },
        { path: 'review/:rosterId', component: Review },
        { path: 'mine', component: Mine },
        { path: 'rules', component: Rules }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('zhcp_token')) return '/login'
})

export default router
