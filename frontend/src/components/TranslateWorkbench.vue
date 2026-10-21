<template>
  <div class="translate-page tool-page">
    <div class="container">
      <div class="tool-page__header page-header">
        <div>
          <h1>论文翻译</h1>
          <p>上传 PDF 或 PNG、JPG 等常见图片，翻译后生成保留版式的中文或双语结果。</p>
        </div>
        <n-button class="settings-button" secondary @click="showSettings = true">
          <template #icon><n-icon><SettingsOutline /></n-icon></template>
          设置
        </n-button>
      </div>
      <SettingsDrawer v-model:show="showSettings" @saved="applySettings" />

      <n-alert v-if="missingPdfKey" type="info" title="还没有配置翻译模型" class="setup-hint">
        翻译 PDF 需要一个 OpenAI 兼容接口的 API Key。
        <n-button text type="primary" @click="showSettings = true">打开设置</n-button>
        填写，或写入 <code>.env</code> 后重启。
      </n-alert>

      <!-- 上传态 -->
      <div v-if="step === 'upload'" class="upload-section">
        <button
          type="button"
          class="drop-zone"
          :class="{ dragging: isDragging }"
          aria-describedby="translate-upload-hint"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          @click="triggerFileInput"
        >
          <div class="drop-icon">
            <n-icon size="64" color="#b83126">
              <CloudUploadOutline />
            </n-icon>
          </div>
          <p class="drop-text">拖拽 PDF 或图片到此处，或点击选择文件</p>
          <p id="translate-upload-hint" class="drop-hint">支持 .pdf、.png、.jpg、.jpeg、.gif、.bmp，最大 50MB</p>
        </button>
        <input ref="fileInput" class="visually-hidden" type="file" accept=".pdf,application/pdf,.png,image/png,.jpg,image/jpeg,.jpeg,image/jpeg,.gif,image/gif,.bmp,image/bmp" aria-label="选择需要翻译的 PDF 或图片文件" @change="handleFileSelect" />

        <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" style="margin-top: 16px" />
      </div>

      <!-- 配置态：选择页面范围 -->
      <div v-else-if="step === 'config'" class="config-section">
        <div class="config-card">
          <div class="config-header">
            <n-icon size="32" color="#b83126">
              <DocumentTextOutline />
            </n-icon>
            <div>
              <h2>{{ fileName }}</h2>
              <p v-if="isUploading">正在上传并读取{{ isImageInput ? '图片' : 'PDF 页数' }}...</p>
              <p v-else>共 {{ totalPages }} 页</p>
            </div>
          </div>

          <div class="config-body">
            <n-alert v-if="isUploading" type="info" title="正在准备翻译配置" style="margin-bottom: 20px">
              文件正在上传到服务器并读取页数，完成后即可选择翻译范围。
            </n-alert>

              <n-alert
                v-if="textQualityWarning"
              type="warning"
              title="PDF 文本层可能不可用"
              style="margin-bottom: 20px"
            >
              {{ textQualityWarning }}
            </n-alert>

            <div v-if="!isImageInput" class="range-label">翻译页面范围</div>
            <div v-if="!isImageInput" class="range-row">
              <n-input-number
                v-model:value="startPage"
                aria-label="翻译起始页"
                :min="1"
                :max="endPage"
                :disabled="isUploading || !taskId"
                placeholder="起始页"
                style="width: 120px"
              />
              <span class="range-sep">至</span>
              <n-input-number
                v-model:value="endPage"
                aria-label="翻译结束页"
                :min="startPage"
                :max="totalPages"
                :disabled="isUploading || !taskId"
                placeholder="结束页"
                style="width: 120px"
              />
              <n-button text type="primary" :disabled="isUploading || !taskId" @click="selectAll" style="margin-left: 8px">
                全选
              </n-button>
            </div>
            <p v-if="!isImageInput && isUploading" class="range-hint">页数读取完成后可选择翻译范围</p>
            <p v-else-if="!isImageInput" class="range-hint">将翻译第 {{ startPage }} 到 {{ endPage }} 页的内容</p>
            <p v-else class="range-hint">将识别图片中的文字并生成一张中文译文图。</p>

            <div v-if="!isImageInput" class="range-label option-label">译文字体风格</div>
            <n-select
              v-if="!isImageInput"
              v-model:value="fontFamily"
              aria-label="译文字体风格"
              :options="fontFamilyOptions"
              :disabled="isUploading || !taskId"
              style="width: 100%"
            />
            <p v-if="!isImageInput" class="range-hint">字号由 BabelDOC 根据原始文本框自动适配，以尽量保持版式。</p>
            <p v-if="!isImageInput" class="range-hint">并发和每批页数由设置里的性能档位决定；内存紧张时会自动降低并发重试。</p>

            <n-button
              type="primary"
              size="large"
              :loading="isUploading || isStarting"
              :disabled="isUploading || !taskId"
              @click="handleStartTranslate"
              style="margin-top: 24px; width: 100%"
            >
              {{ isUploading ? `正在读取${isImageInput ? '图片' : 'PDF'}...` : '开始翻译' }}
            </n-button>

            <n-button text :disabled="isUploading" @click="resetToUpload" style="margin-top: 12px; width: 100%">
              重新选择文件
            </n-button>
          </div>
        </div>

        <n-alert v-if="errorMsg" type="error" :title="errorMsg" closable @close="errorMsg = ''" style="margin-top: 16px" />
      </div>

      <!-- 翻译失败态：单独突出恢复入口，避免失败原因被进度条和普通提示淹没 -->
      <section v-else-if="step === 'translating' && errorMsg" class="translation-failure-panel" role="alert" aria-live="assertive">
        <div class="failure-hero">
          <div class="failure-icon" aria-hidden="true">
            <n-icon size="46"><AlertCircleOutline /></n-icon>
          </div>
          <div>
            <span class="failure-eyebrow">TRANSLATION FAILED · 翻译失败</span>
            <h2>这次翻译没有完成</h2>
            <p>{{ fileName || (isImageInput ? '当前图片' : '当前 PDF') }}<span v-if="!isImageInput && totalPages"> · 第 {{ translateStartPage }}-{{ translateEndPage }} 页</span></p>
          </div>
        </div>

        <div class="failure-reason">
          <strong>失败原因</strong>
          <p>{{ errorMsg }}</p>
        </div>

        <div class="failure-actions">
          <n-button type="primary" size="large" @click="retryTranslation">
            <template #icon><n-icon><RefreshOutline /></n-icon></template>
            重新翻译
          </n-button>
          <n-button size="large" @click="resetToUpload">重新上传文件</n-button>
        </div>
        <p class="failure-hint">原文件和页面设置仍已保留，可以直接调整参数后再次提交。</p>
      </section>

      <!-- 翻译中态 -->
      <div v-else-if="step === 'translating'" class="progress-section">
        <div class="progress-header">
          <n-icon size="32" color="#b83126">
            <DocumentTextOutline />
          </n-icon>
          <div>
            <h2>{{ fileName }}</h2>
            <p v-if="queuePosition > 0">{{ isImageInput ? '图片' : `第 ${translateStartPage}-${translateEndPage} 页` }} · 队列第 {{ queuePosition }} 位</p>
            <p v-else>{{ isImageInput ? '视觉模型图片翻译' : `第 ${translateStartPage}-${translateEndPage} 页 · BabelDOC 保留版式翻译` }}</p>
          </div>
        </div>

        <div class="progress-bar-wrap" role="status" aria-live="polite">
          <n-progress
            type="line"
            :percentage="translationProgress"
            :processing="translationProgress < 100"
            indicator-text-color="#b83126"
          />
          <p class="progress-text">
            {{ progressStageLabel }}
          </p>
        </div>

        <n-alert
          v-if="resourceDowngraded"
          type="warning"
          title="已自动降低并发"
          style="margin-top: 16px"
        >
          内存压力较高，已降低并发重新处理当前任务。
        </n-alert>

        <div class="next-submission">
          <n-button type="error" secondary @click="cancelCurrentTranslation">取消本次任务</n-button>
          <n-button @click="continueWithNewTranslation">
            <template #icon><n-icon><CloudUploadOutline /></n-icon></template>
            继续提交新任务
          </n-button>
          <span>当前任务会继续在后台处理，可在“最近翻译”中随时查看结果。</span>
        </div>

      </div>

      <!-- 结果态 -->
      <div v-else-if="step === 'result'" class="result-section">
        <div class="result-toolbar">
          <div class="result-info">
            <n-icon size="24" color="#52c41a">
              <CheckmarkCircleOutline />
            </n-icon>
            <span>翻译完成<span v-if="!isImageInput"> · 第 {{ translateStartPage }}-{{ translateEndPage }} 页</span></span>
          </div>

          <div class="result-actions">
            <n-radio-group class="result-mode" v-model:value="pdfPreviewMode" size="small" @update:value="loadPdfPreview">
              <n-radio-button value="translated">{{ isImageInput ? '中文译图' : '纯中文' }}</n-radio-button>
              <n-radio-button value="bilingual">{{ isImageInput ? '双语译图' : '双语对照' }}</n-radio-button>
            </n-radio-group>
            <n-button size="small" @click="downloadResult">
              <template #icon><n-icon><DownloadOutline /></n-icon></template>
              下载中文 TXT
            </n-button>
            <n-button size="small" type="primary" @click="downloadPdf">
              <template #icon><n-icon><DownloadOutline /></n-icon></template>
              下载当前{{ isImageInput ? '图片' : 'PDF' }}
            </n-button>
            <n-button size="small" @click="resetToUpload">
              <template #icon><n-icon><RefreshOutline /></n-icon></template>
              重新翻译
            </n-button>
          </div>
        </div>

        <div v-if="!isImageInput" class="pdf-preview-panel">
          <div class="pdf-preview-header">
            <div>
              <h3>{{ pdfPreviewMode === 'bilingual' ? '双语对照 PDF 预览' : '纯中文 PDF 预览' }}</h3>
              <p>先检查选中页的版式、页码、图片、表格和公式位置，再下载 PDF。</p>
            </div>
            <n-button size="small" :loading="isLoadingPdfPreview" @click="loadPdfPreview">
              刷新预览
            </n-button>
          </div>

          <div v-if="isLoadingPdfPreview" class="pdf-preview-loading">
            正在生成 PDF 预览...
          </div>
          <PdfCanvasPreview v-else-if="pdfPreviewBlob" :file="pdfPreviewBlob" />
          <n-alert v-else type="warning" title="PDF 预览暂不可用">
            {{ pdfPreviewError || '请刷新预览或稍后重试。' }}
          </n-alert>
        </div>

        <div v-else class="image-preview-panel">
          <div class="pdf-preview-header">
            <div>
              <h3>{{ pdfPreviewMode === 'bilingual' ? '双语译图预览' : '中文译图预览' }}</h3>
              <p>视觉模型会覆盖识别到的原文区域；如有文字位置不准确，可调整图片后重新翻译。</p>
            </div>
            <n-button size="small" :loading="isLoadingPdfPreview" @click="loadPdfPreview">刷新预览</n-button>
          </div>
          <div v-if="isLoadingPdfPreview" class="pdf-preview-loading">正在生成图片预览...</div>
          <img v-else-if="pdfPreviewUrl" class="image-preview" :src="pdfPreviewUrl" alt="翻译后的图片预览" />
          <n-alert v-else type="warning" title="图片预览暂不可用">
            {{ pdfPreviewError || '请刷新预览或稍后重试。' }}
          </n-alert>
        </div>
      </div>

      <section class="recent-section">
        <div class="recent-header">
          <div>
            <h2>最近翻译</h2>
            <p>
              服务器保留的最近任务和结果文件<span v-if="recentTasks.length">，当前 {{ recentTasks.length }} 条</span>；后端重启后未完成任务会重新排队。
            </p>
          </div>
          <n-button size="small" :loading="isLoadingRecent" @click="loadRecentTranslations">刷新</n-button>
        </div>

        <n-alert
          v-if="recentLoadError"
          type="warning"
          title="最近翻译记录加载失败"
          style="margin-bottom: 12px"
        >
          {{ recentLoadError }}
        </n-alert>

        <div v-if="recentTasks.length" class="recent-list">
          <button
            v-for="item in recentTasks"
            :key="item.taskId"
            class="recent-item"
            type="button"
            @click="openRecentTask(item)"
          >
            <div class="recent-copy">
              <strong>{{ item.fileName }}</strong>
              <span>
                第 {{ item.startPage }}-{{ item.endPage }} 页 · {{ speedLabel(item) }} · {{ formatTaskTime(item.createdAt) }}
              </span>
            </div>
            <div class="recent-status">
              <n-tag size="small" :type="taskTagType(item.status)">
                {{ taskStatusLabel(item) }}
              </n-tag>
              <span v-if="item.status === 'translating'">{{ Math.round(item.progress || 0) }}%</span>
            </div>
          </button>
        </div>
        <n-empty v-else-if="!isLoadingRecent" description="暂无翻译记录" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, defineAsyncComponent, onMounted, onBeforeUnmount } from 'vue'
