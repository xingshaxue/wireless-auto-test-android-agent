<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { WsEvent } from '../api/types'

// 与 server/src/wireless_server/protocol/events.py 一致的事件类型
const EVENT_TYPES = [
  'REGISTER', 'HEARTBEAT', 'DEVICE_STATE', 'CONNECTION_SLOT_ACQUIRED',
  'CONNECTION_SLOT_RELEASED', 'POLL_RESULT', 'POLL_DATA_STALE', 'CMD_ACK',
  'DEVICE_PAUSED', 'DEVICE_RESUMED', 'CONNECTION_STATISTICS', 'FILE_PROGRESS',
  'FILE_RESULT', 'ERROR', 'TOPOLOGY', 'FILE_REQUEST', 'FILE_DOWNLOAD_READY',
  'FILE_DOWNLOAD_ACK', 'FILE_DOWNLOAD_RESUME', 'LOG_UPLOAD_DONE',
]

const MAX_ROWS = 1000
const FLUSH_INTERVAL_MS = 200
const RECONNECT_MIN_MS = 1000
const RECONNECT_MAX_MS = 30000

const connected = ref(false)
const paused = ref(false)
const agentFilter = ref('')
const typeFilter = ref<string[]>([])
const rows = ref<WsEvent[]>([])

let ws: WebSocket | null = null
let reconnectDelay = RECONNECT_MIN_MS
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let flushTimer: ReturnType<typeof setInterval> | null = null
let buffer: WsEvent[] = []
let stopped = false

const wsStatusText = computed(() => (connected.value ? '已连接' : '未连接'))

function wsUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const params = new URLSearchParams()
  if (typeFilter.value.length > 0) {
    params.set('type', typeFilter.value.join(','))
  }
  const query = params.toString()
  return `${proto}//${location.host}/ws/events${query ? `?${query}` : ''}`
}

function connect() {
  if (stopped) return
  cleanupSocket()
  ws = new WebSocket(wsUrl())
  ws.onopen = () => {
    connected.value = true
    reconnectDelay = RECONNECT_MIN_MS
  }
  ws.onmessage = (ev: MessageEvent<string>) => {
    try {
      const msg = JSON.parse(ev.data) as WsEvent
      buffer.push(msg)
    } catch {
      // 忽略无法解析的帧
    }
  }
  ws.onclose = () => {
    connected.value = false
    ws = null
    scheduleReconnect()
  }
  ws.onerror = () => {
    ws?.close()
  }
}

function cleanupSocket() {
  if (ws) {
    ws.onclose = null
    ws.onerror = null
    ws.onmessage = null
    ws.onopen = null
    ws.close()
    ws = null
  }
}

function scheduleReconnect() {
  if (stopped || reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, reconnectDelay)
  reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS)
}

function flush() {
  if (paused.value || buffer.length === 0) return
  const batch = buffer
  buffer = []
  rows.value.push(...batch)
  if (rows.value.length > MAX_ROWS) {
    rows.value.splice(0, rows.value.length - MAX_ROWS)
  }
}

// 类型过滤变化时带 type 参数重连，缩小服务端推送范围
watch(typeFilter, () => {
  reconnectDelay = RECONNECT_MIN_MS
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  connect()
})

// agentId 过滤为纯本地过滤（服务端不支持按 agent 过滤）
const visibleRows = computed(() => {
  const f = agentFilter.value.trim()
  if (!f) return rows.value
  return rows.value.filter((r) => r.agentId.includes(f))
})

function clearRows() {
  rows.value = []
  buffer = []
}

function fmtTs(ts: number): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleTimeString('zh-CN', { hour12: false }) +
    `.${String(ts % 1000).padStart(3, '0')}`
}

function prettyJson(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function typeTagType(t: string): string {
  if (t === 'ERROR') return 'danger'
  if (t === 'POLL_DATA_STALE') return 'warning'
  if (t === 'CMD_ACK') return 'success'
  return 'info'
}

onMounted(() => {
  connect()
  flushTimer = setInterval(flush, FLUSH_INTERVAL_MS)
})
onBeforeUnmount(() => {
  stopped = true
  cleanupSocket()
  if (reconnectTimer) clearTimeout(reconnectTimer)
  if (flushTimer) clearInterval(flushTimer)
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="toolbar">
        <el-tag :type="connected ? 'success' : 'danger'" size="small">
          WebSocket {{ wsStatusText }}
        </el-tag>
        <el-input
          v-model="agentFilter"
          placeholder="按 agentId 过滤（本地）"
          clearable
          class="agent-filter"
        />
        <el-select
          v-model="typeFilter"
          multiple
          collapse-tags
          placeholder="事件类型过滤（重连生效）"
          class="type-filter"
        >
          <el-option v-for="t in EVENT_TYPES" :key="t" :label="t" :value="t" />
        </el-select>
        <el-button :type="paused ? 'primary' : 'default'" @click="paused = !paused">
          {{ paused ? '继续' : '暂停' }}
        </el-button>
        <el-button @click="clearRows">清空</el-button>
        <span class="count">共 {{ visibleRows.length }} 条</span>
      </div>
    </template>
    <el-table :data="visibleRows" size="small" height="calc(100vh - 220px)">
      <el-table-column type="expand">
        <template #default="{ row }">
          <pre class="json-block">{{ prettyJson(row.payload) }}</pre>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="120">
        <template #default="{ row }">{{ fmtTs(row.ts) }}</template>
      </el-table-column>
      <el-table-column prop="agentId" label="agentId" min-width="150" show-overflow-tooltip />
      <el-table-column label="类型" min-width="180">
        <template #default="{ row }">
          <el-tag :type="typeTagType(row.type)" size="small">{{ row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="payload" min-width="260">
        <template #default="{ row }">
          <span class="payload-preview">{{ JSON.stringify(row.payload) }}</span>
        </template>
      </el-table-column>
      <template #empty>暂无事件</template>
    </el-table>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.agent-filter {
  width: 220px;
}
.type-filter {
  width: 320px;
}
.count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.payload-preview {
  font-family: monospace;
  font-size: 12px;
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.json-block {
  margin: 0;
  padding: 8px 16px;
  background: var(--el-fill-color-light);
  overflow: auto;
  max-height: 300px;
}
</style>
