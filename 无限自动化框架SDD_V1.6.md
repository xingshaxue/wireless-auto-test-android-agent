# 无限自动化框架 SDD — Android 端软件设计文档

| 项目 | 内容 |
|------|------|
| 文档名称 | 无限自动化框架 Android 端软件设计文档 |
| 版本 | V1.6（真机联调同步） |
| 日期 | 2026-09-26 |
| 范围 | 服务器 — Android 手机 — BLE 多 DUT 架构中的 Android 端详细设计 |
| 参考资料 | 设计讨论记录（见附录 B） |

**修订记录**

| 版本 | 日期 | 修订内容 |
|------|------|----------|
| V1.0 | 2026-08-19 | 初稿；合并初始设计框架与修订版连接调度设计，明确 BLE 并发连接 2~5 台、受管设备 20+ 台的约束 |
| V1.1 | 2026-08-20 | 补齐：连接后动作决策、复杂轮询、规则引擎、文件传输、设备移除/暂停流程、通知事件处理、槽位动态调整、队列背压、错误码体系、安全设计、日志与可观测性、系统权限异常、本地存储管理、风险与测试策略扩充、实现要点（接口签名、包结构、帧格式、配置 Schema、算法伪代码） |
| V1.2 | 2026-08-20 | 设计级修订（21 项缺陷收口）：状态机补合法迁移表(4.1)；SET_MAX_CONNECTIONS 失败回 3003(5.4)；stale 清除时机唯一化+欠账防重入(6.1)；notifyCharacteristics 配置项与 tick 周期约束(6.4)；SET_DEVICE_STATE 改写业务标记而非状态机状态(7.3.2)；通知保槽优先级改为带过期的 notifyBoost(7.3.3)；ManagedDeviceInfo 快照须回写内部状态(8.1)；PollingConfig 增 notifyCharacteristics(8.5)；事件命名统一为 DEVICE_STATE(10.2)；错误码增 rawStatus 透传(12.9)；配置 Schema 增 profile/notifyCharacteristics/tickIntervalMs/cooldownMs(16.4) |
| V1.3 | 2026-08-21 | 设计级修订（调度与状态机 8 项收口）：时间片预算纳入建连开销、新增 setupBudgetMs 且切片自 READY 起算(5.2/6.2/6.4)；RECONNECTING 改为不持槽的退避计时状态、到期经 WAITING_SLOT 重新调度(4.1/7.5)；新增欠账老化 agingThresholdMs 防轮询饥饿(5.2/12.4)；蓝牙关闭批量迁移限定连接态设备(12.10)；PAUSE 在连接中/活动中延迟生效(pendingPause)、与 pinned 文件传输的挂起/中止交互明确化(7.7)；16.5 伪代码收口：tick 循环改状态白名单+显式防重入、requestSlot 幂等 upsert(3.6)、selectVictim 补 notifyBoost 过滤、冷却过滤落分配侧、补时间片到期释放与建连超预算强释放逻辑 |
| V1.4 | 2026-08-21 | 协议与模型收口（19 项）：TCP 流 JSON 长度前缀分帧与二进制帧解复用规则(16.3)；CONNECT_DEVICE 幂等/默认即时连接/兼作 ERROR 恢复入口、ACK 语义明确(7.2)；REMOVE 与 RESET 清理队列逐条回 ACK、新增错误码 2004(7.7/7.8/12.9)；新增 RESET 软重置流程(7.8)；snapshot 改不可变副本+显式写接口的并发约定(8.1)；ManagedDeviceInfo 增 notifyBoostUntil、pendingCommands 统一 QueuedTask 承载命令与轮询任务(8.1/7.4.1/8.6)；Condition 模型对齐规则 JSON 的 value 与 BETWEEN 区间(8.7)；tick 校验条件修正为 >最小间隔/2 即钳制(6.4/16.4)；POLL_RESULT 去 requestId、增 stale 字段(10.3)；POLL_DATA_STALE 不带错误码、改带 lastPollTime/reason(12.9)；新增 FILE_REQUEST 上行事件打通规则触发传输(7.3.2/10.2/7.6)；调度计时统一单调时钟(9)；BLE Executor 多线程按 MAC 分片(3.8/9)；配置 Schema 补 11 个运行旋钮(16.4)；FILE_DOWNLOAD_RESUME 统一 seq 口径、文件哈希定 SHA-256(7.6/16.3)；MTU 阶梯协商与吞吐预估前提(3.2)；GATT 码表实测校准说明、133 区分来源(12.1)；DUT 地址随机化(RPA)前提风险(15)；测试策略补全迁移表单测/故障注入/Schema 校验用例(14) |
| V1.5 | 2026-08-21 | 开发基线收口（2 项硬缺口 + 4 项并行项）：新增**附录 A 通信协议字段级定义**——通用信封与编码约定(A.1：byte[] base64 / MAC / UUID / 枚举)、19 条命令(A.2)与 18 类事件(A.3)逐字段表；**Decoder 字段映射入配置**（16.4 `devices[].fields` + 8.9 FieldMapping 模型 + 7.3.2 规则引用加载时校验）；10.2 补 `REGISTER` 事件；文件传输分块写定为 WRITE_NO_RESPONSE、`WRITE_CHAR` 增 `writeType` 参数(7.6/A.2)；配置增 `configVersion` 与"整项替换、下一 tick 原子生效"更新约定(16.4)；14.3 补量化验收目标基线（一轮耗时/命令延迟 P95/24h 内存与失败率/老化获槽上限） |
| V1.6 | 2026-09-26 | 真机联调（手环 p67）落地同步（8 项）：A.2 命令 19→21 条——补 `FILE_EXPORT` 与新增 `SET_TRANSFER_CHANNEL`（传输通道运行中整项替换，任务启动时读快照选路、进行中不换）；A.3 事件 18→20 类——补 `EXPORT_RESULT` 与新增 `EXPORT_PROGRESS`（~500ms 节流、覆盖式）；7.6 增 **SPP 加速通道**（DeviceConfig.transferChannel ble/spp/auto、RFCOMM 承载 33x 协议、auto 握手失败回退 BLE）与 **fileId+SHA-256 下载去重**（`<fileId>.part`/`.bin` 缓存、命中直进 BLE 段），`FILE_TRANSFER` 增 `fileName` 字段；新增 7.9 设备文件导出流程（061/062/063 锁步拉取、目录模式、SPP 承载、EXPORT_FRAME/END 回传）；16.3 帧类型增 0x05/0x06；16.4 DeviceConfig 增 `transferChannel`、运行中更新清单补 `SET_TRANSFER_CHANNEL`；新增 7.10 批量 OTA（server 侧 BatchOrchestrator 编排，agent 协议零改动） |

---

## 1. 引言

### 1.1 目的

本文档描述无限自动化测试框架中 **Android 端** 的软件设计，覆盖总体架构、模块划分、设备状态机、连接调度、轮询机制、通信协议、异常处理与测试策略，作为后续编码实现的依据。

### 1.2 系统范围

整体测试框架拓扑：

- **服务器**：自动化测试/控制中心，一台服务器对接多台 Android 手机，通过局域网 TCP/IP 连接；
- **Android 手机**：服务器 Agent + BLE Central，一台手机通过 BLE 对接多台 DUT；
- **DUT（被测设备）**：通过 BLE 与 Android 手机连接，接受命令并上报状态。

Android 端通过轮询方式持续查询每台受管设备的状态。

### 1.3 关键设计约束（修订后）

| 约束 | 取值 | 说明 |
|------|------|------|
| BLE 并发连接数 | **2~5 台** | 由配置决定上限，需考虑 Android 蓝牙栈实际能力 |
| 受管设备总数 | **≥ 20 台，可配置扩展** | 远超并发连接数，因此连接资源必须时分复用 |
| 传输方式 | BLE（GATT）；文件传输/导出可选经典蓝牙 SPP 加速通道（7.6） | 服务器 ↔ Android 为 TCP/IP（JSON/Protobuf） |

### 1.4 术语与缩写

| 术语 | 说明 |
|------|------|
| DUT | Device Under Test，被测设备 |
| Agent | Android 手机端与服务器通信的客户端进程 |
| BLE Central | BLE 主设备角色（Android 手机） |
| GATT | Generic Attribute Profile，BLE 数据交互协议 |
| MTU | Maximum Transmission Unit，单次传输最大字节数 |
| 连接槽（Connection Slot） | Android 端抽象出的 BLE 并发连接资源单位 |
| 受管设备（Managed Device） | 已注册到 Device Registry 的设备，可能处于未连接/连接中/已连接状态 |

---

## 2. 总体架构

### 2.1 系统拓扑

```
+---------------------------+
|          Server           |
|  自动化测试/控制中心        |
+-------------+-------------+
              | TCP/IP
              | JSON / Protobuf
+-------------v-------------+
|        Android Agent       |
|  +----------------------+  |
|  |  TCP Client / Codec  |  |
|  +----------------------+  |
|  | Command Dispatcher   |  |
|  +----------------------+  |
|  | State Reporter       |  |
|  +----------+-----------+  |
|             |              |
|    Device Registry         |  <-- 所有受管设备（20+），包含未连接设备
|             |              |
|   ConnectionSlotManager    |  <-- 维护 2~5 个活动连接槽
|             |              |
|   ActiveConnectionPool     |  <-- 当前实际连接的 DeviceController
|             |              |
|   ConnectionScheduler      |  <-- 决定哪个设备获得连接槽
|             |              |
|   PollingScheduler         |  <-- 基于虚拟轮询计划
|             |              |
|   GattExecutor             |
+----------------------------+
         |  BLE
+--------v--------+     +------------------+
|  DUT 1 ... DUT N| ... |  DUT (未连接)      |
|  （2~5 台在线）  |     |  （受管，等待调度）  |
+-----------------+     +------------------+
```

### 2.2 核心设计思想

- **设备管理与物理连接解耦**：`DeviceRegistry` 管理全部设备，`ActiveConnectionPool` 只管理当前持有连接槽的设备。
- **连接槽管理**：明确限制并发连接数为 2~5，实现资源复用。
- **连接调度器**：基于优先级与时间片，动态决定哪些设备获得有限的连接槽。
- **轮询虚拟化**：每台设备维护独立的虚拟轮询时钟（`nextPollTime`），与设备是否在线无关、照常推进；到期时若设备没有槽位则记为欠账，获得连接槽后补执行。例如 3 槽 6 设备、间隔 30s：t=30s 时 6 台全部到期，调度器按到期顺序轮流给槽，每台设备只是**晚一点**被轮询到，绝不会永远轮不到（由 5.2 第 6 条的欠账老化机制保证）。

### 2.3 设计目标

- 管理设备总数 ≥ 20 台，可通过配置扩展；
- 同时保持 BLE 连接 2~5 台，上限可配置；
- 连接资源动态调度，优先服务于：① 服务器即时命令 ② 定时轮询需求 ③ 需持续上报状态的设备；
- 未连接设备仍可接收命令，命令进入等待队列，获得连接槽后执行；
- BLE 操作串行化、可重试、可超时，避免 GATT 操作冲突；
- 每台 DUT 独立轮询状态，轮询间隔可配置；
- 与服务器通信异步、可靠、可恢复（断线重连、失败数据补报）；
- 设备可动态添加、移除、暂停、恢复，互不影响。

---

## 3. 模块划分与职责

### 3.1 TCP 通信层

**主要类**：`TcpClient`、`ProtocolCodec`、`HeartbeatManager`

职责：

- 建立/重连 TCP 连接；
- 心跳保活（`heartbeatIntervalMs`，默认 5s 一次，见 16.4）；
- 报文编解码（JSON 或 Protobuf）；
- 接收服务器命令并分发；
- 将本地事件、响应、状态快照发送给服务器。

关键点：

- 所有网络操作在独立线程执行，避免阻塞主线程；
- TCP 断开后自动重连，本地缓存上报失败的数据，连接恢复后补报；
- TCP 与 BLE 操作完全解耦，网络阻塞不影响 BLE 连接。

### 3.2 BLE Central Manager

**主要类**：`BleCentralManager`、`BluetoothAdapterWrapper`、`ScanFilter`

职责：

- 初始化 `BluetoothAdapter`；
- 根据 MAC 直连 DUT（不依赖扫描），必要时通过服务 UUID 扫描；
- 创建 `DeviceController` 并放入 `DeviceRegistry`；
- 维护手机自身 BLE 状态（蓝牙开关、支持的最大连接数等）；
- 请求 MTU：连接后按阶梯协商 **512 → 247 → 185 → 默认 23**（逐级失败后保持当前值），记录实际协商值；7.6 的 10~30 KB/s 吞吐预估以高 MTU（≥ 247）+ 短连接间隔为前提，MTU=23 时吞吐下降约一个量级，文件传输时长估算须按实际 MTU 修正。

关键点：

- 每个 DUT 使用独立的 `BluetoothGatt` 与 `BluetoothGattCallback`，避免回调混乱；
- 连接超时控制（建议 10s）；
- BLE 连接不稳定，所有连接操作均需超时与重试机制。

### 3.3 Device Registry（设备注册表）

- 保存所有受管设备信息：MAC、设备 ID、类型、状态、轮询配置、优先级、最近轮询结果、下次应轮询时间、待执行命令队列等；
- 设备总数可超过并发连接数；
- 提供按状态、优先级、下次轮询时间等维度的查询接口；
- 设备添加后即进入注册表，但不一定立即连接。

### 3.4 ConnectionSlotManager（连接槽管理器）

- 维护固定数量的连接槽（2~5 个，可配置）；
- 每个槽位对应一个可用的 BLE 连接资源；
- 提供 `acquireSlot(device)` / `releaseSlot(device)` 接口；
- 槽位已满时新设备必须等待，由 `ConnectionScheduler` 决定踢出某设备或排队；
- 槽位可被抢占，但须经过策略判断。

### 3.5 ActiveConnectionPool（活动连接池）

- 持有当前实际占用连接槽的 `DeviceController` 实例；
- 最大容量 = 连接槽数；
- 设备进入池时执行实际 BLE 连接，离开池时执行断开。

### 3.6 ConnectionScheduler（连接调度器）

**核心模块**，解决 "20+ 设备 vs 2~5 连接槽" 的矛盾。

职责：

- 接收来自命令分发器、轮询调度器、状态上报器的连接需求；
- 根据优先级与调度策略决定哪个设备获得连接槽；
- 管理连接切换：槽满时选择踢出对象（最久未使用、优先级低、空闲超时等）；
- 支持配置常驻连接设备（需持续监控的设备）；
- 连接请求按 `deviceMac` 幂等去重：重复 `requestSlot` 执行 upsert（更新优先级、请求时间与过期时间），不重复排队；授槽前执行冷却过滤与通知活跃期过滤（5.2 / 7.3.3 / 16.5）。