import {
  NAlert, NButton, NEmpty, NIcon, NInputNumber, NProgress,
  NRadioButton, NRadioGroup, NSelect, NTag, useMessage
} from 'naive-ui'
import {
  CloudUploadOutline,
  DocumentTextOutline,
  CheckmarkCircleOutline,
  DownloadOutline,
  AlertCircleOutline,
  RefreshOutline,
  SettingsOutline
} from '@vicons/ionicons5'
import {
  uploadTranslationFile,
  startTranslation,
  cancelTranslation,
  getTranslationStatus,
  getRecentTranslations,
  downloadTranslation,
  downloadTranslatedPdf,
  getTranslatedPdfBlob,
  getTranslatedImageBlob,
  downloadTranslatedImage,
  getSettings
} from '@/api'
// pdf.js is large; load it only when a result is previewed.
const PdfCanvasPreview = defineAsyncComponent(() => import('@/components/PdfCanvasPreview.vue'))
const SettingsDrawer = defineAsyncComponent(() => import('@/components/SettingsDrawer.vue'))

const message = useMessage()
const showSettings = ref(false)
const missingPdfKey = ref(false)

function applySettings (data) {
  missingPdfKey.value = !data?.pdf?.apiKey?.set
}
const DEFAULT_FAST_QPS = 4

