# wireless-server —— 无限自动化测试框架生产服务器

无限自动化测试框架（SDD V1.6）的服务器端生产实现：基于 FastAPI + asyncio 的 Python 服务，
同时承载面向 Android Agent 的 TCP 网关与面向使用方的 REST API / WebSocket。

## 1. 项目简介

在整体拓扑中的位置：

```
使用方（curl/脚本/前端）──REST/WS──► server ──TCP（长度前缀JSON + 0xAC42 二进制帧）──► Android Agent ──BLE──► DUT（被测设备）
```

- 网关与 agent 之间的报文格式、二进制帧协议实现 SDD V1.6 **附录 A** 与 **§16.3**；
  限流错误码等见 §12.9。本系统纯内网部署，无任何鉴权。
- 与仓库内 `tools/mock_server.py` 的关系：mock 是**联调参考实现**（单文件、交互式、
  内置示例配置），用于与 agent 跑通协议链路和协议自测（`--selftest`）；
  **本包是生产服务器**：持久化（SQLite）、配置管理、命令台账、文件传输、测试编排、
  REST/WS 接口与 systemd 部署。协议层两者对齐，可互为对照。

## 2. 功能清单

- **多 agent 接入与心跳离线检测**：REGISTER/REGISTER_ACK 握手，
  心跳看门狗按 `heartbeat_timeout_factor × heartbeatIntervalMs` 无消息判离线。
- **内网免鉴权**：REGISTER、REST、WS 均不校验 token；旧 agent 上报的
  REGISTER token 字段作为历史字段保留并忽略（附录 A.3）。
- **配置集中管理与 configVersion 发号**（§16.4）：设备配置、全局参数入库，
  注册时随 REGISTER_ACK 全量下发；运行中修改立即下发并等 ACK。
- **命令台账**（§8.4 / §12.9）：按 requestId 登记、CMD_ACK 对账销账、
  超时按 `ack_max_retries` 重发、仍失败标记 failed；限流类错误（3001/3002）
  单独计数，提示调用方放缓下发。
- **文件下发与日志接收**（§7.6 / §16.3）：FILE_TRANSFER → READY →
  FILE_FRAME 分帧推送（seq 从 1 连续编号）→ FILE_END（整体 SHA-256）；
  FILE_DOWNLOAD_ACK 的 resendSeqs 触发重传；断连任务挂起（PAUSED），
  agent 重连 FILE_DOWNLOAD_RESUME 后从断点续传；agent 侧规则动作触发
  FILE_REQUEST 时自动并入同一流程下发。LOG_UPLOAD 请求 + LOG_FRAME 接收落盘。
- **事件入库与实时视图**：全部 agent 事件写 SQLite events 表，同时维护
  agent 最后已知状态视图（供 assertView 与 API 查询）。
- **声明式测试编排引擎**（§14）：JSON 场景（setup/steps/teardown），
  命令下发 + ACK 对账 + 事件断言 + 视图断言，报告落库（test_runs / test_results），
  统计 ACK / 事件等待时延 P50/P95（§14.3）。
- **批量 OTA 编排**（BatchOrchestrator）：一个 OTA 包推送到 N 台设备，
  每台独立跑"传输→升级命令→黑窗→回连→验版本"子流程；per-agent 并发上限
  （默认 1，BLE 带宽瓶颈）、失败隔离、逐设备汇总报告；默认参数对应
  p67 手环 OTA 实测流程（docs/04）。
- **REST API + WebSocket**：全部 REST 路由挂 `/api` 前缀；
  `/ws/events` 实时事件推送。
- **可选 TLS**：gateway 与 api 各自可配置证书；启用后握手失败**不降级**。
  默认不加密，生产有加密需求时显式开启。

## 3. 安装

要求 **Python 3.12+**（配置解析用 stdlib `tomllib`）。

```bash
cd server
python3 -m venv .venv
.venv/bin/pip install -e .          # 生产依赖：fastapi / uvicorn[standard] / pydantic / python-multipart
# 开发（含 pytest / pytest-asyncio / httpx）：
.venv/bin/pip install -e '.[dev]'
```

