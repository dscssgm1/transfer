<script setup>
import { computed, onMounted, ref } from 'vue'

const page = ref(window.location.hash === '#/manage' ? 'manage' : 'home')
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
const toast = ref('')
const serviceStatus = ref('checking')
const capacity = ref({ used: 0, limit: 0, available: 0 })
const helpOpen = ref(false)
const helpTab = ref('web')
let toastTimer
let statusTimer

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
  window.location.hash = next === 'manage' ? '/manage' : '/'
  if (next === 'manage') loadTransfers()
}

function onHashChange() {
  page.value = window.location.hash === '#/manage' ? 'manage' : 'home'
  if (page.value === 'manage') loadTransfers()
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
      xhr.upload.onprogress = event => {
        if (event.lengthComputable) uploadProgress.value = Math.round(event.loaded / event.total * 100)
      }
      xhr.onload = () => xhr.status >= 200 && xhr.status < 300 ? resolve(JSON.parse(xhr.responseText)) : reject(new Error(readApiError(xhr.responseText, xhr.status)))
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
    const response = await fetch(`/api/transfers/${encodeURIComponent(key)}`)
    if (!response.ok) throw new Error(readApiError(await response.text(), response.status))
    lookup.value = await response.json()
  } catch (error) {
    lookupError.value = error.message || '没有找到此文件。'
  }
}

function downloadUrl(key) {
  return `/api/transfers/${encodeURIComponent(key)}/download`
}

async function loadTransfers() {
  listBusy.value = true
  listError.value = ''
  try {
    const response = await fetch('/api/transfers', { credentials: 'include' })
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
    const response = await fetch(`/api/transfers/${encodeURIComponent(item.key)}`, { method: 'DELETE', credentials: 'include' })
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
    desc: '成功返回 6 位验证码。-c 会把管理身份保存到 cookies.txt，后续管理需要它。',
    code: `curl -fS -X POST \\
  -F "file=@/path/to/file.zip" \\
  -c cookies.txt \\
  ${siteOrigin}/api/transfers`,
  },
  {
    label: '查询文件信息',
    desc: '只需验证码，不需要 Cookie。验证码错误或已过期返回 404。',
    code: `curl -fS ${siteOrigin}/api/transfers/ABCD23`,
  },
  {
    label: '下载文件',
    desc: '-J 使用服务端返回的原始文件名，-O 按该名称保存到当前目录。',
    code: `curl -fL -OJ ${siteOrigin}/api/transfers/ABCD23/download`,
  },
  {
    label: '列出自己上传的文件',
    desc: '必须带上上传时保存的 Cookie，否则返回空列表。',
    code: `curl -fS -b cookies.txt ${siteOrigin}/api/transfers`,
  },
  {
    label: '立即删除文件',
    desc: '验证码本身无权删除，必须使用上传时的 Cookie。成功返回 204。',
    code: `curl -i -X DELETE -b cookies.txt \\
  ${siteOrigin}/api/transfers/ABCD23`,
  },
])

