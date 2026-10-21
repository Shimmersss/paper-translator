<template>
  <n-drawer :show="show" :width="drawerWidth" placement="right" @update:show="emit('update:show', $event)">
    <n-drawer-content title="设置" closable :native-scrollbar="false">
      <div v-if="loading" class="settings-loading" role="status">正在读取设置…</div>
      <n-alert v-else-if="loadError" type="error" title="读取设置失败">{{ loadError }}</n-alert>

      <form v-else class="settings-form" @submit.prevent="save">
        <n-alert v-if="view.readOnly" type="warning" title="设置页只读" class="block">
          服务正在对外开放，设置页不能修改。请改 <code>.env</code> 并重启，同时在前面加一层需要认证的反向代理。
        </n-alert>

        <!-- PDF 翻译模型 -->
        <section class="settings-section" aria-labelledby="settings-pdf">
          <div class="section-head">
            <h3 id="settings-pdf">PDF 翻译模型</h3>
            <n-button text size="small" :disabled="view.readOnly" @click="resetGroup('pdf')">恢复为 .env / 默认值</n-button>
          </div>
          <p class="section-hint">任意 OpenAI 兼容接口，例如 OpenAI、DeepSeek、通义千问或本地 vLLM / Ollama。</p>
          <label class="field">
            <span class="field-label">接口地址 <SourceTag :source="view.pdf.baseUrl.source" /></span>
            <n-input v-model:value="form.pdf.baseUrl" :disabled="view.readOnly" placeholder="https://api.openai.com/v1"
              :status="errors['pdf.baseUrl'] ? 'error' : undefined" />
            <span v-if="errors['pdf.baseUrl']" class="field-error" role="alert">{{ errors['pdf.baseUrl'] }}</span>
          </label>
          <label class="field">
            <span class="field-label">API Key <SourceTag :source="view.pdf.apiKey.source" /></span>
            <n-input v-model:value="form.pdf.apiKey" type="password" show-password-on="click" autocomplete="off"
              :disabled="view.readOnly" :placeholder="keyPlaceholder(view.pdf.apiKey)" />
            <n-button v-if="view.pdf.apiKey.source === 'settings'" text size="tiny" class="clear-key"
              :disabled="view.readOnly" @click="clearKey('pdf')">清除页面保存的 Key，改用 .env</n-button>
          </label>
          <label class="field">
            <span class="field-label">模型名 <SourceTag :source="view.pdf.model.source" /></span>
            <n-input v-model:value="form.pdf.model" :disabled="view.readOnly" placeholder="gpt-4o-mini"
              :status="errors['pdf.model'] ? 'error' : undefined" />
            <span v-if="errors['pdf.model']" class="field-error" role="alert">{{ errors['pdf.model'] }}</span>
          </label>
          <div class="test-row">
            <n-button size="small" :loading="testing.pdf" :disabled="view.readOnly" @click="test('pdf')">测试连接</n-button>
            <span v-if="testResult.pdf" :class="['test-result', testResult.pdf.ok ? 'ok' : 'bad']" role="status">
              {{ testResult.pdf.message }}
            </span>
          </div>
        </section>

        <!-- 图片翻译模型 -->
        <section class="settings-section" aria-labelledby="settings-image">
          <div class="section-head">
            <h3 id="settings-image">图片翻译模型</h3>
            <n-button text size="small" :disabled="view.readOnly" @click="resetGroup('image')">恢复为 .env / 默认值</n-button>
          </div>
          <p class="section-hint">需要支持图片输入的模型，OpenAI 兼容接口或 Anthropic 均可。</p>
          <label class="field">
            <span class="field-label">接口地址 <SourceTag :source="view.image.baseUrl.source" /></span>
            <n-input v-model:value="form.image.baseUrl" :disabled="view.readOnly"
              :status="errors['image.baseUrl'] ? 'error' : undefined" />
            <span v-if="errors['image.baseUrl']" class="field-error" role="alert">{{ errors['image.baseUrl'] }}</span>
          </label>
          <label class="field">
            <span class="field-label">API Key <SourceTag :source="view.image.apiKey.source" /></span>
            <n-input v-model:value="form.image.apiKey" type="password" show-password-on="click" autocomplete="off"
              :disabled="view.readOnly" :placeholder="keyPlaceholder(view.image.apiKey)" />
            <n-button v-if="view.image.apiKey.source === 'settings'" text size="tiny" class="clear-key"
              :disabled="view.readOnly" @click="clearKey('image')">清除页面保存的 Key，改用 .env</n-button>
          </label>
          <div class="field-pair">
            <label class="field">
              <span class="field-label">模型名 <SourceTag :source="view.image.model.source" /></span>
              <n-input v-model:value="form.image.model" :disabled="view.readOnly" />
            </label>
            <label class="field">
              <span class="field-label">视觉模型（可选） <SourceTag :source="view.image.visionModel.source" /></span>
              <n-input v-model:value="form.image.visionModel" :disabled="view.readOnly" placeholder="留空则用模型名" />
            </label>
          </div>
          <label class="field">
            <span class="field-label">协议 <SourceTag :source="view.image.protocol.source" /></span>
            <n-select v-model:value="form.image.protocol" :options="protocolOptions" :disabled="view.readOnly"
              aria-label="图片翻译接口协议" />
          </label>
          <div class="test-row">
            <n-button size="small" :loading="testing.image" :disabled="view.readOnly" @click="test('image')">测试连接</n-button>
            <span v-if="testResult.image" :class="['test-result', testResult.image.ok ? 'ok' : 'bad']" role="status">
              {{ testResult.image.message }}
            </span>
          </div>
        </section>

        <!-- 性能 -->
        <section class="settings-section" aria-labelledby="settings-performance">
          <div class="section-head">
            <h3 id="settings-performance">性能</h3>
          </div>
          <p class="section-hint">
            <template v-if="view.performance.hardware.detected">
              检测到 {{ view.performance.hardware.cores }} 核 / {{ gib(view.performance.hardware.memoryMiB) }} 内存。
            </template>
            <template v-else>未能识别硬件，按 4 核 / 8 GB 估算。</template>
            BabelDOC 始终只运行一个进程，档位决定每批页数和同时发给模型的请求数。CPU 占用只能通过并发间接影响。
          </p>
          <n-alert v-if="view.performance.tier.value === 'env'" type="info" class="block">
            当前由 .env 中的性能变量决定。选择下面任一档位后，改由设置页决定。
          </n-alert>
          <n-radio-group v-model:value="form.tier" :disabled="view.readOnly" name="performance-tier"
            aria-label="性能档位">
            <n-radio-button value="auto">自动</n-radio-button>
            <n-radio-button value="max">最高性能</n-radio-button>
            <n-radio-button value="manual">手动</n-radio-button>
          </n-radio-group>
          <span v-if="errors['performance.tier']" class="field-error" role="alert">{{ errors['performance.tier'] }}</span>

          <dl class="profile">
            <template v-for="item in profileRows" :key="item.key">
              <dt>{{ item.label }}</dt>
              <dd>{{ item.format(previewProfile[item.key]) }}</dd>
            </template>
          </dl>

          <div v-if="form.tier === 'manual'" class="manual-grid">
            <label v-for="item in manualFields" :key="item.key" class="field">
              <span class="field-label">{{ item.label }}</span>
              <n-input-number v-model:value="form.manual[item.key]" :min="range(item.key).min" :max="range(item.key).max"
                :disabled="view.readOnly" :placeholder="String(view.performance.recommended.auto[item.key])" clearable
                :status="errors['performance.manual.' + item.key] ? 'error' : undefined" />
              <span class="field-help">{{ range(item.key).min }}–{{ range(item.key).max }}{{ item.unit }}，留空用推荐值</span>
              <span v-if="errors['performance.manual.' + item.key]" class="field-error" role="alert">
                {{ errors['performance.manual.' + item.key] }}
              </span>
            </label>
            <n-button text size="small" :disabled="view.readOnly" @click="resetManual">恢复推荐值</n-button>
          </div>

          <label class="field">
            <span class="field-label">服务商并发上限（可选）</span>
            <n-input-number v-model:value="form.providerMaxQps" :min="range('providerMaxQps').min"
              :max="range('providerMaxQps').max" clearable :disabled="view.readOnly" placeholder="不限制" />
            <span class="field-help">模型服务商限流（HTTP 429）时，进度里会出现提示，可在这里调低。</span>
            <span v-if="errors['performance.providerMaxQps']" class="field-error" role="alert">
              {{ errors['performance.providerMaxQps'] }}
            </span>
          </label>
        </section>

        <!-- 存储 -->
        <section class="settings-section" aria-labelledby="settings-storage">
          <div class="section-head">
            <h3 id="settings-storage">存储</h3>
          </div>
          <label class="field">
            <span class="field-label">保留最近任务数 <SourceTag :source="view.storage.maxHistory.source" /></span>
            <n-input-number v-model:value="form.maxHistory" :min="view.storage.maxHistoryRange.min"
              :max="view.storage.maxHistoryRange.max" :disabled="view.readOnly" />
            <span class="field-help">超出后自动删除最旧的任务和结果文件。</span>
            <span v-if="errors['storage.maxHistory']" class="field-error" role="alert">{{ errors['storage.maxHistory'] }}</span>
          </label>
        </section>

        <p class="section-hint">保存后对下一个任务生效，正在运行的任务继续用原来的设置。</p>
        <n-alert v-if="saveError" type="error" class="block" role="alert">{{ saveError }}</n-alert>
      </form>

      <template #footer>
        <div class="footer-actions">
          <n-button @click="emit('update:show', false)">关闭</n-button>
          <n-button type="primary" :loading="saving" :disabled="loading || !!loadError || view?.readOnly" @click="save">
            保存
          </n-button>
        </div>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup>