也可用 `uv` 等价替代（`uv pip install -e .`）。

## 4. 配置

配置文件为 TOML，样例见 `deploy/server.toml.example`，字段与默认值一一对应
`src/wireless_server/settings.py`。所有字段均有默认值。纯内网部署，无任何
鉴权；旧配置文件中残留的 `gateway.agent_token` / `api.token` 键按未知字段忽略。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `gateway.host` | `0.0.0.0` | TCP 网关监听地址 |
| `gateway.port` | `10409` | TCP 网关端口（agent 连接） |
| `gateway.tls_cert` / `gateway.tls_key` | `""` | 可选 TLS；配置后启用，握手失败不降级 |
| `gateway.write_queue_max` | `1000` | 每 session 写队列上限，超限断开（背压） |
| `gateway.ack_timeout_ms` | `30000` | CMD_ACK 等待超时 |
| `gateway.ack_max_retries` | `1` | ACK 超时后的重发次数 |
| `gateway.heartbeat_timeout_factor` | `3` | 超过 factor × heartbeatIntervalMs 无消息判离线 |
| `gateway.default_heartbeat_interval_ms` | `5000` | agent 未上报时的兜底心跳间隔 |
| `api.host` / `api.port` | `0.0.0.0` / `8080` | REST/WS 监听地址与端口 |
| `api.tls_cert` / `api.tls_key` | `""` | API 侧可选 TLS（uvicorn ssl_certfile/ssl_keyfile） |
| `storage.db_path` | `data/server.db` | SQLite 数据库路径 |
| `storage.files_dir` | `data/files` | 待下发文件登记目录 |
| `storage.logs_dir` | `data/agent_logs` | agent 上传日志接收目录 |
| `storage.files_quota_mb` | `10240` | files_dir 配额，超限拒绝登记 |
| `storage.chunk_size` | `4096` | 二进制帧分块大小（≤64KB，§16.3） |
| `storage.file_push_yield_frames` | `16` | 每推送 N 帧让出事件循环，避免阻塞命令下发 |
| `log.level` | `INFO` | 日志级别 |
| `log.dir` | `logs` | 日志目录 |
| `log.max_bytes` | `10485760` | 单日志文件大小上限（轮转） |
| `log.backup_count` | `5` | 轮转保留份数 |

TLS 说明：gateway 与 api 的 TLS 相互独立，均为可选。配置证书后启用 TLS，
握手失败直接断开，**不会静默降级为明文**；不配置则按明文运行。

## 5. 运行

### 直接运行

```bash
cd server
.venv/bin/python -m wireless_server --config /path/to/server.toml

# 仅校验配置后退出（部署前置检查）：
.venv/bin/python -m wireless_server --config /path/to/server.toml --check-config
```

不传 `--config` 则使用全部默认值（仅限本地试验）。启动顺序：
settings → logging → store → configsvc → ingest → ledger → gateway(TCP) →
transfer → engine → api(FastAPI/uvicorn)。

### systemd 部署（deploy/wireless-server.service）

```bash
# 1. 建用户与目录
sudo useradd -r -s /usr/sbin/nologin wireless
sudo mkdir -p /opt/wireless-server /etc/wireless-server

# 2. 部署代码与虚拟环境
sudo cp -r server/. /opt/wireless-server/
cd /opt/wireless-server && sudo python3 -m venv .venv && sudo .venv/bin/pip install -e .

# 3. 拷贝配置（按需调整生产参数）
sudo cp /opt/wireless-server/deploy/server.toml.example /etc/wireless-server/server.toml
sudoedit /etc/wireless-server/server.toml

# 4. 数据与日志目录（service 中 WorkingDirectory 相对路径 data/ logs/ 即落在这里）
sudo mkdir -p /opt/wireless-server/data /opt/wireless-server/logs
sudo chown -R wireless:wireless /opt/wireless-server

# 5. 安装并启动
sudo cp /opt/wireless-server/deploy/wireless-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now wireless-server
sudo systemctl status wireless-server
```

