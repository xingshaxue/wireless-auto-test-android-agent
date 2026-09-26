<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type {
  AgentListItem,
  BatchDetail,
  BatchListResponse,
  BatchOtaRequest,
  BatchStartResult,
  BatchSummary,
  BatchUpgradeWrite,
  BatchVersionCheck,
  FileRecord,
} from '../api/types'

// ---------------- 发起区：文件选择 ----------------
const route = useRoute()
const files = ref<FileRecord[]>([])
const selectedFileId = ref('')

async function fetchFiles() {
  try {
    const resp = await client.get<FileRecord[]>('/files')
    files.value = resp.data
  } catch (e) {
    ElMessage.error(`获取文件列表失败：${errorDetail(e)}`)
  }
}

// ---------------- 发起区：目标设备多选 ----------------
interface TargetRow {
  agentId: string
  deviceMac: string
  state: string | null
  checked: boolean
}

interface AgentGroup {
  agentId: string
  rows: TargetRow[]
}

const agentGroups = ref<AgentGroup[]>([])

async function fetchAgents() {
  try {
    const resp = await client.get<AgentListItem[]>('/agents')
    const online = resp.data.filter((a) => !!a.sessionId)
    // 跨刷新保留勾选状态：按 agentId|deviceMac 回收旧行的 checked
    const checkedMap = new Map<string, boolean>()
    for (const g of agentGroups.value) {
      for (const r of g.rows) checkedMap.set(`${r.agentId}|${r.deviceMac}`, r.checked)
    }
    const groups: AgentGroup[] = []
    for (const a of online) {
      const macs = Object.keys(a.view?.devices ?? {})
      if (macs.length === 0) continue
      groups.push({
        agentId: a.agentId,
        rows: macs.map((mac) => ({
          agentId: a.agentId,
          deviceMac: mac,
          state: a.view?.devices?.[mac]?.state ?? null,
          checked: checkedMap.get(`${a.agentId}|${mac}`) ?? false,
        })),
      })
    }
    agentGroups.value = groups
  } catch (e) {
    ElMessage.error(`获取 agent 列表失败：${errorDetail(e)}`)
  }
}

const selectedTargets = computed(() =>
  agentGroups.value.flatMap((g) =>
    g.rows.filter((r) => r.checked).map((r) => ({ agentId: r.agentId, deviceMac: r.deviceMac })),
  ),
)

function isAllChecked(g: AgentGroup): boolean {
  return g.rows.length > 0 && g.rows.every((r) => r.checked)
}

function isIndeterminate(g: AgentGroup): boolean {
  const n = g.rows.filter((r) => r.checked).length
  return n > 0 && n < g.rows.length
}

function toggleAgent(g: AgentGroup, checked: boolean) {
  for (const r of g.rows) r.checked = checked
}

// ---------------- 发起区：高级参数 ----------------
const advOpen = ref<string[]>([])
const blackoutMs = ref(30000)
const perAgentConcurrency = ref(1)
const reconnectTimeoutMs = ref(600000)
const expectContains = ref('')
const upgradeWriteJson = ref('')
const versionCheckJson = ref('')

// ---------------- 发起区：下发 ----------------
const sending = ref(false)
const canSubmit = computed(
  () => !!selectedFileId.value && selectedTargets.value.length > 0 && !sending.value,
)
const submitHint = computed(() => {
  if (!selectedFileId.value) return '请先选择升级文件'
  if (selectedTargets.value.length === 0) return '请勾选至少一台目标设备'
  return ''
})