### 3.7 DeviceController（受管设备对象）

- 不再直接对应一个实际连接，而是对应一个设备实体；
- 保存设备状态机、待执行命令队列、轮询配置、下一次轮询时间等；
- 获得连接槽时才内部创建 `BluetoothGatt` 并执行实际连接；
- 失去连接槽时保存执行上下文，等待下次获得槽后继续；
- 每个设备内部串行执行命令，避免同一设备的 GATT 操作并发；
- 命令队列支持优先级，服务器命令优先于轮询任务；
- 每个 DUT 的 GATT 操作超时独立，避免单设备阻塞其他设备。

### 3.8 GattExecutor

- 接收来自 `DeviceController` 的 `GattCommand`；
- 串行或有限并发执行 GATT 读写、通知订阅等操作（只作用于 `ActiveConnectionPool` 中的设备）；
- 处理回调结果并回传 `DeviceController` 或上报服务器。

关键点：

- Android BLE 的 GATT 操作建议串行化，尤其是同一连接上的操作；
- 全局建议使用单线程执行器或 2~3 线程的线程池，防止并发过多导致蓝牙栈不稳定；使用多线程池时必须**按设备 MAC 哈希分片**——同一设备的所有操作固定落到同一线程，3.7 的"同设备串行"约束在多线程下依然成立（9 章）。

### 3.9 PollingScheduler（轮询调度器）

- 维护所有设备的**虚拟轮询计划**，不依赖实际连接状态；
- 根据每个设备的 `nextPollTime` 判断是否到期；
- 到期设备产生轮询需求，交由 `ConnectionScheduler` 申请连接槽；
- 设备已连接则直接轮询；未连接则等待获得槽后执行；
- 轮询完成后设备可能被释放连接槽，供其他设备使用。

### 3.10 StateReporter（状态上报器）

- 异步上报设备状态、轮询结果、命令响应、心跳等事件；
- 上报失败时本地缓存，TCP 恢复后补报。

---

## 4. 设备状态机

每个受管设备使用独立状态机，管理"受管生命周期 + 连接生命周期"：

```
REGISTERED          // 已注册，未连接，等待调度
  -> WAITING_SLOT   // 有任务需求，等待连接槽
  -> CONNECTING     // 已获得槽，正在建立 BLE 连接
  -> SERVICE_DISCOVERING
  -> CONFIGURING    // 请求 MTU、订阅通知、缓存特征
  -> READY          // 已连接并可用
  -> POLLING / COMMANDING
  -> DISCONNECTED   // 主动或被动断开，设备仍受管
  -> RECONNECTING   // 异常断开且需要重连时
  -> ERROR          // 多次失败，暂时不可用
  -> PAUSED         // 被服务器或系统暂停，不参与调度
  -> TERMINATED     // 服务器要求终止或设备移除
```

> 上图为状态总览，并非允许任意相邻迁移。合法迁移以 **4.1 迁移表**为准；非法迁移由状态机抛 `IllegalStateException` 拦截。

### 4.1 合法迁移表

| 当前状态 \ 可迁至 | WAITING_SLOT | CONNECTING | SERVICE_DISCOVERING | CONFIGURING | READY | POLLING / COMMANDING | DISCONNECTED | RECONNECTING | ERROR | PAUSED | REGISTERED | TERMINATED |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| REGISTERED | ✓ | | | | | | | | ✓ | ✓ | — | ✓ |
| WAITING_SLOT | ✓ | ✓ | | | | | | | ✓ | ✓ | ✓ | ✓ |
| CONNECTING | | | ✓ | | | | ✓ | | ✓ | | | ✓ |
| SERVICE_DISCOVERING | | | | ✓ | | | ✓ | | ✓ | | | ✓ |
| CONFIGURING | | | | | ✓ | | ✓ | | ✓ | | | ✓ |
| READY | | | | | — | ✓ | ✓ | | | ✓ | | ✓ |
| POLLING | | | | | ✓ | — | ✓ | | ✓ | | | ✓ |
| COMMANDING | | | | | ✓ | — | ✓ | | ✓ | | | ✓ |
| DISCONNECTED | ✓ | | | | | | — | ✓ | | ✓ | ✓ | ✓ |
| RECONNECTING | ✓ | | | | | | ✓ | — | ✓ | | | ✓ |
| ERROR | | | | | | | | | — | ✓ | ✓ | ✓ |
| PAUSED | ✓ | | | | | | | | | — | ✓ | ✓ |
| TERMINATED | | | | | | | | | | | | — |

**关键迁移语义补充**：

- `READY`、`POLLING`、`COMMANDING` 均可直接迁至 `DISCONNECTED`（异常断开或被动断开）——这三条是连接生命周期内的断开，与"瞬态活动状态不参与调度决策"不冲突。
- `POLLING` / `COMMANDING` 是瞬态活动状态：任务正常结束迁回 `READY`；异常断开迁至 `DISCONNECTED`（随后按 7.5 进入 `RECONNECTING`）。
- `CONNECTING` 连接失败可直接 `DISCONNECTED` → `RECONNECTING`，或多次失败直接 `ERROR`。
- `RECONNECTING` 为**退避计时状态，不持有连接槽**（7.5：异常断开即释放槽位）；退避到期迁至 `WAITING_SLOT` 重新参与调度，获槽后才经 `CONNECTING` 重建连接——不允许绕过调度直接 `RECONNECTING → CONNECTING`（4.1 表中该迁移已移除）。
- `ERROR → REGISTERED` 由服务器重发 `CONNECT_DEVICE` 触发（7.2 第 3 条），重连计数清零后重新参与调度。
- `PAUSED` 阻断一切调度迁移，仅可恢复至 `REGISTERED`（有欠账则经 `WAITING_SLOT`）或 `TERMINATED`。
- `TERMINATED` 为终态，不再迁移。

### 4.2 状态说明

- `REGISTERED`：初始状态，设备已注册但无任何任务；
- `WAITING_SLOT`：有任务（命令或轮询）但连接槽已满，等待调度；
- `PAUSED`：设备被暂停，不会被调度也不会连接；
- `DISCONNECTED` 后若任务未完成，按策略回到 `WAITING_SLOT` 等待重新调度；
- `RECONNECTING`：异常断开后的退避等待状态，**不占用连接槽**；退避到期转 `WAITING_SLOT`，按欠账任务的最高优先级重新申请槽位（7.5）；
- `POLLING / COMMANDING`：瞬态活动状态，表示设备正在执行轮询或服务器命令，任务结束后回到 `READY`，不参与调度决策。

状态机是 `DeviceController` 的核心属性，任何状态变更都应通过事件上报服务器。

---

## 5. 连接调度策略

### 5.1 连接需求来源与优先级

| 需求来源 | 优先级 | 说明 |
|----------|--------|------|
| 服务器即时命令 | 高 | 如 `READ_CHAR`、`WRITE_CHAR` |
| 设备事件上报 | 中高 | 仅对已连接设备有效：收到 GATT 通知需要上报时，提高该设备保持槽位的优先级（未连接设备无法产生通知，见 7.3.3） |
| 定时轮询 | 中 | 设备轮询到期 |
| 断线重连 | 随欠账 | 重连只是恢复通道：优先级取该设备欠账任务的最高优先级，无欠账不触发重连（7.5） |
| 保持连接（常驻） | 低 | 配置指定的常驻设备 |

### 5.2 槽位分配策略

采用 **优先级 + 时间片轮转 + 空闲回收**：

1. **常驻设备优先**：可配置 1~2 个设备长期占用连接槽，适合需实时监控的设备；
2. **高优先级任务抢占**：服务器命令到达且无空闲槽时，踢出优先级最低且空闲时间最长的设备；
3. **轮询时间片**：轮询任务按设备分组，每组获得固定时间片（如 2s）。**时间片自设备进入 `READY` 起算**——建连消耗（connect + 服务发现 + MTU + 订阅）不占时间片，由独立的建连预算 `setupBudgetMs` 兜底（6.4）；时间片结束且排队任务清空后释放槽位，切换下一组；切片内仍在执行的轮询任务由 GATT 操作超时兜底，不被中途打断；
4. **空闲超时释放**：设备在 `READY` 状态但无任务持续 N 秒后主动释放槽位（常驻设备除外）；
5. **防止抖动**：设备被踢出后记录 `coolDownUntil = now + cooldownMs`（如 5s），**冷却过滤在分配侧执行**——调度器授槽时跳过 `now < coolDownUntil` 的设备（16.5），避免被踢设备凭欠账立即抢回槽位；
6. **欠账老化（anti-starvation）**：连接请求每持续等待 `agingThresholdMs`（默认 30s，可配），有效优先级自动升一级，封顶 `HIGH`；保证命令洪泛下轮询欠账最终必然获槽（兑现 2.2 "绝不会永远轮不到"），12.4 的"可提高优先级"即以本机制为准。

### 5.3 设备切换流程

1. `ConnectionScheduler` 收到连接需求；
2. 检查目标设备是否已在连接池：
   - 是 → 直接执行任务；
   - 否 → 检查是否有空闲槽：
     - 有空闲槽 → 直接分配槽位，触发连接；
     - 无空闲槽 → 执行抢占策略，选择牺牲设备，断开并释放其槽位；
3. 被踢设备进入 `DISCONNECTED`，保留任务队列与轮询上下文，并记录 `coolDownUntil = now + cooldownMs`（5.2 第 5 条）；
4. 新设备获得槽位，执行连接、服务发现、配置；
5. 连接就绪后执行排队命令或轮询。

### 5.4 槽位数动态调整（SET_MAX_CONNECTIONS 驱逐策略）

- **调大**：新槽位立即生效，等待队列按优先级补位；
- **调小**（如 5 → 2）：需驱逐超出上限的已连接设备，按以下顺序选择牺牲者：
  1. 无任务且非 pinned、非常驻的设备（空闲时间长的优先）；
  2. 有任务但任务可延迟的设备（轮询、普通命令）；
  3. 常驻与 pinned 设备最后考虑；若新上限低于常驻 + pinned 数量，**拒绝调整**——不改变任何已连状态，回 `CMD_ACK`（错误码 `3003 槽位不足`），并在报文体携带 `required=<常驻+pinned 数>` / `max=<请求值>` 供服务器决策。

> 参数校验与驱逐失败统一走资源与配置错误码段（SDD 12.9 的 3xxx）：`setMaxSlots` 请求越界（<2 或 >5）回 `3003`；驱逐后仍超上限（常驻+pinned 无法释放）亦回 `3003`，二者均不在 `CommandDispatcher` 归类为 `2001` 协议错误。

---

## 6. 轮询调度设计（多设备、少连接）

### 6.1 虚拟轮询计划

- 每个设备独立维护 `nextPollTime`，设备未连接时计划仍继续推进；
- 获得连接槽并完成轮询后更新 `nextPollTime = now + interval`；
- 长时间未获得槽的设备保留旧结果，但需上报"数据过期"标记（`pollDataStale=true`）；
- **`pollDataStale` 清除时机唯一**：该设备一轮轮询成功走完处理链（`PollingScheduler.onPollCompleted`）时清为 `false`；任何其它路径（获得槽、连接就绪、命令执行）不清除——只有真正补采到新数据才算"不过期"；
- **欠账防重入**：设备到期但上一轮轮询任务尚未完成（任务仍在 `pendingCommands` 或 `GattExecutor` 中未回调）时，**本轮只标记、不入队新任务**，避免同一设备重复堆积轮询任务；任务回调完成、`nextPollTime` 推进后才允许下一轮入队；
- 轮询周期从上一轮完成时起算（而非固定频率触发），避免任务堆积；
- 欠账时长参与调度：持续等待超 `agingThresholdMs` 的轮询请求按 5.2 第 6 条自动提升有效优先级，防止被持续的服务器命令饿死。

### 6.2 轮询分组与时间片

每组的实际槽位占用 = **建连预算 + 时间片**：动态槽上的设备每次都是冷建连（`connectGatt` + 服务发现 + MTU 协商 + 通知订阅，实测普遍 2~5s），这部分消耗由 `setupBudgetMs`（默认 4000ms，按机型实测校准）兜底，**不计入时间片**；时间片自 `READY` 起算，只覆盖轮询读本身。建连超预算视为建连失败，按 12.5 强制释放并走 7.5 重连策略。

示例（常驻 1 槽 + 动态 3 槽 + 20 台设备）：

- 常驻连接槽：1 个（设备 A 长期连接）；
- 动态连接槽：3 个（轮询其余设备）；
- 剩余 19 台设备，每 3 台一组；
- 每组占用 ≈ setupBudgetMs 4s + 时间片 2s = **6s**；
- 完整一轮约 7 组 × 6s ≈ **42 秒**（而非忽略建连开销时的 14 秒）；
- 因此本例中 `intervalMs` 应 ≥ 42s 并留余量（建议 60s）；若误配 30s，欠账将持续累积、数据长期带过期标记（6.4）；
- 可通过调整轮询间隔、时间片长度、槽数平衡实时性与资源占用；缩短建连耗时（如精简订阅特征数、复用已缓存的服务发现结果）对一轮时长的收益大于压缩时间片。

### 6.3 轮询结果缓存

- `DeviceRegistry` 保存每台设备的最后轮询结果与时间戳；
- 服务器查询状态时优先返回缓存，避免不必要的连接；
- 缓存过期（超过 `staleThresholdMs`，默认 120s，见 16.4）可触发按需连接读取；
- 结果无变化时可只上报心跳计数，有变化时上报详细字段（`reportOnlyChanged` 配置项）。

### 6.4 轮询配置建议（PollingConfig）

**intervalMs**：先计算一轮完整轮询时间，再定间隔。

```
一轮完整轮询时间 ≈ ceil((设备总数 − 常驻设备数) / 动态槽数) × (setupBudgetMs + 时间片)
```

- `setupBudgetMs`：单组设备的建连预算（CONNECTING → READY 全流程，默认 4000ms，按主流机型实测校准；应 ≤ `connectGatt` 硬超时 10s，超预算即按 12.5 强制释放）。**忽略建连开销的一轮时长估算必然偏小，是本调度模型最常见的配置错误**；
- `intervalMs` 应 ≥ 一轮时间且留余量；小于一轮时间则欠账永远还不完，实际轮询频率退化为"一轮一次"，数据持续带过期标记；
- 最小值 200ms（防蓝牙栈过载）；温度/内存异常时自动调大；
- 常驻设备可配小间隔，普通设备配大间隔，按需分层。

**readCharacteristics**：少而精（典型 3~8 个），重要特征排前；特征数 × 单次 GATT 往返时间必须小于时间片，否则一轮读不完；有"先写后读"依赖时改用 `PollingTask` 动作序列（7.3.1）。