// 状态
const step = ref('upload') // 'upload' | 'config' | 'translating' | 'result'
const isDragging = ref(false)
const errorMsg = ref('')
const fileInput = ref(null)
const fileName = ref('')
const taskId = ref('')
const inputKind = ref('pdf')
const textQualitySuspicious = ref(false)
const textQualityWarning = ref('')
const totalPages = ref(0)
const startPage = ref(1)
const endPage = ref(1)
const translateStartPage = ref(1)
const translateEndPage = ref(1)
const fontFamily = ref('auto')
const fontFamilyOptions = [
  { label: '自动匹配原文', value: 'auto' },
  { label: '衬线字体', value: 'serif' },
  { label: '无衬线字体', value: 'sans-serif' },
  { label: '手写 / 斜体风格', value: 'script' }
]
const translationQps = ref(DEFAULT_FAST_QPS)
const requestedQps = ref(DEFAULT_FAST_QPS)
const resourceDowngraded = ref(false)
const translationProgress = ref(0)
const progressStageLabel = ref('正在启动 BabelDOC...')
const queuePosition = ref(0)
const recentTasks = ref([])
const isLoadingRecent = ref(false)
const recentLoadError = ref('')
const pdfPreviewMode = ref('translated')
const isUploading = ref(false)
const isStarting = ref(false)
const isLoadingPdfPreview = ref(false)
const isGeneratingPdf = ref(false)
const pdfPreviewUrl = ref('')
const pdfPreviewBlob = ref(null)
const pdfPreviewError = ref('')
let eventSource = null
let recentRefreshTimer = null
const isImageInput = computed(() => inputKind.value === 'image')

