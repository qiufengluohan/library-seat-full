import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/components/Layout.vue'
import { TOKEN_KEY } from '@/utils/request'

/**
 * 页面集合依据《四端统一最终方案》§30，只保留六个：
 * 登录 / Dashboard / 座位管理 / 设备管理 / RFID管理 / 违规与统计。
 *
 * 已按方案 §41 移除：多管理员、RBAC、复杂权限树、复杂热力图、Excel/PDF 导出、
 * 用户画像、复杂年度报表、拖拽式全馆布局。
 */
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    meta: { requiresAuth: true },
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '实时大屏', icon: 'DataAnalysis' }
      },
      {
        path: 'seats',
        name: 'SeatManage',
        component: () => import('@/views/SeatManage.vue'),
        meta: { title: '座位管理', icon: 'Grid' }
      },
      {
        path: 'devices',
        name: 'DeviceManage',
        component: () => import('@/views/DeviceManage.vue'),
        meta: { title: '设备管理', icon: 'Cpu' }
      },
      {
        path: 'rfid',
        name: 'RfidManage',
        component: () => import('@/views/RfidManage.vue'),
        meta: { title: 'RFID管理', icon: 'Postcard' }
      },
      {
        path: 'violations',
        name: 'ViolationManage',
        component: () => import('@/views/ViolationManage.vue'),
        meta: { title: '违规与统计', icon: 'Warning' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem(TOKEN_KEY)

  if (to.meta.requiresAuth !== false && !token) {
    return { path: '/login' }
  }
  if (to.path === '/login' && token) {
    return { path: '/dashboard' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 智慧图书馆管理后台` : '智慧图书馆管理后台'
})

export default router