**notifyCharacteristics**：需在 `CONFIGURING` 阶段订阅通知的特征 UUID 列表（7.3.3 的数据来源）。未列入的特征不会被订阅，其变化只能靠轮询补采。与 `readCharacteristics` 可重叠（同一特征既轮询又订阅）。

**reportOnlyChanged**：默认 `true`（上报流量小）；需要完整时间序列时才开 `false`。

**调度 tick 周期约束**：`AgentService` 驱动 `PollingScheduler` 的 tick 周期必须 **≤ 最小轮询间隔的一半**（最小间隔 200ms → tick ≤ 100ms），否则 200ms 间隔设备实际被拖到 tick 周期。tick 周期由配置项 `tickIntervalMs` 控制，默认 100ms，不可大于配置中最小 `intervalMs` 的一半（校验与钳制规则见 16.4）。

配置来源：注册时服务器随 `REGISTER_ACK` 下发（**每设备一份**），运行中 `SET_POLLING_INTERVAL` 动态调整，Android 端内置本地默认值兜底。

---

## 7. 关键流程设计

### 7.1 启动与注册流程

1. Android 端启动**前台 Service**（保证长连接与 BLE 连接在后台不被系统回收）；
2. 初始化 TCP Client，连接服务器；
3. 注册 Android 设备信息：设备 ID、IP、端口、Android 版本、BLE 支持情况、最大连接数；
4. 服务器返回注册确认，下发初始 DUT 列表、轮询配置、常驻设备列表；
5. Android 端所有设备初始状态为 `REGISTERED`，不主动连接，按需逐步恢复工作。

### 7.2 设备接入与连接流程

1. 服务器下发 `CONNECT_DEVICE`（携带 DUT MAC 或设备 ID，可带 `lazyConnect`，默认 `false`）；
2. `CommandDispatcher` 转发给 `BleCentralManager`；
3. **幂等注册**：MAC 未注册 → 创建 `DeviceController` 并放入 `DeviceRegistry`，状态 `REGISTERED`；已注册 → 不重复创建 Controller，视为重激活（`ERROR` 状态设备借此走 4.1 的 `ERROR → REGISTERED` 恢复路径，重连计数清零——本命令兼作 ERROR 恢复入口）；
4. **默认即时连接**（`lazyConnect=false`）：命令本身即一条 HIGH 优先级连接需求——设备转 `WAITING_SLOT` 申请槽位；`lazyConnect=true` 时仅注册、不主动连接，待轮询到期或后续命令触发（冷启动批量恢复场景，7.1）；
5. 获得槽位后进入 `CONNECTING`，调用 `BluetoothDevice.connectGatt`（超时 `connectTimeoutMs`，默认 10s）；
6. 连接成功 → `SERVICE_DISCOVERING`（等 `onServicesDiscovered` 回调），缓存所需服务与特征 UUID；
7. 按阶梯协商 MTU（3.2）、按 `notifyCharacteristics` 订阅必要通知 → `READY`，上报 `DEVICE_STATE`（`state=READY`），并回 `CMD_ACK`（errorCode=0）——**`CONNECT_DEVICE` 的 ACK 语义为"设备已就绪"，不是"已登记"**；
8. 连接失败 → 按策略重连（7.5）；最终失败回 `CMD_ACK`（1xxx 错误码 + `rawStatus` 透传原始 GATT status，见 12.9），并上报 `DEVICE_STATE`（`state=ERROR` + `errorCode`）；
9. 设备已 `READY` 时收到本命令 → 直接回 `CMD_ACK` 成功（幂等）。

#### 7.2.1 连接后动作决策（READY 时刻）

动作的确定分两半：**登记在连接前，消费在连接后**。`DeviceController` 状态机从 `CONFIGURING` 迁移到 `READY` 的那一刻（代码层面为 `onReady()` 回调）是唯一决策点。

**① 登记环节（连接前，随时发生）**——不看设备是否在线，只记账：

| 需求来源 | 登记人 | 登记内容 | 优先级 |
|----------|--------|----------|--------|
| 服务器命令（READ_CHAR / WRITE_CHAR） | `CommandDispatcher` | 命令入 `DeviceController.pendingCommands` | HIGH |
| 轮询到期 | `PollingScheduler` | 轮询任务入队 | NORMAL |

登记后设备状态进入 `WAITING_SLOT`，向 `ConnectionScheduler` 申请连接槽。

**② 消费环节（READY 时刻）**：

1. 从 `pendingCommands` 按优先级取任务（HIGH 服务器命令 > NORMAL 轮询）；
2. 逐个提交给 `GattExecutor` 串行执行；
3. 队列清空且无新任务 → 开始空闲计时，超时释放槽位（常驻设备除外）。

连接过程中（`CONNECTING` / `CONFIGURING`）新到的需求**照常入队**，`READY` 后一起消费，无需特殊处理。

**整体闭环**：

```
需求登记（连接前）          调度            连接            消费（连接后）
服务器命令 ─┐
           ├→ pendingCommands ─→ ConnectionScheduler 分槽 ─→ READY 迁移 ─→ 按优先级 drain
轮询到期 ──┘     ↑ 记账，与连接无关              ↑                ↑ 唯一决策点
                └── 有欠账才申请槽 ──────────────┘                └→ GattExecutor 串行执行
```

设计意图：**动作需求驱动连接（有欠账才申请槽），连接触发动作执行（READY 就还账）**。"决定要做什么"的事实来源唯一——设备自己的 `pendingCommands` 队列，连接只是触发消费的开关。若反其道而行（连接建立后才临时向各方询问该设备要做什么），会导致决策逻辑分散、连接过程中到达的需求失去归属。

### 7.3 轮询状态流程

1. `PollingScheduler` 定期扫描所有受管设备；
2. 设备 `currentTime >= nextPollTime` 且状态为 `READY` → 直接创建 `PollingTask`；
3. 设备未连接 → 产生轮询需求交 `ConnectionScheduler` 申请连接槽，获得槽后执行；
4. `PollingTask` 加入设备命令队列（优先级 `NORMAL`）；
5. `GattExecutor` 执行特征读取，结果更新设备状态缓存；
6. `reportOnlyChanged = true` 时仅在有变化时上报 `POLL_RESULT`；
7. 更新 `nextPollTime = now + intervalMs`，按策略决定保留或释放槽位。

#### 7.3.1 复杂轮询场景（多特征依赖 / 先写后读）

部分 DUT 的轮询不是"独立读一组特征"这么简单，常见三类场景：

| 场景 | 例子 |
|------|------|
| 写命令触发响应 | 先 WRITE 请求码，再 READ 响应内容 |
| 特征间依赖 | 读特征 B 前必须先读/写特征 A |
| 多步序列 | 状态 → 错误码 → 固件版本，按序读取并组合上报 |

为此 `PollingTask` 设计为**有序动作序列**，而非单纯的特征列表：

```java
class PollingTask {
    String deviceMac;
    List<PollStep> steps;   // 有序执行的动作序列
    long timeoutMs;         // 整任务超时
    GattCommand.Priority priority;  // NORMAL
}

class PollStep {
    GattCommand.Type type;  // READ / WRITE
    UUID serviceUuid;
    UUID charUuid;
    byte[] payload;         // WRITE 时使用
    int timeoutMs;
    int maxRetry;
}
```

执行规则：

- 序列由 `GattExecutor` 按顺序串行执行（同一设备 GATT 操作天然串行）；
- 任一步失败 → 重试一次，仍失败则任务中止并上报错误；
- 简单轮询是该模型的退化形式（全部为 READ 的序列），`readCharacteristics` 视为 `steps` 全 READ 的简写，保持向后兼容。

#### 7.3.2 轮询结果处理与规则引擎（分支判断）

轮询不只是"读回来上报"，读到的值经常需要触发分支动作（电量 ≥ 20 做什么、温度 20~40 做什么、> 40 做什么）。分支判断**不属于 PollingConfig 的职责**（它只管"多久轮一次、读什么"），而是轮询结果处理链的职责，位于 7.3 流程第 5、6 步之间：

```
轮询结果（原始 bytes）
  → Decoder 解析为字段值 {battery: 85, temperature: 42}
  → RuleEngine 按优先级求值规则集
  → ActionExecutor 执行命中规则的动作
  → 结果缓存 + 上报服务器
```

**处理链组件**：

- **Decoder**：特征 UUID → 字段名 + 解析格式（uint8 / sint16 / 字符串等），映射由服务器按设备随配置下发（16.4 `devices[].fields` / 8.9 `FieldMapping`），轮询与通知处理链共用；规则 `conditions[].field` 引用的字段必须存在映射，加载时校验，缺失按配置错误拒绝并上报；
- **RuleEngine**：声明式规则求值，规则由服务器以 JSON 配置下发；
- **ActionExecutor**：执行动作词表中的动作，执行结果通过事件上报服务器。

**动作词表**：

| 动作 | 说明 |
|------|------|
| `REPORT_EVENT` | 上报事件/告警给服务器 |
| `EXECUTE_GATT` | 触发一次 GATT 操作（如温度过高写入关闭命令） |
| `SET_INTERVAL` | 动态修改本设备轮询间隔（自适应用户逻辑） |
| `SET_DEVICE_STATE` | 设置设备的**业务标记**（非状态机状态）——写入 `ManagedDeviceInfo.stateFlag`（自由字符串，如 `"OVERTEMP"`），由服务器/上层读取；**不得**直接修改 `DeviceState` 状态机状态，避免破坏状态机不变量 |
| `RELEASE_SLOT` | 主动释放连接槽 |
| `FILE_TRANSFER` | 触发文件传输任务（如"固件版本 < X → 自动 OTA"），目标文件须已在服务器登记；命中后 Android 上行 `FILE_REQUEST` 事件（10.2）请求文件，服务器补发 `FILE_TRANSFER` 后流程并入 7.6 |

**规则示例**（服务器下发的 JSON 配置，对应"温度 20/40 分档、电量 ≥ 20"）：

```json
{
  "rules": [
    {"ruleId": "r1", "priority": 10, "stopOnMatch": false,
     "conditions": [{"field": "temperature", "op": "GT", "value": 40}],
     "actions": [
       {"type": "REPORT_EVENT", "params": {"event": "TEMP_CRITICAL"}},
       {"type": "SET_INTERVAL", "params": {"intervalMs": 5000}}
     ]},
    {"ruleId": "r2", "priority": 5, "stopOnMatch": false,
     "conditions": [{"field": "temperature", "op": "GT", "value": 20}],
     "actions": [{"type": "REPORT_EVENT", "params": {"event": "TEMP_WARN"}}]},
    {"ruleId": "r3", "priority": 5, "stopOnMatch": false,
     "conditions": [{"field": "battery", "op": "GE", "value": 20}],
     "actions": [
       {"type": "EXECUTE_GATT",
        "params": {"type": "WRITE", "service": "180F", "char": "2A19", "payload": "..."}}
     ]}
  ]
}
```

**求值语义**：

- 按 `priority` 从大到小求值；命中且 `stopOnMatch=true` 则停止求值后续规则，否则继续（上例温度 42 时 r1、r2 都命中，warning 与 critical 事件都上报）；
- 同一字段的互斥档位（如 20~40 与 >40）用 priority + `stopOnMatch=true` 表达；层层上报用默认值。

**本地决策 vs 服务器决策**：

阈值判断与动作执行在 Android 本地完成，不走"上报 → 等服务器指示"的回路。原因：轮询周期为秒级，服务器往返延迟不可控；测试框架要求设备在服务器断连时仍能按规则自治运行。规则由服务器配置下发，执行结果通过事件上报。

**复杂逻辑兜底**：

声明式规则覆盖不了的逻辑（多字段联合计算、时序判断、与外部状态联动），由代码接口 `PollResultHandler` 实现——按 DUT 类型注册处理器，与规则引擎串在一条处理链上。

#### 7.3.3 通知事件处理流程

GATT 通知（Notify/Indicate）是 DUT 主动上报数据的通道，处理链与轮询共用（Decoder → RuleEngine → ActionExecutor）：

1. 设备 `CONFIGURING` 阶段订阅必要通知特征（`ENABLE_NOTIFY`）；
2. `BluetoothGattCallback.onCharacteristicChanged` 收到通知（Binder 线程）；
3. 数据原样入队，抛回 `BLE Executor` 处理（回调中不做耗时操作）；
4. 处理链：Decoder 解析 → RuleEngine 求值（通知字段同样可配规则）→ 结果缓存 + 上报；
5. 通知事件上报优先级高于轮询结果，立即排队发送，不等下一轮轮询。

边界语义：

- 通知仅对**已连接**设备有效——未连接设备的事件通道天然关闭，其状态只能靠轮询补采；
- 设备持槽期间收到通知 → **临时提升该设备的"保槽优先级"**：在 `ConnectionScheduler` 中给该设备设一个带过期时间的 `notifyBoostUntil`（默认 `now + timeSliceMs`）；`selectVictim` 在挑选牺牲者时跳过 `now < notifyBoostUntil` 的设备，等价于"通知活跃期内不被抢占"。这不是给已连接设备申请新槽（它已持槽），而是抬高它被踢出的门槛；过期后自动回落，避免长期占槽。
- 高频连续通知（传感器流式数据）需节流：最小上报间隔可配置（`notifyMinReportIntervalMs`，默认 200ms，见 16.4），防 TCP 拥塞。

### 7.4 服务器命令处理流程

1. 服务器下发命令（`READ_CHAR`、`WRITE_CHAR`、`START_POLL`、`STOP_POLL` 等），目标设备 MAC；
2. `CommandDispatcher` 在 `DeviceRegistry` 中查找设备；
3. 命令加入该设备待执行队列（优先级 `HIGH`）；
4. 设备已在池中且 `READY` → 立即执行；
5. 设备未连接 → 状态转 `WAITING_SLOT`，向 `ConnectionScheduler` 申请连接槽；
6. 连接成功后取出队列中命令执行；
7. 执行完成后按策略判断保持连接或释放槽位；
8. 结果通过 `StateReporter` 上报 `CMD_ACK`（携带请求 ID）。

#### 7.4.1 命令队列与背压

- `pendingCommands` 深度上限（默认 64，可配置），**命令与轮询任务共用同一上限**；队列满 → 新命令立即回 `CMD_ACK`（错误码 `3001 QUEUE_FULL`），不入队；新到的**轮询任务**无 requestId 可 ACK，直接丢弃，由下一周期自然补偿（6.1 欠账语义不受影响）；
- 每个命令带 TTL（默认 5 分钟），过期未执行 → 丢弃并回 `CMD_ACK`（`3002 COMMAND_EXPIRED`）；**轮询任务同样携带 TTL**，超期未执行直接丢弃（数据本已过期，执行无意义）；
- `StateReporter` 上报缓存设上限（如 1000 条），满则丢弃最旧事件并上报 `ERROR` 提示服务器；
- 服务器可用 `GET_STATUS` 查询各设备队列深度，用于上层限流。

