<script setup>
import { computed, onMounted, ref } from 'vue'

function pageFromHash() {
  if (window.location.hash === '#/manage') return 'manage'
  if (window.location.hash === '#/admin') return 'admin'
  return 'home'
}

const page = ref(pageFromHash())
const code = ref('')
const lookup = ref(null)
const lookupError = ref('')
const selectedFile = ref(null)
const uploadProgress = ref(0)
const uploadBusy = ref(false)
const uploadCancelled = ref(false)
const uploadResult = ref(null)
const uploadError = ref('')
const transfers = ref([])
const listBusy = ref(false)
const listError = ref('')
const deletingKey = ref('')
const downloadingKey = ref('')
const toast = ref('')
const serviceStatus = ref('checking')
const capacity = ref({ used: 0, limit: 0, available: 0 })
const helpOpen = ref(false)
const helpTab = ref('web')
let toastTimer
let statusTimer

const AUTH_TOKEN_KEY = 'dropkey.auth.jwt'
const LEGACY_ADMIN_TOKEN_KEY = 'dropkey.admin.jwt'

function loadStoredToken() {
  const stored = localStorage.getItem(AUTH_TOKEN_KEY)
  if (stored) return stored
  // 旧版本只保存管理员令牌，升级后直接迁移，避免管理员需要重新登录。
  const legacy = localStorage.getItem(LEGACY_ADMIN_TOKEN_KEY)
  if (!legacy) return ''
  localStorage.setItem(AUTH_TOKEN_KEY, legacy)
  localStorage.removeItem(LEGACY_ADMIN_TOKEN_KEY)
  return legacy
}

const authToken = ref(loadStoredToken())
const authUsername = ref('')
const authRoles = ref([])
const authPermissions = ref([])
const authExpiresAt = ref('')
const authReady = ref(false)
const loginOpen = ref(false)
const loginUsername = ref('')
const loginPassword = ref('')
const loginBusy = ref(false)
const loginError = ref('')
const adminConfigured = ref(true)
const adminTab = ref('accounts')

const adminAccounts = ref([])
const adminListBusy = ref(false)
const adminListError = ref('')
const accountBusyId = ref(0)
const newAccountUsername = ref('')
const newAccountPassword = ref('')
const newAccountEnabled = ref(true)
const createAccountBusy = ref(false)
const createAccountError = ref('')
const passwordDialog = ref(null)

const permissionCatalog = ref([])
const catalogBusy = ref(false)
const catalogError = ref('')
const permissionDialog = ref(null)
const selectedAccountIds = ref([])
const selectedBulkPermissions = ref([])
const bulkBusy = ref(false)
const newPermissionCode = ref('')
const newPermissionName = ref('')
const newPermissionDescription = ref('')
const createPermissionBusy = ref(false)
const createPermissionError = ref('')
const deletingPermissionCode = ref('')

const isAuthenticated = computed(() => authReady.value && !!authToken.value)
const isAdmin = computed(() => authRoles.value.includes('ADMIN'))
const allAccountsSelected = computed(() =>
  adminAccounts.value.length > 0 && selectedAccountIds.value.length === adminAccounts.value.length)
const canBulkApply = computed(() =>
  !bulkBusy.value && selectedAccountIds.value.length > 0 && selectedBulkPermissions.value.length > 0)

const MAX_SIZE = 2147483647
const siteOrigin = window.location.origin

const capacityPercent = computed(() => {
  if (!capacity.value.limit) return 0
  return Math.min(100, Math.round((capacity.value.used / capacity.value.limit) * 100))
})
const capacityLow = computed(() => capacity.value.limit > 0 && capacityPercent.value >= 90)
const validCode = computed(() => /^[A-Z2-9]{6}$/.test(code.value.trim().toUpperCase()))

function navigate(next) {
  page.value = next
  window.location.hash = next === 'manage' ? '/manage' : next === 'admin' ? '/admin' : '/'
  if (next === 'manage') loadTransfers()
  if (next === 'admin') loadAdmin()
}

function onHashChange() {
  page.value = pageFromHash()
  if (page.value === 'manage') loadTransfers()
  if (page.value === 'admin') loadAdmin()
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++ }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`
}

function timeLeft(expiresAt) {
  const remaining = Math.max(0, new Date(expiresAt).getTime() - Date.now())
  const minutes = Math.ceil(remaining / 60000)
  return minutes > 0 ? `${minutes} 分钟后过期` : '即将过期'
}

function chooseFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  uploadError.value = ''
  uploadResult.value = null
  if (file.size > MAX_SIZE) {
    uploadError.value = '文件必须小于 2 GiB。'
    return
  }
  if (capacity.value.limit && file.size > capacity.value.available) {
    uploadError.value = `共享空间不足：当前剩余 ${formatSize(capacity.value.available)}，放不下这个文件。`
    return
  }
  selectedFile.value = file
}

async function loadCapacity() {
  try {
    const response = await fetch('/api/transfers/capacity')
    if (!response.ok) return
    capacity.value = await response.json()
  } catch {
    // 容量展示属于增强信息，失败时保持界面可用。
  }
}

function dropFile(event) {
  const file = event.dataTransfer.files?.[0]
  if (!file) return
  const input = document.querySelector('#file-picker')
  const transfer = new DataTransfer()
  transfer.items.add(file)
  input.files = transfer.files
  chooseFile({ target: input })
}

let uploadXhr = null

function cancelUpload() {
  if (!uploadXhr) return
  uploadCancelled.value = true
  uploadXhr.abort()
}

async function uploadFile() {
  if (!selectedFile.value || uploadBusy.value) return
  uploadBusy.value = true
  uploadCancelled.value = false
  uploadProgress.value = 0
  uploadError.value = ''
  uploadResult.value = null
  const form = new FormData()
  form.append('file', selectedFile.value)
  try {
    const xhr = new XMLHttpRequest()
    uploadXhr = xhr
    const response = await new Promise((resolve, reject) => {
      xhr.open('POST', '/api/transfers')
      xhr.withCredentials = true
      if (authToken.value) xhr.setRequestHeader('Authorization', `Bearer ${authToken.value}`)
      xhr.upload.onprogress = event => {
        if (event.lengthComputable) uploadProgress.value = Math.round(event.loaded / event.total * 100)
      }
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) resolve(JSON.parse(xhr.responseText))
        else reject(new Error(readTransferError(xhr.responseText, xhr.status)))
      }
      xhr.onerror = () => reject(new Error('网络连接失败，请检查服务状态后重试。'))
      xhr.onabort = () => reject(new Error('上传已取消'))
      xhr.send(form)
    })
    uploadResult.value = response
    selectedFile.value = null
    uploadProgress.value = 100
    notify('文件已上传')
    loadCapacity()
  } catch (error) {
    if (uploadCancelled.value) {
      uploadError.value = '上传已取消，服务器上没有留下任何文件。'
      uploadProgress.value = 0
    } else {
      uploadError.value = error.message || '上传失败，请稍后重试。'
    }
    loadCapacity()
  } finally {
    uploadXhr = null
    uploadBusy.value = false
  }
}

async function findTransfer() {
  if (!validCode.value) return
  lookupError.value = ''
  lookup.value = null
  const key = code.value.trim().toUpperCase()
  try {
    const response = await transferFetch(`/api/transfers/${encodeURIComponent(key)}`)
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    lookup.value = await response.json()
  } catch (error) {
    lookupError.value = error.message || '没有找到此文件。'
  }
}

/**
 * 浏览器下载无法附加 Authorization 头，因此先换取一次性短时票据，
 * 再用隐藏链接触发下载。票据只对当前验证码有效，默认 60 秒过期。
 */
async function downloadTransfer(key) {
  if (downloadingKey.value) return
  downloadingKey.value = key
  try {
    const response = await transferFetch(`/api/transfers/${encodeURIComponent(key)}/download-ticket`, {
      method: 'POST',
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const ticket = await response.json()
    const link = document.createElement('a')
    link.href = ticket.url
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
  } catch (error) {
    notify(error.message || '下载失败，请重试')
  } finally {
    downloadingKey.value = ''
  }
}

async function loadTransfers() {
  listBusy.value = true
  listError.value = ''
  try {
    const response = await transferFetch('/api/transfers')
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    transfers.value = await response.json()
  } catch (error) {
    listError.value = error.message || '暂时无法加载文件列表。'
  } finally {
    listBusy.value = false
  }
}

async function deleteTransfer(item) {
  if (!window.confirm(`确定立即删除“${item.name}”吗？删除后无法恢复。`)) return
  deletingKey.value = item.key
  try {
    const response = await transferFetch(`/api/transfers/${encodeURIComponent(item.key)}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    transfers.value = transfers.value.filter(transfer => transfer.key !== item.key)
    if (uploadResult.value?.key === item.key) uploadResult.value = null
    notify('文件已删除')
    loadCapacity()
  } catch (error) {
    notify(error.message || '删除失败，请重试')
  } finally {
    deletingKey.value = ''
  }
}

