<template>
  <div ref="shellRef" class="pdf-canvas-preview">
    <div v-if="loading" class="preview-state">正在渲染 PDF...</div>
    <n-alert v-else-if="error" type="warning" title="PDF 预览暂不可用">
      {{ error }}
    </n-alert>
    <template v-else>
      <div class="preview-controls" aria-label="PDF 分页">
        <n-button size="small" :disabled="pageNumber <= 1" @click="showPage(pageNumber - 1)">上一页</n-button>
        <span>第 {{ pageNumber }} / {{ pageCount }} 页</span>
        <n-button size="small" :disabled="pageNumber >= pageCount" @click="showPage(pageNumber + 1)">下一页</n-button>
      </div>
      <div class="canvas-shell">
        <canvas ref="canvasRef" aria-label="PDF 当前页预览"></canvas>
      </div>
    </template>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist/legacy/build/pdf.mjs'
import PdfWorker from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?worker'
import { createPdfRenderCoordinator } from '@/utils/pdfRenderCoordinator'

if (!GlobalWorkerOptions.workerPort) {
  GlobalWorkerOptions.workerPort = new PdfWorker()
}

const props = defineProps({
  file: {
    type: Blob,
    required: true
  }
})

const shellRef = ref(null)
const canvasRef = ref(null)
const loading = ref(true)
const error = ref('')
const pageNumber = ref(1)
const pageCount = ref(0)

let pdfDocument = null
let loadGeneration = 0
let resizeObserver = null
let resizeTimer = null
const renderCoordinator = createPdfRenderCoordinator()

async function destroyDocument() {
  await renderCoordinator.invalidate()
  const document = pdfDocument
  pdfDocument = null
  if (document) await document.destroy()
}

async function renderPage() {
  if (!pdfDocument || !canvasRef.value || !shellRef.value) return
  const document = pdfDocument
  const documentGeneration = loadGeneration
  const requestedPage = pageNumber.value
  const renderToken = await renderCoordinator.prepare()
  if (
    !renderCoordinator.isCurrent(renderToken) ||
    documentGeneration !== loadGeneration ||
    document !== pdfDocument
  ) return

  const page = await document.getPage(requestedPage)
  if (
    !renderCoordinator.isCurrent(renderToken) ||
    documentGeneration !== loadGeneration ||
    document !== pdfDocument
  ) return

  const baseViewport = page.getViewport({ scale: 1 })
  const availableWidth = Math.max(240, Math.min(shellRef.value.clientWidth - 24, 1100))
  const cssScale = availableWidth / baseViewport.width
  const outputScale = Math.min(window.devicePixelRatio || 1, 2)
  const viewport = page.getViewport({ scale: cssScale * outputScale })
  const canvas = canvasRef.value
  const context = canvas.getContext('2d', { alpha: false })

  canvas.width = Math.ceil(viewport.width)
  canvas.height = Math.ceil(viewport.height)
  canvas.style.width = `${Math.ceil(viewport.width / outputScale)}px`
  canvas.style.height = `${Math.ceil(viewport.height / outputScale)}px`

  const task = page.render({ canvasContext: context, viewport })
  if (!renderCoordinator.attach(renderToken, task)) return
  await renderCoordinator.wait(renderToken, task)
}

async function showPage(nextPage) {
  if (!pdfDocument) return
  pageNumber.value = Math.min(Math.max(1, nextPage), pageCount.value)
  try {
    await renderPage()
  } catch {
    error.value = '当前页渲染失败，请刷新预览或直接下载 PDF。'
  }
}

async function loadDocument(file) {
  const generation = ++loadGeneration
  loading.value = true
  error.value = ''
  pageNumber.value = 1
  pageCount.value = 0
  await destroyDocument()

  try {
    const data = new Uint8Array(await file.arrayBuffer())
    const document = await getDocument({ data }).promise
    if (generation !== loadGeneration) {
      await document.destroy()
      return
    }
    pdfDocument = document
    pageCount.value = document.numPages
    loading.value = false
    await nextTick()
    await renderPage()
  } catch {
    if (generation === loadGeneration) {
      error.value = 'PDF 无法在当前设备渲染，请刷新预览或直接下载 PDF。'
    }
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

watch(() => props.file, file => loadDocument(file), { immediate: true })

onMounted(() => {
  resizeObserver = new ResizeObserver(() => {
    window.clearTimeout(resizeTimer)
    resizeTimer = window.setTimeout(() => renderPage().catch(() => {}), 120)
  })
  if (shellRef.value) resizeObserver.observe(shellRef.value)
})

onBeforeUnmount(() => {
  loadGeneration += 1
  resizeObserver?.disconnect()
  window.clearTimeout(resizeTimer)
  destroyDocument().catch(() => {})
})
</script>

<style scoped>
.pdf-canvas-preview {
  width: 100%;
  min-width: 0;
  background: #f8fafc;
}

.preview-state {
  min-height: 360px;
  display: grid;
  place-items: center;
  color: #777;
}

.preview-controls {
  min-height: 52px;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-bottom: 1px solid #eef0f3;
  background: #fff;
}

.preview-controls span {
  min-width: 92px;
  text-align: center;
  color: #555;
  font-size: 13px;
}

.canvas-shell {
  width: 100%;
  min-height: 360px;
  max-height: 72vh;
  padding: 12px;
  overflow: auto;
  text-align: center;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

canvas {
  display: block;
  max-width: 100%;
  height: auto !important;
  margin: 0 auto;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.12);
}

@media (max-width: 480px) {
  .preview-controls {
    gap: 8px;
  }

  .canvas-shell {
    padding: 8px;
    max-height: 66vh;
  }
}
</style>