service 单元：`User=wireless`，`WorkingDirectory=/opt/wireless-server`，
`ExecStart=/opt/wireless-server/.venv/bin/python -m wireless_server --config /etc/wireless-server/server.toml`，
`Restart=on-failure`（5s），`LimitNOFILE=65536`。

### 数据与日志目录

- `storage.db_path`（默认 `data/server.db`）：SQLite，agents / 配置 / events /
  files / test_runs / test_results 等表。
- `storage.files_dir`（默认 `data/files`）：REST 上传的待下发文件（磁盘名带随机前缀防碰撞）。
- `storage.logs_dir`（默认 `data/agent_logs`）：agent LOG_FRAME 上传日志落盘目录。
- `log.dir`（默认 `logs`）：服务自身日志，按 `max_bytes`/`backup_count` 轮转。
  systemd 下亦可用 `journalctl -u wireless-server` 查看 stdout。

以上路径均相对 WorkingDirectory 解析，部署时必须保证对 `wireless` 用户可写。

## 6. API 参考

REST 全部挂在 `/api` 前缀下，**内网免鉴权**，直接调用即可（客户端残留的
Authorization 头会被忽略）。WebSocket 走 `/ws/events`（同样免鉴权）。

错误码语义（`src/wireless_server/api/routes.py` 头部约定）：

- `400` — 校验/越界类 ValueError（如配额超限、参数越界）
- `404` — 设备 / 文件 / run / 任务不存在，或 agent 不在线
- `409` — agent 不在线（设备归属 agent 离线）、同 agent 已有场景在跑、传输冲突
- `422` — 请求体字段缺失或类型非法（FastAPI/pydantic 校验亦返回 422）
- `504` — 等待 CMD_ACK 超时或 ACK 非 acked
- `503` — transfer 未装配（正常运行不会出现）

### 路由表