// 触发文件选择
function triggerFileInput() {
  fileInput.value?.click()
}

// 处理拖拽
function handleDrop(e) {
  isDragging.value = false
  const files = e.dataTransfer?.files
  if (files && files.length > 0) {
    processFile(files[0])
  }
}

// 处理文件选择
function handleFileSelect(e) {
  const files = e.target.files
  if (files && files.length > 0) {
    processFile(files[0])
  }
  e.target.value = ''
}

// 处理文件上传（只获取页数/图片信息，不开始翻译）
async function processFile(file) {
  errorMsg.value = ''

  const extension = file.name.toLowerCase().split('.').pop()
  const supportedExtensions = ['pdf', 'png', 'jpg', 'jpeg', 'gif', 'bmp']
  if (!supportedExtensions.includes(extension)) {
    errorMsg.value = '仅支持 PDF、PNG、JPG、JPEG、GIF、BMP 文件格式'
    return
  }
  if (file.size > 50 * 1024 * 1024) {
    errorMsg.value = '文件大小超过 50MB 限制'
    return
  }

  fileName.value = file.name
  inputKind.value = extension === 'pdf' ? 'pdf' : 'image'
  taskId.value = ''
  textQualitySuspicious.value = false
  textQualityWarning.value = ''
  totalPages.value = 0
  startPage.value = 1
  endPage.value = 1
  isUploading.value = true
  step.value = 'config'

  try {
    const res = await uploadTranslationFile(file)
    if (res.code !== 200) {
      throw new Error(res.message || '上传失败')
    }

    taskId.value = res.data.taskId
    inputKind.value = res.data.inputKind || inputKind.value
    textQualitySuspicious.value = Boolean(res.data.textQualitySuspicious)
    textQualityWarning.value = res.data.textQualityWarning || ''
    totalPages.value = res.data.totalPages
    startPage.value = 1
    endPage.value = res.data.totalPages

    // 保存 taskId 用于断线重连
    sessionStorage.setItem('translateTaskId', taskId.value)

    loadRecentTranslations()
  } catch (e) {
    errorMsg.value = e.message || '上传失败，请重试'
    textQualitySuspicious.value = false
    textQualityWarning.value = ''
  } finally {
    isUploading.value = false
  }
}

// 全选页面
function selectAll() {
  startPage.value = 1
  endPage.value = totalPages.value
}

// 开始翻译
async function handleStartTranslate() {
  isStarting.value = true
  errorMsg.value = ''

  try {
    const res = await startTranslation(taskId.value, startPage.value, endPage.value, fontFamily.value)
    if (res.code !== 200) {
      throw new Error(res.message || '启动翻译失败')
    }

    translateStartPage.value = startPage.value
    translateEndPage.value = endPage.value
    translationQps.value = res.data.qps || translationQps.value
    requestedQps.value = res.data.requestedQps || translationQps.value
    resourceDowngraded.value = false
    isGeneratingPdf.value = false
    translationProgress.value = 0
    progressStageLabel.value = isImageInput.value ? '正在准备视觉模型翻译...' : '正在启动 BabelDOC...'
    queuePosition.value = res.data.queuePosition || 0

    // 进入翻译态并开始 SSE
    step.value = 'translating'
    startSSE()
    loadRecentTranslations()
  } catch (e) {
    errorMsg.value = e.message || '启动翻译失败，请重试'
  } finally {
    isStarting.value = false
  }
}

