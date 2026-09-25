<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { errorDetail } from '../api/client'
import type { DeviceCommandResponse, DeviceConfig } from '../api/types'

// SDD §16.4 设备配置样例模板
const DEVICE_TEMPLATE = `{
  "deviceId": "dut-xxx",
  "mac": "AA:BB:CC:DD:EE:FF",
  "type": "watch",
  "priority": 5,
  "persistent": false,
  "transferChannel": "ble",
  "profile": { "2A19": "180F", "2A21": "180A" },
  "fields": {
    "battery": { "char": "2A19", "format": "uint8" },
    "temperature": { "char": "2A21", "format": "sint16", "scale": 0.1 }
  },
  "polling": {
    "intervalMs": 5000,
    "readCharacteristics": ["2A19", "2A21"],
    "reportOnlyChanged": true
  },
  "rules": [
    {
      "ruleId": "r1",
      "priority": 5,
      "stopOnMatch": false,
      "conditions": [{ "field": "temperature", "op": "GT", "value": 20 }],
      "actions": [{ "type": "REPORT_EVENT", "params": { "event": "TEMP_WARN" } }]
    }
  ]
}`

// ---------------- 设备列表 ----------------
const devices = ref<DeviceConfig[]>([])
const loading = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

async function fetchDevices(showError = true) {
  loading.value = true
  try {
    const resp = await client.get<DeviceConfig[]>('/devices')
    devices.value = resp.data
  } catch (e) {
    if (showError) ElMessage.error(`获取设备列表失败：${errorDetail(e)}`)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchDevices()
  fetchGlobals()
  timer = setInterval(() => fetchDevices(false), 10000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

function prettyJson(v: unknown): string {
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

// ---------------- 新增 / 编辑（整设备 JSON） ----------------
const editVisible = ref(false)
const editTitle = ref('新增设备')
const editJson = ref('')
const editError = ref('')
const saving = ref(false)

function openCreate() {
  editTitle.value = '新增设备'
  editJson.value = DEVICE_TEMPLATE
  editError.value = ''
  editVisible.value = true
}

function openEdit(d: DeviceConfig) {
  editTitle.value = `编辑设备 ${d.mac}`
  editJson.value = prettyJson(d)
  editError.value = ''
  editVisible.value = true
}

function copyFrom(d: DeviceConfig) {
  editTitle.value = '新增设备（从现有设备复制）'
  const copy: Record<string, unknown> = { ...d }
  delete copy.mac
  delete copy.deviceId
  editJson.value = prettyJson(copy)
  editError.value = ''
  editVisible.value = true
}

function formatEditJson() {
  try {
    editJson.value = prettyJson(JSON.parse(editJson.value))
    editError.value = ''
  } catch (e) {
    editError.value = `JSON 格式非法：${(e as Error).message}`
  }
}

async function saveDevice() {
  let body: unknown
  try {
    body = JSON.parse(editJson.value)
    editError.value = ''
  } catch (e) {
    editError.value = `JSON 格式非法：${(e as Error).message}`
    return
  }
  saving.value = true
  try {
    await client.put('/devices', body)
    ElMessage.success('设备已保存')
    editVisible.value = false
    fetchDevices()
  } catch (e) {
    // 422：后端校验失败，detail 为错误描述字符串
    editError.value = errorDetail(e)
  } finally {
    saving.value = false
  }
}

async function removeDevice(d: DeviceConfig) {
  try {
    await ElMessageBox.confirm(`确认删除设备 ${d.mac}（${d.deviceId}）？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await client.delete(`/devices/${d.mac}`)
    ElMessage.success('已删除')
    fetchDevices()
  } catch (e) {
    ElMessage.error(`删除失败：${errorDetail(e)}`)
  }
}

// ---------------- 运行中更新 ----------------
function showDispatchResult(resp: DeviceCommandResponse) {
  if (resp.dispatched) {
    ElMessage.success('已下发归属 agent 并收到 ACK')
  } else {
    ElMessage.info('配置已生效；设备尚未归属在线 agent，重连后随全量配置下发')
  }
}

const intervalVisible = ref(false)
const intervalMac = ref('')
const intervalMs = ref(5000)

function openInterval(d: DeviceConfig) {
  intervalMac.value = d.mac
  intervalMs.value = d.polling?.intervalMs ?? 5000
  intervalVisible.value = true
}

async function saveInterval() {
  try {
    const resp = await client.post<DeviceCommandResponse>(
      `/devices/${intervalMac.value}/polling-interval`, { intervalMs: intervalMs.value })
    showDispatchResult(resp.data)
    intervalVisible.value = false
  } catch (e) {
    ElMessage.error(`设置失败：${errorDetail(e)}`)
  }
}

const rulesVisible = ref(false)
const rulesMac = ref('')
const rulesJson = ref('')

function openRules(d: DeviceConfig) {
  rulesMac.value = d.mac
  rulesJson.value = prettyJson(d.rules ?? [])
  rulesVisible.value = true
}

async function saveRules() {
  let rules: unknown
  try {
    rules = JSON.parse(rulesJson.value)
  } catch (e) {
    ElMessage.error(`rules JSON 非法：${(e as Error).message}`)
    return
  }
  try {
    const resp = await client.post<DeviceCommandResponse>(
      `/devices/${rulesMac.value}/rules`, { rules })
    showDispatchResult(resp.data)
    rulesVisible.value = false
  } catch (e) {
    ElMessage.error(`设置失败：${errorDetail(e)}`)
  }
}

async function togglePersistent(d: DeviceConfig, on: boolean) {
  try {
    const resp = await client.post<DeviceCommandResponse>(
      `/devices/${d.mac}/persistent`, { on })
    showDispatchResult(resp.data)
  } catch (e) {
    ElMessage.error(`设置失败：${errorDetail(e)}`)
    fetchDevices() // 失败回滚显示
  }
}

// ---------------- 传输通道（ble/spp/auto，SPP 加速通道） ----------------
const channelVisible = ref(false)
const channelMac = ref('')
const channelValue = ref<'ble' | 'spp' | 'auto'>('ble')
let channelDevice: DeviceConfig | null = null

const CHANNEL_LABELS: Record<string, string> = { ble: 'BLE', spp: 'SPP', auto: '自动' }

function openChannel(d: DeviceConfig) {
  channelDevice = d
  channelMac.value = d.mac
  channelValue.value = d.transferChannel ?? 'ble'
  channelVisible.value = true
}

async function saveChannel() {
  if (!channelDevice) return
  try {
    // 无专用端点：整设备配置 PUT（store 直通，字段随全量配置下发 agent）
    await client.put('/devices', { ...channelDevice, transferChannel: channelValue.value })
    ElMessage.success('传输通道已保存')
    channelVisible.value = false
    fetchDevices()
  } catch (e) {
    ElMessage.error(`保存失败：${errorDetail(e)}`)
  }
}

// ---------------- 全局区 ----------------
const maxSlots = ref(3)
const maxSlotsSaving = ref(false)
const globalsJson = ref('')
const globalsSaving = ref(false)

async function fetchGlobals() {
  try {
    const resp = await client.get<Record<string, unknown>>('/config/globals')
    globalsJson.value = prettyJson(resp.data)
    if (typeof resp.data.maxSlots === 'number') maxSlots.value = resp.data.maxSlots
  } catch (e) {
    ElMessage.error(`获取全局参数失败：${errorDetail(e)}`)
  }
}

async function saveMaxSlots() {
  maxSlotsSaving.value = true
  try {
    const resp = await client.post('/config/max-connections', { maxSlots: maxSlots.value })
    ElMessage.success(`已广播全部在线 agent（ACK ${resp.data.acks?.length ?? 0} 个）`)
  } catch (e) {
    // 400：maxSlots 越界
    ElMessage.error(`设置失败：${errorDetail(e)}`)
  } finally {
    maxSlotsSaving.value = false
  }
}

async function saveGlobals() {
  let params: unknown
  try {
    params = JSON.parse(globalsJson.value)
  } catch (e) {
    ElMessage.error(`全局参数 JSON 非法：${(e as Error).message}`)
    return
  }
  try {
    await ElMessageBox.confirm('确认保存全局参数？将影响全部 agent 的运行行为。', '保存确认', {
      type: 'warning',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  globalsSaving.value = true
  try {
    await client.put('/config/globals', params)
    ElMessage.success('全局参数已保存')
    fetchGlobals()
  } catch (e) {
    ElMessage.error(`保存失败：${errorDetail(e)}`)
  } finally {
    globalsSaving.value = false
  }
}
</script>

<template>
  <div class="devices-page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>设备配置（10s 轮询）</span>
          <div>
            <el-button size="small" @click="fetchDevices()">刷新</el-button>
            <el-button size="small" type="primary" @click="openCreate">新增设备</el-button>
          </div>
        </div>
      </template>
      <el-table :data="devices" size="small" v-loading="loading">
        <el-table-column prop="deviceId" label="deviceId" min-width="110" />
        <el-table-column prop="mac" label="mac" min-width="150" />
        <el-table-column prop="type" label="type" width="90" />
        <el-table-column prop="priority" label="priority" width="80" />
        <el-table-column label="persistent" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="!!row.persistent"
              size="small"
              @change="(v: boolean) => togglePersistent(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="轮询间隔" width="100">
          <template #default="{ row }">{{ row.polling?.intervalMs ?? '-' }} ms</template>
        </el-table-column>
        <el-table-column label="传输通道" width="90">
          <template #default="{ row }">{{ CHANNEL_LABELS[row.transferChannel ?? 'ble'] }}</template>
        </el-table-column>
        <el-table-column label="读取特征" min-width="140">
          <template #default="{ row }">
            {{ (row.polling?.readCharacteristics ?? []).join(', ') || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openInterval(row)">轮询间隔</el-button>
            <el-button size="small" @click="openRules(row)">规则</el-button>
            <el-button size="small" @click="openChannel(row)">传输通道</el-button>
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" @click="copyFrom(row)">复制</el-button>
            <el-button size="small" type="danger" @click="removeDevice(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无设备配置</template>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>全局参数</template>
      <div class="globals-row">
        <span>最大连接数 maxSlots（2~5，广播全部在线 agent）：</span>
        <el-input-number v-model="maxSlots" :min="2" :max="5" controls-position="right" />
        <el-button type="primary" :loading="maxSlotsSaving" @click="saveMaxSlots">下发</el-button>
      </div>
      <el-input v-model="globalsJson" type="textarea" :rows="12" class="globals-editor" />
      <div class="globals-actions">
        <el-button size="small" @click="fetchGlobals">重新加载</el-button>
        <el-button size="small" type="primary" :loading="globalsSaving" @click="saveGlobals">
          保存全局参数
        </el-button>
      </div>
    </el-card>

    <el-dialog v-model="editVisible" :title="editTitle" width="640px">
      <el-input v-model="editJson" type="textarea" :rows="18" />
      <el-alert v-if="editError" type="error" :title="editError" show-icon :closable="false" class="dialog-alert" />
      <template #footer>
        <el-button @click="formatEditJson">格式化 / 校验 JSON</el-button>
        <el-button type="primary" :loading="saving" @click="saveDevice">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="intervalVisible" :title="`轮询间隔 — ${intervalMac}`" width="420px">
      <el-form label-width="120px">
        <el-form-item label="intervalMs（≥200）">
          <el-input-number v-model="intervalMs" :min="200" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveInterval">下发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rulesVisible" :title="`轮询规则 — ${rulesMac}`" width="640px">
      <el-input v-model="rulesJson" type="textarea" :rows="14" placeholder='[{"ruleId":"r1", ...}]' />
      <template #footer>
        <el-button type="primary" @click="saveRules">下发（整集替换）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="channelVisible" :title="`传输通道 — ${channelMac}`" width="420px">
      <el-form label-width="120px">
        <el-form-item label="传输通道">
          <el-select v-model="channelValue">
            <el-option label="BLE（LC 通道，默认）" value="ble" />
            <el-option label="SPP（RFCOMM 加速）" value="spp" />
            <el-option label="自动（先 SPP，失败回退 BLE）" value="auto" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="saveChannel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.devices-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.globals-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.globals-editor {
  font-family: monospace;
}
.globals-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.dialog-alert {
  margin-top: 12px;
}
</style>