| 方法 | 路径 | 请求体 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `/api/agents` | — | agent 列表 | 在线 registry + 最后已知视图合并，含 `state: online/offline` |
| GET | `/api/agents/{agent_id}/status` | — | 状态详情 | sessionId / profile / configVersion / view / lastOfflineTs；未知 agent → 404 |
| GET | `/api/ledger` | — | `{pending, stats}` | 在途命令快照 + 累计统计（pending/acked/failed/throttled，§8.4/§12.9） |
| GET | `/api/devices` | — | 设备配置列表 | §16.4 |
| PUT | `/api/devices` | 设备配置对象 | `{ok, mac}` | 新增/更新；校验失败 422 |
| DELETE | `/api/devices/{mac}` | — | `{ok, mac}` | 不存在 404 |
| POST | `/api/devices/{mac}/polling-interval` | `{intervalMs: int}` | 见下注 | SET_POLLING_INTERVAL：写库 + 在线下发等 ACK |
| POST | `/api/devices/{mac}/rules` | `{rules: [...]}` | 见下注 | SET_POLL_RULES |
| POST | `/api/devices/{mac}/persistent` | `{on: bool}` | 见下注 | SET_PERSISTENT_DEVICE |
| GET | `/api/config/globals` | — | 全局参数对象 | §16.4 顶层旋钮 |
| PUT | `/api/config/globals` | 参数对象 | `{ok}` | 校验失败 422 |
| POST | `/api/config/max-connections` | `{maxSlots: int}` | `{ok, maxSlots, acks}` | SET_MAX_CONNECTIONS 广播全部在线 agent 并等各 ACK |
| POST | `/api/commands/{agent_id}` | `{type, ...字段}` | ACK 对账结果 | 任意命令下发 + 同步等待 CMD_ACK；agent 不在线 404，缺 type 422，ACK 超时/失败 504 |
| POST | `/api/files` | multipart `file` | 文件记录 | 落 files_dir 并登记（size/SHA-256）；配额超限 400 |
| GET | `/api/files` | — | 文件记录列表 | |
| DELETE | `/api/files/{file_id}` | — | `{ok, fileId}` | 登记目录内磁盘副本一并清理；不存在 404 |
| POST | `/api/files/{file_id}/transfer` | `{agentId, deviceMac}` | `{taskId}` | 下发 FILE_TRANSFER；文件不存在 404，agent 不在线等 409 |
| GET | `/api/transfers` | — | 任务列表 | 状态机：WAIT_READY/PUSHING/PAUSED/DOWNLOADED/COMPLETED/FAILED/CANCELLED |
| GET | `/api/transfers/{task_id}` | — | 任务详情 | 含 percent / errorCode / detail；不存在 404 |
| POST | `/api/transfers/{task_id}/cancel` | — | `{ok, taskId}` | FILE_CANCEL；不存在或已结束 404 |
| POST | `/api/agents/{agent_id}/log-upload` | `{sinceTs?, minLevel?}`（可空） | `{requestId}` | LOG_UPLOAD 请求；agent 不在线 409 |
| GET | `/api/scenarios` | — | `[{name, path}]` | 列出 scenarios/ 目录下场景文件 |
| POST | `/api/tests/run` | `{agentId, scenario}` | `{runId}` | 异步启动场景，立即返回 runId；同 agent 在跑 409 |
| GET | `/api/tests/runs?limit=50` | — | `{runs, running}` | 历史 run + 在跑快照 |
| GET | `/api/tests/runs/{run_id}` | — | 报告 JSON | 不存在 404 |
| POST | `/api/tests/runs/{run_id}/stop` | — | `{ok, runId}` | 取消在跑场景；不存在或已结束 404 |
| POST | `/api/batch/ota` | 批量 OTA 请求（见 §7.1） | `{batchId}` | 校验失败 422；fileId 未登记 404 |
| GET | `/api/batch` | — | `{running, done}` | 批次摘要列表（驻内存，环形保留 50） |
| GET | `/api/batch/{batchId}` | — | 批次详情 | 含逐设备 phase/taskId/error/detail/version；不存在 404 |
| POST | `/api/batch/{batchId}/cancel` | — | `{ok, batchId}` | 仅 RUNNING 可取消；否则 409 |
| GET | `/api/events?agent_id=&type=&limit=200` | — | 事件列表 | 按 agentId / type 过滤 |

注：设备配置类 POST（polling-interval / rules / persistent）的响应：
设备归属 agent 在线时 `{ok, dispatched: true, result: <ACK对账>}`；
归属 agent 离线 → 409；尚未归属任何 agent 时 `{ok, dispatched: false, fields}`，
配置已生效，待 agent 重连后随全量配置下发。

### curl 示例

```bash
BASE="http://127.0.0.1:8080"

# agent 在线列表
curl -s $BASE/api/agents

# 下发命令并同步等 ACK
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"type": "GET_STATUS"}' $BASE/api/commands/phone-01

# 设置轮询间隔
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"intervalMs": 5000}' $BASE/api/devices/AA:BB:CC:DD:EE:FF/polling-interval

# 上传文件并发起下发
curl -s -X POST -F "file=@firmware.bin" $BASE/api/files
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"agentId": "phone-01", "deviceMac": "AA:BB:CC:DD:EE:FF"}' \
  $BASE/api/files/file-abcd1234/transfer

# 运行内置场景
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"agentId": "phone-01", "scenario": {"path": "basic_connect_poll.json"}}' \
  $BASE/api/tests/run
```

### WebSocket：/ws/events

纯推送的实时事件流，免鉴权。握手可带 query：`/ws/events?type=DEVICE_STATE,POLL_RESULT`
（`type` 可选，逗号分隔过滤；缺省推全部类型）。
推送报文格式：

```json
{"agentId": "phone-01", "type": "POLL_RESULT", "ts": 1730000000000, "payload": { ...原始事件... }}
```

慢消费者丢帧（推送队列上限 1000），不影响 ingest 主链路；断开即清理。