// 开始 SSE 连接
function startSSE() {
  if (eventSource) {
    eventSource.close()
  }

  eventSource = new EventSource(`/api/translate/stream/${taskId.value}`)

  eventSource.addEventListener('done', () => {
    isGeneratingPdf.value = false
    translationProgress.value = 100
    progressStageLabel.value = '翻译完成'
    step.value = 'result'
    eventSource?.close()
    eventSource = null
    message.success('翻译完成！')
    loadPdfPreview()
    loadRecentTranslations()
  })

  eventSource.addEventListener('layout', () => {
    isGeneratingPdf.value = true
    queuePosition.value = 0
    progressStageLabel.value = isImageInput.value
      ? '正在使用视觉模型识别图片文字并生成译文图像...'
      : '正在使用 BabelDOC 分析版面、翻译并重建 PDF...'
  })

  eventSource.addEventListener('queued', (e) => {
    try {
      const data = JSON.parse(e.data)
      queuePosition.value = data.queuePosition || 1
      progressStageLabel.value = `等待后台翻译，当前队列第 ${queuePosition.value} 位`
    } catch {
      progressStageLabel.value = '等待后台翻译'
    }
  })

  eventSource.addEventListener('progress', (e) => {
    try {
      const data = JSON.parse(e.data)
      translationProgress.value = Math.round(data.progress || 0)
      progressStageLabel.value = data.stageLabel || '处理中...'
      translationQps.value = data.qps || translationQps.value
      resourceDowngraded.value = Boolean(data.resourceDowngraded)
    } catch (err) {
      console.error('解析翻译进度失败:', err)
    }
  })

  eventSource.addEventListener('task-error', (e) => {
    try {
      const data = JSON.parse(e.data)
      errorMsg.value = data.message || '翻译过程中发生错误'
    } catch {
      if (step.value === 'translating') {
        errorMsg.value = '翻译连接中断，请重试'
      }
    }
    eventSource?.close()
    eventSource = null
  })

  eventSource.onerror = () => {
    if (step.value === 'translating') {
      message.warning('连接中断，尝试重连...')
      setTimeout(() => {
        if (step.value === 'translating') {
          reconnectSSE()
        }
      }, 2000)
    }
    eventSource?.close()
    eventSource = null
  }
}

// 断线重连
async function reconnectSSE() {
  try {
    const res = await getTranslationStatus(taskId.value)
    if (res.code === 200) {
      const data = res.data
      if (data.status === 'completed') {
        translationProgress.value = 100
        progressStageLabel.value = '翻译完成'
        step.value = 'result'
        message.success('翻译已完成')
        loadPdfPreview()
        return
      }

      if (data.status === 'error') {
        errorMsg.value = data.errorMessage || '翻译失败'
        loadRecentTranslations()
        return
      }

      queuePosition.value = data.queuePosition || 0
      translationProgress.value = Math.round(data.progress || 0)
      translationQps.value = data.qps || translationQps.value
      requestedQps.value = data.requestedQps || requestedQps.value
      resourceDowngraded.value = Boolean(data.resourceDowngraded)
      progressStageLabel.value = data.status === 'queued'
        ? `等待后台翻译，当前队列第 ${queuePosition.value || 1} 位`
        : data.progressStageLabel || '正在使用 BabelDOC 处理...'
      setTimeout(() => {
        if (step.value === 'translating') {
          reconnectSSE()
        }
      }, 2000)
    }
  } catch {
    errorMsg.value = '重连失败，请刷新页面重试'
  }
}

// 复用已经上传的 PDF 和任务目录，只重新打开配置界面；后端允许 error 任务重新入队，避免用户再次上传大文件。
function retryTranslation() {
  eventSource?.close()
  eventSource = null
  errorMsg.value = ''
  isGeneratingPdf.value = false
  translationProgress.value = 0
  queuePosition.value = 0
  progressStageLabel.value = '准备重新翻译...'
  step.value = 'config'
}

async function cancelCurrentTranslation() {
  if (!taskId.value || !window.confirm('取消本次翻译？后台处理会停止。')) return
  try {
    const res = await cancelTranslation(taskId.value)
    if (res.code !== 200) throw new Error(res.message || '取消失败')
    eventSource?.close(); eventSource = null
    message.success('翻译任务已取消')
    resetToUpload(); loadRecentTranslations()
  } catch (e) { message.error(e.message || '取消失败') }
}

// 下载结果
function downloadResult() {
  downloadTranslation(taskId.value)
}

// 下载翻译 PDF
function downloadPdf() {
  if (isImageInput.value) {
    downloadTranslatedImage(taskId.value, pdfPreviewMode.value)
  } else {
    downloadTranslatedPdf(taskId.value, pdfPreviewMode.value)
  }
}

async function loadPdfPreview() {
  if (!taskId.value) return
  isLoadingPdfPreview.value = true
  pdfPreviewError.value = ''

  try {
    const blob = isImageInput.value
      ? await getTranslatedImageBlob(taskId.value, pdfPreviewMode.value)
      : await getTranslatedPdfBlob(taskId.value, pdfPreviewMode.value)
    if (pdfPreviewUrl.value) {
      URL.revokeObjectURL(pdfPreviewUrl.value)
    }
    pdfPreviewUrl.value = ''
    if (isImageInput.value) {
      pdfPreviewBlob.value = null
      pdfPreviewUrl.value = URL.createObjectURL(blob)
    } else {
      pdfPreviewBlob.value = blob
    }
  } catch (e) {
    pdfPreviewError.value = e.message || (isImageInput.value ? '生成图片预览失败' : '生成 PDF 预览失败')
  } finally {
    isLoadingPdfPreview.value = false
  }
}

