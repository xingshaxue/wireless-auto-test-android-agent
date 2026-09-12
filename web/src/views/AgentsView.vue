<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type {
  AgentListItem,
  AgentStatus,
  CommandAckResult,
  LedgerSnapshot,
} from '../api/types'

// ---------------- Agent 列表与选中 ----------------
const agents = ref<AgentListItem[]>([])
const selectedAgentId = ref('')
const status = ref<AgentStatus | null>(null)
const statusLoading = ref(false)
let agentsTimer: ReturnType<typeof setInterval> | null = null

async function fetchAgents() {
  try {
    const resp = await client.get<AgentListItem[]>('/agents')
    agents.value = resp.data
    if (!selectedAgentId.value && agents.value.length > 0) {
      selectAgent(agents.value[0].agentId)
    }
  } catch (e) {
    ElMessage.error(`获取 agent 列表失败：${errorDetail(e)}`)
  }
}

async function fetchStatus(agentId: string) {
  statusLoading.value = true
  try {
    const resp = await client.get<AgentStatus>(`/agents/${agentId}/status`)
    status.value = resp.data
  } catch (e) {
    status.value = null
    ElMessage.error(`获取状态失败：${errorDetail(e)}`)
  } finally {
    statusLoading.value = false
  }
}

function selectAgent(agentId: string) {
  selectedAgentId.value = agentId
  fetchStatus(agentId)
}

onMounted(() => {
  fetchAgents()
  fetchLedger()
  agentsTimer = setInterval(fetchAgents, 5000)
  ledgerTimer = setInterval(fetchLedger, 10000)
})
onBeforeUnmount(() => {
  if (agentsTimer) clearInterval(agentsTimer)
  if (ledgerTimer) clearInterval(ledgerTimer)
})

// ---------------- 命令下发 ----------------
interface CmdField {
  key: string
  label: string
  kind: 'text' | 'number' | 'switch' | 'textarea' | 'select'
  options?: string[]
  placeholder?: string
}

// 字段与 server/src/wireless_server/protocol/commands.py 逐一对齐
const CMD_FIELDS: Record<string, CmdField[]> = {
  CONNECT_DEVICE: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    { key: 'deviceId', label: 'deviceId（可选）', kind: 'text' },
    { key: 'lazyConnect', label: 'lazyConnect', kind: 'switch' },
  ],
  DISCONNECT_DEVICE: [{ key: 'deviceMac', label: 'deviceMac', kind: 'text' }],
  READ_CHAR: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    { key: 'service', label: 'service', kind: 'text', placeholder: '短格式或 128 位 UUID' },
    { key: 'char', label: 'char', kind: 'text', placeholder: '短格式或 128 位 UUID' },
    { key: 'timeoutMs', label: 'timeoutMs（可选）', kind: 'number' },
  ],
  WRITE_CHAR: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    { key: 'service', label: 'service', kind: 'text' },
    { key: 'char', label: 'char', kind: 'text' },
    { key: 'payload', label: 'payload（base64）', kind: 'text', placeholder: '如 AQID' },
    {
      key: 'writeType', label: 'writeType', kind: 'select',
      options: ['WITH_RESPONSE', 'NO_RESPONSE'],
    },
    { key: 'timeoutMs', label: 'timeoutMs（可选）', kind: 'number' },
  ],
  START_POLLING: [{ key: 'deviceMac', label: 'deviceMac', kind: 'text' }],
  STOP_POLLING: [{ key: 'deviceMac', label: 'deviceMac', kind: 'text' }],
  SET_POLLING_INTERVAL: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    { key: 'intervalMs', label: 'intervalMs（≥200）', kind: 'number' },
  ],
  SET_POLL_RULES: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    {
      key: 'rules', label: 'rules（JSON 数组）', kind: 'textarea',
      placeholder: '[{"service":"180A","char":"2A29","intervalMs":1000}]',
    },
  ],
  SET_MAX_CONNECTIONS: [{ key: 'maxSlots', label: 'maxSlots（2~5）', kind: 'number' }],
  SET_PERSISTENT_DEVICE: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    { key: 'on', label: 'on', kind: 'switch' },
  ],
  REMOVE_DEVICE: [{ key: 'deviceMac', label: 'deviceMac', kind: 'text' }],
  PAUSE_DEVICE: [
    { key: 'deviceMac', label: 'deviceMac', kind: 'text' },
    { key: 'abortTransfer', label: 'abortTransfer', kind: 'switch' },
  ],
  RESUME_DEVICE: [{ key: 'deviceMac', label: 'deviceMac', kind: 'text' }],
  UPLOAD_LOG: [
    { key: 'sinceTs', label: 'sinceTs（可选，毫秒）', kind: 'number' },
    { key: 'minLevel', label: 'minLevel（可选）', kind: 'text', placeholder: 'DEBUG/INFO/WARN/ERROR' },
  ],
  GET_STATUS: [{ key: 'deviceMac', label: 'deviceMac（可选）', kind: 'text' }],
  RESET: [],
  FILE_CANCEL: [{ key: 'taskId', label: 'taskId', kind: 'text' }],
}
const CMD_TYPES = Object.keys(CMD_FIELDS)