```bash
# wscat
wscat -c "ws://127.0.0.1:8080/ws/events?type=DEVICE_STATE,POLL_RESULT"
```

```python
# python websockets
import asyncio, websockets

async def main():
    url = "ws://127.0.0.1:8080/ws/events?type=POLL_RESULT"
    async with websockets.connect(url) as ws:
        async for msg in ws:
            print(msg)

asyncio.run(main())
```

## 7. 测试编排（SDD §14）

场景为 JSON 文件（可放 `scenarios/` 目录，也可内联在 POST /api/tests/run 请求体中），
顶层结构：

```json
{
  "name": "场景名（必填）",
  "description": "描述",
  "setup":    [ /* Step 列表，前置准备；失败则不进入 steps */ ],
  "steps":    [ /* Step 列表，主流程 */ ],
  "teardown": [ /* Step 列表，收尾清理；无论成败都执行 */ ]
}
```

顶层字段仅允许以上五个（多余字段校验拒绝）。每段均为 Step 列表。

### 动作词表

每个 Step 必填 `action`，公共字段：`name`（可选，报告用）、
`optional`（默认 false；true 时该步失败不中止后续步骤、不影响总结果）。

| action | 必填字段 | 可选字段 | 语义 |
|---|---|---|---|
| `command` | `command`（命令 type，如 CONNECT_DEVICE） | `params`、`expectAck`、`ackTimeoutMs`（默认 30000） | 经台账下发；`expectAck` 非空时等待 CMD_ACK 并按匹配器对账 |
| `expect` | `event`（事件 type，如 DEVICE_STATE） | `match`、`timeoutMs`（默认 30000） | 等待实时事件流中出现匹配事件（缓冲扫描 + 即时唤醒） |
| `delay` | `ms`（>0） | — | 等待指定毫秒 |
| `assertView` | `path`、`expect` | — | 对 agent 最后已知状态视图按点号路径断言（A.3） |
| `set_polling_interval` | `deviceMac`、`intervalMs`（>0） | `expectAck`、`ackTimeoutMs` | §16.4 配置更新 + SET_POLLING_INTERVAL 下发等 ACK |
| `set_poll_rules` | `deviceMac`、`rules`（数组） | `expectAck`、`ackTimeoutMs` | §16.4 配置更新 + SET_POLL_RULES 下发等 ACK |
| `file_transfer` | `deviceMac`、`fileId` | — | 调 TransferManager 下发文件任务（§7.6）；transfer 未装配则该步 SKIP |

各 action 缺必填字段、或出现未列字段，均校验拒绝（422）。

### 匹配器

`expectAck`、`match` 均为「字段路径 → 匹配器」的对象，字段路径支持点号嵌套
（如 `values.battery`）；`expect`（assertView）为单个匹配器。

- 匹配器为**标量**：要求实际值相等。
- 匹配器为**对象**：操作符子集，多个操作符须全部成立：

| 操作符 | 语义 |
|---|---|
| `eq` / `ne` | 相等 / 不等 |
| `gt` / `gte` / `lt` / `lte` | 数值比较（类型不可比较视为不满足） |
| `contains` | 实际值包含目标值 |
| `exists` | 路径存在性（true=须存在，false=须缺失） |

### 执行语义

- **并发互斥**：同一 agent 同时只允许一个场景在跑，第二个 POST /tests/run
  立即返回 409；不同 agent 可并行。
- **失败中止**：setup/steps 段内首个非 optional 步骤 FAIL/ERROR → 段内剩余步骤
  记 SKIP（"前序步骤失败"）；setup 失败则不进入 steps（全部 SKIP "setup 失败"）。
  teardown 无论成败都执行，且 teardown 的 optional 失败不计入总结果。
- **stop**：POST /api/tests/runs/{run_id}/stop 取消在跑场景——当前步骤记
  FAIL(已取消)、剩余步骤不执行、teardown 跳过、总状态 ERROR，报告照常落库。