// 重置到上传态
function resetToUpload() {
  eventSource?.close()
  eventSource = null
  if (pdfPreviewUrl.value) {
    URL.revokeObjectURL(pdfPreviewUrl.value)
  }
  pdfPreviewUrl.value = ''
  pdfPreviewBlob.value = null
  pdfPreviewError.value = ''
  step.value = 'upload'
  errorMsg.value = ''
  isUploading.value = false
  isGeneratingPdf.value = false
  fontFamily.value = 'auto'
  translationQps.value = DEFAULT_FAST_QPS
  requestedQps.value = DEFAULT_FAST_QPS
  resourceDowngraded.value = false
  translationProgress.value = 0
  queuePosition.value = 0
  progressStageLabel.value = '正在启动 BabelDOC...'
  pdfPreviewMode.value = 'translated'
  totalPages.value = 0
  inputKind.value = 'pdf'
  taskId.value = ''
  textQualitySuspicious.value = false
  textQualityWarning.value = ''
  fileName.value = ''
  sessionStorage.removeItem('translateTaskId')
}

function continueWithNewTranslation() {
  resetToUpload()
  message.info('当前翻译仍在后台继续，现可提交新的翻译任务')
}

async function loadRecentTranslations() {
  isLoadingRecent.value = true
  try {
    const res = await getRecentTranslations()
    if (res.code !== 200 || !Array.isArray(res.data)) {
      throw new Error(res.message || '服务器返回的翻译记录格式不正确')
    }
    recentTasks.value = res.data
    recentLoadError.value = ''
  } catch (e) {
    recentLoadError.value = e.message || '无法连接服务器，请稍后重试'
  } finally {
    isLoadingRecent.value = false
  }
}

function taskStatusLabel(item) {
  if (item.status === 'queued') return `排队中 · 第 ${item.queuePosition || 1} 位`
  if (item.status === 'translating') return item.progressStageLabel || '翻译中'
  if (item.status === 'completed') return '已完成'
  if (item.status === 'error') return '失败'
  return '待配置'
}

function speedLabel(item) {
  const currentQps = item.qps || item.requestedQps || 1
  if (item.resourceDowngraded) return `已降低并发 · ${currentQps}`
  return `并发 ${currentQps}`
}

function taskTagType(status) {
  if (status === 'completed') return 'success'
  if (status === 'error') return 'error'
  if (status === 'queued') return 'warning'
  return 'info'
}

function formatTaskTime(timestamp) {
  return timestamp ? new Date(timestamp).toLocaleString() : ''
}

async function openRecentTask(item) {
  sessionStorage.setItem('translateTaskId', item.taskId)
  await restoreTask(item.taskId, true)
}

async function restoreTask(savedTaskId, notify = false) {
  const res = await getTranslationStatus(savedTaskId)
  if (res.code !== 200) return
  const data = res.data
  if (taskId.value !== savedTaskId && pdfPreviewUrl.value) {
    URL.revokeObjectURL(pdfPreviewUrl.value)
    pdfPreviewUrl.value = ''
  }
  if (taskId.value !== savedTaskId) pdfPreviewBlob.value = null
  taskId.value = savedTaskId
  fileName.value = data.fileName
  inputKind.value = data.inputKind || 'pdf'
  textQualitySuspicious.value = Boolean(data.textQualitySuspicious)
  textQualityWarning.value = data.textQualityWarning || ''
  totalPages.value = data.totalPages || 0
  startPage.value = data.startPage || 1
  endPage.value = data.endPage || data.totalPages || 1
  translateStartPage.value = data.startPage || 1
  translateEndPage.value = data.endPage || data.totalPages || 1
  fontFamily.value = data.fontFamily || 'auto'
  translationQps.value = data.qps || 4
  requestedQps.value = data.requestedQps || translationQps.value
  resourceDowngraded.value = Boolean(data.resourceDowngraded)
  translationProgress.value = Math.round(data.progress || 0)
  queuePosition.value = data.queuePosition || 0
  progressStageLabel.value = data.status === 'queued'
    ? `等待后台翻译，当前队列第 ${queuePosition.value || 1} 位`
    : data.progressStageLabel || '正在使用 BabelDOC 处理...'

  if (data.status === 'completed') {
    progressStageLabel.value = '翻译完成'
    step.value = 'result'
    if (notify) message.info('已打开翻译结果')
    loadPdfPreview()
  } else if (data.status === 'queued' || data.status === 'translating') {
    step.value = 'translating'
    isGeneratingPdf.value = data.status === 'translating'
    if (notify) message.info(data.status === 'queued' ? '已恢复排队任务' : '已恢复翻译进度')
    reconnectSSE()
  } else if (data.status === 'preview') {
    step.value = 'config'
  } else {
    errorMsg.value = data.errorMessage || '翻译失败'
    step.value = 'translating'
  }
}

// 页面加载时检查是否有进行中的任务
onMounted(async () => {
  getSettings().then((res) => { if (res.code === 200) applySettings(res.data) }).catch(() => {})
  await loadRecentTranslations()
  recentRefreshTimer = window.setInterval(loadRecentTranslations, 5000)
  const requestedTaskId = new URLSearchParams(window.location.search).get('taskId') || ''
  const savedTaskId = requestedTaskId || sessionStorage.getItem('translateTaskId')
  if (savedTaskId) {
    try {
      await restoreTask(savedTaskId)
      sessionStorage.setItem('translateTaskId', savedTaskId)
    } catch {
      sessionStorage.removeItem('translateTaskId')
    }
  }
})