function handleKeydown(event) {
  if (event.key === 'Escape') helpOpen.value = false
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

onMounted(() => {
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('keydown', handleKeydown)
  checkService()
  loadCapacity()
  statusTimer = setInterval(checkService, 30000)
  if (page.value === 'manage') loadTransfers()
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
        <a class="download-button" :href="downloadUrl(lookup.key)" :download="lookup.name"><svg viewBox="0 0 24 24"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 17v3h14v-3"/></svg><span>下载文件</span></a>
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

    <main v-else class="main-content manage-view">
      <div class="manage-title-row"><div><div class="eyebrow"><span class="live-dot"></span> YOUR TRANSFERS</div><h1>我的文件</h1><p>通过此浏览器上传的文件会显示在这里。</p></div><button class="refresh-button" :disabled="listBusy" @click="loadTransfers"><svg :class="{ spinning: listBusy }" viewBox="0 0 24 24"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.6 9a7 7 0 0 1 11.8-2L20 12M4 12l2.6 5a7 7 0 0 0 11.8-2"/></svg><span>刷新</span></button></div>
      <div class="manage-toolbar"><span>{{ listBusy ? '正在读取…' : `共 ${transfers.length} 个文件` }}</span><span class="retention"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.2 2"/></svg>文件上传 20 分钟后自动删除</span></div>
      <div v-if="listError" class="list-error" role="alert"><span>{{ listError }}</span><button @click="loadTransfers">重试</button></div>
      <div v-else-if="listBusy && !transfers.length" class="empty-state"><span class="spinner dark-spinner"></span><p>正在加载文件</p></div>
      <div v-else-if="!transfers.length" class="empty-state"><span class="empty-icon"><svg viewBox="0 0 24 24"><path d="M13.5 2.8H6.8a1.8 1.8 0 0 0-1.8 1.8v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V8.3Z"/><path d="M13.5 2.8v5.5H19"/></svg></span><h2>这里暂时没有文件</h2><p>你上传的文件会出现在这里，可随时下载或删除。</p><button class="send-button" @click="navigate('home')">上传一个文件 <svg viewBox="0 0 24 24"><path d="M5 12h14m-6-6 6 6-6 6"/></svg></button></div>
      <div v-else class="transfer-list">
        <article v-for="item in transfers" :key="item.key" class="transfer-row">
          <div class="row-file-icon"><svg viewBox="0 0 24 24"><path d="M13.5 2.8H6.8a1.8 1.8 0 0 0-1.8 1.8v14.8a1.8 1.8 0 0 0 1.8 1.8h10.4a1.8 1.8 0 0 0 1.8-1.8V8.3Z"/><path d="M13.5 2.8v5.5H19"/></svg></div>
          <div class="row-main"><strong :title="item.name">{{ item.name }}</strong><div class="row-meta"><span>{{ formatSize(item.size) }}</span><i></i><span class="row-code">{{ item.key }}</span><i></i><span>{{ timeLeft(item.expiresAt) }}</span></div></div>
          <div class="row-actions"><button class="icon-button" title="复制验证码" aria-label="复制验证码" @click="copyCode(item.key)"><svg viewBox="0 0 24 24"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/></svg></button><a class="icon-button" title="下载文件" aria-label="下载文件" :href="downloadUrl(item.key)" :download="item.name"><svg viewBox="0 0 24 24"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 17v3h14v-3"/></svg></a><button class="icon-button delete-button" :disabled="deletingKey === item.key" title="立即删除" aria-label="立即删除" @click="deleteTransfer(item)"><svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6m4-6v6M5.5 7l1 13h11l1-13M9 7V4h6v3"/></svg></button></div>
        </article>
      </div>
      <footer class="manage-footer"><span>DROPKEY <i>·</i> SIMPLE, PRIVATE, TEMPORARY</span><span>文件所有权保存在当前浏览器中</span></footer>
    </main>
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
              <p class="help-cli-intro">所有接口都不需要登录。把示例中的验证码 <code>ABCD23</code> 换成实际值即可。</p>
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
              <div><dt>账号</dt><dd>无需注册或登录。管理身份由浏览器 Cookie 承载，服务端用签名校验，防止伪造。</dd></div>
              <div><dt>验证码</dt><dd>6 位，由大写字母和数字组成，已排除易混淆的 <code>I</code>、<code>O</code>、<code>0</code>、<code>1</code>。</dd></div>
              <div><dt>权限边界</dt><dd>验证码只能用于<strong>查询和下载</strong>，不能列出或删除文件。删除必须由上传时的同一浏览器发起。</dd></div>
              <div><dt>安全建议</dt><dd>验证码相当于下载凭证，请通过可信渠道分享，不要公开张贴。</dd></div>
            </dl>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="toast"><div v-if="toast" class="toast-message" role="status">{{ toast }}</div></Transition>
  </div>
</template>
