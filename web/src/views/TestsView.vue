<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type {
  AgentListItem,
  ReportStep,
  ScenarioDetail,
  ScenarioItem,
  ScenarioSaveResult,
  TestRunDetail,
  TestRunsResponse,
} from '../api/types'

// ---------------- 场景区 ----------------
const scenarios = ref<ScenarioItem[]>([])
const selectedScenario = ref<ScenarioItem | null>(null)
const scenarioSource = ref<'path' | 'inline'>('path')
const inlineJson = ref('')
const INLINE_HINT = `{
  "name": "内联场景",
  "description": "粘贴或编辑场景 JSON（结构同 server/scenarios/*.json）",
  "setup": [],
  "steps": [
    { "name": "连接设备", "action": "command", "command": "CONNECT_DEVICE",
      "params": { "deviceMac": "AA:BB:CC:DD:EE:01" },
      "expectAck": { "errorCode": 0 }, "ackTimeoutMs": 30000 }
  ],
  "teardown": []
}`

async function fetchScenarios() {
  try {
    const resp = await client.get<ScenarioItem[]>('/scenarios')
    scenarios.value = resp.data
  } catch (e) {
    ElMessage.error(`获取场景列表失败：${errorDetail(e)}`)
  }
}

function selectScenario(s: ScenarioItem) {
  selectedScenario.value = s
  scenarioSource.value = 'path'
}

function runScenario(s: ScenarioItem) {
  selectScenario(s)
  startRun()
}