async function startBatch() {
  if (!selectedFileId.value) {
    ElMessage.warning('请先选择升级文件')
    return
  }
  if (selectedTargets.value.length === 0) {
    ElMessage.warning('请勾选至少一台目标设备')
    return
  }
  let upgradeWrite: BatchUpgradeWrite | undefined
  if (upgradeWriteJson.value.trim()) {
    try {
      upgradeWrite = JSON.parse(upgradeWriteJson.value) as BatchUpgradeWrite
    } catch (e) {
      ElMessage.error(`upgradeWrite JSON 非法：${(e as Error).message}`)
      return
    }
  }
  let versionCheck: BatchVersionCheck | undefined
  if (versionCheckJson.value.trim()) {
    try {
      versionCheck = JSON.parse(versionCheckJson.value) as BatchVersionCheck
    } catch (e) {
      ElMessage.error(`versionCheck JSON 非法：${(e as Error).message}`)
      return
    }
  } else if (expectContains.value.trim()) {
    versionCheck = { expectContains: expectContains.value.trim() }
  }
  const body: BatchOtaRequest = {
    fileId: selectedFileId.value,
    targets: selectedTargets.value,
    blackoutMs: blackoutMs.value,
    perAgentConcurrency: perAgentConcurrency.value,
    reconnectTimeoutMs: reconnectTimeoutMs.value,
  }
  if (upgradeWrite) body.upgradeWrite = upgradeWrite
  if (versionCheck) body.versionCheck = versionCheck
  sending.value = true
  try {
    const resp = await client.post<BatchStartResult>('/batch/ota', body)
    ElMessage.success(`批量 OTA 已下发：${resp.data.batchId}（${body.targets.length} 台设备）`)
    selectedBatchId.value = resp.data.batchId
    fetchBatches()
    fetchDetail(resp.data.batchId)
  } catch (e) {
    ElMessage.error(`下发失败：${errorDetail(e)}`)
  } finally {
    sending.value = false
  }
}

// ---------------- 批次列表（3s 轮询） ----------------
const running = ref<BatchSummary[]>([])
const done = ref<BatchSummary[]>([])
let pollTimer: ReturnType<typeof setInterval> | null = null

async function fetchBatches() {
  try {
    const resp = await client.get<BatchListResponse>('/batch')
    running.value = resp.data.running ?? []
    done.value = resp.data.done ?? []
  } catch (e) {
    ElMessage.error(`获取批次列表失败：${errorDetail(e)}`)
  }
}

function stateTagType(state: string): string {
  switch (state) {
    case 'DONE': return 'success'
    case 'CANCELLED': return 'info'
    case 'RUNNING': return 'primary'
    default: return 'warning'
  }
}

function phaseTagType(phase: string): string {
  switch (phase) {
    case 'DONE': return 'success'
    case 'FAILED': return 'danger'
    case 'CANCELLED': return 'info'
    case 'TRANSFERRING':
    case 'VERIFYING': return 'primary'
    default: return 'warning' // QUEUED / UPGRADE_CMD / BLACKOUT / RECONNECTING
  }
}

function summaryText(b: BatchSummary): string {
  const s = b.summary
  if (!s) return '-'
  return `${s.succeeded}/${s.total} 成功，${s.failed} 失败`
}

// ---------------- 批次详情 ----------------
const selectedBatchId = ref('')
const detail = ref<BatchDetail | null>(null)

async function fetchDetail(batchId: string) {
  try {
    const resp = await client.get<BatchDetail>(`/batch/${batchId}`)
    detail.value = resp.data
  } catch (e) {
    ElMessage.error(`获取批次详情失败：${errorDetail(e)}`)
  }
}

function viewBatch(b: BatchSummary) {
  selectedBatchId.value = b.batchId
  fetchDetail(b.batchId)
}

const detailPercent = computed(() => {
  const s = detail.value?.summary
  if (!s || !s.total) return 0
  return Math.round(((s.succeeded + s.failed) * 100) / s.total)
})