const cmdType = ref('CONNECT_DEVICE')
const cmdForm = reactive<Record<string, unknown>>({})
const sending = ref(false)
const cmdResult = ref<CommandAckResult | null>(null)
const cmdError = ref('')

const currentFields = computed(() => CMD_FIELDS[cmdType.value] ?? [])

watch(cmdType, () => {
  // 切换类型时重置表单并填默认值
  Object.keys(cmdForm).forEach((k) => delete cmdForm[k])
  cmdResult.value = null
  cmdError.value = ''
  if (cmdType.value === 'WRITE_CHAR') cmdForm.writeType = 'WITH_RESPONSE'
  if (cmdType.value === 'SET_PERSISTENT_DEVICE') cmdForm.on = true
}, { immediate: true })

function buildCommandBody(): Record<string, unknown> {
  const body: Record<string, unknown> = { type: cmdType.value }
  for (const f of currentFields.value) {
    const v = cmdForm[f.key]
    if (v === undefined || v === null || v === '') continue
    if (f.kind === 'textarea') {
      body[f.key] = JSON.parse(String(v)) // 非法 JSON 会在此处抛错，由调用方捕获
    } else if (f.kind === 'number') {
      body[f.key] = Number(v)
    } else {
      body[f.key] = v
    }
  }
  return body
}

function friendlyCmdError(e: unknown): string {
  const err = e as AxiosError<{ detail?: unknown }>
  const code = err.response?.status
  const detail = errorDetail(e)
  if (code === 404) return `agent 不在线或不存在：${detail}`
  if (code === 409) return `操作冲突（agent 忙或状态不允许）：${detail}`
  if (code === 504) return `命令 ACK 超时或失败：${detail}`
  return detail
}

async function sendCommand() {
  if (!selectedAgentId.value) {
    ElMessage.warning('请先选择 agent')
    return
  }
  let body: Record<string, unknown>
  try {
    body = buildCommandBody()
  } catch {
    ElMessage.error('JSON 字段格式非法，请检查输入')
    return
  }
  sending.value = true
  cmdResult.value = null
  cmdError.value = ''
  try {
    const resp = await client.post<CommandAckResult>(
      `/commands/${selectedAgentId.value}`, body)
    cmdResult.value = resp.data
    ElMessage.success('命令已下发并收到 ACK')
    fetchLedger()
  } catch (e) {
    cmdError.value = friendlyCmdError(e)
  } finally {
    sending.value = false
  }
}

// ---------------- 台账 ----------------
const ledger = ref<LedgerSnapshot | null>(null)
let ledgerTimer: ReturnType<typeof setInterval> | null = null

async function fetchLedger() {
  try {
    const resp = await client.get<LedgerSnapshot>('/ledger')
    ledger.value = resp.data
  } catch (e) {
    ElMessage.error(`获取台账失败：${errorDetail(e)}`)
  }
}