- **总状态**：PASS / FAIL（断言类失败）/ ERROR（意外异常或被取消）。

### 完整示例

`scenarios/basic_connect_poll.json`（对齐用例 TC04-01）：

```json
{
  "name": "基本连接与轮询",
  "description": "对齐用例 TC04-01：mock smoke 场景的声明式版本（CONNECT → READY → POLL_RESULT → 槽位断言）",
  "setup": [
    {"name": "加快轮询节奏", "action": "set_polling_interval",
     "deviceMac": "AA:BB:CC:DD:EE:FF", "intervalMs": 5000,
     "expectAck": {"errorCode": 0}, "ackTimeoutMs": 30000}
  ],
  "steps": [
    {"name": "连接设备", "action": "command", "command": "CONNECT_DEVICE",
     "params": {"deviceMac": "AA:BB:CC:DD:EE:FF"},
     "expectAck": {"errorCode": 0}, "ackTimeoutMs": 30000},
    {"name": "等待 READY", "action": "expect", "event": "DEVICE_STATE",
     "match": {"deviceMac": "AA:BB:CC:DD:EE:FF", "state": "READY"},
     "timeoutMs": 60000},
    {"name": "等待轮询结果", "action": "expect", "event": "POLL_RESULT",
     "match": {"values.battery": {"gte": 0}}, "timeoutMs": 120000},
    {"name": "暂停 2s", "action": "delay", "ms": 2000},
    {"name": "断言槽位", "action": "assertView",
     "path": "slots.slotsUsed", "expect": {"lte": 3}}
  ],
  "teardown": [
    {"name": "断开设备", "action": "command", "command": "DISCONNECT_DEVICE",
     "params": {"deviceMac": "AA:BB:CC:DD:EE:FF"}, "optional": true}
  ]
}
```

### 报告结构

GET `/api/tests/runs/{run_id}` 返回的报告（同步落库 test_runs / test_results）：

```json
{
  "runId": "run-xxxxxxxxxxxx", "scenario": "基本连接与轮询", "agentId": "phone-01",
  "startedTs": 1730000000000, "finishedTs": 1730000060000,
  "status": "PASS", "detail": "",
  "steps": [{"phase": "steps", "name": "连接设备", "status": "PASS",
             "elapsedMs": 123.4, "detail": ""}],
  "stats": {
    "ackCount": 2, "ackP50Ms": 120.5, "ackP95Ms": 200.1,
    "eventWaitCount": 2, "eventWaitP50Ms": 5000.0, "eventWaitP95Ms": 8000.0
  }
}
```

`stats` 为 SDD §14.3 要求的时延基线：命令 ACK 时延与事件等待时延的 P50/P95
（最近秩百分位），可回填用例库基线。

## 7.1 批量 OTA（BatchOrchestrator）

批量是**调度问题**而非测试步骤问题：server 端一等公民模块，把一个 OTA 包
推送到 N 台设备，每台独立跑子流程，失败隔离，终态出逐设备汇总。agent 协议
零改动，完全复用现有 FILE_TRANSFER（§7.6）/ WRITE_CHAR / CONNECT_DEVICE
命令与 DEVICE_STATE / POLL_RESULT 事件。

### 设备相位机

```
QUEUED → TRANSFERRING → UPGRADE_CMD → BLACKOUT → RECONNECTING → VERIFYING → DONE
                                                                          ↘ FAILED / CANCELLED
```

1. **TRANSFERRING**：复用 FilePusher 下发并轮询任务快照直到 COMPLETED
   （FAILED/CANCELLED → 本设备 FAILED，记 errorCode/detail）。
2. **UPGRADE_CMD**：WRITE_CHAR 写升级命令并等 ACK。
3. **BLACKOUT**：固定等待 blackoutMs（可被 cancel 打断）。
4. **RECONNECTING**：发 CONNECT_DEVICE，等该设备 DEVICE_STATE state=READY
   （超时 reconnectTimeoutMs → FAILED）。黑窗期设备反复 ERROR/DISCONNECTED
   由 agent errorRetryMs 自愈（docs/04 §10 实战验证），server 只需等 READY。
