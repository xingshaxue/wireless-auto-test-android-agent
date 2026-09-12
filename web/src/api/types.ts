// 与 server/src/wireless_server 代码逐字段对齐的 API 类型定义

/** ingest 视图中单设备条目（ingest.py _device_entry） */
export interface DeviceEntry {
  state: string | null
  stateFlag: 'ok' | 'error' | 'unknown'
  errorCode: number | null
  rawStatus: number | null
  lastPollTime: number | null
  pollDataStale: boolean
  values: Record<string, unknown>
}

/** ingest 视图（ingest.py _new_view + 各事件分支） */
export interface AgentView {
  devices: Record<string, DeviceEntry>
  slots: { slotsUsed?: number | null; slotsTotal?: number | null }
  updated_ts: number
  devicesManaged?: number | null
  devicesReady?: number | null
  cpuPercent?: number | null
  memAvailMb?: number | null
  status?: Record<string, unknown>
}

/** GET /api/agents 列表项：registry 在线快照 ∪ ingest 视图 */
export interface AgentListItem {
  agentId: string
  state: 'online' | 'offline'
  sessionId?: string
  peer?: string
  connectedSec?: number
  lastSeenSec?: number
  heartbeatIntervalMs?: number
  view: AgentView | null
}

/** GET /api/agents/{id}/status */
export interface AgentStatus {
  agentId: string
  state: 'online' | 'offline'
  sessionId: string | null
  profile: Record<string, unknown> | null
  configVersion: number | null
  view: AgentView
  lastOfflineTs: number | null
}

/** GET /api/ledger pending 记录（去掉内部句柄后的台账记录） */
export interface LedgerPendingRecord {
  requestId: string
  type: string
  agentId: string
  targetMac: string | null
  status: string
  attempts: number
  sentTs: number | null
  errorCode: number | null
  rawStatus: number | null
  result: unknown
}

export interface LedgerStats {
  pending: number
  acked: number
  failed: number
  throttled: number
}

export interface LedgerSnapshot {
  pending: LedgerPendingRecord[]
  stats: LedgerStats
}

/** POST /api/commands/{agentId} 成功响应（ledger.wait_ack 结果） */
export interface CommandAckResult {
  requestId: string
  type: string
  errorCode: number
  rawStatus: number | null
  result: unknown
  ledgerStatus: string
}

/** WS /ws/events 推送报文 */
export interface WsEvent {
  agentId: string
  type: string
  ts: number
  payload: Record<string, unknown>
}

/** FastAPI 错误体：detail 可能是字符串或对象 */
export interface ApiErrorBody {
  detail?: unknown
}
/** GET /api/devices 设备配置条目（§16.4 结构） */
export interface DeviceConfig {
  deviceId: string
  mac: string
  type?: string
  priority?: number
  persistent?: boolean
  profile?: Record<string, string>
  fields?: Record<string, unknown>
  polling?: {
    intervalMs?: number
    readCharacteristics?: string[]
    reportOnlyChanged?: boolean
  }
  rules?: Record<string, unknown>[]
  [key: string]: unknown
}

/** 运行中配置更新响应（_device_config_command） */
export interface DeviceCommandResponse {
  ok: boolean
  dispatched: boolean
  fields?: Record<string, unknown>
  result?: CommandAckResult
}

/** GET /api/files 文件记录 */
export interface FileRecord {
  fileId: string
  name: string
  size: number
  sha256: string
  path: string
  createdTs: number
}

/** GET /api/transfers 传输任务（pusher 任务快照） */
export interface TransferTask {
  taskId: string
  fileId: string
  agentId: string
  deviceMac: string
  state: string
  ackedSeq: number
  totalChunks: number
  size: number
  percent: number
  downloadPercent: number
  errorCode: number | null
  detail: string | null
  createdTs: number
}

/** GET /api/scenarios 场景条目 */
export interface ScenarioItem {
  name: string
  path: string
}

/** GET /api/scenarios/{path} 场景内容 */
export interface ScenarioDetail {
  path: string
  name: string
  content: Record<string, unknown>
}

/** PUT /api/scenarios/{path} 成功响应 */
export interface ScenarioSaveResult {
  ok: boolean
  path: string
  name: string
}

/** DELETE /api/scenarios/{path} 响应 */
export interface ScenarioDeleteResult {
  ok: boolean
  path: string
}

/** GET /api/tests/runs 列表行（注意：列表行无 agentId，仅 running 快照有） */
export interface TestRunRow {
  runId: string
  scenario: string
  startedTs: number
  finishedTs: number | null
  status: string
}

export interface RunningScenario {
  runId: string
  agentId: string
  scenario: string
}

export interface TestRunsResponse {
  runs: TestRunRow[]
  running: RunningScenario[]
}

export interface ReportStep {
  phase: string
  name: string
  status: string
  elapsedMs: number
  detail: string
}

export interface ReportStats {
  ackCount: number
  ackP50Ms: number | null
  ackP95Ms: number | null
  eventWaitCount: number
  eventWaitP50Ms: number | null
  eventWaitP95Ms: number | null
}

export interface RunReport {
  runId: string
  scenario: string
  agentId: string
  startedTs: number
  finishedTs: number | null
  status: string
  detail: string
  steps: ReportStep[]
  stats: ReportStats
}

export interface RunResultRow {
  stepIdx: number
  name: string
  status: string
  detail: string | null
  elapsedMs: number | null
}

/** GET /api/tests/runs/{id} 详情 */
export interface TestRunDetail extends TestRunRow {
  report: RunReport | null
  results: RunResultRow[]
}