async function copyCode(value) {
  await copyText(value, '验证码已复制')
}

async function copyText(value, message = '已复制') {
  try {
    await navigator.clipboard.writeText(value)
    notify(message)
  } catch {
    notify('无法访问剪贴板，请手动复制')
  }
}

function readApiError(body, status) {
  try {
    const data = JSON.parse(body)
    return data.message || data.error || (status === 404 ? '验证码无效或文件已过期。' : `请求失败（${status}）`)
  } catch {
    return status === 404 ? '验证码无效或文件已过期。' : `请求失败（${status}）`
  }
}

function notify(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 2600)
}

const serviceLabel = computed(() => ({
  checking: '检测中',
  up: '服务正常',
  down: '服务不可用',
}[serviceStatus.value]))

const cliCommands = computed(() => [
  {
    label: '上传文件',
    desc: '成功返回 6 位验证码。需要 transfer.upload 权限（未登录用户默认拥有）。-c 会保存浏览器归属 Cookie。',
    code: `curl -fS -X POST \\
  -F "file=@/path/to/file.zip" \\
  -c cookies.txt \\
  ${siteOrigin}/api/transfers`,
  },
  {
    label: '查询文件信息',
    desc: '只需验证码，不需要 Cookie。需要 transfer.query 权限（未登录用户默认拥有）。验证码错误或已过期返回 404。',
    code: `curl -fS ${siteOrigin}/api/transfers/ABCD23`,
  },
  {
    label: '下载文件',
    desc: '需要 transfer.download 权限（未登录用户默认拥有）。-J 使用服务端返回的原始文件名，-O 按该名称保存到当前目录。',
    code: `curl -fL -OJ ${siteOrigin}/api/transfers/ABCD23/download`,
  },
  {
    label: '列出自己上传的文件',
    desc: '需要 transfer.list 权限，并带上上传时保存的 Cookie，否则返回空列表。',
    code: `curl -fS -b cookies.txt ${siteOrigin}/api/transfers`,
  },
  {
    label: '立即删除文件',
    desc: '需要 transfer.delete 权限，且必须使用上传时的 Cookie。成功返回 204。',
    code: `curl -i -X DELETE -b cookies.txt \\
  ${siteOrigin}/api/transfers/ABCD23`,
  },
])

function formatDateTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false })
}

function applySession(data, token) {
  authToken.value = token
  authUsername.value = data.username || ''
  authRoles.value = data.roles || []
  authPermissions.value = data.permissions || []
  authExpiresAt.value = data.expiresAt || ''
  localStorage.setItem(AUTH_TOKEN_KEY, token)
}

function clearSession() {
  authToken.value = ''
  authUsername.value = ''
  authRoles.value = []
  authPermissions.value = []
  authExpiresAt.value = ''
  adminAccounts.value = []
  adminListError.value = ''
  permissionCatalog.value = []
  catalogError.value = ''
  selectedAccountIds.value = []
  selectedBulkPermissions.value = []
  newPermissionCode.value = ''
  newPermissionName.value = ''
  newPermissionDescription.value = ''
  createPermissionError.value = ''
  passwordDialog.value = null
  permissionDialog.value = null
  localStorage.removeItem(AUTH_TOKEN_KEY)
}

async function restoreSession() {
  if (!authToken.value) {
    authReady.value = true
    return
  }
  try {
    const response = await fetch('/api/auth/me', {
      headers: { Authorization: `Bearer ${authToken.value}` },
    })
    if (!response.ok) {
      clearSession()
      return
    }
    applySession(await response.json(), authToken.value)
  } catch {
    // 网络故障时不清除令牌，下次刷新会重试。
  } finally {
    authReady.value = true
  }
}

async function authFetch(path, options = {}) {
  const headers = new Headers(options.headers || {})
  if (authToken.value) headers.set('Authorization', `Bearer ${authToken.value}`)
  if (options.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...options, headers })
  if (response.status === 401) {
    clearSession()
    throw new Error('登录已过期，请重新登录。')
  }
  if (response.status === 403) {
    throw new Error('当前账号没有此操作的权限。')
  }
  return response
}

function permissionMessage() {
  return authToken.value
    ? '当前账号没有此操作的权限，请联系管理员。'
    : '未登录用户没有此操作的权限，请登录或联系管理员。'
}

/** 把传输接口的 401 / 403 转换为用户能理解的中文提示。 */
function readTransferError(body, status) {
  if (status === 401) {
    clearSession()
    return '登录已过期，请重新登录。'
  }
  if (status === 403) return permissionMessage()
  return readApiError(body, status)
}

/**
 * 文件传输接口统一入口：携带浏览器 owner Cookie 和当前登录令牌。
 * 401 表示登录已失效，403 表示当前身份（含未登录用户）没有对应功能权限。
 */
async function transferFetch(path, options = {}) {
  const headers = new Headers(options.headers || {})
  if (authToken.value) headers.set('Authorization', `Bearer ${authToken.value}`)
  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (response.status === 401) {
    clearSession()
    throw new Error('登录已过期，请重新登录。')
  }
  if (response.status === 403) throw new Error(permissionMessage())
  return response
}

function openLogin(prefill = '') {
  loginUsername.value = prefill
  loginError.value = ''
  loginOpen.value = true
}

async function submitLogin() {
  if (loginBusy.value) return
  loginBusy.value = true
  loginError.value = ''
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: loginUsername.value.trim(),
        password: loginPassword.value,
      }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const data = await response.json()
    applySession(data, data.token)
    authReady.value = true
    loginOpen.value = false
    loginPassword.value = ''
    notify(`已登录为 ${data.username}`)
    if (page.value === 'admin') await loadAdmin()
  } catch (error) {
    loginError.value = error.message || '登录失败，请稍后重试。'
  } finally {
    loginBusy.value = false
  }
}

