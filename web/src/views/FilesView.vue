<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type { AgentListItem, ExportStartResult, FileRecord, TransferTask } from '../api/types'

const TERMINAL_STATES = ['COMPLETED', 'FAILED', 'CANCELLED']

// ---------------- 文件区 ----------------
const files = ref<FileRecord[]>([])
const uploadPercent = ref(-1) // -1 = 无上传
const uploadName = ref('')

async function fetchFiles() {
  try {
    const resp = await client.get<FileRecord[]>('/files')
    files.value = resp.data
  } catch (e) {
    ElMessage.error(`获取文件列表失败：${errorDetail(e)}`)
  }
}

// multipart 字段名 file（routes.py upload_file）
async function doUpload(options: UploadRequestOptions) {
  uploadName.value = options.file.name
  uploadPercent.value = 0
  const form = new FormData()
  form.append('file', options.file)
  try {
    await client.post('/files', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (ev) => {
        if (ev.total) uploadPercent.value = Math.round((ev.loaded * 100) / ev.total)
      },
    })
    ElMessage.success('上传成功')
    options.onSuccess({})
    fetchFiles()
  } catch (e) {
    ElMessage.error(`上传失败：${errorDetail(e)}`)
    options.onError(e as unknown as Parameters<typeof options.onError>[0])
  } finally {
    uploadPercent.value = -1
  }
}