5. **VERIFYING**：WRITE_CHAR 写查版本命令；仅当 versionCheck 给了
   `expectContains` 才等内容——等该设备 POLL_RESULT 的 raw 中出现该子串
   （超时 60s → FAILED，detail 记实际最后收到的值），匹配内容记入
   `version` 字段；不给则 ACK 成功即过。

全部设备终态后批次 → DONE。

### 并发模型与失败隔离

- 同一 agent 内同时进行的设备数 ≤ `perAgentConcurrency`（缺省 1，BLE 带宽
  瓶颈），per-agent `asyncio.Semaphore` 实现；跨 agent 天然并行；同 agent
  其余设备排队（QUEUED）。生效值还会被全局参数 `maxConcurrentTransfers`
  （§16.4，默认 1）截顶。
- 一台失败（连不上 / 传输校验失败 / 版本不对）只落本设备记录，不影响其余
  设备；批次 summary 汇总 succeeded / failed / remaining。
- 全部 asyncio 实现（无独立线程）；批次/设备状态**驻内存**（上限 50 批次
  环形保留），**server 重启不恢复**。

### API 示例

```bash
# 发起（全部可选字段取 p67 默认值时可只给 fileId + targets）
curl -s -X POST -H "Content-Type: application/json" -d '{
  "fileId": "file-abcd1234",
  "targets": [{"agentId": "phone-01", "deviceMac": "2C:0D:CF:72:20:84"},
              {"agentId": "phone-02", "deviceMac": "2C:0D:CF:72:20:85"}],
  "versionCheck": {"expectContains": "3.101"},
  "blackoutMs": 30000, "perAgentConcurrency": 1, "reconnectTimeoutMs": 600000
}' $BASE/api/batch/ota
# → {"batchId": "batch-xxxxxxxx"}

curl -s $BASE/api/batch                  # {"running": [...], "done": [...]}
curl -s $BASE/api/batch/batch-xxxxxxxx   # 详情 + 逐设备相位
curl -s -X POST $BASE/api/batch/batch-xxxxxxxx/cancel
```

### 默认参数与 p67 的对应关系（docs/04 §2/§7 实测）

| 请求字段 | 默认值 | p67 依据 |
|---|---|---|
| `upgradeWrite` | service `1b7e8251-…-562023`，char `8ac32d3f-…-9f626`，payload `00AT^OTA_UPDATE`（base64），writeType `NO_RESPONSE` | LC 通道；特征 props=0x14，带响应写被固件 0xFC 拒绝 |
| `versionCheck` | 同上 service/char，writePayload `00AT^SWVER=APP`，**不校验内容**（只要求 ACK 成功） | 回 `SWVER=OK,3.101.042monkey`；给 `expectContains` 才启用内容校验 |
| `blackoutMs` | 30000 | recovery 刷写黑窗实测约 4~6 分钟，此处是"等黑窗开始"的固定段，回连由 reconnectTimeoutMs 兜底 |
| `reconnectTimeoutMs` | 600000 | 黑窗约 6 分钟 + 回连余量 |
| `perAgentConcurrency` | 1 | BLE 传输约 12.5 KB/s，同机并发无收益 |

注意：p67 的版本应答经 POLL_RESULT 上报时值为 hex 编码（docs/04 §6），
若直接对真机用 `expectContains`，子串需按实际上报编码匹配。


## 8. 与 Android Agent 联调

agent 侧配置（详见 `android-agent/README.md`），二选一：

- **控制台**：安装 APK 打开「无线测试Agent」，填服务器地址 / 端口（默认 10409）/
  设备 ID → 保存配置 → 启动服务。（服务端已免鉴权，token 栏填任意值或留空，
  旧版本 agent 仍会发送，服务端忽略。）
- **ADB 拉起**（联调 / CI）：

