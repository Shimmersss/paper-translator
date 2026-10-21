// Thin client for the backend's /api/translate endpoints.

const API = '/api/translate'

async function json(response) {
  try {
    return await response.json()
  } catch {
    return { code: response.status, message: `服务器返回了无法解析的响应（HTTP ${response.status}）` }
  }
}

async function blob(url, fallbackMessage) {
  const response = await fetch(url, { cache: 'no-store' })
  if (!response.ok) {
    const data = await json(response)
    throw new Error(data.message || fallbackMessage)
  }
  return response.blob()
}

function saveFrom(url) {
  const link = document.createElement('a')
  link.href = url
  link.rel = 'noopener'
  link.download = ''
  document.body.appendChild(link)
  link.click()
  link.remove()
}

/** 上传 PDF 或图片：只读取页数/图片信息，不立即翻译 */
export async function uploadTranslationFile(file) {
  const formData = new FormData()
  formData.append('file', file)
  return json(await fetch(`${API}/upload`, { method: 'POST', body: formData }))
}

/** 开始翻译（指定页面范围和字体风格；并发不传时由设置页的性能档位决定） */
export async function startTranslation(taskId, startPage, endPage, fontFamily = 'auto', qps) {
  const params = new URLSearchParams({ startPage, endPage, fontFamily })
  if (qps) params.set('qps', qps)
  return json(await fetch(`${API}/start/${encodeURIComponent(taskId)}?${params}`, { method: 'POST' }))
}

export async function cancelTranslation(taskId) {
  return json(await fetch(`${API}/cancel/${encodeURIComponent(taskId)}`, { method: 'POST' }))
}

/** 查询任务状态（断线重连用） */
export async function getTranslationStatus(taskId) {
  return json(await fetch(`${API}/status/${encodeURIComponent(taskId)}`, { cache: 'no-store' }))
}

export async function getRecentTranslations() {
  return json(await fetch(`${API}/recent`, { cache: 'no-store' }))
}

/** 页面内预览用的 PDF / PNG */
export function getTranslatedPdfBlob(taskId, mode = 'translated') {
  return blob(`${API}/download-pdf/${encodeURIComponent(taskId)}?mode=${encodeURIComponent(mode)}`, '读取翻译 PDF 失败')
}

export function getTranslatedImageBlob(taskId, mode = 'translated') {
  return blob(`${API}/download-image/${encodeURIComponent(taskId)}?mode=${encodeURIComponent(mode)}`, '读取翻译图片失败')
}

// ==================== 设置 ====================

/** 当前设置；API Key 只返回末 4 位 */
export async function getSettings() {
  return json(await fetch('/api/settings', { cache: 'no-store' }))
}

/** 部分更新：未出现的字段不变，null 恢复为 .env / 默认值，API Key 留空表示不修改 */
export async function saveSettings(changes) {
  return json(await fetch('/api/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(changes)
  }))
}

/** 用一个极小请求测试模型连接，不保存 */
export async function testSettings(body) {
  return json(await fetch('/api/settings/test', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }))
}

/** 下载：后端用 Content-Disposition 给出文件名 */
export function downloadTranslation(taskId) {
  saveFrom(`${API}/download/${encodeURIComponent(taskId)}`)
}

export function downloadTranslatedPdf(taskId, mode = 'translated') {
  saveFrom(`${API}/download-pdf/${encodeURIComponent(taskId)}?mode=${encodeURIComponent(mode)}`)
}

export function downloadTranslatedImage(taskId, mode = 'translated') {
  saveFrom(`${API}/download-image/${encodeURIComponent(taskId)}?mode=${encodeURIComponent(mode)}`)
}