function logout() {
  clearSession()
  loginPassword.value = ''
  loginError.value = ''
  if (page.value === 'admin') navigate('home')
  notify('已退出登录')
}

async function loadAdminConfig() {
  try {
    const response = await fetch('/api/admin/config')
    if (response.ok) adminConfigured.value = !!(await response.json()).configured
  } catch {
    // 配置接口失败时保持现有状态，登录时仍会得到明确错误。
  }
}

async function loadAdmin() {
  await loadAdminConfig()
  if (!authReady.value) await restoreSession()
  if (!isAdmin.value) return
  await Promise.all([loadAccounts(), loadCatalog()])
}

async function loadAccounts() {
  adminListBusy.value = true
  adminListError.value = ''
  try {
    const response = await authFetch('/api/admin/accounts')
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const data = await response.json()
    adminAccounts.value = data.accounts || []
    const ids = new Set(adminAccounts.value.map(account => account.id))
    selectedAccountIds.value = selectedAccountIds.value.filter(id => ids.has(id))
  } catch (error) {
    adminListError.value = error.message || '暂时无法加载账号列表。'
  } finally {
    adminListBusy.value = false
  }
}

async function loadCatalog() {
  catalogBusy.value = true
  catalogError.value = ''
  try {
    const response = await authFetch('/api/admin/permissions')
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const data = await response.json()
    permissionCatalog.value = data.permissions || []
    const codes = new Set(permissionCatalog.value.map(permission => permission.code))
    selectedBulkPermissions.value = selectedBulkPermissions.value.filter(code => codes.has(code))
  } catch (error) {
    catalogError.value = error.message || '暂时无法加载权限目录。'
  } finally {
    catalogBusy.value = false
  }
}