async function deleteScenario(s: ScenarioItem) {
  try {
    await ElMessageBox.confirm(`确认删除场景 ${s.name}（${s.path}）？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await client.delete(`/scenarios/${s.path}`)
    ElMessage.success(`已删除 ${s.path}`)
    if (selectedScenario.value?.path === s.path) selectedScenario.value = null
    fetchScenarios()
  } catch (e) {
    ElMessage.error(`删除失败：${errorDetail(e)}`)
  }
}

// ---------------- 场景编辑器 ----------------
const NEW_SCENARIO_TEMPLATE = `{
  "name": "新场景",
  "description": "场景描述（结构见 SDD §14 / server/scenarios/*.json）",
  "setup": [],
  "steps": [
    { "name": "连接设备", "action": "command", "command": "CONNECT_DEVICE",
      "params": { "deviceMac": "AA:BB:CC:DD:EE:01" },
      "expectAck": { "errorCode": 0 }, "ackTimeoutMs": 30000 }
  ],
  "teardown": []
}`

const editorVisible = ref(false)
const editorLoading = ref(false)
const editorSaving = ref(false)
/** 当前编辑的场景 path；空串表示新建未保存 */
const editorPath = ref('')
const editorContent = ref('')
/** 本地 JSON 语法错误；空串表示合法 */
const jsonError = ref('')
/** 服务端 422 校验错误原文 */
const serverError = ref('')

async function openEditor(s: ScenarioItem) {
  editorVisible.value = true
  editorLoading.value = true
  editorPath.value = s.path
  editorContent.value = ''
  jsonError.value = ''
  serverError.value = ''
  try {
    const resp = await client.get<ScenarioDetail>(`/scenarios/${s.path}`)
    editorContent.value = JSON.stringify(resp.data.content, null, 2)
  } catch (e) {
    ElMessage.error(`载入场景失败：${errorDetail(e)}`)
    editorVisible.value = false
  } finally {
    editorLoading.value = false
  }
}

function openNewScenario() {
  editorVisible.value = true
  editorPath.value = ''
  editorContent.value = NEW_SCENARIO_TEMPLATE
  jsonError.value = ''
  serverError.value = ''
}

function validateJson(): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(editorContent.value) as unknown
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      jsonError.value = '场景必须是 JSON 对象'
      return null
    }
    jsonError.value = ''
    return parsed as Record<string, unknown>
  } catch (e) {
    jsonError.value = (e as Error).message
    return null
  }
}

function formatJson() {
  const parsed = validateJson()
  if (parsed) editorContent.value = JSON.stringify(parsed, null, 2)
}

/** 保存到指定 path；成功返回保存后的 path，失败返回 null */
async function saveScenario(path: string): Promise<string | null> {
  const parsed = validateJson()
  if (!parsed) {
    ElMessage.error(`JSON 语法错误：${jsonError.value}`)
    return null
  }
  serverError.value = ''
  editorSaving.value = true
  try {
    const resp = await client.put<ScenarioSaveResult>(`/scenarios/${path}`, parsed)
    editorPath.value = resp.data.path
    ElMessage.success(`已保存 ${resp.data.path}`)
    fetchScenarios()
    return resp.data.path
  } catch (e) {
    const status = (e as { response?: { status?: number } }).response?.status
    if (status === 422) {
      serverError.value = errorDetail(e)
    } else {
      ElMessage.error(`保存失败：${errorDetail(e)}`)
    }
    return null
  } finally {
    editorSaving.value = false
  }
}

async function saveCurrent() {
  if (!editorPath.value) {
    await saveAs()
    return
  }
  await saveScenario(editorPath.value)
}

async function saveAs(): Promise<string | null> {
  let name: string
  try {
    const res = await ElMessageBox.prompt('输入新场景文件名（可省略 .json 后缀）', '另存为', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: editorPath.value || 'new_scenario.json',
      inputPattern: /^[\w./-]+$/,
      inputErrorMessage: '文件名只能包含字母、数字、_、-、.、/',
    })
    name = res.value.trim()
  } catch {
    return null
  }
  if (!name) return null
  return saveScenario(name)
}

async function saveAndRun() {
  const path = editorPath.value ? await saveScenario(editorPath.value) : await saveAs()
  if (!path) return
  selectedScenario.value = { name: path, path }
  scenarioSource.value = 'path'
  editorVisible.value = false
  startRun()
}

// ---------------- 运行表单 ----------------
const agents = ref<AgentListItem[]>([])
const runAgentId = ref('')
const running = ref(false)

async function fetchAgents() {
  try {
    const resp = await client.get<AgentListItem[]>('/agents')
    agents.value = resp.data.filter((a) => !!a.sessionId)
  } catch (e) {
    ElMessage.error(`获取 agent 列表失败：${errorDetail(e)}`)
  }
}

async function startRun() {
  if (!runAgentId.value) {
    ElMessage.warning('请选择 agent')
    return
  }
  let scenario: unknown
  if (scenarioSource.value === 'path') {
    if (!selectedScenario.value) {
      ElMessage.warning('请选择场景文件')
      return
    }
    scenario = { path: selectedScenario.value.path }
  } else {
    try {
      scenario = JSON.parse(inlineJson.value)
    } catch (e) {
      ElMessage.error(`内联场景 JSON 非法：${(e as Error).message}`)
      return
    }
  }
  running.value = true
  try {
    const resp = await client.post<{ runId: string }>(
      '/tests/run', { agentId: runAgentId.value, scenario })
    ElMessage.success(`场景已启动，runId：${resp.data.runId}`)
    fetchRuns()
  } catch (e) {
    const status = (e as { response?: { status?: number } }).response?.status
    if (status === 409) {
      ElMessage.error(`该 agent 有场景在跑：${errorDetail(e)}`)
    } else {
      ElMessage.error(`启动失败：${errorDetail(e)}`)
    }
  } finally {
    running.value = false
  }
}

// ---------------- 运行历史 ----------------
const runs = ref<TestRunsResponse>({ runs: [], running: [] })
let runsTimer: ReturnType<typeof setInterval> | null = null

async function fetchRuns() {
  try {
    const resp = await client.get<TestRunsResponse>('/tests/runs')
    runs.value = resp.data
  } catch (e) {
    ElMessage.error(`获取运行历史失败：${errorDetail(e)}`)
  }
}

function agentOf(runId: string): string {
  // 列表行无 agentId（store.list_test_runs 未返回），在跑快照里有
  return runs.value.running.find((r) => r.runId === runId)?.agentId ?? '-'
}

function isRunning(runId: string): boolean {
  return runs.value.running.some((r) => r.runId === runId)
}

function runTagType(status: string): string {
  switch (status) {
    case 'PASS': return 'success'
    case 'FAIL': return 'danger'
    case 'ERROR': return 'warning'
    case 'RUNNING': return 'primary'
    default: return 'info'
  }
}

async function stopRun(runId: string) {
  try {
    await ElMessageBox.confirm(`确认停止 ${runId}？`, '停止确认', {
      type: 'warning',
      confirmButtonText: '停止',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await client.post(`/tests/runs/${runId}/stop`)
    ElMessage.success('已请求停止')
    fetchRuns()
  } catch (e) {
    ElMessage.error(`停止失败：${errorDetail(e)}`)
  }
}

// ---------------- 详情抽屉 ----------------
const detailVisible = ref(false)
const detail = ref<TestRunDetail | null>(null)
const detailLoading = ref(false)

async function openDetail(runId: string) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const resp = await client.get<TestRunDetail>(`/tests/runs/${runId}`)
    detail.value = resp.data
  } catch (e) {
    ElMessage.error(`获取详情失败：${errorDetail(e)}`)
  } finally {
    detailLoading.value = false
  }
}

function stepTagType(s: ReportStep): string {
  switch (s.status) {
    case 'PASS': return 'success'
    case 'FAIL': return 'danger'
    case 'ERROR': return 'warning'
    case 'SKIP': return 'info'
    default: return 'primary'
  }
}

function fmtTs(ts: number | null | undefined): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString()
}

function fmtMs(v: number | null | undefined): string {
  if (v == null) return '-'
  return `${v.toFixed(1)} ms`
}

onMounted(() => {
  fetchScenarios()
  fetchAgents()
  fetchRuns()
  runsTimer = setInterval(fetchRuns, 5000)
})
onBeforeUnmount(() => {
  if (runsTimer) clearInterval(runsTimer)
})
</script>

<template>
  <div class="tests-page">
    <div class="left-col">
      <el-card shadow="never">
        <template #header>
          <div class="card-header">
            <span>场景列表</span>
            <div>
              <el-button size="small" type="primary" @click="openNewScenario">新建</el-button>
              <el-button size="small" @click="fetchScenarios">刷新</el-button>
            </div>
          </div>
        </template>
        <div
          v-for="s in scenarios"
          :key="s.path"
          class="scenario-row"
          :class="{ active: selectedScenario?.path === s.path }"
          @click="selectScenario(s)"
        >
          <div class="scenario-item">
            <span>{{ s.name }}</span>
            <span class="scenario-path">{{ s.path }}</span>
          </div>
          <div class="scenario-actions" @click.stop>
            <el-button size="small" @click="openEditor(s)">编辑</el-button>
            <el-button size="small" type="primary" @click="runScenario(s)">运行</el-button>
            <el-button size="small" type="danger" @click="deleteScenario(s)">删除</el-button>
          </div>
        </div>
        <el-empty v-if="scenarios.length === 0" description="暂无场景文件" :image-size="60" />
      </el-card>

      <el-card shadow="never">
        <template #header>运行场景</template>
        <el-form label-width="90px">
          <el-form-item label="agentId">
            <el-select v-model="runAgentId" placeholder="选择在线 agent" class="full-width">
              <el-option v-for="a in agents" :key="a.agentId" :label="a.agentId" :value="a.agentId" />
            </el-select>
          </el-form-item>
          <el-form-item label="场景来源">
            <el-radio-group v-model="scenarioSource">
              <el-radio-button value="path" :disabled="!selectedScenario">场景文件</el-radio-button>
              <el-radio-button value="inline">内联 JSON</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="scenarioSource === 'path'" label="已选场景">
            <template v-if="selectedScenario">
              <el-tag>{{ selectedScenario.name }}</el-tag>
              <span class="scenario-path">（{{ selectedScenario.path }}）</span>
            </template>
            <span v-else class="hint">请先在上方列表选择场景</span>
          </el-form-item>
          <el-form-item v-else label="场景 JSON">
            <div class="inline-block full-width">
              <p class="hint">粘贴/编辑场景 JSON，以内联对象运行（不落盘）：</p>
              <el-input v-model="inlineJson" type="textarea" :rows="10" :placeholder="INLINE_HINT" />
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="running" @click="startRun">启动场景</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>

    <div class="right-col">
      <el-card shadow="never">
        <template #header>
          <div class="card-header">
            <span>运行历史（5s 轮询）</span>
            <el-tag v-if="runs.running.length > 0" type="primary" size="small">
              在跑 {{ runs.running.length }} 个
            </el-tag>
          </div>
        </template>
        <el-table :data="runs.runs" size="small">
          <el-table-column prop="runId" label="runId" min-width="150" show-overflow-tooltip />
          <el-table-column prop="scenario" label="场景" min-width="140" show-overflow-tooltip />
          <el-table-column label="agentId" min-width="130">
            <template #default="{ row }">{{ agentOf(row.runId) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="runTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" min-width="150">
            <template #default="{ row }">{{ fmtTs(row.startedTs) }}</template>
          </el-table-column>
          <el-table-column label="结束时间" min-width="150">
            <template #default="{ row }">{{ fmtTs(row.finishedTs) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row.runId)">详情</el-button>
              <el-button
                v-if="isRunning(row.runId)"
                size="small"
                type="danger"
                @click="stopRun(row.runId)"
              >
                停止
              </el-button>
            </template>
          </el-table-column>
          <template #empty>暂无运行记录</template>
        </el-table>
      </el-card>
    </div>

    <el-dialog
      v-model="editorVisible"
      :title="editorPath ? `编辑场景 — ${editorPath}` : '新建场景'"
      width="820px"
      :close-on-click-modal="false"
    >
      <div v-loading="editorLoading">
        <div class="editor-toolbar">
          <el-button size="small" @click="formatJson">格式化</el-button>
          <span class="hint">编辑后实时本地校验 JSON 语法；保存时服务端再次校验</span>
        </div>
        <el-input
          v-model="editorContent"
          type="textarea"
          :rows="22"
          class="json-editor"
          spellcheck="false"
          @input="validateJson"
        />
        <el-alert
          v-if="jsonError"
          type="error"
          :title="`JSON 语法错误：${jsonError}`"
          :closable="false"
          class="editor-alert"
        />
        <el-alert
          v-else-if="serverError"
          type="error"
          :title="`服务端校验失败（422）：${serverError}`"
          :closable="false"
          class="editor-alert"
        />
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button :loading="editorSaving" :disabled="!!jsonError" @click="saveAs">另存为</el-button>
        <el-button type="primary" :loading="editorSaving" :disabled="!!jsonError" @click="saveCurrent">
          保存
        </el-button>
        <el-button type="success" :loading="editorSaving || running" :disabled="!!jsonError" @click="saveAndRun">
          保存并运行
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="`运行详情 — ${detail?.runId ?? ''}`" size="620px">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="场景">{{ detail.scenario }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="runTagType(detail.status)" size="small">{{ detail.status }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="agentId">{{ detail.report?.agentId ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="detail">{{ detail.report?.detail || '-' }}</el-descriptions-item>
            <el-descriptions-item label="开始">{{ fmtTs(detail.startedTs) }}</el-descriptions-item>
            <el-descriptions-item label="结束">{{ fmtTs(detail.finishedTs) }}</el-descriptions-item>
          </el-descriptions>

          <template v-if="detail.report">
            <h4 class="section-title">统计</h4>
            <div class="stats-row">
              <el-tag>ACK 数 {{ detail.report.stats.ackCount }}</el-tag>
              <el-tag>ACK P50 {{ fmtMs(detail.report.stats.ackP50Ms) }}</el-tag>
              <el-tag>ACK P95 {{ fmtMs(detail.report.stats.ackP95Ms) }}</el-tag>
              <el-tag>事件等待数 {{ detail.report.stats.eventWaitCount }}</el-tag>
              <el-tag>等待 P50 {{ fmtMs(detail.report.stats.eventWaitP50Ms) }}</el-tag>
              <el-tag>等待 P95 {{ fmtMs(detail.report.stats.eventWaitP95Ms) }}</el-tag>
            </div>

            <h4 class="section-title">步骤</h4>
            <el-timeline>
              <el-timeline-item
                v-for="(s, i) in detail.report.steps"
                :key="i"
                :type="stepTagType(s) as '' | 'success' | 'warning' | 'danger' | 'info' | 'primary'"
                :timestamp="`${s.phase} · ${fmtMs(s.elapsedMs)}`"
              >
                <div class="step-line">
                  <span>{{ s.name }}</span>
                  <el-tag :type="stepTagType(s)" size="small">{{ s.status }}</el-tag>
                </div>
                <div v-if="s.detail" class="step-detail">{{ s.detail }}</div>
              </el-timeline-item>
            </el-timeline>
          </template>
          <el-alert
            v-else-if="detail.status === 'RUNNING'"
            type="info"
            title="场景仍在运行，报告尚未生成"
            :closable="false"
            class="section-title"
          />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.tests-page {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.left-col {
  width: 420px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.right-col {
  flex: 1;
  min-width: 0;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.scenario-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 4px;
  cursor: pointer;
}
.scenario-row:hover {
  background: var(--el-fill-color-light);
}
.scenario-row.active {
  background: var(--el-color-primary-light-9);
}
.scenario-item {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 4px 0;
  min-width: 0;
}
.scenario-actions {
  flex-shrink: 0;
}
.scenario-path {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 0 0 6px;
}
.full-width {
  width: 100%;
}
.section-title {
  margin: 16px 0 8px;
}
.stats-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.step-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.step-detail {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  word-break: break-all;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.json-editor :deep(textarea) {
  font-family: 'Courier New', Courier, monospace;
  font-size: 13px;
  line-height: 1.5;
}
.editor-alert {
  margin-top: 8px;
}
</style>