### 7.5 断线重连流程

1. `BluetoothGattCallback.onConnectionStateChange` 检测到异常断开；
2. **立即释放连接槽**（通知 `ConnectionSlotManager` / `ConnectionScheduler`），状态机经 `DISCONNECTED` 进入 `RECONNECTING`；
3. `RECONNECTING` **不持有槽位**，仅做指数退避计时：1s、2s、4s、8s……最大 60s——退避期间槽位服务其他设备，避免单个不稳定设备长期占槽、饿死其余设备；
4. 退避到期 → `WAITING_SLOT`，以欠账任务的最高优先级（5.1）走正常调度重新申请槽位；退避期间新到的需求照常按 7.2.1 登记入队，无需特殊处理；
5. 获槽后 → `CONNECTING`，重连成功后重新发现服务、配置特征；
6. 超过最大重试次数进入 `ERROR`，上报服务器；
7. 主动断开不自动重连，进入 `DISCONNECTED`。

### 7.6 文件传输流程（OTA / 文件下发）

#### 场景与挑战

- 文件沿 服务器 → TCP → Android → BLE → DUT 链路下发；
- BLE 吞吐有限：MTU 512 + 快速连接间隔下实际约 10~30 KB/s，MB 级文件需数分钟；
- 传输是**长时占用型任务**，与"时间片轮转"的槽位调度冲突：传输中的设备不能被时间片踢下线。

#### 传输机制

文件传输统一采用 **GATT 分块写**（约 10~30 KB/s），不引入 L2CAP CoC。分块数据一律使用 **WRITE_NO_RESPONSE**（无响应写，是吞吐达标的前提）；握手、窗口 ACK 协商等关键步骤使用带响应写。服务器单发 `WRITE_CHAR` 默认带响应写，可由 `writeType` 参数覆盖（附录 A.2）。

DUT 端传输协议（标准 OTA 服务或自定义写入特征）通过**传输适配器（TransferAdapter）**接口接入，分块、CRC、续传逻辑与具体 DUT 协议解耦。

#### 传输通道（transferChannel：ble / spp / auto）

每台设备的传输通道由 `DeviceConfig.transferChannel` 决定（16.4），运行中可经 `SET_TRANSFER_CHANNEL` 整项替换（A.2）：agent 写 Controller 快照即时生效，文件传输/导出任务**启动时**读快照选路，**进行中的任务不换通道**。

- **`ble`（缺省）**：BLE GATT 承载，走 LC 工厂通道（`LcProtoTransferAdapter`）；
- **`spp`**：经典蓝牙 RFCOMM 加速通道。前置：经 BLE LC 通道写 `00AT^BT_ENABLE` + `00AT^BT_ACCESS_SET=3` 开经典蓝牙（已开过时固件幂等回 OK），随后 insecure RFCOMM 免配对直连（`spp/SppClient`）；数据面由 `spp/SppTransferAdapter` 承载同一套 33x 协议（不同块尺寸：39600B 块切 960B 包连续写，块尾 4B CRC32 小端，每块等 `310`/`311`），真机实测 BLE 12.5KB/s → SPP 100KB/s+；
- **`auto`**：`FallbackTransferAdapter`——先跑 SPP 握手，握手失败则关闭 SPP 通道改用 BLE LC 重新握手；回退只发生在握手阶段，数据传输中的失败按断线语义重建适配器重跑握手（仍从 SPP 开始尝试）。

SPP 传输期间 BLE 连接保持（设备管理/回退通道），任务结束只关 SPP socket。

#### 长任务与槽位调度

- 文件传输任务将目标设备**临时升级为常驻设备（pinned）**，占用槽位直到传输完成，调度器期间不踢出该设备；
- 槽位预算收缩：传输期间其他设备只能使用 N−1 个槽，通过 `CONNECTION_STATISTICS` / 任务事件让服务器知悉；
- 传输期间**暂停该设备的轮询**（避免 GATT 操作冲突），完成后恢复；
- 同时传输的任务数上限可配置（建议 1~2），防止多个长任务占满所有槽导致其他设备饿死。

#### 传输协议（GATT 分块，默认）

- 分块大小 = MTU − 3（512 MTU → 509 字节）；
- 每块带序号，按窗口（如 64 块）做 CRC 校验，DUT 返回窗口 ACK；
- NAK（CRC 错误）→ 重发窗口内块；连续失败 N 次 → 任务失败；
- 传输前握手：文件大小、总块数、CRC 类型、写入特征 UUID、是否支持偏移写入。

#### 断点续传

- Android 端记录 `transferredOffset`（已确认偏移）；
- 连接意外中断 → 重连后从 `transferredOffset` 继续，不从头开始（前提：DUT 支持偏移写入）；
- 不支持偏移写入的 DUT 从头重传，仍按重试策略处理。

#### 传输流程

1. 任务发起两个入口：服务器直接下发 `FILE_TRANSFER`（fileId、大小、SHA-256 哈希、目标 MAC、传输参数，可选 `fileName` 原始文件名——`.zip` 结尾时 agent 传输落盘名为 DUT 侧 `/data/ota.zip`）；或规则动作命中（7.3.2）→ Android 上行 `FILE_REQUEST` → 服务器补发 `FILE_TRANSFER`，两者后续流程一致；
2. Android 经 TCP 接收文件数据，缓存到本地存储并校验哈希（SHA-256；按 fileId 去重，见下文 TCP 侧文件下载协议）；
3. `CommandDispatcher` 创建 `FileTransferTask`，向 `ConnectionScheduler` 申请 pinned 槽位；
4. 设备连接 → 按任务启动时的通道快照选路（ble / spp / auto，见"传输通道"）→ 握手（元数据协商）→ 分块传输；
5. 按窗口上报 `FILE_PROGRESS`（百分比、速率）；
6. 完成 → DUT 校验 → 上报 `FILE_RESULT`；
7. 解除 pinned 状态，恢复轮询，槽位归还调度；
8. 失败 → 按错误码决定重试（从断点或从头）或上报失败。

#### 取消与超时

- `FILE_CANCEL` 任意时刻可取消；
- `PAUSE_DEVICE` 默认挂起传输（保留 `transferredOffset`，恢复后断点续传），`abortTransfer=true` 时中止（7.7）；
- 分块无响应超时（如 30s）→ 暂停并上报，等待服务器决策。

#### TCP 侧文件下载协议

文件数据不走 JSON 报文（体积开销不可接受），在既有 TCP 长连接上以**二进制帧**传输：

1. 服务器下发 `FILE_TRANSFER`（fileId、大小、哈希、目标 MAC）；
2. **按 fileId + SHA-256 去重**：agent 先查本地完整缓存 `<fileId>.bin`（存在 + 长度 + SHA-256 全匹配即命中）；命中则跳过整个 TCP 下载段——直接回 `resendSeqs` 为空的 `FILE_DOWNLOAD_ACK`，服务器在 `WAIT_READY` 态收空 ACK 即转 `DOWNLOADED`，不推任何下载帧（零下载帧），任务直进 BLE 段；排队等待并发的任务提升时同样复查缓存；
3. 未命中：Android 回 `FILE_DOWNLOAD_READY` 后，服务器按二进制帧推送文件：帧头（帧类型 + 序号 + 长度）+ 数据段；下载中文件命名 `<fileId>.part`，与完整缓存区分；
4. Android 落盘并逐帧校验，收完后校验整体哈希，回 `FILE_DOWNLOAD_ACK`（成功或需重传的帧号）；校验通过将 `.part` 改名为 `<fileId>.bin` 完整缓存，供后续同 fileId 任务（批量 OTA，7.10）去重命中；
5. TCP 中断 → 下载任务挂起，重连后发 `FILE_DOWNLOAD_RESUME`（携带**已确认的最大连续块号 seq**，与 16.3 帧编号一致；字节偏移 = seq × chunkSize，两端口径统一）续传；
6. 下载完成后才进入 BLE 传输阶段（7.6 传输流程第 3 步），未下载完不申请 pinned 槽位。

#### 本地存储管理

- 传输缓存目录固定（如 `files/transfer/`），文件按 `fileId` 命名：下载中 `<fileId>.part`，校验通过的完整缓存 `<fileId>.bin`；
- 磁盘配额：缓存总量上限可配置（如 1GB），超限拒绝新任务并上报；缓存命中不占用新配额；
- 清理策略：成功任务的 `.bin` 缓存**保留**供批量 OTA 复用（不再传完即删）；缓存清除时机 = `RESET`（7.8）、超过 `failedTaskRetentionDays` 保留期的过期清理、SHA-256 不符的新任务覆盖（先删不匹配缓存再下载）；
- 存储空间不足（可用空间低于阈值）→ 暂停接收新任务，上报 `ERROR`。

### 7.7 设备移除与暂停恢复流程

#### 移除流程（REMOVE_DEVICE）

1. 服务器下发 `REMOVE_DEVICE`（MAC 或设备 ID）；
2. 设备不在注册表 → 回 `CMD_ACK` 成功（幂等）；
3. 设备已连接 → 等待正在执行的 GATT 命令结束（或立即中断），断开 Gatt，槽位归还 `ConnectionSlotManager`；
4. 清空 `pendingCommands` 与轮询欠账，取消该设备的 `ConnectionRequest`；**丢弃命令前逐条回 `CMD_ACK`（错误码 `2004 命令已取消`）**，保证服务器 requestId 对账不悬挂（7.4 第 8 步）；轮询任务无 requestId，直接丢弃；
5. 若存在进行中的 `FileTransferTask` → 中止并上报 `FILE_RESULT`（cancelled）；
6. 从 `DeviceRegistry` 移除，状态机进入 `TERMINATED`，上报 `DEVICE_STATE`。

#### 暂停/恢复流程（PAUSE_DEVICE / RESUME_DEVICE）

1. 服务器下发 `PAUSE_DEVICE`（可带 `abortTransfer` 参数，默认 `false`）：立即回 `CMD_ACK`（已受理）；
2. **即时迁移**：设备处于 `REGISTERED` / `WAITING_SLOT` / `READY` / `DISCONNECTED` / `ERROR` → 直接迁 `PAUSED`（均为 4.1 合法迁移）；`WAITING_SLOT` 设备同时取消其 `ConnectionRequest`；
3. **延迟生效（pendingPause）**：设备处于 `CONNECTING` / `SERVICE_DISCOVERING` / `CONFIGURING` / `POLLING` / `COMMANDING` / `RECONNECTING` 时**不做非法迁移**，置 `pendingPause=true`；待状态回到 `READY`（建连完成或当前任务结束）或 `DISCONNECTED` 后补迁至 `PAUSED`，随后主动断开放槽；
4. **与 pinned 文件传输的交互**：设备存在进行中 `FileTransferTask` 时——`abortTransfer=false`（默认）→ 挂起传输，保留 `transferredOffset`，`RESUME_DEVICE` 后按 7.6 断点续传恢复；`abortTransfer=true` → 中止传输并上报 `FILE_RESULT`（cancelled），再进入 `PAUSED`；
5. `PAUSED` 不再参与调度（不申请槽、不轮询；命令只入队不触发连接）；
6. `RESUME_DEVICE`：回到 `REGISTERED`（有欠账则直接 `WAITING_SLOT`），重新参与调度；被挂起的传输任务按断点续传恢复；
7. 暂停期间命令照常入队（受背压上限约束），恢复后按优先级消费。

### 7.8 重置流程（RESET）

1. 服务器下发 `RESET`：立即回 `CMD_ACK`（已受理）；
2. 中止所有进行中任务：GATT 操作取消、进行中的文件传输中止并逐任务上报 `FILE_RESULT`（cancelled）、`pendingCommands` 中未执行命令逐条回 `CMD_ACK`（`2004 命令已取消`）；
3. 全部设备断开连接、释放所有连接槽、清空连接请求队列；
4. 设备状态机统一回 `REGISTERED`，轮询计划重置（`nextPollTime = now + intervalMs`），重连计数清零；
5. 注册表、`AgentConfig`、本地日志缓存保留——等价于 12.6 冷启动完成后的已注册状态，不重新走 TCP 注册（7.1）。

### 7.9 设备文件导出流程（FILE_EXPORT，DUT → 手机 → 服务器）

文件下发的逆向链路：从 DUT 拉取文件（产测日志、缓存数据等）回服务器入库。协议实证见 docs/02 B.6，真机行为见 docs/04。

#### 导出协议（LC 产测通道 061/062/063）

- **开始**：写 `061<设备侧路径>` → 回二进制帧 `'@'` + u32BE（文件总大小）；大小帧可能与首数据帧粘连（061 应答窗口内按字节流消费）；
- **拉数据**：写 `062` → 回 `'@'` + u32BE（块长）+ 数据 + 4B 大端无符号字节累加和；校验通过 → 追加落盘再发 `062` 拉下一块；校验失败/丢包 → 写 `063` 重传本块；
- **结束**：数据耗尽后再发一次 `062`，设备回 ASCII `FILE_EXPORT_OVER`（逐文件）；
- **锁步（lock-step）**：每块至多一次主动 `062`，之后该块只用 `063` 重传；**重播块识别**——与上一块字节比对，固件重播上一块时丢弃不重复落盘；
- **目录模式**：`remotePath` 以 `/` 结尾 → 先写 `00AT^LS=<目录>` 列举（Notify 多行回包，含 `F` 行取文件名、`OK` 行收尾），再逐文件跑 061；
- **无断点续传**：任何失败整文件重来；061 无响应 5s 重发（上限 3 次）、062 发出后 8s 无响应重发、063 重传上限 10 次（062 超时与 063 共用每块上限）；写入一律 WRITE_NO_RESPONSE。

#### SPP 承载导出

通道选择复用 `DeviceConfig.transferChannel`（7.6 传输通道）：`spp` = RFCOMM 承载同一套 061/062/063，`auto` = 先 SPP 失败回退 BLE。`spp/SppExportOpener` 完成开通道前置（同 7.6：`00AT^BT_ENABLE` / `00AT^BT_ACCESS_SET=3` → insecure RFCOMM），产出 `SppByteStream` 字节流通道。与 BLE 承载的差异（真机校准）：

- 块长上限：BLE 4480B / SPP 65536B；
- SPP 的 `061` 必须以 `\0`（C 字符串终止符）结尾，且按方言轮询命令变体（`061<path>` / `061/<path>` / `061<basename>`），命中后同会话复用该方言；
- SPP 目录模式拆成**一次 LS + 每文件独立 SPP 会话**——规避固件双通道路由不稳定（多文件共用一条 SPP 会话时 061 死信，真机实证）。

