<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type {
  AgentListItem,
  ReportStep,
  ScenarioItem,
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
            <el-button size="small" @click="fetchScenarios">刷新</el-button>
          </div>
        </template>
        <el-menu @select="(i: number) => selectScenario(scenarios[i])">
          <el-menu-item v-for="(s, i) in scenarios" :key="s.path" :index="i">
            <div class="scenario-item">
              <span>{{ s.name }}</span>
              <span class="scenario-path">{{ s.path }}</span>
            </div>
          </el-menu-item>
        </el-menu>
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
              <p class="hint">服务端不提供场景内容查询，可粘贴/编辑后以内联对象运行：</p>
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
.scenario-item {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  padding: 4px 0;
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
</style>