import { computed, h, reactive, ref, watch } from 'vue'
import {
  NAlert, NButton, NDrawer, NDrawerContent, NInput, NInputNumber, NRadioButton, NRadioGroup, NSelect, NTag, useMessage
} from 'naive-ui'
import { getSettings, saveSettings, testSettings } from '@/api'

const props = defineProps({ show: { type: Boolean, default: false } })
const emit = defineEmits(['update:show', 'saved'])
const message = useMessage()

const SourceTag = (tagProps) => {
  const labels = { settings: '设置页', env: '.env', default: '默认' }
  return h(NTag, { size: 'tiny', bordered: false, type: tagProps.source === 'settings' ? 'success' : 'default' },
    () => labels[tagProps.source] || tagProps.source)
}
SourceTag.props = ['source']

const protocolOptions = [
  { label: '自动判断', value: 'auto' },
  { label: 'OpenAI 兼容', value: 'openai' },
  { label: 'Anthropic', value: 'claude' }
]
const manualFields = [
  { key: 'qps', label: '模型并发', unit: '' },
  { key: 'pagesPerChunk', label: '每批页数', unit: ' 页' },
  { key: 'memoryLimitMiB', label: '内存上限', unit: ' MiB' },
  { key: 'timeoutSeconds', label: '单批超时', unit: ' 秒' },
  { key: 'queueCapacity', label: '排队上限', unit: ' 个' }
]
const profileRows = [
  { key: 'qps', label: '模型并发', format: (v) => `${v}` },
  { key: 'pagesPerChunk', label: '每批页数', format: (v) => `${v} 页` },
  { key: 'memoryLimitMiB', label: '内存保护（仅 Linux）', format: (v) => gib(v) },
  { key: 'queueCapacity', label: '排队上限', format: (v) => `${v} 个` }
]