async function createPermission() {
  if (createPermissionBusy.value) return
  createPermissionBusy.value = true
  createPermissionError.value = ''
  try {
    const response = await authFetch('/api/admin/permissions', {
      method: 'POST',
      body: JSON.stringify({
        code: newPermissionCode.value.trim(),
        name: newPermissionName.value.trim(),
        description: newPermissionDescription.value.trim(),
      }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    newPermissionCode.value = ''
    newPermissionName.value = ''
    newPermissionDescription.value = ''
    await loadCatalog()
    notify('权限已添加')
  } catch (error) {
    createPermissionError.value = error.message || '添加权限失败。'
  } finally {
    createPermissionBusy.value = false
  }
}

async function deletePermission(permission) {
  if (!window.confirm(`确定删除权限“${permission.code}”吗？所有账号上的该权限会一并移除。`)) return
  deletingPermissionCode.value = permission.code
  try {
    const response = await authFetch(`/api/admin/permissions/${encodeURIComponent(permission.code)}`, {
      method: 'DELETE',
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    await loadCatalog()
    await loadAccounts()
    notify('权限已删除')
  } catch (error) {
    notify(error.message || '删除权限失败，请重试')
  } finally {
    deletingPermissionCode.value = ''
  }
}

async function createAccount() {
  if (createAccountBusy.value) return
  createAccountBusy.value = true
  createAccountError.value = ''
  try {
    const response = await authFetch('/api/admin/accounts', {
      method: 'POST',
      body: JSON.stringify({
        username: newAccountUsername.value.trim(),
        password: newAccountPassword.value,
        enabled: newAccountEnabled.value,
      }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const account = await response.json()
    adminAccounts.value = [account, ...adminAccounts.value]
    newAccountUsername.value = ''
    newAccountPassword.value = ''
    newAccountEnabled.value = true
    notify('账号已创建')
  } catch (error) {
    createAccountError.value = error.message || '创建账号失败。'
  } finally {
    createAccountBusy.value = false
  }
}

async function toggleAccount(account) {
  const action = account.enabled ? '禁用' : '启用'
  if (!window.confirm(`确定${action}账号“${account.username}”吗？`)) return
  accountBusyId.value = account.id
  try {
    const response = await authFetch(`/api/admin/accounts/${account.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled: !account.enabled }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const updated = await response.json()
    adminAccounts.value = adminAccounts.value.map(item => item.id === updated.id ? updated : item)
    notify(`账号已${action}`)
  } catch (error) {
    notify(error.message || `${action}失败，请重试`)
  } finally {
    accountBusyId.value = 0
  }
}

async function deleteAccount(account) {
  if (!window.confirm(`确定删除账号“${account.username}”吗？删除后无法恢复。`)) return
  accountBusyId.value = account.id
  try {
    const response = await authFetch(`/api/admin/accounts/${account.id}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    adminAccounts.value = adminAccounts.value.filter(item => item.id !== account.id)
    selectedAccountIds.value = selectedAccountIds.value.filter(id => id !== account.id)
    notify('账号已删除')
  } catch (error) {
    notify(error.message || '删除失败，请重试')
  } finally {
    accountBusyId.value = 0
  }
}

function openPasswordDialog(account) {
  passwordDialog.value = { id: account.id, username: account.username, password: '', confirm: '', busy: false, error: '' }
}

async function submitPasswordReset() {
  const dialog = passwordDialog.value
  if (!dialog || dialog.busy) return
  if (new TextEncoder().encode(dialog.password).length < 8) {
    dialog.error = '密码至少需要 8 个字节。'
    return
  }
  if (dialog.password !== dialog.confirm) {
    dialog.error = '两次输入的密码不一致。'
    return
  }
  dialog.busy = true
  dialog.error = ''
  try {
    const response = await authFetch(`/api/admin/accounts/${dialog.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ password: dialog.password }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const updated = await response.json()
    adminAccounts.value = adminAccounts.value.map(item => item.id === updated.id ? updated : item)
    passwordDialog.value = null
    notify('密码已重置')
  } catch (error) {
    dialog.error = error.message || '重置密码失败。'
    dialog.busy = false
  }
}

function accountDisplayName(account) {
  return account.systemAccount ? '未登录用户' : account.username
}

function openPermissionDialog(account) {
  permissionDialog.value = {
    id: account.id,
    username: account.systemAccount ? '未登录用户（anonymous）' : account.username,
    selected: [...(account.permissions || [])],
    busy: false,
    error: '',
  }
}

async function submitPermissions() {
  const dialog = permissionDialog.value
  if (!dialog || dialog.busy) return
  dialog.busy = true
  dialog.error = ''
  try {
    const response = await authFetch(`/api/admin/accounts/${dialog.id}/permissions`, {
      method: 'PUT',
      body: JSON.stringify({ permissions: dialog.selected }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const updated = await response.json()
    adminAccounts.value = adminAccounts.value.map(item => item.id === updated.id ? updated : item)
    permissionDialog.value = null
    notify('权限已保存')
  } catch (error) {
    dialog.error = error.message || '保存权限失败。'
    dialog.busy = false
  }
}

function toggleAccountSelection(id) {
  if (selectedAccountIds.value.includes(id)) {
    selectedAccountIds.value = selectedAccountIds.value.filter(item => item !== id)
  } else {
    selectedAccountIds.value = [...selectedAccountIds.value, id]
  }
}

function toggleSelectAll() {
  selectedAccountIds.value = allAccountsSelected.value ? [] : adminAccounts.value.map(account => account.id)
}

async function applyBulkPermissions(revoke) {
  if (!canBulkApply.value) return
  const action = revoke ? '撤销' : '授予'
  if (revoke && !window.confirm(
    `确定撤销 ${selectedAccountIds.value.length} 个账号的 ${selectedBulkPermissions.value.length} 项权限吗？`)) return
  bulkBusy.value = true
  const accountCount = selectedAccountIds.value.length
  const permissionCount = selectedBulkPermissions.value.length
  try {
    const response = await authFetch(`/api/admin/account-permissions/${revoke ? 'revoke' : 'grant'}`, {
      method: 'POST',
      body: JSON.stringify({
        accountIds: selectedAccountIds.value,
        permissions: selectedBulkPermissions.value,
      }),
    })
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    const result = await response.json()
    await loadAccounts()
    notify(`已为 ${accountCount} 个账号${action} ${permissionCount} 项权限，实际变更 ${result.changed} 条`)
  } catch (error) {
    notify(error.message || `${action}失败，请重试`)
  } finally {
    bulkBusy.value = false
  }
}

function handleKeydown(event) {
  if (event.key === 'Escape') {
    helpOpen.value = false
    passwordDialog.value = null
    permissionDialog.value = null
    if (!loginBusy.value) loginOpen.value = false
  }
}

async function checkService() {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch('/actuator/health/readiness', { signal: controller.signal })
    const body = await response.json().catch(() => ({}))
    serviceStatus.value = response.ok && body.status === 'UP' ? 'up' : 'down'
  } catch {
    serviceStatus.value = 'down'
  } finally {
    clearTimeout(timeout)
  }
}

onMounted(async () => {
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('keydown', handleKeydown)
  checkService()
  loadCapacity()
  statusTimer = setInterval(checkService, 30000)
  await restoreSession()
  if (page.value === 'manage') loadTransfers()
  if (page.value === 'admin') loadAdmin()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <a class="brand" href="#/" @click.prevent="navigate('home')" aria-label="DropKey 首页">
        <span class="brand-mark"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M16 4.5 27 10.7v10.6l-11 6.2-11-6.2V10.7l11-6.2Z"/><path d="M11.4 16.1 15 19.7l6.2-7"/></svg></span>
        <span>dropkey<span class="brand-period">.</span></span>
      </a>
      <div class="topbar-right">
        <form v-if="page === 'home'" class="code-lookup" @submit.prevent="findTransfer">
          <span class="lookup-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="10.8" cy="10.8" r="6.4"/><path d="m16 16 4.1 4.1"/></svg></span>
          <input v-model="code" maxlength="6" autocomplete="off" autocapitalize="characters" placeholder="输入 6 位验证码" aria-label="输入文件验证码" @input="code = code.toUpperCase().replace(/[^A-Z2-9]/g, '')" />
          <button class="lookup-submit" type="submit" :disabled="!validCode" aria-label="查找文件"><svg viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg></button>
        </form>
        <a v-else class="back-home" href="#/" @click.prevent="navigate('home')"><svg viewBox="0 0 24 24"><path d="m15 18-6-6 6-6"/></svg><span>返回传输</span></a>
        <span class="topbar-divider"></span>
        <button class="help-link" @click="helpOpen = true" aria-label="打开使用说明">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9.7 9.3a2.4 2.4 0 1 1 3.2 2.3c-.6.2-.9.8-.9 1.4v.3"/><circle cx="12" cy="16.9" r=".7" fill="currentColor" stroke="none"/></svg>
          <span>使用说明</span>
        </button>
        <template v-if="isAuthenticated">
          <span class="topbar-user" :title="authUsername">{{ authUsername }}</span>
          <button v-if="isAdmin" class="help-link admin-link" :class="{ active: page === 'admin' }" aria-label="系统管理" @click="navigate(page === 'admin' ? 'home' : 'admin')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3.6"/><path d="M4.8 19.2a7.2 7.2 0 0 1 14.4 0"/></svg>
            <span>系统管理</span>
          </button>
          <button class="help-link logout-link" aria-label="退出登录" @click="logout">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5h4v14h-4M10 8l-4 4 4 4M6 12h9"/></svg>
            <span>退出</span>
          </button>
        </template>
        <button v-else class="help-link login-link" aria-label="登录" @click="openLogin()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v14h-5M10 8l4 4-4 4M3 12h11"/></svg>
          <span>登录</span>
        </button>
        <button class="manage-link" :class="{ active: page === 'manage' }" @click="navigate(page === 'manage' ? 'home' : 'manage')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7.5h16M4 12h16M4 16.5h16"/><circle cx="7" cy="7.5" r=".8"/><circle cx="7" cy="12" r=".8"/><circle cx="7" cy="16.5" r=".8"/></svg>
          <span>{{ page === 'manage' ? '文件传输' : '我的文件' }}</span>
        </button>
      </div>
    </header>

    <main v-if="page === 'home'" class="main-content home-view">
      <section class="intro">
        <div class="eyebrow"><span class="live-dot"></span> PRIVATE FILE TRANSFER</div>
        <h1>文件，轻松<br /><span>送达彼端。</span></h1>
        <p class="intro-copy">不需要注册。上传文件，分享一组验证码，<br class="desktop-break" />让文件在需要的地方准时出现。</p>
      </section>

      <section v-if="lookup" class="lookup-result" aria-live="polite">
        <div class="result-icon"><svg viewBox="0 0 24 24"><path d="M13.5 2.8H6.8a1.8 1.8 0 0 0-1.8 1.8v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V8.3Z"/><path d="M13.5 2.8v5.5H19M8.5 14h7M8.5 17.3h7"/></svg></div>
        <div class="result-main"><div class="result-label">文件已就绪 <span class="result-key">{{ lookup.key }}</span></div><h2>{{ lookup.name }}</h2><div class="result-meta"><span>{{ formatSize(lookup.size) }}</span><i></i><span>{{ timeLeft(lookup.expiresAt) }}</span></div></div>
        <button class="download-button" :disabled="downloadingKey === lookup.key" @click="downloadTransfer(lookup.key)">
          <span v-if="downloadingKey === lookup.key" class="spinner"></span>
          <svg v-else viewBox="0 0 24 24"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 17v3h14v-3"/></svg>
          <span>{{ downloadingKey === lookup.key ? '正在准备' : '下载文件' }}</span>
        </button>
        <button class="result-close" aria-label="关闭文件信息" @click="lookup = null; lookupError = ''">×</button>
      </section>
      <div v-else-if="lookupError" class="lookup-error" role="alert"><span class="error-symbol">!</span><span>{{ lookupError }}</span><button @click="lookupError = ''" aria-label="关闭提示">×</button></div>

      <section class="upload-section">
        <div class="section-heading"><div><span class="section-kicker">SEND A FILE</span><h2>开始一次传输</h2></div><span class="retention"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.2 2"/></svg>20 分钟后自动清除</span></div>

        <div v-if="capacity.limit" class="capacity-widget" :class="{ low: capacityLow }">
          <div class="capacity-head">
            <span class="capacity-title"><svg viewBox="0 0 24 24" aria-hidden="true"><ellipse cx="12" cy="6" rx="7.5" ry="3"/><path d="M4.5 6v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6M4.5 12v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-6"/></svg>共享存储空间</span>
            <span class="capacity-value">{{ formatSize(capacity.used) }} <span>/ {{ formatSize(capacity.limit) }}</span></span>
          </div>
          <div class="capacity-track"><span :style="{ width: `${capacityPercent}%` }"></span></div>
          <div class="capacity-foot">
            <span>全站已用 {{ capacityPercent }}%</span>
            <span>剩余 <strong>{{ formatSize(capacity.available) }}</strong></span>
          </div>
        </div>
        <div v-if="!uploadResult" class="drop-zone" :class="{ 'is-hover': false, 'has-file': selectedFile }" @dragover.prevent @drop.prevent="dropFile">
          <template v-if="!selectedFile">
            <div class="upload-art"><span class="art-orbit orbit-one"></span><span class="art-orbit orbit-two"></span><span class="art-core"><svg viewBox="0 0 32 32"><path d="M16 22V8m0 0-5 5m5-5 5 5M7 20v5h18v-5"/></svg></span></div>
            <h3>把文件放在这里</h3><p>或从设备中选择要传输的文件</p>
            <label for="file-picker" class="choose-button"><svg viewBox="0 0 24 24"><path d="M12 16V4m0 0L8 8m4-4 4 4M5 15v4h14v-4"/></svg>选择文件</label>
            <input id="file-picker" class="visually-hidden" type="file" @change="chooseFile" />
            <div class="limit-note"><span>单个文件最大 2 GiB</span><i></i><span>无需注册</span><i></i><span>验证码下载</span></div>
          </template>
          <template v-else>
            <div class="selected-file"><div class="file-glyph"><svg viewBox="0 0 24 24"><path d="M13.5 2.8H6.8a1.8 1.8 0 0 0-1.8 1.8v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V8.3Z"/><path d="M13.5 2.8v5.5H19"/></svg></div><div class="selected-details"><strong>{{ selectedFile.name }}</strong><span>{{ formatSize(selectedFile.size) }}</span></div><button class="remove-file" aria-label="移除文件" @click="selectedFile = null">×</button></div>
            <div v-if="uploadBusy" class="progress-wrap"><div class="progress-label"><span>正在上传</span><span>{{ uploadProgress }}%</span></div><div class="progress-track"><span :style="{ width: `${uploadProgress}%` }"></span></div></div>
            <div class="selected-actions">
              <button v-if="uploadBusy" class="choose-button secondary cancel-button" @click="cancelUpload">
                <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>取消上传
              </button>
              <button v-else class="choose-button secondary" @click="document.querySelector('#file-picker').click()">更换文件</button>
              <button class="send-button" :disabled="uploadBusy" @click="uploadFile">
                <span v-if="uploadBusy" class="spinner"></span>
                <svg v-else viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
                {{ uploadBusy ? '正在发送' : '开始上传' }}
              </button>
            </div>
          </template>
        </div>
        <p v-if="uploadError" class="inline-error" role="alert"><span>!</span>{{ uploadError }}</p>
        <section v-if="uploadResult" class="upload-success" aria-live="polite">
          <button class="success-dismiss" aria-label="关闭上传结果" @click="uploadResult = null">×</button>
          <span class="success-check"><svg viewBox="0 0 24 24"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg></span>
          <div class="success-heading"><span>上传完成</span><h3>{{ uploadResult.name }}</h3><p>{{ formatSize(uploadResult.size) }} <i></i> 20 分钟后自动删除</p></div>
          <div class="code-share"><div><span class="code-caption">分享验证码</span><strong>{{ uploadResult.key }}</strong></div><button @click="copyCode(uploadResult.key)" aria-label="复制验证码"><svg viewBox="0 0 24 24"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg></button></div>
          <div class="success-footer"><span>对方在页面右上角输入验证码即可下载</span><button @click="navigate('manage')">管理我的文件 <svg viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6"/></svg></button></div>
        </section>
      </section>
      <footer class="home-footer"><span>DROPKEY <i>·</i> SIMPLE, PRIVATE, TEMPORARY</span><span class="footer-status" :class="`is-${serviceStatus}`"><span class="live-dot"></span>{{ serviceLabel }}</span></footer>
    </main>

    <main v-else-if="page === 'manage'" class="main-content manage-view">
      <div class="manage-title-row"><div><div class="eyebrow"><span class="live-dot"></span> YOUR TRANSFERS</div><h1>我的文件</h1><p>通过此浏览器上传的文件会显示在这里。</p></div><button class="refresh-button" :disabled="listBusy" @click="loadTransfers"><svg :class="{ spinning: listBusy }" viewBox="0 0 24 24"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.6 9a7 7 0 0 1 11.8-2L20 12M4 12l2.6 5a7 7 0 0 0 11.8-2"/></svg><span>刷新</span></button></div>
      <div class="manage-toolbar"><span>{{ listBusy ? '正在读取…' : `共 ${transfers.length} 个文件` }}</span><span class="retention"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.2 2"/></svg>文件上传 20 分钟后自动删除</span></div>
      <div v-if="listError" class="list-error" role="alert"><span>{{ listError }}</span><button @click="loadTransfers">重试</button></div>
      <div v-else-if="listBusy && !transfers.length" class="empty-state"><span class="spinner dark-spinner"></span><p>正在加载文件</p></div>
      <div v-else-if="!transfers.length" class="empty-state"><span class="empty-icon"><svg viewBox="0 0 24 24"><path d="M13.5 2.8H6.8a1.8 1.8 0 0 0-1.8 1.8v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V8.3Z"/><path d="M13.5 2.8v5.5H19"/></svg></span><h2>这里暂时没有文件</h2><p>你上传的文件会出现在这里，可随时下载或删除。</p><button class="send-button" @click="navigate('home')">上传一个文件 <svg viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6"/></svg></button></div>
      <div v-else class="transfer-list">
        <article v-for="item in transfers" :key="item.key" class="transfer-row">
          <div class="row-file-icon"><svg viewBox="0 0 24 24"><path d="M13.5 2.8H6.8a1.8 1.8 0 0 0-1.8 1.8v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V8.3Z"/><path d="M13.5 2.8v5.5H19"/></svg></div>
          <div class="row-main"><strong :title="item.name">{{ item.name }}</strong><div class="row-meta"><span>{{ formatSize(item.size) }}</span><i></i><span class="row-code">{{ item.key }}</span><i></i><span>{{ timeLeft(item.expiresAt) }}</span></div></div>
          <div class="row-actions"><button class="icon-button" title="复制验证码" aria-label="复制验证码" @click="copyCode(item.key)"><svg viewBox="0 0 24 24"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg></button><button class="icon-button" title="下载文件" aria-label="下载文件" :disabled="downloadingKey === item.key" @click="downloadTransfer(item.key)"><span v-if="downloadingKey === item.key" class="spinner"></span><svg v-else viewBox="0 0 24 24"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 17v3h14v-3"/></svg></button><button class="icon-button delete-button" :disabled="deletingKey === item.key" title="立即删除" aria-label="立即删除" @click="deleteTransfer(item)"><svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6m4-6v6M5.5 7l1 13h11l1-13M9 7V4h6v3"/></svg></button></div>
        </article>
      </div>
      <footer class="manage-footer"><span>DROPKEY <i>·</i> SIMPLE, PRIVATE, TEMPORARY</span><span>文件所有权保存在当前浏览器中</span></footer>
    </main>

    <main v-else class="main-content admin-view">
      <div class="manage-title-row">
        <div><div class="eyebrow"><span class="live-dot"></span> ADMIN CONSOLE</div><h1>系统管理</h1><p>账号由管理员创建；普通账号登录后只能执行已授权的功能。</p></div>
        <button v-if="isAdmin" class="refresh-button" @click="logout"><svg viewBox="0 0 24 24"><path d="M15 5h4v14h-4M10 8l-4 4 4 4M6 12h9"/></svg><span>退出登录</span></button>
      </div>

      <section v-if="!authReady" class="empty-state"><span class="spinner dark-spinner"></span><p>正在恢复登录状态</p></section>

      <section v-else-if="!isAuthenticated" class="admin-login-card">
        <div class="admin-login-head"><span class="section-kicker">SIGN IN</span><h2>先登录再管理</h2><p>管理员和普通账号使用同一个登录入口。</p></div>
        <p v-if="!adminConfigured" class="admin-hint">当前未配置管理员。请在服务端设置 <code>TRANSFER_ADMIN_USERNAME</code> 与 <code>TRANSFER_ADMIN_PASSWORD</code>。</p>
        <button class="send-button" @click="openLogin()">
          <svg viewBox="0 0 24 24"><path d="M14 5h5v14h-5M10 8l4 4-4 4M3 12h11"/></svg>
          登录
        </button>
      </section>

      <section v-else-if="!isAdmin" class="admin-disabled">
        <span class="empty-icon"><svg viewBox="0 0 24 24"><rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v2"/></svg></span>
        <h2>需要管理员权限</h2>
        <p v-if="adminConfigured">当前登录账号是 <strong>{{ authUsername }}</strong>，只有管理员可以进入系统管理。</p>
        <p v-else>管理员登录未启用。请在后端设置 <code>TRANSFER_ADMIN_USERNAME</code> 与 <code>TRANSFER_ADMIN_PASSWORD</code> 环境变量并重启服务。</p>
      </section>

      <template v-else>
        <div class="admin-session-bar">
          <span>当前管理员 <strong>{{ authUsername }}</strong></span>
          <span v-if="authExpiresAt">登录有效期至 {{ formatDateTime(authExpiresAt) }}</span>
        </div>

        <div class="admin-tabs" role="tablist">
          <button :class="{ active: adminTab === 'accounts' }" role="tab" @click="adminTab = 'accounts'">账号管理</button>
          <button :class="{ active: adminTab === 'permissions' }" role="tab" @click="adminTab = 'permissions'">权限管理</button>
        </div>

        <template v-if="adminTab === 'accounts'">
          <section class="admin-create-card">
            <div class="admin-create-head"><h2>添加账号</h2><span>用户名创建后不可修改</span></div>
            <form class="admin-form admin-create-form" @submit.prevent="createAccount">
              <label>用户名<input v-model="newAccountUsername" autocomplete="off" placeholder="例如 alice" required /></label>
              <label>初始密码<input type="password" v-model="newAccountPassword" autocomplete="new-password" placeholder="至少 8 个字符" required /></label>
              <label class="admin-checkbox"><input type="checkbox" v-model="newAccountEnabled" /><span>创建后立即启用</span></label>
              <button class="send-button" type="submit" :disabled="createAccountBusy">
                <span v-if="createAccountBusy" class="spinner"></span>
                <svg v-else viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>
                {{ createAccountBusy ? '正在创建' : '添加账号' }}
              </button>
            </form>
            <p class="admin-hint">用户名 3–32 位，只能包含小写字母、数字、点、下划线和短横线；密码 8–72 个字节。</p>
            <p class="admin-hint">「未登录用户」是代表所有未登录访客的系统账号：它的权限决定匿名访客能做什么，该账号不能登录、禁用、删除或重置密码，但可以随时调整权限。</p>
            <p v-if="createAccountError" class="inline-error" role="alert"><span>!</span>{{ createAccountError }}</p>
          </section>

          <div class="manage-toolbar"><span>{{ adminListBusy ? '正在读取…' : `共 ${adminAccounts.length} 个账号` }}</span><span>权限在「权限管理」标签页中分配</span></div>
          <div v-if="adminListError" class="list-error" role="alert"><span>{{ adminListError }}</span><button @click="loadAccounts">重试</button></div>
          <div v-else-if="adminListBusy && !adminAccounts.length" class="empty-state"><span class="spinner dark-spinner"></span><p>正在加载账号</p></div>
          <div v-else-if="!adminAccounts.length" class="empty-state"><span class="empty-icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.4"/><path d="M5.5 19a6.5 6.5 0 0 1 13 0"/></svg></span><h2>还没有普通账号</h2><p>使用上方表单添加第一个账号。</p></div>
          <div v-else class="transfer-list">
            <article v-for="account in adminAccounts" :key="account.id" class="transfer-row">
              <div class="row-file-icon admin-avatar"><svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.4"/><path d="M5.5 19a6.5 6.5 0 0 1 13 0"/></svg></div>
              <div class="row-main">
                <strong class="account-name">{{ accountDisplayName(account) }}<span v-if="account.systemAccount" class="system-badge">系统账号</span></strong>
                <div class="row-meta">
                  <span class="account-status" :class="{ disabled: !account.enabled }">{{ account.enabled ? '已启用' : '已禁用' }}</span>
                  <template v-if="account.systemAccount"><i></i><span>代表所有未登录访客</span></template>
                  <template v-else><i></i><span>创建于 {{ formatDateTime(account.createdAt) }}</span><i></i><span>创建者 {{ account.createdBy }}</span></template>
                </div>
                <div class="permission-chips">
                  <span v-for="permission in account.permissions" :key="permission" class="permission-chip">{{ permission }}</span>
                  <span v-if="!account.permissions?.length" class="permission-empty">无权限</span>
                </div>
              </div>
              <div class="row-actions">
                <button class="icon-button" title="管理权限" aria-label="管理权限" :disabled="accountBusyId === account.id" @click="openPermissionDialog(account)"><svg viewBox="0 0 24 24"><path d="M12 3 4.5 6v5c0 4.4 3 8 7.5 10 4.5-2 7.5-5.6 7.5-10V6Z"/><path d="m8.7 11.8 2.2 2.2 4.4-4.4"/></svg></button>
                <template v-if="!account.systemAccount">
                  <button class="icon-button" title="重置密码" aria-label="重置密码" :disabled="accountBusyId === account.id" @click="openPasswordDialog(account)"><svg viewBox="0 0 24 24"><circle cx="8" cy="12" r="3.2"/><path d="M11.2 12H20m-3 0v3m-3-3v2"/></svg></button>
                  <button class="icon-button" :title="account.enabled ? '禁用账号' : '启用账号'" :aria-label="account.enabled ? '禁用账号' : '启用账号'" :disabled="accountBusyId === account.id" @click="toggleAccount(account)"><svg viewBox="0 0 24 24"><path d="M12 3v8"/><path d="M7 6.3a7 7 0 1 0 10 0"/></svg></button>
                  <button class="icon-button delete-button" title="删除账号" aria-label="删除账号" :disabled="accountBusyId === account.id" @click="deleteAccount(account)"><svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6m4-6v6M5.5 7l1 13h11l1-13M9 7V4h6v3"/></svg></button>
                </template>
              </div>
            </article>
          </div>
        </template>

        <template v-else>
          <section class="admin-create-card">
            <div class="admin-create-head">
              <h2>权限目录</h2>
              <button class="refresh-button" :disabled="catalogBusy" @click="loadCatalog">
                <svg :class="{ spinning: catalogBusy }" viewBox="0 0 24 24"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.6 9a7 7 0 0 1 11.8-2L20 12M4 12l2.6 5a7 7 0 0 0 11.8-2"/></svg>
                <span>刷新</span>
              </button>
            </div>
            <form class="admin-form permission-create-form" @submit.prevent="createPermission">
              <label>权限 Key<input v-model="newPermissionCode" autocomplete="off" placeholder="例如 transfer.manage" required /></label>
              <label>名称<input v-model="newPermissionName" autocomplete="off" placeholder="例如 管理传输" required /></label>
              <label>说明<input v-model="newPermissionDescription" autocomplete="off" placeholder="可选" /></label>
              <button class="send-button" type="submit" :disabled="createPermissionBusy">
                <span v-if="createPermissionBusy" class="spinner"></span>
                <svg v-else viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>
                {{ createPermissionBusy ? '正在添加' : '添加权限' }}
              </button>
            </form>
            <p class="admin-hint">Key 必须与代码注解中的值完全一致，例如 <code>@RequiresPermission("transfer.manage")</code>。添加后即可在下方直接授权。</p>
            <p v-if="createPermissionError" class="inline-error" role="alert"><span>!</span>{{ createPermissionError }}</p>
            <div v-if="catalogError" class="list-error" role="alert"><span>{{ catalogError }}</span><button @click="loadCatalog">重试</button></div>
            <div v-else-if="catalogBusy && !permissionCatalog.length" class="catalog-empty">正在读取权限目录…</div>
            <div v-else-if="!permissionCatalog.length" class="catalog-empty">还没有权限。使用上方表单添加第一个功能权限。</div>
            <div v-else class="catalog-list">
              <div v-for="permission in permissionCatalog" :key="permission.code" class="catalog-row">
                <code class="catalog-code">{{ permission.code }}</code>
                <span class="catalog-name">{{ permission.name }}</span>
                <span class="catalog-desc">{{ permission.description || '—' }}</span>
                <button class="icon-button delete-button catalog-delete" :disabled="deletingPermissionCode === permission.code" title="删除权限" aria-label="删除权限" @click="deletePermission(permission)">
                  <svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6m4-6v6M5.5 7l1 13h11l1-13M9 7V4h6v3"/></svg>
                </button>
              </div>
            </div>
          </section>

          <section class="admin-create-card bulk-permission-bar">
            <div class="bulk-head">
              <h2>批量授权</h2>
              <span>已选 <strong>{{ selectedAccountIds.length }}</strong> 个账号 / <strong>{{ selectedBulkPermissions.length }}</strong> 项权限</span>
            </div>
            <div v-if="!permissionCatalog.length" class="catalog-empty">请先添加权限，再选择要授予的权限项。</div>
            <div v-else class="permission-picker">
              <label v-for="permission in permissionCatalog" :key="permission.code">
                <input type="checkbox" :value="permission.code" v-model="selectedBulkPermissions" />
                <span>{{ permission.name }}</span>
                <code>{{ permission.code }}</code>
              </label>
            </div>
            <div class="bulk-actions">
              <button class="send-button" :disabled="!canBulkApply" @click="applyBulkPermissions(false)">
                <span v-if="bulkBusy" class="spinner"></span>
                <svg v-else viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>
                批量授予
              </button>
              <button class="choose-button secondary" :disabled="!canBulkApply" @click="applyBulkPermissions(true)">批量撤销</button>
            </div>
          </section>

          <div class="manage-toolbar bulk-toolbar">
            <label class="admin-checkbox">
              <input type="checkbox" :checked="allAccountsSelected" :disabled="!adminAccounts.length" @change="toggleSelectAll" />
              <span>全选账号</span>
            </label>
            <span>{{ adminListBusy ? '正在读取…' : `共 ${adminAccounts.length} 个账号` }}</span>
          </div>
          <div v-if="adminListError" class="list-error" role="alert"><span>{{ adminListError }}</span><button @click="loadAccounts">重试</button></div>
          <div v-else-if="adminListBusy && !adminAccounts.length" class="empty-state"><span class="spinner dark-spinner"></span><p>正在加载账号</p></div>
          <div v-else-if="!adminAccounts.length" class="empty-state"><span class="empty-icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.4"/><path d="M5.5 19a6.5 6.5 0 0 1 13 0"/></svg></span><h2>还没有普通账号</h2><p>先在「账号管理」中添加账号。</p></div>
          <div v-else class="transfer-list">
            <article v-for="account in adminAccounts" :key="account.id" class="transfer-row account-select-row">
              <input class="account-select" type="checkbox" :checked="selectedAccountIds.includes(account.id)" :aria-label="`选择账号 ${account.username}`" @change="toggleAccountSelection(account.id)" />
              <div class="row-file-icon admin-avatar"><svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.4"/><path d="M5.5 19a6.5 6.5 0 0 1 13 0"/></svg></div>
              <div class="row-main">
                <strong class="account-name">{{ accountDisplayName(account) }}<span v-if="account.systemAccount" class="system-badge">系统账号</span></strong>
                <div class="row-meta">
                  <span class="account-status" :class="{ disabled: !account.enabled }">{{ account.enabled ? '已启用' : '已禁用' }}</span>
                  <template v-if="account.systemAccount"><i></i><span>代表所有未登录访客</span></template>
                </div>
                <div class="permission-chips">
                  <span v-for="permission in account.permissions" :key="permission" class="permission-chip">{{ permission }}</span>
                  <span v-if="!account.permissions?.length" class="permission-empty">无权限</span>
                </div>
              </div>
              <div class="row-actions">
                <button class="icon-button" title="管理权限" aria-label="管理权限" @click="openPermissionDialog(account)"><svg viewBox="0 0 24 24"><path d="M12 3 4.5 6v5c0 4.4 3 8 7.5 10 4.5-2 7.5-5.6 7.5-10V6Z"/><path d="m8.7 11.8 2.2 2.2 4.4-4.4"/></svg></button>
              </div>
            </article>
          </div>
        </template>
      </template>
      <footer class="manage-footer"><span>DROPKEY <i>·</i> ADMIN CONSOLE</span><span>账号保存在数据库中，管理员来自环境变量</span></footer>
    </main>

    <Transition name="modal">
      <div v-if="loginOpen" class="modal-backdrop" @click.self="loginOpen = false">
        <div class="help-panel login-panel" role="dialog" aria-modal="true" aria-labelledby="login-title">
          <header class="help-header">
            <div><span class="section-kicker">SIGN IN</span><h2 id="login-title">登录</h2></div>
            <button class="help-close" aria-label="关闭登录" @click="loginOpen = false">×</button>
          </header>
          <form class="password-form" @submit.prevent="submitLogin">
            <p>管理员和普通账号使用同一入口。普通账号登录后只能执行已授权的功能。</p>
            <label>用户名<input v-model="loginUsername" autocomplete="username" required /></label>
            <label>密码<input type="password" v-model="loginPassword" autocomplete="current-password" required /></label>
            <p v-if="loginError" class="inline-error" role="alert"><span>!</span>{{ loginError }}</p>
            <div class="password-actions">
              <button type="button" class="choose-button secondary" @click="loginOpen = false">取消</button>
              <button type="submit" class="send-button" :disabled="loginBusy">
                <span v-if="loginBusy" class="spinner"></span>
                {{ loginBusy ? '正在登录' : '登录' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Transition>

    <Transition name="modal">
      <div v-if="permissionDialog" class="modal-backdrop" @click.self="permissionDialog = null">
        <div class="help-panel password-panel" role="dialog" aria-modal="true" aria-labelledby="permission-title">
          <header class="help-header">
            <div><span class="section-kicker">PERMISSIONS</span><h2 id="permission-title">管理权限</h2></div>
            <button class="help-close" aria-label="关闭权限管理" @click="permissionDialog = null">×</button>
          </header>
          <form class="password-form" @submit.prevent="submitPermissions">
            <p>账号 <strong>{{ permissionDialog.username }}</strong>。勾选后保存，权限立即生效。</p>
            <div v-if="!permissionCatalog.length" class="catalog-empty">权限目录为空，请先新增权限。</div>
            <div v-else class="permission-picker">
              <label v-for="permission in permissionCatalog" :key="permission.code">
                <input type="checkbox" :value="permission.code" v-model="permissionDialog.selected" />
                <span>{{ permission.name }}</span>
                <code>{{ permission.code }}</code>
              </label>
            </div>
            <p v-if="permissionDialog.error" class="inline-error" role="alert"><span>!</span>{{ permissionDialog.error }}</p>
            <div class="password-actions">
              <button type="button" class="choose-button secondary" @click="permissionDialog = null">取消</button>
              <button type="submit" class="send-button" :disabled="permissionDialog.busy">
                <span v-if="permissionDialog.busy" class="spinner"></span>
                {{ permissionDialog.busy ? '正在保存' : '保存权限' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Transition>

    <Transition name="modal">
      <div v-if="helpOpen" class="modal-backdrop" @click.self="helpOpen = false">
        <div class="help-panel" role="dialog" aria-modal="true" aria-labelledby="help-title">
          <header class="help-header">
            <div><span class="section-kicker">HOW IT WORKS</span><h2 id="help-title">使用说明</h2></div>
            <button class="help-close" aria-label="关闭使用说明" @click="helpOpen = false">×</button>
          </header>

          <div class="help-tabs" role="tablist">
            <button :class="{ active: helpTab === 'web' }" role="tab" @click="helpTab = 'web'">网页使用</button>
            <button :class="{ active: helpTab === 'cli' }" role="tab" @click="helpTab = 'cli'">命令行 / API</button>
            <button :class="{ active: helpTab === 'rules' }" role="tab" @click="helpTab = 'rules'">规则与限制</button>
          </div>

          <div class="help-body">
            <ol v-if="helpTab === 'web'" class="help-steps">
              <li><span class="step-no">1</span><div><h3>上传文件</h3><p>在首页把文件拖到虚线框，或点击「选择文件」。确认文件名和大小后点击「开始上传」。上传过程中可随时点击<strong>取消上传</strong>，服务器不会留下任何文件。</p></div></li>
              <li><span class="step-no">2</span><div><h3>拿到验证码</h3><p>上传完成后会显示一组 6 位验证码，由大写字母和数字组成。点右侧按钮可一键复制。</p></div></li>
              <li><span class="step-no">3</span><div><h3>告知对方</h3><p>对方打开同一网址，在<strong>右上角</strong>输入验证码并按回车或点箭头，页面会出现文件信息卡片，点「下载文件」即可保存。</p></div></li>
              <li><span class="step-no">4</span><div><h3>管理自己的文件</h3><p>点右上角「我的文件」，可以查看剩余时间、复制验证码、重新下载，或点击垃圾桶图标立即删除。</p></div></li>
            </ol>

            <div v-if="helpTab === 'web'" class="help-note">
              <span class="note-icon">i</span>
              <p>文件的管理权限保存在<strong>当前浏览器的 Cookie</strong> 中。换浏览器、用无痕模式或清除 Cookie 后，将无法再列出和删除已上传的文件，但对方仍可用验证码下载。</p>
            </div>

            <div v-if="helpTab === 'cli'" class="help-cli">
              <p class="help-cli-intro">上传、查询、下载等传输接口默认不需要登录：未登录访客由「未登录用户」系统账号统一代表，默认拥有全部文件传输权限。把示例中的验证码 <code>ABCD23</code> 换成实际值即可。</p>
              <article v-for="command in cliCommands" :key="command.label" class="cli-item">
                <div class="cli-head"><strong>{{ command.label }}</strong><button class="cli-copy" @click="copyText(command.code, '命令已复制')">复制</button></div>
                <p class="cli-desc">{{ command.desc }}</p>
                <pre><code>{{ command.code }}</code></pre>
              </article>
            </div>

            <dl v-if="helpTab === 'rules'" class="help-rules">
              <div><dt>文件大小</dt><dd>单个文件必须<strong>小于 2 GiB</strong>，即最多 2,147,483,647 字节。超过会被拒绝（HTTP 413）。</dd></div>
              <div><dt>共享空间</dt><dd>全站文件共享一个存储池，页面上的容量条实时显示已用和剩余。空间不足时上传会被直接拒绝，不会写入半个文件。</dd></div>
              <div><dt>保留时间</dt><dd>文件在上传 <strong>20 分钟后自动删除</strong>，同时清理数据库记录和磁盘文件。过期后验证码立即失效。</dd></div>
              <div><dt>账号</dt><dd>传输文件无需注册或登录。未登录访客由「未登录用户」系统账号统一代表，管理员可调整它的权限来开放或关闭匿名的上传、查询、下载、列表和删除。普通账号由管理员创建并单独授权，每次操作都会实时校验权限。</dd></div>
              <div><dt>验证码</dt><dd>6 位，由大写字母和数字组成，已排除易混淆的 <code>I</code>、<code>O</code>、<code>0</code>、<code>1</code>。</dd></div>
              <div><dt>权限边界</dt><dd>验证码只能用于<strong>查询和下载</strong>，不能列出或删除文件。删除必须由上传时的同一浏览器发起。</dd></div>
              <div><dt>安全建议</dt><dd>验证码相当于下载凭证，请通过可信渠道分享，不要公开张贴。</dd></div>
            </dl>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="modal">
      <div v-if="passwordDialog" class="modal-backdrop" @click.self="passwordDialog = null">
        <div class="help-panel password-panel" role="dialog" aria-modal="true" aria-labelledby="password-title">
          <header class="help-header">
            <div><span class="section-kicker">RESET PASSWORD</span><h2 id="password-title">重置密码</h2></div>
            <button class="help-close" aria-label="关闭重置密码" @click="passwordDialog = null">×</button>
          </header>
          <form class="password-form" @submit.prevent="submitPasswordReset">
            <p>为账号 <strong>{{ passwordDialog.username }}</strong> 设置新密码。</p>
            <label>新密码<input type="password" v-model="passwordDialog.password" autocomplete="new-password" required /></label>
            <label>确认新密码<input type="password" v-model="passwordDialog.confirm" autocomplete="new-password" required /></label>
            <p v-if="passwordDialog.error" class="inline-error" role="alert"><span>!</span>{{ passwordDialog.error }}</p>
            <div class="password-actions">
              <button type="button" class="choose-button secondary" @click="passwordDialog = null">取消</button>
              <button type="submit" class="send-button" :disabled="passwordDialog.busy">
                <span v-if="passwordDialog.busy" class="spinner"></span>
                {{ passwordDialog.busy ? '正在保存' : '重置密码' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Transition>

    <Transition name="toast"><div v-if="toast" class="toast-message" role="status">{{ toast }}</div></Transition>
  </div>
</template>