#### 任务编排与回传

1. 服务器下发 `FILE_EXPORT`（deviceMac、remotePath，exportId 缺省由 agent 生成；A.2）；
2. agent 单工作线程串行受理（同 exportId 幂等），pinned 占槽建连、导出期间暂停该设备轮询（对齐 7.6 长任务调度）；
3. 拉齐的文件经 TCP 二进制帧回传：`EXPORT_FRAME(0x05)` / `EXPORT_END(0x06)`（16.3 扩展；payload = u16BE 文件名长度 + 文件名 UTF-8 + 数据 / 32B SHA-256，每文件独立 seq 序列从 1 连续编号）；服务器校验 SHA-256 后登记 files 表（origin="device"）；
4. 进行中按 ~500ms 节流上报 `EXPORT_PROGRESS`（覆盖式语义：同 exportId 后到整体取代先到；文件开始/完成必报；`filesTotal=0` 表未知——LS 未完成或单文件模式）；
5. 收尾上报 `EXPORT_RESULT`（exportId、files 清单、errorCode、detail）；本地暂存成功已上传/失败无续传价值，一律删除。

### 7.10 批量 OTA（server 侧编排协同）

批量 OTA 是 **server 侧一等公民编排模块**（BatchOrchestrator，详见 server README §7.1）：把一个 OTA 包推送到 N 台设备，每台设备独立跑相位机——

```
QUEUED → TRANSFERRING → UPGRADE_CMD → BLACKOUT → RECONNECTING → VERIFYING → DONE / FAILED / CANCELLED
```

与 Android 端的协同关系（**agent 协议零改动**）：

- TRANSFERRING 相位复用 §7.6 全链路：`FILE_TRANSFER` + pinned 传输 + TCP 下载去重——同 fileId 的第二批起命中 `<fileId>.bin` 缓存，agent 直回空 `resendSeqs` 的 `FILE_DOWNLOAD_ACK` 进 BLE 段；
- UPGRADE_CMD / VERIFYING 复用 `WRITE_CHAR`（等 ACK / 等 `POLL_RESULT` 内容匹配）；RECONNECTING 复用 §7.2 `CONNECT_DEVICE` 等 `DEVICE_STATE state=READY`；
- 并发上限：per-agent 同时传输设备数 ≤ min(请求值 perAgentConcurrency, 全局 `maxConcurrentTransfers`)，跨 agent 天然并行；单设备失败只落本设备记录（失败隔离），不影响批次内其余设备。

---

## 8. 数据模型

### 8.1 ManagedDeviceInfo

```java
class ManagedDeviceInfo {
    String deviceId;        // 业务ID
    String mac;             // BLE MAC
    DeviceState state;      // 状态机状态
    PollingConfig pollingConfig;
    long nextPollTime;      // 下次轮询时间
    long lastPollTime;      // 上次轮询时间
    Map<UUID, byte[]> lastPollResult; // 缓存的轮询结果
    boolean pollDataStale;  // 数据是否过期
    PriorityQueue<QueuedTask> pendingCommands; // 待执行队列：统一承载 GattCommand 与 PollingTask（上限与 TTL 见 7.4.1）
    int maxPendingCommands;  // 队列深度上限
    int priority;           // 静态优先级，可配置
    boolean isPersistent;   // 是否常驻连接
    long lastConnectedTime; // 上次连接时间
    long disconnectedTime;  // 最近断开时间
    long coolDownUntil;     // 冷却时间戳
    int reconnectCount;     // 重连计数
    String stateFlag;       // SET_DEVICE_STATE 动作写入的业务标记（非状态机状态，7.3.2）
    long notifyBoostUntil;  // 通知活跃期截止时间：此前 selectVictim 不踢该设备（7.3.3 / 16.5）
    String transferChannel; // 文件传输/导出通道快照：ble / spp / auto（默认 ble；SET_TRANSFER_CHANNEL 显式写接口更新，7.6 / 16.4）
}
```

> `QueuedTask` 为密封接口，`GattCommand`（8.4）与 `PollingTask`（8.6）均实现之；队列按优先级排序、同优先级按入队时间先后。

> **并发与回写约定**：`ManagedDeviceInfo` 的全部可变字段归 `DeviceController` 所有，读写必须经过控制器的同步访问器（`synchronized` / 锁），禁止跨线程直接暴露内部可变引用。`snapshot()` 返回**不可变副本**，供 `GET_STATUS`（TCP 线程）等读取方使用；写入方（调度器、轮询回调等）一律调用控制器的显式写接口（如 `updatePollTimes()` / `setStateFlag()`）在锁内完成修改——既保证修改不丢失，又消除"快照别名"带来的线程安全隐患（线程模型见第 9 章）。

### 8.2 ConnectionSlot

```java
class ConnectionSlot {
    int slotId;
    String currentDeviceMac; // 当前占用设备
    long acquireTime;        // 获得槽位时间
    boolean isPinned;        // 是否被 pinned 任务（文件传输）占用；常驻由 ManagedDeviceInfo.isPersistent 表达（8.1），二者在 selectVictim 中均不可踢（16.5）
}
```

### 8.3 ConnectionRequest

```java
class ConnectionRequest {
    String deviceMac;
    int priority;          // 高/中/低
    String reason;         // COMMAND / POLL / PERSISTENT / EVENT / FILE_TRANSFER
    long requestTime;
    long expireTime;       // 请求过期时间，超过后自动取消
}
```

### 8.4 GattCommand

```java
class GattCommand {
    enum Type { READ, WRITE, ENABLE_NOTIFY, DISABLE_NOTIFY, SET_MTU }
    enum Priority { HIGH, NORMAL, LOW }

    String deviceMac;
    String requestId;     // 关联服务器命令，用于 CMD_ACK 对账
    Type type;
    UUID serviceUuid;
    UUID charUuid;
    byte[] payload;
    int timeoutMs;         // 建议 READ/WRITE 3s
    int maxRetry;
    Priority priority;
}
```

### 8.5 PollingConfig

```java
class PollingConfig {
    long intervalMs;                // 轮询间隔
    List<UUID> readCharacteristics; // 需要读取的特征（简单轮询）
    List<UUID> notifyCharacteristics; // CONFIGURING 阶段需订阅通知的特征（7.3.3）
    boolean reportOnlyChanged;      // 是否只在变化时上报
}
```

> 复杂轮询（先写后读、特征依赖）使用 `PollingTask` 动作序列描述，见 7.3.1；`readCharacteristics` 仅适用于简单轮询。`notifyCharacteristics` 为 null/空时跳过订阅。

### 8.6 PollingTask / PollStep

```java
class PollingTask {
    String deviceMac;
    List<PollStep> steps;   // 有序执行的动作序列
    long timeoutMs;         // 整任务超时
    GattCommand.Priority priority;  // NORMAL
}

class PollStep {
    GattCommand.Type type;  // READ / WRITE
    UUID serviceUuid;
    UUID charUuid;
    byte[] payload;         // WRITE 时使用
    int timeoutMs;
    int maxRetry;
}
```

> `PollingTask` 与 `GattCommand` 同实现 `QueuedTask` 接口（8.1），可混排入 `pendingCommands`；轮询任务入队即携带 TTL，超期丢弃（7.4.1）。

### 8.7 PollRule / Condition / RuleAction

```java
class PollRule {
    String ruleId;
    List<Condition> conditions;   // 条件列表，AND 组合
    List<RuleAction> actions;     // 命中后执行的动作
    int priority;                 // 求值顺序，大的先
    boolean stopOnMatch;          // true = 命中后停止求值后续规则
}

class Condition {
    String field;      // Decoder 解析出的字段名，如 "battery"
    Operator op;       // GT / GE / LT / LE / EQ / NE / BETWEEN
    double value;      // 与规则 JSON 的 value 字段一致（16.4）
    double valueMax;   // 仅 BETWEEN 使用：闭区间 [value, valueMax]
}

class RuleAction {
    RuleActionType type;  // REPORT_EVENT / EXECUTE_GATT / SET_INTERVAL /
                          // SET_DEVICE_STATE / RELEASE_SLOT / FILE_TRANSFER
    Map<String, Object> params;
}
```

### 8.8 FileTransferTask

```java
class FileTransferTask {
    String taskId;
    String deviceMac;
    String fileId;
    String fileName;          // 原始文件名（可选；.zip 结尾时 DUT 侧落 /data/ota.zip，A.2）
    long totalSize;
    long transferredOffset;   // 已确认偏移（断点续传）
    int chunkSize;            // MTU - 3
    int windowSize;           // 每窗口块数（CRC 校验窗口）
    FileTransferState state;  // PENDING / HANDSHAKE / TRANSFERRING /
                              // PAUSED / COMPLETED / FAILED / CANCELLED
    long startTime;
    int retryCount;
}
```

### 8.9 FieldMapping（Decoder 字段映射）

```java
class FieldMapping {
    String field;        // 字段名，如 "battery"——规则条件与 POLL_RESULT 上报均引用此名
    UUID charUuid;       // 数据来源特征（可经 16.4 profile 解析短 UUID）
    Format format;       // UINT8 / UINT16 / UINT32 / SINT8 / SINT16 / SINT32 / UTF8 / BOOL / HEX
    ByteOrder order;     // LE / BE，仅数值型有效，默认 LE
    double scale;        // 缩放系数，默认 1.0（解析值 = 原始值 × scale，如 0.1 表示缩小 10 倍）
    int byteOffset;      // 特征值内的起始字节偏移，默认 0
}
```

配置来源：16.4 `devices[].fields`，注册时随 `REGISTER_ACK` 按设备下发，运行中可随配置更新；加载时校验——规则 `conditions[].field` 必须存在映射，未配置映射的特征按 char UUID 原样上报原始 hex、不参与规则求值。

---

## 9. 线程模型

| 线程 | 职责 |
|------|------|
| Main Thread | Service 生命周期，不做 IO |
| TCP Thread | TCP 读写、心跳、协议解析 |
| BLE Executor | 执行 GATT 操作（单线程或 2~3 线程池；多线程时按设备 MAC 哈希分片，保证同设备串行，3.8） |
| Scheduler Thread | 轮询调度、重连定时、连接调度 |
| Reporter Thread | 异步上报事件，避免阻塞主流程 |

关键点：

- BLE 回调发生在 Binder 线程，回调中不做耗时操作，尽快将结果抛回 `BLE Executor`；
- TCP 与 BLE 完全解耦，避免网络阻塞影响 BLE 连接；
- 调度相关计时统一使用**单调时钟**（`SystemClock.elapsedRealtime()`）：`nextPollTime`、命令/任务 TTL、`coolDownUntil`、`notifyBoostUntil`、重连退避等全部基于此；禁止 `System.currentTimeMillis()`——NTP 校时跳变会击穿全部计时逻辑；仅上报给服务器的时间戳字段使用墙钟。

---

## 10. 通信协议

### 10.1 服务器 → Android

逐字段定义见**附录 A.2**；`byte[]` 一律 base64，MAC / UUID / 枚举编码约定见附录 A.1。

| 命令 | 说明 |
|------|------|
| `REGISTER_ACK` | 注册确认 |
| `CONNECT_DEVICE` | 注册并连接指定 DUT（幂等，可带 `lazyConnect`；ACK 语义为"已就绪"，兼作 ERROR 恢复入口，7.2） |
| `DISCONNECT_DEVICE` | 断开指定 DUT |
| `READ_CHAR` | 读取特征 |
| `WRITE_CHAR` | 写入特征 |
| `START_POLLING` | 启动轮询 |
| `STOP_POLLING` | 停止轮询 |
| `SET_POLLING_INTERVAL` | 动态调整轮询间隔 |
| `SET_POLL_RULES` | 下发/更新轮询结果处理规则（7.3.2） |
| `FILE_TRANSFER` | 下发文件传输任务（fileId、大小、哈希、目标 MAC，可带 `fileName`） |
| `FILE_CANCEL` | 取消文件传输任务 |
| `FILE_EXPORT` | 设备文件导出（DUT → 手机 → 服务器，7.9） |
| `SET_MAX_CONNECTIONS` | 设置最大并发连接数（2~5） |
| `SET_PERSISTENT_DEVICE` | 指定常驻设备，始终占用一个连接槽 |
| `SET_TRANSFER_CHANNEL` | 传输通道运行中整项替换（ble/spp/auto，7.6 / 16.4） |
| `REMOVE_DEVICE` | 移除受管设备（7.7） |
| `PAUSE_DEVICE` / `RESUME_DEVICE` | 暂停/恢复设备调度（7.7） |
| `UPLOAD_LOG` | 触发日志打包上传（13） |
| `GET_STATUS` | 查询手机和 DUT 状态 |
| `RESET` | 软重置 Android 端（7.8）：断开全部连接、清队列与欠账、回到冷启动等价状态 |

### 10.2 Android → 服务器

逐字段定义见**附录 A.3**。

| 事件 | 说明 |
|------|------|
| `REGISTER` | 手机注册上报：设备 ID、IP、端口、Android 版本、BLE 能力、实测最大连接数（7.1） |
| `HEARTBEAT` | 心跳，携带手机资源状态 |
| `DEVICE_STATE` | DUT 状态机变化（统一事件，`state` 字段取 `DeviceState` 枚举名；7.2 文中"上报 `DEVICE_CONNECTED` / `DEVICE_UNREACHABLE`"均经此事件，分别对应 `state=READY` 与 `state=ERROR` + `errorCode`，不再使用独立事件名） |
| `CONNECTION_SLOT_ACQUIRED` | 设备获得连接槽，开始连接 |
| `CONNECTION_SLOT_RELEASED` | 设备释放连接槽 |
| `POLL_RESULT` | 轮询结果 |
| `POLL_DATA_STALE` | 轮询数据过期，设备暂时无法连接 |
| `CMD_ACK` | 命令响应 |
| `DEVICE_PAUSED` / `DEVICE_RESUMED` | 设备暂停/恢复 |
| `CONNECTION_STATISTICS` | 连接槽使用率、切换次数等统计 |
| `FILE_PROGRESS` | 文件传输进度（百分比、速率） |
| `FILE_RESULT` | 文件传输结果（成功/失败/错误码） |
| `ERROR` | 异常错误 |
| `TOPOLOGY` | 当前连接的 DUT 拓扑 |
| `FILE_REQUEST` | 规则动作触发传输时向服务器请求文件（fileId、taskId、目标 MAC）；服务器收后补发 `FILE_TRANSFER`，流程并入 7.6（7.3.2） |
| `FILE_DOWNLOAD_READY` / `FILE_DOWNLOAD_ACK` / `FILE_DOWNLOAD_RESUME` | 文件下载握手/确认/续传（二进制帧协议，7.6） |
| `EXPORT_PROGRESS` / `EXPORT_RESULT` | 设备文件导出进度（节流、覆盖式）/ 导出结果（7.9） |
| `LOG_UPLOAD_DONE` | 日志上传完成（13） |