const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const saveError = ref('')
const view = ref(null)
const originalTier = ref('auto')
const errors = ref({})
const testing = reactive({ pdf: false, image: false })
const testResult = reactive({ pdf: null, image: null })
const form = reactive(emptyForm())
let original = emptyForm()

const drawerWidth = computed(() => Math.min(560, window.innerWidth))

function emptyForm () {
  return {
    pdf: { baseUrl: '', apiKey: '', model: '' },
    image: { baseUrl: '', apiKey: '', model: '', visionModel: '', protocol: 'auto' },
    tier: 'auto',
    manual: { qps: null, pagesPerChunk: null, memoryLimitMiB: null, timeoutSeconds: null, queueCapacity: null },
    providerMaxQps: null,
    maxHistory: null
  }
}

function fill (data) {
  view.value = data
  const next = {
    pdf: { baseUrl: data.pdf.baseUrl.value || '', apiKey: '', model: data.pdf.model.value || '' },
    image: {
      baseUrl: data.image.baseUrl.value || '',
      apiKey: '',
      model: data.image.model.value || '',
      visionModel: data.image.visionModel.value || '',
      protocol: (data.image.protocol.value || 'auto').toLowerCase()
    },
    tier: data.performance.tier.value === 'env' ? 'env' : data.performance.tier.value,
    manual: { ...data.performance.manual },
    providerMaxQps: data.performance.providerMaxQps,
    maxHistory: data.storage.maxHistory.value
  }
  Object.assign(form, JSON.parse(JSON.stringify(next)))
  original = JSON.parse(JSON.stringify(next))
  originalTier.value = next.tier
  errors.value = {}
  saveError.value = ''
}

