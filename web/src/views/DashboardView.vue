<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type { AgentListItem, DeviceEntry } from '../api/types'

const agents = ref<AgentListItem[]>([])
let timer: ReturnType<typeof setInterval> | null = null

async function fetchAgents() {
  try {
    const resp = await client.get<AgentListItem[]>('/agents')
    agents.value = resp.data
  } catch (e) {
    ElMessage.error(`获取 agent 列表失败：${errorDetail(e)}`)
  }
}

onMounted(() => {
  fetchAgents()
  timer = setInterval(fetchAgents, 5000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

function isOnline(a: AgentListItem): boolean {
  return !!a.sessionId
}

interface DeviceRow extends DeviceEntry {
  deviceMac: string
}

function deviceRows(a: AgentListItem): DeviceRow[] {
  const devices = a.view?.devices ?? {}
  return Object.entries(devices).map(([mac, d]) => ({ deviceMac: mac, ...d }))
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

const onlineCount = computed(() => agents.value.filter(isOnline).length)
</script>

<template>
  <div>
    <div class="summary">
      <el-statistic title="Agent 总数" :value="agents.length" />
      <el-statistic title="在线" :value="onlineCount" />
      <el-statistic
        title="离线"
        :value="agents.length - onlineCount"
      />
    </div>
    <el-empty v-if="agents.length === 0" description="暂无 agent" />
    <el-row :gutter="16">
      <el-col v-for="a in agents" :key="a.agentId" :xs="24" :md="12" :lg="12">
        <el-card class="agent-card" shadow="hover">
          <template #header>
            <div class="card-header">
              <span class="agent-id">{{ a.agentId }}</span>
              <el-tag :type="isOnline(a) ? 'success' : 'info'" size="small">
                {{ isOnline(a) ? '在线' : '离线' }}
              </el-tag>
            </div>
          </template>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="连接时长">
              {{ a.connectedSec != null ? `${a.connectedSec.toFixed(0)} 秒` : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="最近心跳">
              {{ a.lastSeenSec != null ? `${a.lastSeenSec.toFixed(1)} 秒前` : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="连接槽位">
              {{ a.view?.slots?.slotsUsed ?? '-' }} / {{ a.view?.slots?.slotsTotal ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="纳管 / 就绪设备">
              {{ a.view?.devicesManaged ?? '-' }} / {{ a.view?.devicesReady ?? '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="CPU">
              {{ a.view?.cpuPercent != null ? `${a.view.cpuPercent}%` : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="可用内存">
              {{ a.view?.memAvailMb != null ? `${a.view.memAvailMb} MB` : '-' }}
            </el-descriptions-item>
          </el-descriptions>
          <el-table :data="deviceRows(a)" size="small" class="device-table">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="values-block">
                  <div>stateFlag: {{ row.stateFlag }}；errorCode: {{ row.errorCode ?? '-' }}；rawStatus: {{ row.rawStatus ?? '-' }}</div>
                  <pre>{{ prettyJson(row.values) }}</pre>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="deviceMac" label="设备 MAC" min-width="150" />
            <el-table-column prop="state" label="状态" min-width="120">
              <template #default="{ row }">
                <el-tag :type="row.stateFlag === 'error' ? 'danger' : row.state === 'READY' ? 'success' : 'info'" size="small">
                  {{ row.state ?? '-' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近轮询" min-width="160">
              <template #default="{ row }">{{ fmtTs(row.lastPollTime) }}</template>
            </el-table-column>
            <el-table-column label="数据过期" width="90">
              <template #default="{ row }">
                <el-tag :type="row.pollDataStale ? 'warning' : 'success'" size="small">
                  {{ row.pollDataStale ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty>暂无设备数据</template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  gap: 48px;
  margin-bottom: 16px;
}
.agent-card {
  margin-bottom: 16px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.agent-id {
  font-weight: 600;
}
.device-table {
  margin-top: 12px;
  width: 100%;
}
.values-block {
  padding: 8px 16px;
}
.values-block pre {
  margin: 8px 0 0;
  background: var(--el-fill-color-light);
  padding: 8px;
  border-radius: 4px;
  overflow: auto;
}
</style>