onBeforeUnmount(() => {
  eventSource?.close()
  if (recentRefreshTimer) {
    window.clearInterval(recentRefreshTimer)
    recentRefreshTimer = null
  }
  if (pdfPreviewUrl.value) {
    URL.revokeObjectURL(pdfPreviewUrl.value)
  }
  pdfPreviewBlob.value = null
})
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.settings-button {
  flex-shrink: 0;
  margin-top: 6px;
}

.setup-hint {
  margin-bottom: 20px;

  code {
    font-size: 12px;
  }
}

.translate-page {
  background: var(--desk-bg);
}

.container {
  max-width: 960px;
  margin: 0 auto;
  padding: 0 24px;
  min-width: 0;
}

.recent-section {
  margin-top: 28px;
  padding: 20px 24px;
  border: 1px solid var(--desk-border);
  border-radius: 2px;
  background: var(--desk-surface);
  box-shadow: var(--desk-shadow);
}

.recent-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;

  h2 {
    margin: 0;
    font-size: 18px;
    color: var(--desk-text);
  }

  p {
    margin: 4px 0 0;
    font-size: 13px;
    color: var(--desk-subtle);
  }
}

.recent-list {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.recent-item {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid #edf0f3;
  border-radius: 10px;
  background: var(--desk-surface);
  overflow: hidden;
  text-align: left;
  cursor: pointer;

  &:hover {
    border-color: #91caff;
    background: #f7fbff;
  }
}

.recent-copy,
.recent-status {
  display: flex;
  min-width: 0;
}

.recent-copy {
  flex: 1 1 auto;
  flex-direction: column;
  gap: 4px;

  strong {
    overflow: hidden;
    color: var(--desk-text);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  span {
    font-size: 12px;
    color: var(--desk-subtle);
  }
}

.recent-status {
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--desk-subtle);
}

/* ===== 上传态 ===== */
.upload-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.drop-zone {
  appearance: none;
  color: inherit;
  font: inherit;
  width: 100%;
  max-width: 600px;
  padding: 60px 40px;
  border: 2px dashed #d0d7de;
  border-radius: 2px;
  background: var(--desk-surface);
  text-align: center;
  cursor: pointer;
  transition: all 0.3s ease;

  &:hover,
  &.dragging {
    border-color: var(--desk-accent);
    background: var(--desk-tint);
    transform: translateY(-2px);
    box-shadow: var(--desk-shadow);
  }

  &:focus-visible {
    outline: 3px solid rgba(24, 144, 255, 0.3);
    outline-offset: 4px;
  }

  .drop-icon {
    margin-bottom: 16px;
  }

  .drop-text {
    font-size: 18px;
    color: var(--desk-text);
    margin: 0 0 8px;
    font-weight: 500;
  }

  .drop-hint {
    font-size: 14px;
    color: #aaa;
    margin: 0;
  }
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

/* ===== 配置态 ===== */
.config-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.config-card {
  width: 100%;
  max-width: 480px;
  background: var(--desk-surface);
  border: 1px solid var(--desk-border);
  border-radius: 2px;
  box-shadow: var(--desk-shadow);
  overflow: hidden;
}

.config-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px 28px;
  border-bottom: 1px solid var(--desk-soft);

  h2 {
    font-size: 18px;
    font-weight: 600;
    margin: 0;
    color: var(--desk-text);
    word-break: break-all;
  }

  p {
    font-size: 14px;
    color: var(--desk-subtle);
    margin: 4px 0 0;
  }
}

.config-body {
  padding: 24px 28px;
}

.range-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--desk-text);
  margin-bottom: 12px;
}

.option-label {
  margin-top: 20px;
}

.range-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.range-row :deep(.n-input-number) {
  flex: 1 1 120px;
  min-width: 0;
}

.range-sep {
  color: var(--desk-subtle);
  font-size: 14px;
}

.range-hint {
  font-size: 13px;
  color: #aaa;
  margin: 12px 0 0;
}