### 10.3 报文结构示例

```json
{
  "type": "POLL_RESULT",
  "timestamp": 1720000000000,
  "deviceMac": "AA:BB:CC:DD:EE:FF",
  "stale": false,
  "values": {
    "battery": 85,
    "status": "IDLE"
  }
}
```

> `requestId` 仅出现在服务器命令与对应 `CMD_ACK` 中，用于对账；`POLL_RESULT` 等主动上报事件不携带。`stale` 字段对应 6.1 的过期标记（`pollDataStale`）。

---

## 11. 安全设计

### 11.1 威胁模型

| 威胁 | 场景 |
|------|------|
| TCP 链路窃听/篡改 | 局域网内第三方嗅探服务器指令与轮询数据 |
| 伪冒 Agent 接入 | 非受控设备连接服务器 |
| BLE 链路窃听 | 敏感数据（WiFi 凭据、证书）空中可被截获 |
| 非受控 DUT 接入 | 邻近陌生设备被误连 |

### 11.2 安全决策

| 决策点 | 决策（默认） | 说明 |
|--------|------------|------|
| TCP 传输 | 内网明文 + Agent Token 认证 | 注册时服务器校验 token，报文头携带；仅适用于隔离网络 |
| TCP 传输（可选） | TLS | 跨网络部署时启用；握手失败不降级为明文 |
| DUT 鉴权 | MAC 白名单 | 注册表只接受服务器下发的 MAC，扫描结果不自动接入 |
| BLE 配对 | 默认不 bonding | 测试 DUT 通常无配对能力；敏感数据场景再评估 |
| 敏感数据 | 应用层加密 | 凭据/证书类数据由服务器加密、DUT 解密，Android 只做透传 |

### 11.3 日志与数据脱敏

- 日志、报文样例禁止记录完整凭据与 token 明文；
- 上报字段由服务器配置白名单控制，DUT 敏感数据默认不上报。

---

## 12. 异常处理与可靠性

### 12.1 BLE GATT 错误处理

| GATT Status | 含义 | 处理 |
|-------------|------|------|
| 0 | 成功 | 无 |
| 8 | 连接超时 | 进入重连 |
| 133 | 连接超时/断开 | 进入重连 |
| 137 | 服务发现失败 | 重试 3 次，失败断开 |
| 143 | 特征不存在 | 上报配置错误 |

> 上表语义需按主流机型实测校准（137/143 等在不同蓝牙栈上含义有差异，测试阶段以实测数据修订本表）；`133` 须区分来源——`onConnectionStateChange` 回调的 133 = 连接失败/断开（走 7.5 重连），操作回调的 133 = 单次操作失败（走 12.2 超时重试），二者不混用。

### 12.2 命令超时

- 每个 `GattCommand` 独立超时（`READ/WRITE` 建议 3s）；
- 超时后重试一次，仍失败则上报错误，视情况进入断线重连。

### 12.3 TCP 断线

- TCP 断开后指数退避重连；
- 本地未上报数据缓存，重连后补发；
- BLE 侧不受影响，继续按本地配置工作。

### 12.4 连接槽争用与切换失败

- 多设备同时请求槽位时按优先级排序，同优先级按请求时间排序；
- 高优先级请求等待超时仍未获槽，按 5.2 第 6 条的老化机制自动提升有效优先级（封顶 `HIGH`），仍无槽时可强制抢占；
- 新设备连接失败立即释放槽位，通知调度器尝试下一候选设备；
- 被踢设备异常断开时保留任务队列，稍后重试。

### 12.5 连接槽泄漏防护

- GATT 操作挂起时，`GattExecutor` 超时后主动断开并释放槽位；
- 建连超预算：获得槽位后 `setupBudgetMs` 内未进入 `READY` → 强制释放并计一次连接失败（5.2 第 3 条 / 16.5）；
- `ConnectionSlotManager` 定期检查槽位占用时间，超限且无任务时强制释放。

### 12.6 冷启动恢复

- 启动后所有设备初始 `REGISTERED`，不主动连接；
- 服务器下发轮询配置与常驻设备列表；
- 连接槽按需分配，逐步恢复工作。

### 12.7 资源控制

- 最大连接数可配置，防止蓝牙栈过载；
- 轮询间隔限制最小值（建议不小于 200ms）；
- 手机温度、内存异常时主动降低轮询频率并上报服务器。

### 12.8 文件传输异常处理

- **传输中断线**：重连后从 `transferredOffset` 断点续传（DUT 支持偏移写入时）；不支持则从头重传；
- **CRC 校验失败**：重发窗口内块，连续失败 N 次 → 任务失败并上报；
- **分块无响应**：超时（如 30s）→ 任务暂停并上报，等待服务器决策；
- **槽位被抢占防护**：pinned 状态保证传输任务不被时间片踢出；仅服务器显式命令（如更高优先级任务）可介入；
- **多任务排队**：同时传输任务数达上限时，新任务进入等待队列，按优先级调度。

### 12.9 错误码体系

所有**结果类**上报报文（`CMD_ACK` / `ERROR` / `FILE_RESULT`）携带统一错误码：

| 码段 | 类别 | 示例 |
|------|------|------|
| 0 | 成功 | `0` |
| 1xxx | GATT/蓝牙错误 | `1001` 连接超时、`1002` 服务发现失败、`1003` 特征不存在 |
| 2xxx | 协议错误 | `2001` 报文格式错误、`2002` 未知命令、`2003` 设备不存在、`2004` 命令已取消（REMOVE/RESET 清理队列，7.7/7.8） |
| 3xxx | 资源与配置错误 | `3001` 队列已满、`3002` 命令过期、`3003` 槽位不足、`3004` 磁盘不足 |
| 4xxx | 文件传输错误 | `4001` 哈希校验失败、`4002` CRC 失败、`4003` DUT 写入拒绝（含导出协议拒绝/超时，7.9）、`4004` 不支持偏移写入 |

原则：Android 端不自行扩展码表，新增错误码随版本发布；未知错误归入对应类别，原始状态码（如 GATT status 133）放在报文体 `rawStatus` 字段透传。`CMD_ACK` / `DEVICE_STATE` / `ERROR` 报文统一带 `errorCode`（本码段）与可选 `rawStatus`（底层原始码），二者并存：`errorCode` 供服务器逻辑判断，`rawStatus` 供排障。`SET_MAX_CONNECTIONS` 驱逐失败、参数越界统一回 `3003`（不走 `2001`）。`POLL_DATA_STALE` 属状态提示而非错误，不携带 `errorCode`，改带 `lastPollTime` 与 `reason`（`NO_SLOT` / `CONNECT_FAILED` 等），供服务器判断过期时长与原因。

### 12.10 系统与权限异常

- **Doze / 后台限制**：前台 Service + 部分唤醒锁（PARTIAL_WAKE_LOCK）保持 TCP 与 BLE 会话；屏幕灭后系统可能断开 TCP，心跳超时即触发重连；
- **厂商省电限制**：安装引导时要求加入省电白名单（小米/华为/OPPO 等）；检测未加入时上报 `ERROR` 提示服务器告警；
- **蓝牙运行时权限**：Android 12+ 需要 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，启动时检查并引导授权；缺失时进入受限模式（仅上报，不操作 BLE）；
- **蓝牙关闭 / 飞行模式**：监听系统广播；蓝牙关闭 → **仅连接态与连接中设备**（`CONNECTING` / `SERVICE_DISCOVERING` / `CONFIGURING` / `READY` / `POLLING` / `COMMANDING` / `RECONNECTING`）置 `DISCONNECTED`（保留欠账）并释放全部槽位；`REGISTERED` / `WAITING_SLOT` 设备保持原状态（欠账不清零），调度器暂停授槽；`PAUSED` / `TERMINATED` 不受影响——全部迁移严格限定在 4.1 合法迁移表内。上报 `ERROR` 后停止连接尝试，以指数退避探测恢复；恢复后按欠账重建调度；
- **进程被杀**：无法完全避免；重启后走 7.1 冷启动恢复，本地未上报缓存持久化到磁盘以跨进程保留。

---

## 13. 日志与可观测性

测试框架排障依赖日志，Android 端实现本地滚动日志 + 按需上传：

- **日志分级**：DEBUG（GATT 原始交互，单独开关）/ INFO（连接、调度、轮询）/ WARN（重试、超时）/ ERROR（失败、异常）；
- **滚动策略**：按大小切分（如 10MB × 5 个文件）环形覆盖，防磁盘占满；
- **崩溃日志**：捕获未处理异常与 native 崩溃写独立文件，下次启动随注册上报；
- **上传通道**：服务器下发 `UPLOAD_LOG` → Android 打包 → 经 TCP 二进制帧上传（复用 7.6 文件帧格式）→ 完成上报 `LOG_UPLOAD_DONE`；
- **运行时统计**：随 `CONNECTION_STATISTICS` 上报槽位切换次数、GATT 失败率、平均轮询耗时，供服务器侧观测整机健康度。

---

## 14. 测试策略

### 14.1 功能测试

- **设备注册与移除**：20+ 设备注册、移除、信息正确性；
- **连接槽分配与回收**：槽位数量限制、抢占、空闲释放、常驻设备占用；
- **轮询时间片切换**：多组设备轮流连接、数据更新、过期标记；
- **高优先级命令插入**：未连接设备收到命令时能否快速获得连接槽并执行；
- **动态配置**：轮询间隔、最大连接数、常驻设备在线调整；
- **设备生命周期**：移除已连接/未连接设备、暂停/恢复、移除时文件传输中止；
- **规则引擎**：规则求值单测（阈值边界、priority/stopOnMatch 语义、动作执行）；
- **协议 mock**：用 mock 服务器/DUT 模拟协议，验证命令对账（requestId → CMD_ACK）；
- **状态机全迁移表**：参数化单测遍历 4.1 全表，合法迁移全部走通、非法迁移必须抛 `IllegalStateException`（重点回归 V1.3 移除的 `RECONNECTING → CONNECTING`）；
- **配置 Schema 校验**：tick 钳制（配置值 > 最小 intervalMs/2 → 告警并钳制）、`setMaxSlots` 越界与驱逐失败回 3003、缺省字段默认值兜底（16.4）；
- **REMOVE / RESET 清理对账**：进行中命令逐条回 `2004`、服务器侧无悬挂 requestId、传输中止上报 `FILE_RESULT`（cancelled）、RESET 后状态与轮询计划复位（7.8）。

### 14.2 异常与稳定性测试

- **异常断线恢复**：连接切换过程中断线、GATT 超时等场景下槽位正确回收；
- **长时间稳定性**：24 小时持续运行，观察槽位切换是否导致蓝牙栈不稳定或内存泄漏；
- **TCP 断线**：命令补报、缓存数据重发；
- **文件传输**：传输中断点续传、CRC 错误重发、大文件（≥ 1MB）长时间传输稳定性、传输期间其他设备轮询不被饿死、多文件排队与取消；
- **系统异常**：蓝牙关闭/飞行模式恢复、权限缺失降级、进程被杀后冷启动恢复、磁盘空间不足；
- **故障注入**：GATT 133 风暴（连续连接失败下的放槽 + 退避 + 老化行为）、槽位泄漏（模拟挂起的 GATT 操作，验证 12.5 强释放）、建连超 `setupBudgetMs` 强制释放；NTP 校时跳变下调度计时不受影响（验证 9 章单调时钟约定）。

### 14.3 性能测试

- 20 台设备、3 个连接槽、轮询间隔 1s 时，完整一轮轮询耗时及资源占用；
- 高频命令与轮询并发时的队列深度、CPU/内存占用；
- 命令洪泛压测：服务器向离线设备持续下发命令，验证背压与队列上限行为；
- 规则引擎压力：大量规则 + 高频轮询结果时的求值耗时。

**验收目标（建议基线，首轮实测后校准为正式 SLA）**：

- 完整一轮轮询耗时 ≤ 6.4 公式估算值 × 1.2；
- 命令延迟：设备已连接时 P95 < 2s；设备未连接（含调度 + 建连）P95 < `setupBudgetMs` + `timeSliceMs` × 动态槽数；
- 24h 持续运行：内存增长 < 10%，槽位泄漏 = 0，GATT 操作失败率 < 5%；
- 命令洪泛（触发背压）下，轮询欠账经老化机制最晚 2 × `agingThresholdMs` 内获得槽位。

---

## 15. 风险与约束

| 风险 | 说明 | 缓解措施 |
|------|------|----------|
| Android 碎片化 | 不同厂商/版本蓝牙栈行为差异 | 抽象 `BluetoothAdapterWrapper`，实测主流机型 |
| BLE 连接数限制 | 芯片与系统对并发连接的实际限制可能低于配置值 | 连接数可配置，上报实际能力 |
| 后台运行限制 | 系统可能回收后台服务 | 前台 Service + 常驻通知 |
| 功耗与发热 | 轮询与连接切换带来持续开销 | 时间片轮转、空闲释放、动态降频 |
| 切换抖动 | 频繁踢出/重连导致不稳定 | 冷却时间 + 优先级策略 |
| 2.4GHz 射频干扰 | 多台手机 + 20+ DUT 同室，WiFi/BLE 共用频段互相干扰 | 实验室信道规划、错峰调度、连接失败重试与降速 |
| DUT 地址随机化（RPA） | MAC 直连（3.2）与 MAC 白名单（11.2）以 DUT 使用静态/公共地址为前提；DUT 启用 RPA 后按地址将无法直连与识别 | 准入前提：测试 DUT 一律固定地址；若必须支持 RPA，改为"扫描 + 广播数据（Service UUID / 自定义字段）识别"后再连接 |
| 安全风险 | 内网明文 TCP、BLE 空口可截获 | 见第 11 章安全设计；隔离网络 + token + 可选 TLS |

---

## 16. 实现要点

### 16.1 核心接口签名

接口是模块间唯一通信契约，跨包只调接口、不触字段。