async function cancelBatch(batchId: string) {
  try {
    await ElMessageBox.confirm(`确认取消批次 ${batchId}？未开始的设备将不再升级。`, '取消确认', {
      type: 'warning',
      confirmButtonText: '取消批次',
      cancelButtonText: '返回',
    })
  } catch {
    return
  }
  try {
    await client.post(`/batch/${batchId}/cancel`)
    ElMessage.success('已请求取消批次')
    fetchBatches()
    fetchDetail(batchId)
  } catch (e) {
    ElMessage.error(`取消失败：${errorDetail(e)}`)
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

onMounted(async () => {
  await fetchFiles()
  // 从文件页「批量 OTA」按钮跳入时预选文件
  const q = route.query.fileId
  const preselect = typeof q === 'string' ? q : ''
  if (preselect && files.value.some((f) => f.fileId === preselect)) {
    selectedFileId.value = preselect
  }
  fetchAgents()
  fetchBatches()
  pollTimer = setInterval(() => {
    fetchBatches()
    if (selectedBatchId.value) fetchDetail(selectedBatchId.value)
  }, 3000)
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="batch-ota-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>发起批量 OTA</span>
          <el-button size="small" @click="() => { fetchFiles(); fetchAgents() }">刷新文件/设备</el-button>
        </div>
      </template>
      <el-form label-width="120px">
        <el-form-item label="升级文件">
          <el-select
            v-model="selectedFileId"
            filterable
            placeholder="选择要下发的固件文件"
            class="file-select"
          >
            <el-option
              v-for="f in files"
              :key="f.fileId"
              :label="`${f.name}（${fmtSize(f.size)}）`"
              :value="f.fileId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标设备">
          <div class="targets-block">
            <template v-if="agentGroups.length > 0">
              <div v-for="g in agentGroups" :key="g.agentId" class="agent-group">
                <div class="agent-group-header">
                  <el-checkbox
                    :model-value="isAllChecked(g)"
                    :indeterminate="isIndeterminate(g)"
                    @change="(v: string | number | boolean) => toggleAgent(g, Boolean(v))"
                  >
                    <span class="mono">{{ g.agentId }}</span>
                    <span class="hint">（{{ g.rows.length }} 台设备）</span>
                  </el-checkbox>
                </div>
                <el-table :data="g.rows" size="small">
                  <el-table-column width="50">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.checked" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="deviceMac" label="deviceMac" min-width="150" />
                  <el-table-column label="设备状态" min-width="120">
                    <template #default="{ row }">{{ row.state ?? '-' }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
            <el-empty v-else description="暂无在线 agent 或其视图中无设备" :image-size="60" />
          </div>
        </el-form-item>
        <el-form-item label="高级参数">
          <el-collapse v-model="advOpen" class="full-width">
            <el-collapse-item title="断连窗口 / 并发 / 重连超时 / 版本校验（缺省 p67 默认）" name="adv">
              <el-form label-width="180px" class="adv-form">
                <el-form-item label="blackoutMs（升级断连窗口）">
                  <el-input-number v-model="blackoutMs" :min="0" :step="1000" controls-position="right" />
                </el-form-item>
                <el-form-item label="perAgentConcurrency（单 agent 并发）">
                  <el-input-number v-model="perAgentConcurrency" :min="1" :max="32" controls-position="right" />
                </el-form-item>
                <el-form-item label="reconnectTimeoutMs（重连超时）">
                  <el-input-number v-model="reconnectTimeoutMs" :min="0" :step="10000" controls-position="right" />
                </el-form-item>
                <el-form-item label="期望版本包含">
                  <el-input
                    v-model="expectContains"
                    placeholder="如 3.101；留空则不校验（被 versionCheck JSON 覆盖）"
                    clearable
                  />
                </el-form-item>
                <el-form-item label="upgradeWrite JSON">
                  <el-input
                    v-model="upgradeWriteJson"
                    type="textarea"
                    :rows="3"
                    placeholder='可选，完整覆盖：{"service":"...","char":"...","payloadBase64":"..."}'
                    spellcheck="false"
                  />
                </el-form-item>
                <el-form-item label="versionCheck JSON">
                  <el-input
                    v-model="versionCheckJson"
                    type="textarea"
                    :rows="3"
                    placeholder='可选，完整覆盖：{"service":"...","char":"...","writePayloadBase64":"...","expectContains":"3.101"}'
                    spellcheck="false"
                  />
                </el-form-item>
              </el-form>
            </el-collapse-item>
          </el-collapse>
        </el-form-item>
        <el-form-item>
          <div class="submit-row">
            <el-button type="primary" :loading="sending" :disabled="!canSubmit" @click="startBatch">
              下发批量 OTA（已选 {{ selectedTargets.length }} 台）
            </el-button>
            <span v-if="submitHint" class="hint">{{ submitHint }}</span>
          </div>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>进行中批次（3s 轮询）</template>
      <el-table :data="running" size="small">
        <el-table-column prop="batchId" label="batchId" min-width="150" show-overflow-tooltip />
        <el-table-column label="fileId" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fileId ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.state ?? 'RUNNING')" size="small">
              {{ row.state ?? 'RUNNING' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ fmtTs(row.createdTs) }}</template>
        </el-table-column>
        <el-table-column label="汇总" min-width="140">
          <template #default="{ row }">{{ summaryText(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="viewBatch(row)">查看</el-button>
            <el-button size="small" type="warning" @click="cancelBatch(row.batchId)">取消批次</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无进行中批次</template>
      </el-table>

      <template v-if="detail">
        <div class="detail-header">
          <span class="detail-title mono">{{ detail.batchId }}</span>
          <el-tag :type="stateTagType(detail.state)" size="small">{{ detail.state }}</el-tag>
          <span class="hint">
            文件 {{ detail.fileId }} · 创建 {{ fmtTs(detail.createdTs) }} · 结束 {{ fmtTs(detail.finishedTs) }}
          </span>
          <el-button
            v-if="detail.state === 'RUNNING'"
            size="small"
            type="warning"
            @click="cancelBatch(detail.batchId)"
          >
            取消批次
          </el-button>
        </div>
        <div class="summary-bar">
          <el-tag>总数 {{ detail.summary.total }}</el-tag>
          <el-tag type="success">成功 {{ detail.summary.succeeded }}</el-tag>
          <el-tag type="danger">失败 {{ detail.summary.failed }}</el-tag>
          <el-tag type="warning">剩余 {{ detail.summary.remaining }}</el-tag>
          <el-progress
            :percentage="detailPercent"
            :status="detail.state === 'DONE' && detail.summary.failed === 0 ? 'success' : undefined"
            class="summary-progress"
          />
        </div>
        <el-table :data="detail.devices" size="small">
          <el-table-column prop="agentId" label="agentId" min-width="130" show-overflow-tooltip />
          <el-table-column prop="deviceMac" label="deviceMac" min-width="140" />
          <el-table-column label="相位" width="130">
            <template #default="{ row }">
              <el-tag :type="phaseTagType(row.phase)" size="small">{{ row.phase }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="验证版本" width="110">
            <template #default="{ row }">{{ row.version ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="taskId" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.taskId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="error" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="detail" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.detail ?? '-' }}</template>
          </el-table-column>
          <template #empty>暂无设备明细</template>
        </el-table>
      </template>
    </el-card>

    <el-card shadow="never">
      <template #header>历史批次</template>
      <el-table :data="done" size="small">
        <el-table-column prop="batchId" label="batchId" min-width="150" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.state ?? '')" size="small">{{ row.state ?? '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ fmtTs(row.createdTs) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="160">
          <template #default="{ row }">{{ fmtTs(row.finishedTs) }}</template>
        </el-table-column>
        <el-table-column label="汇总" min-width="140">
          <template #default="{ row }">{{ summaryText(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="viewBatch(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无历史批次</template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.batch-ota-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.file-select {
  width: 420px;
}
.targets-block {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.agent-group-header {
  margin-bottom: 4px;
}
.adv-form {
  padding-top: 8px;
}
.submit-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.detail-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0 8px;
}
.detail-title {
  font-weight: 600;
}
.summary-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.summary-progress {
  flex: 1;
  min-width: 200px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.mono {
  font-family: monospace;
}
.full-width {
  width: 100%;
}
</style>