/* ===== 翻译中态 ===== */
.translation-failure-panel {
  padding: 32px;
  border: 2px solid #d84a3a;
  border-radius: 14px;
  background: linear-gradient(135deg, #fff7f5 0%, #fff 72%);
  box-shadow: 0 10px 28px rgba(184, 49, 38, 0.14), 4px 4px 0 rgba(184, 49, 38, 0.08);
}

.next-submission {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 20px;
  color: #7e786d;
  font-size: 13px;
}

.failure-hero {
  display: flex;
  align-items: center;
  gap: 18px;
}

.failure-icon {
  flex: 0 0 auto;
  width: 78px;
  height: 78px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  color: var(--desk-accent);
  background: #ffe1dc;
  box-shadow: inset 0 0 0 8px rgba(255, 255, 255, 0.66);
}

.failure-eyebrow {
  color: var(--desk-accent);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.failure-hero h2 {
  margin: 5px 0 0;
  color: #7d211b;
  font-size: 25px;
}

.failure-hero p {
  margin-top: 6px;
  color: #7b645f;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.failure-reason {
  margin-top: 26px;
  padding: 16px 18px;
  border-left: 4px solid #d84a3a;
  background: rgba(255, 255, 255, 0.78);
}

.failure-reason strong {
  color: #7d211b;
  font-size: 13px;
}

.failure-reason p {
  margin: 7px 0 0;
  color: #5f4843;
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.failure-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 26px;
}

.failure-actions :deep(.n-button) {
  min-width: 148px;
}

.failure-hint {
  margin: 14px 0 0;
  color: #8c716b;
  font-size: 13px;
}

.progress-section {
  background: var(--desk-surface);
  border-radius: 16px;
  padding: 32px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.progress-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;

  h2 {
    font-size: 20px;
    font-weight: 600;
    margin: 0;
    color: var(--desk-text);
  }

  p {
    font-size: 14px;
    color: var(--desk-subtle);
    margin: 4px 0 0;
  }
}

.progress-bar-wrap {
  margin-bottom: 32px;

  .progress-text {
    font-size: 14px;
    color: var(--desk-muted);
    margin: 8px 0 0;
    text-align: center;
  }
}

.preview-list {
  max-height: 400px;
  overflow-y: auto;
  border-top: 1px solid var(--desk-soft);
  padding-top: 16px;
}

.preview-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--desk-soft);

  .preview-original {
    font-size: 13px;
    color: var(--desk-subtle);
    margin-bottom: 6px;
    line-height: 1.5;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  .preview-translated {
    font-size: 14px;
    color: var(--desk-text);
    line-height: 1.6;
  }
}

/* ===== 结果态 ===== */
.result-section {
  background: var(--desk-surface);
  border-radius: 16px;
  padding: 24px 32px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}

.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--desk-soft);
}

.result-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 500;
  color: var(--desk-text);
  min-width: 0;
  overflow-wrap: anywhere;
}

.result-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.result-mode {
  flex: 0 0 auto;
}

.pdf-preview-panel {
  margin-bottom: 24px;
  border: 1px solid var(--desk-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--desk-surface);
}

.image-preview-panel {
  margin-bottom: 24px;
  border: 1px solid var(--desk-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--desk-soft);
}

.image-preview {
  display: block;
  max-width: 100%;
  max-height: 72vh;
  margin: 0 auto;
  object-fit: contain;
  background: var(--desk-soft);
}

.pdf-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-bottom: 1px solid #eef0f3;
  background: var(--desk-surface);
  min-width: 0;

  h3 {
    margin: 0;
    font-size: 15px;
    color: var(--desk-text);
  }

  p {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--desk-subtle);
  }
}

.pdf-preview-loading {
  height: 70vh;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--desk-subtle);
  background: var(--desk-soft);
}

.result-list {
  max-height: 70vh;
  overflow-y: auto;
}

.result-item {
  padding: 16px 0;
  border-bottom: 1px solid var(--desk-soft);

  &:last-child {
    border-bottom: none;
  }
}

.result-page-tag {
  font-size: 12px;
  color: #aaa;
  margin-bottom: 8px;
}

.result-original {
  font-size: 14px;
  color: var(--desk-muted);
  line-height: 1.7;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  margin-bottom: 8px;
  border-left: 3px solid #d9d9d9;
}

.result-translated {
  font-size: 15px;
  color: var(--desk-text);
  line-height: 1.8;
  padding: 12px 16px;
  background: #f6ffed;
  border-radius: 8px;
  border-left: 3px solid #52c41a;
}

.result-translated-only {
  font-size: 15px;
  color: var(--desk-text);
  line-height: 1.8;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .container {
    padding: 0 16px;
  }

  .recent-section {
    padding: 18px 16px;
    border-radius: 12px;
  }

  .recent-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .drop-zone {
    padding: 36px 18px;
  }

  .config-header,
  .progress-header {
    align-items: flex-start;
  }

  .config-header {
    padding: 20px 16px;
  }

  .config-body {
    padding: 20px 16px;
  }

  .progress-section,
  .translation-failure-panel,
  .result-section {
    padding: 20px 16px;
  }

  .failure-hero {
    align-items: flex-start;
  }

  .failure-icon {
    width: 60px;
    height: 60px;
  }

  .failure-hero h2 {
    font-size: 21px;
  }

  .failure-actions,
  .failure-actions :deep(.n-button) {
    width: 100%;
  }

  .result-toolbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .range-row {
    flex-wrap: wrap;
  }

  .range-sep {
    flex: 0 0 auto;
  }

  .result-actions,
  .result-actions :deep(.n-button) {
    width: 100%;
  }

  .result-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    align-items: stretch;
  }

  .result-actions :deep(.n-radio-group) {
    width: 100%;
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .result-actions :deep(.n-radio-button) {
    min-width: 0;
  }

  .result-actions :deep(.n-radio-button__state-border) {
    width: 100%;
  }

  .pdf-preview-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .pdf-preview-loading {
    height: 62vh;
    min-height: 360px;
  }

  .recent-item {
    align-items: flex-start;
    flex-direction: column;
  }

  .recent-copy strong {
    white-space: normal;
  }
}
</style>