```java
// 3.7 受管设备对象
interface DeviceController {
    void pause(boolean abortTransfer);           // → PAUSED（7.7：连接中/活动中置 pendingPause 延迟生效；abortTransfer 控制 pinned 传输挂起或中止）
    void resume();                             // → REGISTERED / WAITING_SLOT
    void terminate();                          // → TERMINATED，清理资源
    void onSlotAcquired();                     // 调度器调用 → CONNECTING，创建 BluetoothGatt
    void onSlotReleased();                     // 主动断开，保留欠账上下文
    void enqueueCommand(GattCommand cmd);      // HIGH，受背压上限约束（7.4.1）
    void enqueuePollTask(PollingTask task);    // NORMAL
    void setPollingConfig(PollingConfig config);
    void setPollRules(List<PollRule> rules);
    void setTransferChannel(String channel);   // 传输通道快照写接口（SET_TRANSFER_CHANNEL，7.6 / 16.4）
    boolean isReady();
    DeviceState getState();
    ManagedDeviceInfo snapshot();              // 不可变状态快照（GET_STATUS 用；并发与回写约定见 8.1）
}

// 3.2 BLE Central
interface BleCentralManager {
    void init();
    boolean isBleAvailable();                              // 蓝牙开关/权限检查（12.10）
    DeviceController createController(String mac, String deviceId);  // 创建并注册
    void destroyController(String mac);                    // 移除（7.7）
    int supportedMaxConnections();                         // 手机能力上报
}

// 3.4 连接槽管理
interface ConnectionSlotManager {
    int slotCount();
    ConnectionSlot acquire(String deviceMac, boolean pinned);
    void release(String deviceMac);
    void forceRelease(String deviceMac);       // 泄漏防护（12.5）
}

// 3.6 连接调度
interface ConnectionScheduler {
    void start();
    void requestSlot(ConnectionRequest request);   // 需求登记（命令/轮询/事件/常驻/重连）；同一 deviceMac 幂等 upsert，不重复排队（3.6）
    void cancelRequest(String deviceMac);
    void setMaxSlots(int max);                     // 5.4 动态调整
    void setPersistent(String mac, boolean on);
    void pin(String mac, String reason);           // 文件传输 pinned（7.6）
    void unpin(String mac);
}

// 3.8 GATT 执行
interface GattExecutor {
    void execute(GattCommand command);         // 单命令，串行
    void executeTask(PollingTask task);        // 动作序列（7.3.1）
    void cancelPending(String deviceMac);      // 设备移除时清空
}

// 3.9 轮询调度
interface PollingScheduler {
    void start();
    void updateConfig(String mac, PollingConfig config);   // 6.4
    void onPollCompleted(String mac, Map<UUID, byte[]> rawResults);  // 进处理链
    long estimateFullRoundMs();                 // 一轮时间估算（配置校验用）
}

// 7.3.2 轮询结果处理链
interface PollResultChain {
    Map<String, Object> decode(String mac, Map<UUID, byte[]> raw);       // Decoder
    List<RuleAction> evaluate(String mac, Map<String, Object> fields);   // RuleEngine
    void execute(String mac, List<RuleAction> actions);                  // ActionExecutor
    void registerHandler(String deviceType, PollResultHandler handler);  // 复杂逻辑兜底
}

// 3.10 状态上报
interface StateReporter {
    void report(String event, Map<String, Object> payload);  // 统一入口，带缓存上限（7.4.1）
    void reportCommandAck(String requestId, int errorCode, Object result);
    void reportDeviceState(String mac, DeviceState state);
    void reportPollResult(String mac, Map<String, Object> fields, boolean stale);
    void reportFileProgress(String taskId, double percent, long bytesPerSec);
    void flush();                                            // TCP 恢复后补报
}

// 3.1 TCP 通信
interface TcpClient {
    void connect(String host, int port);
    void sendJson(Map<String, Object> msg);
    void sendFrame(byte[] frame);              // 二进制帧（文件/日志，16.3）
    void setListener(TcpListener listener);
}
interface TcpListener {
    void onCommand(Map<String, Object> command);  // → CommandDispatcher
    void onFrame(byte[] frame);                   // → FileTransferManager / 日志上传
    void onDisconnected();
}

// 7.6 DUT 传输协议适配
interface TransferAdapter {
    void handshake(FileTransferTask task, DeviceController device);
    void sendChunk(byte[] chunk, int seq);
    void onWindowAck(int windowSeq, boolean crcOk);
    boolean supportsOffsetWrite();              // 决定是否可断点续传
}
```

### 16.2 工程结构与包划分

```
com.longcheer.agent/
├── AgentService                    # 前台 Service 入口（7.1）
├── tcp/                            # TCP 通信层（3.1）
│   ├── TcpClient.java
│   ├── ProtocolCodec.java          # JSON 报文 + 二进制帧编解码
│   ├── HeartbeatManager.java
│   └── FileFrameCodec.java         # 16.3 帧格式
├── dispatch/                       # 命令分发（7.4）
│   └── CommandDispatcher.java
├── ble/                            # BLE 中心与执行（3.2 / 3.7 / 3.8）
│   ├── BleCentralManager.java
│   ├── GattExecutor.java
│   └── DeviceController.java
├── registry/                       # 注册表与连接池（3.3 / 3.5）
│   ├── DeviceRegistry.java
│   └── ActiveConnectionPool.java
├── schedule/                       # 调度（3.4 / 3.6 / 3.9）
│   ├── ConnectionSlotManager.java
│   ├── ConnectionScheduler.java
│   └── PollingScheduler.java
├── poll/                           # 轮询处理链（7.3）
│   ├── PollingTask.java / PollStep.java
│   ├── Decoder.java
│   ├── RuleEngine.java
│   ├── ActionExecutor.java
│   └── PollResultHandler.java
├── transfer/                       # 文件传输（7.6）与设备文件导出（7.9）
│   ├── FileTransferManager.java
│   ├── FileTransferTask.java
│   ├── TransferAdapter.java        # LcProtoTransferAdapter / FallbackTransferAdapter 等
│   ├── FileExportManager.java
│   └── LcExporter.java             # 061/062/063 导出协议
├── spp/                            # SPP 加速通道（7.6 / 7.9）
│   ├── SppClient.java              # insecure RFCOMM 直连
│   ├── SppByteStream.java
│   ├── SppTransferAdapter.java     # RFCOMM 承载 33x 协议
│   └── SppExportOpener.java        # 开经典蓝牙前置 + 导出通道开启
├── report/                         # 上报与缓存（3.10 / 7.4.1）
├── config/                         # AgentConfig / PollingConfig / 规则 JSON 解析（16.4）
├── log/                            # 滚动日志 + 崩溃捕获（13）
└── model/                          # 数据模型（8.x）
```

依赖规则：`model` 零依赖；其余包只依赖 `model` 与 `config`；跨包交互一律走 16.1 接口，禁止直接访问对方内部字段。

### 16.3 TCP 二进制帧格式（文件/日志/导出传输）

**流解复用（JSON 报文 ↔ 二进制帧）**：同一条 TCP 长连接混跑两类流量，接收端按以下规则切包——

- **JSON 报文**：4 字节大端长度前缀（不含自身）+ UTF-8 JSON 文本；单条 ≤ 1MB，载荷首字节恒为 `{`（0x7B）；
- **二进制帧**：以 magic `0xAC42` 开头，按下述帧头自描述解析；
- 接收端 peek 前 2 字节：== `0xAC42` → 按二进制帧解析；否则按 4 字节长度前缀读 JSON。JSON 长度高 2 字节恰为 `0xAC42` 意味着单条报文 ≥ 2.8GB，已被 1MB 上限拒绝，判定无歧义。

```
帧结构（大端字节序）：
+---------+--------+-------+----------+----------+------------+--------+
| magic   | type   | seq   | length   | payload  | crc16      |
| 2B      | 1B     | 4B    | 4B       | length B | 2B         |
+---------+--------+-------+----------+----------+------------+--------+
magic = 0xAC 0x42

type 枚举：
0x01 FILE_FRAME   文件数据帧（seq = 文件内块序号，从 1 起连续编号）
0x02 FILE_END     文件结束帧（payload = 整体哈希，SHA-256 32 字节）
0x03 FILE_ACK     确认帧（payload = 需重传的块号列表，空 = 全部成功）
0x04 LOG_FRAME    日志上传数据帧
0x05 EXPORT_FRAME 设备导出数据帧（7.9；payload = u16BE 文件名长度 + 文件名 UTF-8 + 文件数据）
0x06 EXPORT_END   设备导出结束帧（payload = u16BE 文件名长度 + 文件名 UTF-8 + 32B SHA-256）

约束：
- 单帧 payload ≤ 64KB，超出由上层分块；
- crc16 覆盖 type + seq + length + payload，校验失败丢弃并请求重传；
- seq 用于窗口确认（7.6）与断点续传（FILE_DOWNLOAD_RESUME 携带已确认 seq）。
- 文件整体哈希一律 **SHA-256**（32 字节），`FILE_TRANSFER` 命令携带的哈希字段同此算法。
```

### 16.4 配置 JSON Schema

注册时服务器随 `REGISTER_ACK` 下发（6.4 / 7.1），运行中可局部更新：

```json
{
  "configVersion": 3,
  "maxSlots": 3,
  "timeSliceMs": 2000,
  "idleReleaseMs": 30000,
  "commandTtlMs": 300000,
  "maxPendingCommands": 64,
  "tickIntervalMs": 100,
  "cooldownMs": 5000,
  "setupBudgetMs": 4000,
  "agingThresholdMs": 30000,
  "heartbeatIntervalMs": 5000,
  "connectTimeoutMs": 10000,
  "gattTimeoutMs": 3000,
  "maxReconnectAttempts": 5,
  "reconnectBackoffMaxMs": 60000,
  "notifyMinReportIntervalMs": 200,
  "maxConcurrentTransfers": 1,
  "diskQuotaMb": 1024,
  "failedTaskRetentionDays": 7,
  "reportBufferMax": 1000,
  "staleThresholdMs": 120000,
  "devices": [
    {
      "deviceId": "dut-001",
      "mac": "AA:BB:CC:DD:EE:FF",
      "type": "watch",
      "priority": 5,
      "persistent": false,
      "transferChannel": "ble",
      "profile": {
        "2A19": "180F",
        "2A21": "180A",
        "2A24": "180A"
      },
      "fields": {
        "battery":     {"char": "2A19", "format": "uint8"},
        "temperature": {"char": "2A21", "format": "sint16", "byteOrder": "LE", "scale": 0.1},
        "status":      {"char": "2A24", "format": "utf8"}
      },
      "polling": {
        "intervalMs": 60000,
        "readCharacteristics": ["2A19", "2A24"],
        "notifyCharacteristics": ["2A19"],
        "reportOnlyChanged": true
      },
      "rules": [
        {
          "ruleId": "r1",
          "priority": 10,
          "stopOnMatch": false,
          "conditions": [
            {"field": "temperature", "op": "GT", "value": 40}
          ],
          "actions": [
            {"type": "REPORT_EVENT", "params": {"event": "TEMP_CRITICAL"}},
            {"type": "SET_INTERVAL", "params": {"intervalMs": 5000}}
          ]
        }
      ]
    }
  ]
}
```

字段约束：

- `op` 取值：`GT / GE / LT / LE / EQ / NE / BETWEEN`（BETWEEN 时 `value` 为 `[min, max]`）；
- `readCharacteristics` 非空时 `intervalMs` ≥ 200ms；`intervalMs` 小于一轮轮询时间的配置给出告警日志（6.4）；
- `notifyCharacteristics` 为可选，缺失时跳过订阅（7.3.3）；
- `transferChannel`：设备级文件传输/导出通道，`ble`（缺省）/ `spp` / `auto`（7.6 传输通道）；运行中经 `SET_TRANSFER_CHANNEL` **整项替换**——agent 写 Controller 快照即时生效，文件传输/导出任务启动时读快照选路，**进行中的任务不换通道**；非法 channel 回 `2001`、设备不存在回 `2003`（A.2）；server 侧对应 API `POST /devices/{mac}/transfer-channel`（写库 + 归属 agent 在线即下发等 ACK）；
- `profile` 为可选的 char→service 映射（4/8 位短 UUID 或完整 UUID），供 `ServiceResolver` 在 `PollStep.serviceUuid` 为 null 时解析；缺失且 step 未显式给 service 时，该特征读/订阅按配置错误回 `1003 特征不存在`；
- `fields`：Decoder 字段映射（8.9），字段名 → `{char, format, byteOrder, scale, byteOffset}`；`format` ∈ `uint8/uint16/uint32/sint8/sint16/sint32/utf8/bool/hex`，`byteOrder` 默认 `LE`，`scale` 默认 1.0，`byteOffset` 默认 0；**规则 `conditions[].field` 必须在此有映射**，加载时校验，缺失则该设备规则集拒绝加载并上报配置错误；未配置映射的特征按 char UUID 原样上报原始 hex；
- `configVersion`：配置版本号，随 `REGISTER_ACK` 及每次运行中更新单调递增；Android 端按版本号单调应用（低版本晚到直接丢弃）；运行中更新（`SET_POLLING_INTERVAL` / `SET_POLL_RULES` / `SET_PERSISTENT_DEVICE` / `SET_TRANSFER_CHANNEL` 等）**整项替换、下一 tick 原子生效**，不做字段级合并，进行中的本轮任务按旧配置执行完毕；
- `tickIntervalMs` 必须 ≤ 全局最小 `intervalMs / 2`（6.4 的硬约束）：**配置值 > 最小 `intervalMs / 2` 即触发告警并钳制到 `最小 intervalMs / 2`**；
- `cooldownMs` 默认 5000，控制被踢设备的冷却防抖时长，冷却过滤在分配侧执行（5.2 第 5 条 / 16.5）；
- `setupBudgetMs` 默认 4000，单组建连预算（CONNECTING → READY），超预算强制释放（5.2 第 3 条 / 12.5）；一轮时长估算公式必须包含本项（6.4）；
- `agingThresholdMs` 默认 30000，连接请求的老化步长：每等待一个步长有效优先级升一级，封顶 `HIGH`（5.2 第 6 条）；
- `heartbeatIntervalMs` 默认 5000，TCP 心跳周期（3.1）；`connectTimeoutMs` 默认 10000，`connectGatt` 硬超时（7.2，应 ≥ `setupBudgetMs`）；`gattTimeoutMs` 默认 3000，单次 READ/WRITE 操作超时（12.2）；
- `maxReconnectAttempts` 默认 5、`reconnectBackoffMaxMs` 默认 60000，断线重连次数上限与退避封顶（7.5）；
- `notifyMinReportIntervalMs` 默认 200，高频通知的最小上报间隔，防 TCP 拥塞（7.3.3 节流）；
- `maxConcurrentTransfers` 默认 1（建议 1~2），同时进行的文件传输任务上限（7.6）；
- `diskQuotaMb` 默认 1024，传输缓存目录配额；`failedTaskRetentionDays` 默认 7，失败任务文件保留天数（7.6 本地存储管理）；
- `reportBufferMax` 默认 1000，`StateReporter` 离线缓存上限（7.4.1）；`staleThresholdMs` 默认 120000，轮询缓存过期阈值（6.3）；
- 动作 `type` 词表见 7.3.2（含 `FILE_TRANSFER`，`params.fileId` 须已在服务器登记，命中后经 `FILE_REQUEST` 上行事件取文件，10.2）；
- 未下发的字段使用 Android 端内置默认值兜底。