```bash
adb shell am startservice \
  -a com.longcheer.agent.ACTION_START \
  -n com.longcheer.agent/.AgentService \
  --es server_host <服务器IP> --ei server_port 10409 \
  --es device_id phone-01
# 模拟器/无蓝牙环境（虚拟 DUT 模式）：追加 --ez simulateDut true
```

推荐联调流程：

```bash
# 1. 配置校验
.venv/bin/python -m wireless_server --config server.toml --check-config
# 2. 起 server
.venv/bin/python -m wireless_server --config server.toml
# 3. 起 agent（控制台或上面的 am startservice）
# 4. 确认在线
curl -s http://<服务器IP>:8080/api/agents
# 5. 跑内置场景
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"agentId": "phone-01", "scenario": {"path": "basic_connect_poll.json"}}' \
  http://<服务器IP>:8080/api/tests/run
# 6. 查报告
curl -s http://<服务器IP>:8080/api/tests/runs/<runId>
```

模拟器冒烟：`tools/emulator_smoke.sh` 目前默认指向 `tools/mock_server.py`；
可将其中 mock 换为本服务器做等价验证（同一协议，agent 侧只需把
server_host/server_port 指向本服务的 gateway 监听地址）。

## 9. 测试

```bash
cd server
.venv/bin/python -m pytest tests/ -q    # 161 个用例
```

覆盖 codec / protocol / ledger / store / configsvc / transfer / engine /
api / 集成链路。`tools/mock_server.py --selftest` 仍可用于协议对照（mock 与
本服务器实现同一套附录 A / §16.3 协议）。

## 9.1 Web 控制台（前端）

仓库 `web/` 目录是本服务器的配套控制台（Vue 3 + Element Plus）：Dashboard、
Agents（命令下发/台账）、设备管理、文件传输、测试编排、事件流六个页面。

- 构建：`cd web && npm install && npm run build`（产出 `web/dist/`）
- 托管：FastAPI 启动时检测 `web/dist/index.html`，存在即挂载到 `/`
  （SPA fallback；`/api`、`/ws` 优先），单端口交付；dist 不存在退化为纯 API 模式
- 开发：`npm run dev`（5173 端口，代理 `/api`/`/ws` 到 8080）
- 详见 `web/README.md`

## 10. 目录结构

```
server/
├── pyproject.toml                # 打包与依赖定义（src 布局，Python ≥3.12）
├── deploy/
│   ├── server.toml.example       # 配置样例（对照 settings.py）
│   └── wireless-server.service   # systemd 单元
├── scenarios/
│   └── basic_connect_poll.json   # 示例场景（TC04-01 声明式版本）
├── src/wireless_server/
│   ├── __main__.py               # CLI 入口（--config / --check-config）
│   ├── settings.py               # TOML 配置 + pydantic 校验
│   ├── runtime.py                # 装配与编排（REGISTER 处理、回调挂接）
│   ├── logging_setup.py          # 日志初始化（轮转）
│   ├── codec.py                  # 长度前缀 JSON / 0xAC42 二进制帧编解码（§16.3）
│   ├── ingest.py                 # 事件入库 + 最后已知状态视图（A.3）
│   ├── ledger.py                 # 命令台账与 CMD_ACK 对账（§8.4/§12.9）
│   ├── gateway/                  # TCP 网关：server / session / registry（心跳看门狗）
│   ├── protocol/                 # 报文模型：commands / events / errors（附录 A）
│   ├── configsvc/                # 配置集中管理与 configVersion 发号（§16.4）
│   ├── transfer/                 # 文件推送（分帧/重传/续传）与日志接收（§7.6）
│   ├── engine/                   # 测试编排（§14）+ 批量 OTA 编排（batch.py）
│   └── api/                      # FastAPI app 工厂与 REST/WS 路由
└── tests/                        # pytest（158 个用例）
```

设计依据：仓库根目录《无限自动化框架SDD_V1.6.md》（附录 A、§7.6、§8.4、
§9、§11.2、§12.9、§14、§16.3、§16.4）。