async function removeFile(f: FileRecord) {
  try {
    await ElMessageBox.confirm(`确认删除文件 ${f.name}（${f.fileId}）？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await client.delete(`/files/${f.fileId}`)
    ElMessage.success('已删除')
    fetchFiles()
  } catch (e) {
    ElMessage.error(`删除失败：${errorDetail(e)}`)
  }
}

function downloadFile(f: FileRecord) {
  window.open(`/api/files/${f.fileId}/download`)
}

// ---------------- 从设备拉取（FILE_EXPORT，docs/02 B.6） ----------------
const exportVisible = ref(false)
const exportAgentId = ref('')
const exportMac = ref('')
const exportPath = ref('')
const exportIsDir = ref(false)
const exportSending = ref(false)

// 选中 agent 视图内的设备 MAC 列表；无视图时仍可手输（allow-create）
const exportMacOptions = computed(() => {
  const a = agents.value.find((x) => x.agentId === exportAgentId.value)
  return Object.keys(a?.view?.devices ?? {})
})

function openExport() {
  exportAgentId.value = agents.value[0]?.agentId ?? ''
  exportMac.value = ''
  exportPath.value = ''
  exportIsDir.value = false
  exportVisible.value = true
  fetchAgents()
}

async function startExport() {
  if (!exportAgentId.value || !exportMac.value.trim() || !exportPath.value.trim()) {
    ElMessage.warning('请填写 agent、设备 MAC 与设备侧路径')
    return
  }
  let remotePath = exportPath.value.trim()
  if (exportIsDir.value && !remotePath.endsWith('/')) remotePath += '/'
  exportSending.value = true
  try {
    const resp = await client.post<ExportStartResult>('/exports', {
      agentId: exportAgentId.value,
      deviceMac: exportMac.value.trim(),
      remotePath,
    })
    ElMessage.success(`导出任务已下发：${resp.data.exportId}（结果稍后入库，可刷新查看）`)
    exportVisible.value = false
    fetchFiles()
  } catch (e) {
    ElMessage.error(`下发失败：${errorDetail(e)}`)
  } finally {
    exportSending.value = false
  }
}

// ---------------- 发起传输 ----------------
const agents = ref<AgentListItem[]>([])
const transferFile = ref<FileRecord | null>(null)
const transferVisible = ref(false)
const transferAgentId = ref('')
const transferMac = ref('')
const transferSending = ref(false)

async function fetchAgents() {
  try {
    const resp = await client.get<AgentListItem[]>('/agents')
    agents.value = resp.data.filter((a) => !!a.sessionId)
  } catch (e) {
    ElMessage.error(`获取 agent 列表失败：${errorDetail(e)}`)
  }
}

function openTransfer(f: FileRecord) {
  transferFile.value = f
  transferAgentId.value = agents.value[0]?.agentId ?? ''
  transferMac.value = ''
  transferVisible.value = true
  fetchAgents()
}

async function startTransfer() {
  if (!transferFile.value || !transferAgentId.value || !transferMac.value.trim()) {
    ElMessage.warning('请填写 agentId 与 deviceMac')
    return
  }
  transferSending.value = true
  try {
    const resp = await client.post<{ taskId: string }>(
      `/files/${transferFile.value.fileId}/transfer`,
      { agentId: transferAgentId.value, deviceMac: transferMac.value.trim() })
    ElMessage.success(`传输任务已创建：${resp.data.taskId}`)
    transferVisible.value = false
    fetchTasks()
  } catch (e) {
    ElMessage.error(`创建失败：${errorDetail(e)}`)
  } finally {
    transferSending.value = false
  }
}

// ---------------- 任务区 ----------------
const tasks = ref<TransferTask[]>([])
let tasksTimer: ReturnType<typeof setInterval> | null = null

async function fetchTasks() {
  try {
    const resp = await client.get<TransferTask[]>('/transfers')
    tasks.value = resp.data
  } catch (e) {
    ElMessage.error(`获取传输任务失败：${errorDetail(e)}`)
  }
}

async function cancelTask(t: TransferTask) {
  try {
    await client.post(`/transfers/${t.taskId}/cancel`)
    ElMessage.success('已取消')
    fetchTasks()
  } catch (e) {
    ElMessage.error(`取消失败：${errorDetail(e)}`)
  }
}

function phaseText(t: TransferTask): string {
  const dl = Math.round(t.downloadPercent ?? 0)
  if (t.state === 'COMPLETED') return '完成'
  if (t.state === 'PUSHING' || dl < 100) return `下载中 ${dl}%`
  const devBytes = Math.round((t.percent / 100) * t.size)
  return `已下载，传输中 ${Math.round(t.percent)}%（${formatSize(devBytes)}/${formatSize(t.size)}）`
}

function formatSize(bytes: number): string {
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + 'MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + 'KB'
  return bytes + 'B'
}

function stateTagType(state: string): string {
  switch (state) {
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'CANCELLED': return 'info'
    case 'PAUSED': return 'warning'
    default: return 'primary' // WAIT_READY / PUSHING / DOWNLOADED
  }
}

// ---------------- 日志上传 ----------------
const logVisible = ref(false)
const logAgentId = ref('')
const logSinceTs = ref<number | undefined>(undefined)
const logMinLevel = ref('')
const logSending = ref(false)

function openLogUpload() {
  logAgentId.value = agents.value[0]?.agentId ?? ''
  logSinceTs.value = undefined
  logMinLevel.value = ''
  logVisible.value = true
  fetchAgents()
}

async function requestLogUpload() {
  if (!logAgentId.value) {
    ElMessage.warning('请选择 agent')
    return
  }
  const body: Record<string, unknown> = {}
  if (logSinceTs.value != null) body.sinceTs = logSinceTs.value
  if (logMinLevel.value) body.minLevel = logMinLevel.value
  logSending.value = true
  try {
    const resp = await client.post<{ requestId: string }>(
      `/agents/${logAgentId.value}/log-upload`, body)
    ElMessage.success(`日志上传请求已下发，requestId：${resp.data.requestId}`)
    logVisible.value = false
  } catch (e) {
    ElMessage.error(`下发失败：${errorDetail(e)}`)
  } finally {
    logSending.value = false
  }
}

// ---------------- 工具 ----------------
function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}

function fmtTs(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString()
}

function shortSha(s: string): string {
  return s.length > 12 ? `${s.slice(0, 12)}…` : s
}

onMounted(() => {
  fetchFiles()
  fetchTasks()
  fetchAgents()
  // 导出结果异步入库：随任务轮询一并刷新文件列表
  tasksTimer = setInterval(() => {
    fetchTasks()
    fetchFiles()
  }, 2000)
})
onBeforeUnmount(() => {
  if (tasksTimer) clearInterval(tasksTimer)
})
</script>

<template>
  <div class="files-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>文件管理</span>
          <div class="header-actions">
            <el-button size="small" @click="openExport">从设备拉取</el-button>
            <el-button size="small" @click="openLogUpload">请求日志上传</el-button>
            <el-button size="small" @click="fetchFiles">刷新</el-button>
            <el-upload :show-file-list="false" :http-request="doUpload" class="uploader">
              <el-button size="small" type="primary">上传文件</el-button>
            </el-upload>
          </div>
        </div>
      </template>
      <el-progress
        v-if="uploadPercent >= 0"
        :percentage="uploadPercent"
        class="upload-progress"
      >
        <span>{{ uploadName }} {{ uploadPercent }}%</span>
      </el-progress>
      <el-table :data="files" size="small">
        <el-table-column prop="fileId" label="fileId" min-width="130" show-overflow-tooltip />
        <el-table-column prop="name" label="文件名" min-width="160" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="sha256" min-width="120">
          <template #default="{ row }">
            <el-tooltip :content="row.sha256" placement="top">
              <span class="mono">{{ shortSha(row.sha256) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" min-width="160">
          <template #default="{ row }">{{ fmtTs(row.createdTs) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag :type="row.origin === 'device' ? 'success' : 'info'" size="small">
              {{ row.origin === 'device' ? '设备' : '上传' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="openTransfer(row)">发起传输</el-button>
            <el-button size="small" @click="downloadFile(row)">下载</el-button>
            <el-button size="small" type="danger" @click="removeFile(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无文件</template>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>传输任务（2s 轮询）</template>
      <el-table :data="tasks" size="small">
        <el-table-column prop="taskId" label="taskId" min-width="130" show-overflow-tooltip />
        <el-table-column prop="fileId" label="fileId" min-width="120" show-overflow-tooltip />
        <el-table-column prop="agentId" label="agentId" min-width="130" show-overflow-tooltip />
        <el-table-column prop="deviceMac" label="deviceMac" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.state)" size="small">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="200">
          <template #default="{ row }">
            <el-tag
              v-if="row.state === 'WAIT_READY' || row.state === 'PAUSED'"
              :type="stateTagType(row.state)"
              size="small"
            >
              {{ row.state === 'PAUSED' ? '已暂停' : '等待设备' }}
            </el-tag>
            <el-progress
              v-else-if="row.state === 'FAILED' || row.state === 'CANCELLED'"
              :percentage="Math.round(row.percent)"
              :status="row.state === 'FAILED' ? 'exception' : undefined"
            />
            <div v-else class="progress-two-stage">
              <div class="stage-caption">{{ phaseText(row) }}</div>
              <div class="stage-row">
                <span class="stage-label">下载</span>
                <el-progress
                  :percentage="Math.round(row.downloadPercent ?? 0)"
                  :stroke-width="8"
                  color="#409eff"
                  class="stage-bar"
                />
              </div>
              <div v-if="(row.downloadPercent ?? 0) >= 100" class="stage-row">
                <span class="stage-label">传输</span>
                <el-progress
                  :percentage="Math.round(row.percent)"
                  :stroke-width="8"
                  color="#67c23a"
                  :status="row.state === 'COMPLETED' ? 'success' : undefined"
                  class="stage-bar"
                />
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="下载块" width="90">
          <template #default="{ row }">{{ row.ackedSeq }}/{{ row.totalChunks }}</template>
        </el-table-column>
        <el-table-column label="errorCode" width="90">
          <template #default="{ row }">{{ row.errorCode ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="detail" label="detail" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.detail ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              type="warning"
              :disabled="TERMINAL_STATES.includes(row.state)"
              @click="cancelTask(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
        <template #empty>暂无传输任务</template>
      </el-table>
    </el-card>

    <el-dialog v-model="transferVisible" :title="`发起传输 — ${transferFile?.name ?? ''}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="agentId">
          <el-select v-model="transferAgentId" placeholder="选择在线 agent" class="full-width">
            <el-option v-for="a in agents" :key="a.agentId" :label="a.agentId" :value="a.agentId" />
          </el-select>
        </el-form-item>
        <el-form-item label="deviceMac">
          <el-input v-model="transferMac" placeholder="AA:BB:CC:DD:EE:01" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="transferSending" @click="startTransfer">创建任务</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="exportVisible" title="从设备拉取文件（FILE_EXPORT）" width="480px">
      <el-form label-width="110px">
        <el-form-item label="agentId">
          <el-select v-model="exportAgentId" placeholder="选择在线 agent" class="full-width">
            <el-option v-for="a in agents" :key="a.agentId" :label="a.agentId" :value="a.agentId" />
          </el-select>
        </el-form-item>
        <el-form-item label="deviceMac">
          <el-select
            v-model="exportMac"
            filterable
            allow-create
            placeholder="选择或输入设备 MAC"
            class="full-width"
          >
            <el-option v-for="m in exportMacOptions" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备侧路径">
          <el-input v-model="exportPath" placeholder="/data/a.bin 或 /logs/" />
        </el-form-item>
        <el-form-item label="目录模式">
          <el-checkbox v-model="exportIsDir">路径为目录（AT^LS 列举全部文件）</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="exportSending" @click="startExport">下发导出</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="logVisible" title="请求 agent 日志上传" width="480px">      <el-form label-width="140px">
        <el-form-item label="agentId">
          <el-select v-model="logAgentId" placeholder="选择在线 agent" class="full-width">
            <el-option v-for="a in agents" :key="a.agentId" :label="a.agentId" :value="a.agentId" />
          </el-select>
        </el-form-item>
        <el-form-item label="sinceTs（可选，毫秒）">
          <el-input-number v-model="logSinceTs" :min="0" controls-position="right" class="full-width" />
        </el-form-item>
        <el-form-item label="minLevel（可选）">
          <el-select v-model="logMinLevel" clearable class="full-width">
            <el-option v-for="l in ['DEBUG', 'INFO', 'WARN', 'ERROR']" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" :loading="logSending" @click="requestLogUpload">下发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.files-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.uploader {
  display: inline-flex;
}
.upload-progress {
  margin-bottom: 12px;
}
.mono {
  font-family: monospace;
}
.progress-two-stage {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stage-caption {
  font-size: 12px;
  line-height: 1.2;
  color: var(--el-text-color-secondary);
}
.stage-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.stage-label {
  flex: none;
  width: 28px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stage-bar {
  flex: 1;
}
.full-width {
  width: 100%;
}
</style>
