import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from './router'

const http = axios.create({ baseURL: '/prod-api', timeout: 300000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('zhcp_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use((res) => {
  const data = res.data
  if (data && typeof data.code === 'number' && data.code !== 200) {
    if (data.code === 401) {
      localStorage.removeItem('zhcp_token')
      router.push('/login')
    }
    ElMessage.error(data.msg || '请求失败')
    return Promise.reject(new Error(data.msg || '请求失败'))
  }
  return data
}, (err) => {
  ElMessage.error(err.message || '网络异常')
  return Promise.reject(err)
})

export const login = (role, username, password) => http.post('/login', { role, username, password })
export const getInfo = () => http.get('/getInfo')
export const classTable = () => http.get('/zhcp/class')
export const scoreDetail = (id) => http.get(`/zhcp/score/${id}`)
export const mine = () => http.get('/zhcp/mine')
export const updateScore = (id, body) => http.put(`/zhcp/score/${id}`, body)
export const confirmItem = (id, ok) => http.post(`/zhcp/item/${id}/confirm`, { ok })
export const uploadMine = (file) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/zhcp/upload', form)
}
export const ruleList = () => http.get('/zhcp/rules')
export const ruleDetail = (id) => http.get(`/zhcp/rules/${id}`)
export const uploadRule = (file) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/zhcp/rules/upload', form)
}
export const updateRule = (id, body) => http.put(`/zhcp/rules/${id}`, body)
export const confirmRule = (id) => http.post(`/zhcp/rules/${id}/confirm`)
export const fileUrl = (id) => `/prod-api/zhcp/file/${id}?token=${localStorage.getItem('zhcp_token') || ''}`
export const exportUrl = '/prod-api/zhcp/export'
export default http
