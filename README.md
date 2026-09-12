# 无限自动化测试框架

服务器 ⇄（TCP/JSON+二进制帧）⇄ Android 手机 ⇄（BLE/GATT）⇄ 多台 DUT 的无线自动化测试系统。
一台服务器管理多台 Android 手机，每台手机通过 BLE 时分复用调度管理 20+ 台被测设备
（并发连接 2~5 台，连接槽轮转/抢占/老化调度）。

**内网部署，免鉴权**（实验室环境；token 体系已移除，协议字段保留兼容）。

## 系统拓扑

```
                 ┌──────────────────────────────────────┐
  使用方          │              Server                  │
  浏览器 ⇄ REST/WS │  gateway(10086)  api(8080) 引擎/传输/  │
  （Web 控制台）   │  配置中心/命令台账/事件库/测试报告      │
                 └───────────────┬──────────────────────┘
                                 │ TCP 长连接（JSON + 0xAC42 二进制帧）
                 ┌───────────────┴──────────────┐
                 │   Android Agent（前台服务）    │  × N 台手机
                 │   连接槽调度/轮询/规则引擎/传输  │
                 └───────────────┬──────────────┘
                                 │ BLE（GATT）
                    DUT 1 … DUT N（每手机 20+ 台受管）
```

## 仓库结构

| 目录/文件 | 说明 |
|-----------|------|
| `android-agent/` | Android 端 Agent（Java）：前台服务、连接槽调度、轮询处理链、文件传输、控制台 Activity（极简：服务器地址 + 设备 ID + 设备状态卡片列表） |
| `server/` | 生产级服务器（Python 3.12 asyncio + FastAPI + SQLite）：协议网关、命令台账、配置中心、文件服务、事件入库、测试编排引擎、REST API + WS |
| `web/` | Web 控制台（Vue 3 + Element Plus）：Dashboard、Agents（命令下发）、设备管理、文件传输、测试编排（场景编辑器）、事件流 |
| `tools/` | `mock_server.py`（协议参考实现/联调自测）、`emulator_smoke.sh`（模拟器冒烟） |
| `无限自动化框架SDD_V1.5.md` | 软件设计文档（唯一事实来源，含附录 A 协议字段级定义） |
| `M1-M5任务拆解_Jira导入.csv` | 里程碑任务拆解（逐条引用 SDD 章节） |
| `SDD真机测试用例库.csv` | 真机测试用例库（P0~P2，按 SDD 章节组织） |

## 快速开始

### 1. 服务器（含 Web 控制台）

```bash
cd server && python3 -m venv .venv  # 或用 uv
.venv/bin/pip install -e .          # 依赖：fastapi/uvicorn/pydantic
cp deploy/server.toml.example server.toml   # 按需改端口/路径
.venv/bin/python -m wireless_server --config server.toml
```

TCP 网关监听 10086（agent 接入），API/控制台监听 8080。

### 2. Web 控制台

```bash
cd web && npm install && npm run build   # 产出 dist，由 server 托管
# 浏览器打开 http://<server>:8080
```

### 3. Android Agent

```bash
cd android-agent && gradle :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

手机与服务器同局域网，打开「无线测试Agent」→ 填服务器 IP 和设备 ID →
「启动服务」。或 adb 拉起（模拟器/CI 用 `--ez simulateDut true` 虚拟 DUT 模式）：

```bash
adb shell am startservice -a com.longcheer.agent.ACTION_START \
  -n com.longcheer.agent/.AgentService \
  --es server_host <IP> --ei server_port 10086 --es device_id phone-01
```

## 测试

```bash
# server（171 用例：协议/台账/配置/传输/引擎/API/集成）
cd server && .venv/bin/python -m pytest tests/ -q

# agent（225 用例：状态机/调度/轮询链/传输/命令对账）
cd android-agent && gradle :app:testDebugUnitTest

# web（vue-tsc 严格检查 + 构建）
cd web && npm run build

# 协议对照自测
python3 tools/mock_server.py --selftest
```

## 测试编排

测试场景为声明式 JSON（`server/scenarios/`），支持 7 种动作
（command/expect/delay/assertView/set_polling_interval/set_poll_rules/file_transfer）
与 8 种匹配器，步骤级报告含 ACK/事件时延 P50/P95（对齐 SDD §14.3 验收基线）。
样例：`basic_connect_poll.json`（连接+轮询）、`ota_upgrade.json`（OTA 全流程）。
前端「测试编排」页可视化编辑/运行/查看报告，详见 `server/README.md` §7。

## 文档

- 各模块详细文档：`android-agent/README.md`、`server/README.md`、`web/README.md`
- 设计与协议：`无限自动化框架SDD_V1.5.md`（附录 A = 19 命令 + 18 事件逐字段定义）