async function load () {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getSettings()
    if (res.code !== 200) throw new Error(res.message || '读取失败')
    fill(res.data)
  } catch (e) {
    loadError.value = e.message || '无法连接服务'
  } finally {
    loading.value = false
  }
}

watch(() => props.show, (visible) => {
  if (visible) {
    testResult.pdf = null
    testResult.image = null
    load()
  }
})

const previewProfile = computed(() => {
  if (!view.value) return {}
  const perf = view.value.performance
  let base = form.tier === 'max' ? perf.recommended.max
    : form.tier === 'env' ? perf.effective : perf.recommended.auto
  if (form.tier === originalTier.value) base = perf.effective
  const result = { ...base }
  if (form.tier === 'manual') {
    for (const field of manualFields) {
      if (form.manual[field.key] != null) result[field.key] = form.manual[field.key]
    }
  }
  if (form.providerMaxQps != null && form.providerMaxQps < result.qps) result.qps = form.providerMaxQps
  return result
})

function range (key) {
  return view.value?.performance.ranges[key] || { min: 1, max: 100 }
}

function gib (mib) {
  if (mib == null) return '—'
  return mib >= 1024 ? `${(mib / 1024).toFixed(mib % 1024 === 0 ? 0 : 1)} GB` : `${mib} MiB`
}

function keyPlaceholder (key) {
  return key.set ? `已设置 ${key.hint}，留空表示不修改` : '未设置'
}

/** Only changed fields are sent, so values from .env are not copied into settings.json. */
function buildChanges () {
  const body = {}
  for (const group of ['pdf', 'image']) {
    const changes = {}
    for (const [key, value] of Object.entries(form[group])) {
      if (key === 'apiKey') {
        if (value && value.trim()) changes.apiKey = value.trim()
      } else if (value !== original[group][key]) {
        changes[key] = value === '' ? null : value
      }
    }
    if (Object.keys(changes).length) body[group] = changes
  }
  const performance = {}
  if (form.tier !== original.tier && form.tier !== 'env') performance.tier = form.tier
  if (form.providerMaxQps !== original.providerMaxQps) performance.providerMaxQps = form.providerMaxQps
  const manual = {}
  for (const field of manualFields) {
    if (form.manual[field.key] !== original.manual[field.key]) manual[field.key] = form.manual[field.key]
  }
  if (Object.keys(manual).length) performance.manual = manual
  if (Object.keys(performance).length) body.performance = performance
  if (form.maxHistory !== original.maxHistory) body.storage = { maxHistory: form.maxHistory }
  return body
}