### 16.5 关键算法伪代码

**调度器选择牺牲者（5.2 / 5.4）**

```
selectVictim(candidates):
  pool = candidates - pinned - persistent           # 传输中与常驻绝不踢
  pool = pool.filter(now >= notifyBoostUntil)       # 通知活跃期内不踢（7.3.3）
  idle = pool.filter(无待执行任务).maxBy(空闲时长)
  if idle 存在: return idle
  deferrable = pool.filter(无 HIGH 命令)
                  .minBy(priority 升序, 空闲时长 降序)
  if deferrable 存在: return deferrable
  return null   # 全部不可踢 → 需求排队等待（由老化机制兜底，5.2 第 6 条），或上报服务器决策
```

**时间片轮转推进（6.2）**

```
each scheduler tick:
  now = clock()
  # 1) 轮询到期检查：状态白名单 + 欠账防重入（6.1）
  for d in registry.devices:
    if d.hasPendingPollTask(): continue                        # 上一轮未完成 → 只标记、不入队
    if d.state == READY and now >= d.nextPollTime:
      d.enqueuePollTask(createTask(d))                         # 已连接直接执行
    elif d.state in {REGISTERED, DISCONNECTED, WAITING_SLOT} and now >= d.nextPollTime:
      requestSlot(ConnectionRequest(d, POLL, expire = now + timeSliceMs))
      # 同一 deviceMac 每 tick 重复 requestSlot 由调度器幂等 upsert，不重复排队（3.6）
      # CONNECTING / SERVICE_DISCOVERING / CONFIGURING / RECONNECTING / ERROR /
      # PAUSED / TERMINATED 不在白名单内，绝不申请槽
  # 2) 时间片与建连预算管理（5.2 第 3 条）：切片自 READY 起算
  for d in activePool.devices where not (pinned or persistent):
    if d.state in {CONNECTING, SERVICE_DISCOVERING, CONFIGURING}
       and now > d.acquireTime + setupBudgetMs:
      forceRelease(d)                                          # 建连超预算 → 强释放（12.5）
    elif d.state == READY and now > d.readyTime + timeSliceMs
       and d.pendingCommands.isEmpty():
      releaseSlot(d)                                           # 时间片到期 → 释放，换下一组
  # 3) 空闲槽分配：冷却过滤 + 欠账老化
  for slot in freeSlots:
    req = pending.filter(req -> now >= coolDownUntil(req.deviceMac))   # 冷却期跳过（5.2 第 5 条）
                 .maxBy(effectivePriority(req, now) 降序, requestTime 升序)
    if req == null: break
    slot.acquire(req.deviceMac, pinned = req.reason == FILE_TRANSFER)

effectivePriority(req, now):
  steps = (now - req.requestTime) / agingThresholdMs           # 欠账老化（5.2 第 6 条）
  return min(req.priority + steps, HIGH)
```

**断线重连退避（7.5）**

```
onAbnormalDisconnect(d):
  releaseSlot(d)                                   # 立即释放槽位，退避期间不持槽（7.5）
  d.state: DISCONNECTED → RECONNECTING             # RECONNECTING = 纯退避计时状态
  delay = min(1000ms * 2^attempt, 60000ms)         # 1s, 2s, 4s, ... 封顶 60s
  after(delay):                                    # 退避到期
    d.state → WAITING_SLOT
    requestSlot(ConnectionRequest(d, priority = d.欠账任务最高优先级))   # 走正常调度（5.1）
  attempt++ on failure; attempt = 0 on READY       # 获槽 → CONNECTING，成功即清零
  超过 maxAttempts → ERROR 状态上报
```

**文件传输窗口推进（7.6）**

```
offset = 0
while offset < total:
  send window of chunks [offset, offset + windowSize)
  ack = waitAck(timeout = 30s)
  if ack.crcOk:  offset += windowSize; reportProgress(offset / total)
  else:          resend NAKed chunk range
                 retry++;  retry > N → task FAILED
```

**规则求值（7.3.2）**

```
evaluate(fields, rules):
  matched = []
  for rule in rules.sortBy(priority 降序):
    if rule.conditions.allMatch(fields):
      matched.append(rule)
      if rule.stopOnMatch: break
  return flatten(matched.actions)
```

---

## 附录 A：通信协议字段级定义

### A.1 通用信封与编码约定

- 传输载体：JSON 报文 = 4 字节大端长度前缀 + UTF-8 文本（流解复用规则见 16.3）；文件/日志数据走二进制帧，不在本附录范围；
- 公共字段（所有报文均携带，A.2 / A.3 各表不再重复列出）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | 命令/事件名，取 10.1 / 10.2 全表 |
| `timestamp` | long | 是 | 墙钟毫秒时间戳，仅用于上报对齐（本地调度计时一律单调时钟，9 章） |
| `requestId` | string | 命令必填 | 服务器命令携带；`CMD_ACK` 原样回填对账（8.4）；主动上报事件不携带（10.3） |

- 编码约定：`byte[]`（payload、哈希等）一律 **base64 字符串**；MAC 格式 `AA:BB:CC:DD:EE:FF`（大写、冒号分隔）；UUID 支持 4/8 位短格式或 128 位完整格式，解析规则同 16.4 `profile`；枚举字段取枚举名字符串（如 `"state": "READY"`）。

### A.2 服务器 → Android 命令（21 条）

> 各表仅列命令特有字段，公共字段（`type` / `timestamp` / `requestId`）省略。

**`REGISTER_ACK`** — 注册确认（7.1）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `errorCode` | int | 是 | 0 = 成功；拒绝时携带 2xxx（如 token 校验失败） |
| `config` | object | 成功时必填 | AgentConfig 全量（16.4），含 `configVersion`、调度参数、`devices[]` |

**`CONNECT_DEVICE`** — 注册并连接 DUT（7.2，幂等，兼 ERROR 恢复入口）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` | string | 是 | 目标 DUT（与 `deviceId` 二选一，至少其一） |
| `deviceId` | string | 否 | 业务 ID |
| `lazyConnect` | bool | 否 | 默认 `false` = 即时连接；`true` = 仅注册（冷启动批量恢复） |

**`DISCONNECT_DEVICE` / `REMOVE_DEVICE` / `RESUME_DEVICE`** / `START_POLLING` / `STOP_POLLING`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` | string | 是 | 目标 DUT |

**`READ_CHAR`** — 读取特征

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` / `service` / `char` | string | 是 | 目标设备与服务/特征 UUID |
| `timeoutMs` | int | 否 | 默认 `gattTimeoutMs`（3000） |

**`WRITE_CHAR`** — 写入特征

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` / `service` / `char` | string | 是 | 同上 |
| `payload` | string | 是 | base64 |
| `writeType` | string | 否 | `WITH_RESPONSE`（默认）/ `NO_RESPONSE`（7.6 传输机制） |
| `timeoutMs` | int | 否 | 默认 `gattTimeoutMs` |

**`SET_POLLING_INTERVAL`** — 调整轮询间隔

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` | string | 是 | |
| `intervalMs` | long | 是 | ≥ 200，且应满足 6.4 一轮时长约束，否则告警 |

**`SET_POLL_RULES`** — 下发/更新规则集（7.3.2）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` | string | 是 | |
| `rules` | array | 是 | 元素结构 = 16.4 `devices[].rules`；整集替换 |

**`FILE_TRANSFER`** — 文件传输任务（7.6）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `taskId` / `fileId` / `deviceMac` | string | 是 | |
| `size` | long | 是 | 文件字节数 |
| `sha256` | string | 是 | base64 的 32 字节整体哈希（16.3） |
| `windowSize` | int | 否 | CRC 窗口块数，默认 64 |
| `chunkSize` | int | 否 | 默认 MTU − 3 |
| `fileName` | string | 否 | 原始文件名；`.zip` 结尾时 agent 传输落盘名为 DUT 侧 `/data/ota.zip`（7.6） |

**`FILE_CANCEL`** — `taskId`（string，是）。

**`FILE_EXPORT`** — 设备文件导出（7.9，DUT → 手机 → 服务器）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` | string | 是 | 目标 DUT |
| `remotePath` | string | 是 | 设备侧路径；以 `/` 结尾 = 目录模式（`00AT^LS` 列举 + 逐文件导出） |
| `exportId` | string | 否 | 缺省由 agent 生成（`export-<8位hex>`）；重复下发同 exportId 幂等受理；进度/结果走 `EXPORT_PROGRESS` / `EXPORT_RESULT` |

**`SET_MAX_CONNECTIONS`** — `maxSlots`（int，是，2~5；越界或驱逐失败回 `3003`，5.4）。

**`SET_PERSISTENT_DEVICE`** — `deviceMac`（string，是）+ `on`（bool，是）。

**`SET_TRANSFER_CHANNEL`** — 传输通道运行中整项替换（7.6 / 16.4）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceMac` | string | 是 | 目标 DUT（不存在回 `2003`） |
| `channel` | string | 是 | `ble` / `spp` / `auto`（非法回 `2001`）；agent 写 Controller 快照即时生效，文件传输/导出任务启动时读快照选路，进行中的任务不换通道 |

**`PAUSE_DEVICE`** — `deviceMac`（string，是）+ `abortTransfer`（bool，否，默认 `false` = 挂起传输，7.7）。

**`UPLOAD_LOG`** — `sinceTs`（long，否，只打包该时间之后的日志）+ `minLevel`（string，否，默认 DEBUG 全量）。

**`GET_STATUS`** — `deviceMac`（string，否；缺省 = 整机 + 全部设备，返回结构见 A.3 `CMD_ACK.result`）。

**`RESET`** — 无特有字段（7.8）。

### A.3 Android → 服务器事件（20 类）

> 各表仅列事件特有字段，公共字段（`type` / `timestamp`）省略；`CMD_ACK` 另带 `requestId`。

**`REGISTER`** — 注册上报（7.1）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deviceId` / `ip` | string | 是 | 手机业务 ID 与局域网 IP |
| `port` | int | 是 | |
| `androidSdk` | int | 是 | Android API Level |
| `bleSupported` | bool | 是 | |
| `maxConnections` | int | 是 | 实测 BLE 并发能力（`supportedMaxConnections()`） |
| `agentVersion` | string | 是 | |
| `token` | string | 是 | Agent 认证（11.2） |

**`HEARTBEAT`** — `cpuPercent`（int，否）、`memAvailMb`（int，否）、`slotsUsed` / `slotsTotal`（int，是）、`devicesManaged` / `devicesReady`（int，是）。

**`DEVICE_STATE`** — `deviceMac`（string，是）+ `state`（string，是，`DeviceState` 枚举名）+ `errorCode`（int，否）+ `rawStatus`（int，否，底层原始码透传，12.9）。

**`CONNECTION_SLOT_ACQUIRED` / `CONNECTION_SLOT_RELEASED`** — `deviceMac` + `slotId`（int）+ `reason`（string：`COMMAND` / `POLL` / `PERSISTENT` / `EVENT` / `FILE_TRANSFER` / `RECONNECT`）。

**`POLL_RESULT`** — `deviceMac`（是）+ `stale`（bool，是，6.1）+ `values`（map，是；键 = 16.4 `fields` 的字段名，值 = 解析后字段值）。

**`POLL_DATA_STALE`** — `deviceMac`（是）+ `lastPollTime`（long，是）+ `reason`（string，是：`NO_SLOT` / `CONNECT_FAILED`）；不携带 `errorCode`（12.9）。

**`CMD_ACK`** — `requestId`（是）+ `errorCode`（是）+ `rawStatus`（否）+ `result`（object，否；按命令而异——`GET_STATUS` 返回 `{slotsUsed, slotsTotal, devices: [{deviceMac, state, lastPollTime, pollDataStale, queueDepth, stateFlag}]}`，其余命令一般无 `result`）。

**`DEVICE_PAUSED` / `DEVICE_RESUMED`** — `deviceMac`。

**`CONNECTION_STATISTICS`** — `slotsUsed` / `slotsTotal`（int）+ `switchCount`（long）+ `gattFailureRate`（double，0~1）+ `avgPollMs`（long）。

**`FILE_PROGRESS`** — `taskId` + `percent`（double，0~100）+ `bytesPerSec`（long）。

**`FILE_RESULT`** — `taskId` + `errorCode`（是：0 / 4xxx / `2004` 取消）+ `rawStatus`（否）+ `detail`（string，否）。

**`ERROR`** — `errorCode`（是）+ `rawStatus`（否）+ `message`（string，是）+ `deviceMac`（否）。

**`TOPOLOGY`** — `connections`（array）：`[{deviceMac, slotId, state, persistent, pinned}]`。

**`FILE_REQUEST`** — `fileId` + `taskId` + `deviceMac`（均必填；规则动作触发传输的取文件入口，7.3.2 / 7.6）。

**`FILE_DOWNLOAD_READY`** — `taskId` + `fileId`。

**`FILE_DOWNLOAD_ACK`** — `taskId` + `resendSeqs`（int[]，是；空数组 = 全部成功，16.3）。

**`FILE_DOWNLOAD_RESUME`** — `taskId` + `lastSeq`（int，是；已确认最大连续块号，7.6 / 16.3）。

**`EXPORT_PROGRESS`** — 设备文件导出进度（7.9）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `exportId` | string | 是 | 对应 `FILE_EXPORT` |
| `deviceMac` | string | 否 | |
| `channel` | string | 否 | 实际承载通道：`ble` / `spp` |
| `file` | string | 是 | 当前文件名 |
| `fileReceived` / `fileSize` | long | 是 | 当前文件已收 / 总字节数 |
| `filesDone` / `filesTotal` | int | 是 | 已完成 / 总文件数；`filesTotal=0` 表未知（LS 未完成或单文件模式） |

> 覆盖式语义：同 exportId 后到的进度整体取代先到值；agent 按 ~500ms 节流上报，文件开始/完成必报（不受节流）。

**`EXPORT_RESULT`** — 设备文件导出结果（7.9）：`exportId`（是）+ `deviceMac`（否）+ `files`（array，是，已入库文件清单 `[{name, size, ...}]`）+ `errorCode`（是：0 / 4xxx / 2xxx）+ `detail`（string，否）。

**`LOG_UPLOAD_DONE`** — `requestId` + `errorCode` + `size`（long，实际上传字节数）。

---

## 附录 B：参考资料

- 设计讨论（初始框架）：https://chat.deepseek.com/share/12io8ebg7fjr92d3wf
- 设计讨论（完整记录）：https://chat.deepseek.com/share/eed0v8jfdgup4yjdar

> 本 SDD 由上述讨论记录整理而成。原始讨论稿见 `docs/ble_device_ma