function prettyJson(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function fmtTs(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div class="agents-page">
    <div class="agent-list">
      <el-card shadow="never">
        <template #header>Agent 列表（5s 轮询）</template>
        <el-menu :default-active="selectedAgentId" @select="selectAgent">
          <el-menu-item v-for="a in agents" :key="a.agentId" :index="a.agentId">
            <span>{{ a.agentId }}</span>
            <el-tag
              :type="a.sessionId ? 'success' : 'info'"
              size="small"
              class="state-tag"
            >
              {{ a.sessionId ? '在线' : '离线' }}
            </el-tag>
          </el-menu-item>
        </el-menu>
        <el-empty v-if="agents.length === 0" description="暂无 agent" :image-size="60" />
      </el-card>
    </div>

    <div class="agent-detail" v-if="selectedAgentId">
      <el-card shadow="never" v-loading="statusLoading">
        <template #header>状态视图 — {{ selectedAgentId }}</template>
        <template v-if="status">
          <el-descriptions :column="3" size="small" border>
            <el-descriptions-item label="状态">
              <el-tag :type="status.state === 'online' ? 'success' : 'info'" size="small">
                {{ status.state === 'online' ? '在线' : '离线' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="sessionId">{{ status.sessionId ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="configVersion">{{ status.configVersion ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="视图更新时间">{{ fmtTs(status.view?.updated_ts) }}</el-descriptions-item>
            <el-descriptions-item label="最近下线">{{ fmtTs(status.lastOfflineTs) }}</el-descriptions-item>
            <el-descriptions-item label="设备数">{{ Object.keys(status.view?.devices ?? {}).length }}</el-descriptions-item>
          </el-descriptions>
          <el-collapse class="status-detail">
            <el-collapse-item title="完整视图 / profile / status（JSON）">
              <pre class="json-block">{{ prettyJson(status) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </template>
        <el-empty v-else description="暂无状态数据" :image-size="60" />
      </el-card>

      <el-card shadow="never">
        <template #header>命令下发</template>
        <el-form label-width="160px" size="default">
          <el-form-item label="命令类型">
            <el-select v-model="cmdType" class="cmd-type-select">
              <el-option v-for="t in CMD_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
          <el-form-item v-for="f in currentFields" :key="f.key" :label="f.label">
            <el-input
              v-if="f.kind === 'text'"
              v-model="(cmdForm[f.key] as string)"
              :placeholder="f.placeholder"
            />
            <el-input-number
              v-else-if="f.kind === 'number'"
              v-model="(cmdForm[f.key] as number)"
              :min="0"
              controls-position="right"
            />
            <el-switch v-else-if="f.kind === 'switch'" v-model="(cmdForm[f.key] as boolean)" />
            <el-input
              v-else-if="f.kind === 'textarea'"
              v-model="(cmdForm[f.key] as string)"
              type="textarea"
              :rows="4"
              :placeholder="f.placeholder"
            />
            <el-select v-else v-model="(cmdForm[f.key] as string)">
              <el-option v-for="o in f.options" :key="o" :label="o" :value="o" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="sending" @click="sendCommand">下发命令</el-button>
          </el-form-item>
        </el-form>
        <el-alert v-if="cmdError" type="error" :title="cmdError" show-icon :closable="false" />
        <template v-if="cmdResult">
          <el-descriptions :column="2" size="small" border class="cmd-result">
            <el-descriptions-item label="requestId">{{ cmdResult.requestId }}</el-descriptions-item>
            <el-descriptions-item label="ledgerStatus">
              <el-tag :type="cmdResult.ledgerStatus === 'acked' ? 'success' : 'danger'" size="small">
                {{ cmdResult.ledgerStatus }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="errorCode">{{ cmdResult.errorCode }}</el-descriptions-item>
            <el-descriptions-item label="rawStatus">{{ cmdResult.rawStatus ?? '-' }}</el-descriptions-item>
          </el-descriptions>
          <pre v-if="cmdResult.result != null" class="json-block">{{ prettyJson(cmdResult.result) }}</pre>
        </template>
      </el-card>

      <el-card shadow="never">
        <template #header>命令台账（10s 轮询）</template>
        <template v-if="ledger">
          <div class="ledger-stats">
            <el-tag>在途 {{ ledger.stats.pending }}</el-tag>
            <el-tag type="success">ACK {{ ledger.stats.acked }}</el-tag>
            <el-tag type="danger">失败 {{ ledger.stats.failed }}</el-tag>
            <el-tag type="warning">限流 {{ ledger.stats.throttled }}</el-tag>
          </div>
          <el-table :data="ledger.pending" size="small">
            <el-table-column prop="requestId" label="requestId" min-width="180" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" min-width="150" />
            <el-table-column prop="agentId" label="agentId" min-width="130" />
            <el-table-column prop="targetMac" label="targetMac" min-width="140">
              <template #default="{ row }">{{ row.targetMac ?? '-' }}</template>
            </el-table-column>
            <el-table-column prop="attempts" label="尝试次数" width="90" />
            <el-table-column prop="status" label="状态" width="90" />
            <template #empty>无在途命令</template>
          </el-table>
        </template>
      </el-card>
    </div>
    <el-empty v-else description="请选择左侧 agent" class="agent-detail" />
  </div>
</template>

<style scoped>
.agents-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.agent-list {
  width: 260px;
  flex-shrink: 0;
}
.state-tag {
  margin-left: auto;
}
.agent-detail {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.cmd-type-select {
  width: 320px;
}
.status-detail {
  margin-top: 12px;
}
.cmd-result {
  margin-top: 12px;
}
.ledger-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.json-block {
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  overflow: auto;
  max-height: 320px;
}
</style>