async function save () {
  if (view.value?.readOnly) return
  const body = buildChanges()
  if (!Object.keys(body).length) {
    message.info('没有需要保存的修改')
    return
  }
  saving.value = true
  saveError.value = ''
  errors.value = {}
  try {
    const res = await saveSettings(body)
    if (res.code !== 200) {
      errors.value = res.fieldErrors || {}
      throw new Error(res.message || '保存失败')
    }
    fill(res.data)
    message.success('设置已保存，下一个任务生效')
    emit('saved', res.data)
  } catch (e) {
    saveError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function resetGroup (group) {
  const fields = group === 'pdf' ? ['baseUrl', 'apiKey', 'model'] : ['baseUrl', 'apiKey', 'model', 'visionModel', 'protocol']
  saving.value = true
  try {
    const res = await saveSettings({ [group]: Object.fromEntries(fields.map((key) => [key, null])) })
    if (res.code !== 200) throw new Error(res.message || '恢复失败')
    fill(res.data)
    message.success('已恢复为 .env / 默认值')
    emit('saved', res.data)
  } catch (e) {
    saveError.value = e.message || '恢复失败'
  } finally {
    saving.value = false
  }
}

async function clearKey (group) {
  saving.value = true
  try {
    const res = await saveSettings({ [group]: { apiKey: null } })
    if (res.code !== 200) throw new Error(res.message || '清除失败')
    fill(res.data)
    message.success('已清除页面保存的 Key')
    emit('saved', res.data)
  } catch (e) {
    saveError.value = e.message || '清除失败'
  } finally {
    saving.value = false
  }
}

function resetManual () {
  for (const field of manualFields) form.manual[field.key] = null
}

async function test (kind) {
  testing[kind] = true
  testResult[kind] = null
  const values = form[kind]
  const body = { kind, baseUrl: values.baseUrl, model: values.model }
  if (values.apiKey && values.apiKey.trim()) body.apiKey = values.apiKey.trim()
  if (kind === 'image') {
    body.visionModel = values.visionModel
    body.protocol = values.protocol
  }
  try {
    const res = await testSettings(body)
    testResult[kind] = res.code === 200 ? res.data : { ok: false, message: res.message || '测试失败' }
  } catch (e) {
    testResult[kind] = { ok: false, message: e.message || '测试失败' }
  } finally {
    testing[kind] = false
  }
}
</script>

<style scoped lang="scss">
.settings-loading {
  padding: 24px 0;
  color: var(--desk-muted);
}

.settings-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.block {
  margin-bottom: 12px;
}

.settings-section {
  padding: 16px 0 20px;
  border-bottom: 1px solid var(--desk-border);

  &:last-of-type {
    border-bottom: none;
  }
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;

  h3 {
    margin: 0;
    font-size: 16px;
    color: var(--desk-text);
  }
}

.section-hint {
  margin: 6px 0 12px;
  font-size: 13px;
  color: var(--desk-muted);
  line-height: 1.6;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
  min-width: 0;
}

.field-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--desk-text);
}

.field-help {
  font-size: 12px;
  color: var(--desk-subtle);
}

.field-error {
  font-size: 12px;
  color: #d03050;
}

.field-pair,
.manual-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 12px;
}

.manual-grid {
  margin-top: 12px;
  align-items: start;
}

.test-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.test-result {
  font-size: 13px;
  word-break: break-word;

  &.ok { color: #18a058; }
  &.bad { color: #d03050; }
}

.profile {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 16px;
  margin: 14px 0 0;
  font-size: 13px;

  dt { color: var(--desk-muted); }
  dd { margin: 0; color: var(--desk-text); font-variant-numeric: tabular-nums; }
}

.clear-key {
  align-self: flex-start;
}

.footer-actions {
  display: flex;
  gap: 8px;
}

code {
  font-size: 12px;
}

@media (max-width: 480px) {
  .field-pair,
  .manual-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
