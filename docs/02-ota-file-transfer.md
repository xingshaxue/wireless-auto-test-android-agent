<title>02-ota-file-transfer</title>

# 手环 BLE OTA / 文件传输协议（p67 / BES1503）

> 来源：手环固件仓库源码 + libmiwear.a 二进制取证（挖掘日期 2026-09-12）。

**本固件存在两条完全独立的 OTA 下发通道**，自动化测试须先确认目标通道：

| 通道 | 用途 | 加密 | 代码可见性 |
|-|-|-|-|
| **B. 工厂通道（lc_proto）** | 工厂/研发侧文件下发 + OTA（龙旗 APK） | 无加密、ASCII 命令 | **全部源码可见，推荐自动化优先使用** |
| A. 量产通道（miwear SDK / Mass Data） | 小米穿戴 App 正式 OTA | SAR 层有 session 加密（CTR） | 控制面（protobuf）源码可见；传输状态机在 `libmiwear.a`，靠 DWARF/反汇编恢复 |

---

# 通道 B：lc_proto 工厂文件传输 / OTA（源码完整）

## B.1 GATT 承载

源码：`vendor/xiaomi/miwear/bluetooth/vendor/lc/app_lc_proto_gatt.c:39-50`、`app_lc_proto_gatt.h:24-29`

| 条目 | UUID（128-bit 标准文本） | 属性 |
|-|-|-|
| Service | **1b7e8251-2877-41c3-b46e-cf057c562023** | — |
| Characteristic（TX/RX 合一） | **8ac32d3f-5cb9-4d44-bec2-ee689169f626** | **NOTIFY + WRITE_NR** |
| CCCD | 0x2902 | R+W |

- 写入一律 **Write Without Response**；回包一律 **Notify**（`lc_factory_gatts_notify`，`app_lc_proto_gatt.c:338-349`）。
- 连接后固件切到 BLE 通道：`service_bt_lc_proto_set_chn(BT_LC_PROTO_CHN_BLE)`（`app_lc_proto_gatt.c:86`）。
- 写入路径：`lc_gatts_write_request_callback` → `bt_send_to_lcproto_parse_thread` → socketpair → `service_bt_lc_proto_event_parse`（`vendor/xiaomi/miwear/apps/customer/lc_proto_service/lc_proto_thread.c:602-614, 401-412`）。
- （UUID 转换依据：`BT_UUID_DECLARE_128` 小端字节序，规范字符串需倒序，`frameworks/connectivity/bluetooth/framework/common/bt_uuid.c:156`。）

## B.2 应用层帧格式（ASCII）

解析入口 `service_bt_lc_proto_event_parse`（`vendor/xiaomi/miwear/apps/customer/lc_proto_service/service_bt_lc_proto.c:1217-1244`）：

```Plaintext
byte0 = ServiceID（ASCII 字符）：'0'=事件(AT/FI/导出)  '3'=文件接收
byte1 = CommandID（ASCII 字符）
byte2.. = 载荷（ASCII，逗号/空格分隔）
```

文件接收命令集（`BT_LC_PROTO_CID_FILE`，`service_bt_lc_proto.c:151-158`；处理函数 `bt_lc_proto_file_parse`，`:1032-1208`）：

| 手机→手环（ASCII） | 含义 | 手环→手机应答（Notify, ASCII） |
|-|-|-|
| `30<retrans>` | 参数协商；`retrans`='1' 支持断点续传 | `"300"` = 传输开始（`:1052`） |
| `33<format>,<filePath>,<fileSize>` | 通知开始传输；format 实测为 `bin`（紧随 33 无空格，如 `33bin,/data/x.bin,1024`）；filePath 含 `.bin` 则标记为 OTA 文件（`:1086-1090`） | `"330"` 打开成功 / `"331"` 失败 / `"open error:%d"` |
| （无头裸数据流） | 文件内容，按块发送 | 每块 `"310"` OK / `"311"` 重传 |
| `32` | 传输结果校验（核对已收大小 == fileSize） | `"320"` 成功 / `"321"` 失败（失败会删文件） |
| `34` | 停止传输 | `"340"` |

注：枚举里有命令 `'1'`（TRANSMIT_ACTIVE）但 switch 中**无 case**，实际不用。

## B.3 分块与 CRC（字节级）

`service_bt_lc_proto.c:62-71`；接收逻辑 `bt_lc_proto_file_data_recv`（`:582-687`）：

- **BLE 块大小 = 4484 字节**（`BT_LC_PROTO_BLE_FILE_RECV_BLOCK_SIZE = 20*224 + 4`）：4480 数据 + 4 CRC。224 字节/包暗示按 MTU≈227 设计。
- 临时缓冲区 41000 字节（`:72`）。
- **每块布局：`[4480B 数据][4B CRC32 小端，对该块 4480B 计算]`**（`:102, 601-602`）。
- **CRC 算法：CRC-32/ISO-HDLC** —— 反射多项式 0xEDB88320、初值 0xFFFFFFFF、末次异或 0xFFFFFFFF，实现 `CalcCrc32`（`:378-402`）。
- 整块 CRC 错 → 回 `"311"`，手机重发该块；写文件失败 → `"340"` 并终止。
- 总接收量 = fileSize + 4×块数；最后一块收齐判定 `recv_total_len == fileSize + 4`（`:644`）。
- 超时：1000ms 未收满一块回 `"311"`；累计 30 次超时终止（`:100, 498-533`）。

## B.4 断点续传

`service_bt_lc_proto.c:1106-1121`：协商 `retrans='1'` 时以 `O_RDWR|O_APPEND` 打开已存在文件，回复文本  
`"open file success:retransmission start length:%lld"`（%lld = 已收字节数）——**手机端解析该文本获得续传偏移**，从该偏移继续按块发送。`retrans='0'` 则删旧文件重传。

## B.5 升级触发与状态上报

- 触发：AT 命令 `00AT^OTA_UPDATE` → 回 `"OTA_UPDATE=OK\r\n"` → `system("reboot recovery")`（`vendor/xiaomi/miwear/apps/customer/at_service/uAT/port/at_cmd.c:252-262, 2759`）。
- 文件接收状态回调 `recv_state_cb(is_ota_file, progress, state)`；state 枚举 `BT_LC_PROTO_FILE_RECV_STATE_{NORMAL=0, FAIL=1, COMPLETE=2}`（`service_bt_lc_proto.h:35-43`）。

## B.6 文件导出（手环→手机，供参考）

`bt_lc_proto_export_file`（`service_bt_lc_proto.c:694-961`）：`'0' '6'` + 子命令 `'1'` 开始（载荷=路径）→ 回 `'@'` + u32 **大端** 文件大小；`'2'` 请求数据 → 回 `'@'`+u32BE 块长+数据+4B **大端字节累加和**；`'3'` 重传；`'4'`/`'5'` 批量模式。BLE 发送块 4480B、单包 224B（`:66-69`）。

## B.7 自动化推荐流程（通道 B）

1. 写 `30 1` → 等 `"300"`
2. 写 `33bin,/data/ota.bin,<fileSize>` → 等 `"330"`（真机校准：`33` 后紧跟格式名 `bin`，无空格；写成 `33 OTA,...` 会被固件回 `open error:-1`）
3. 按 4484B 块裸发（4480 数据 + 4B CRC32-LE），每块等 `"310"`（`"311"` 则重发该块）
4. 写 `32` → 等 `"320"`
5. 写 `00AT^OTA_UPDATE` → 等 `"OTA_UPDATE=OK"`，设备重启进 recovery

---

# 通道 A：量产 OTA（miwear SDK / Mass Data）

控制面（protobuf）源码可见；SAR 分帧/ACK/会话加密在 `libmiwear.a`，以下为恢复结果（置信度逐节标注）。

## A.1 GATT 承载

复用 MI Service 0xFE95 的 0x005E（Notify）/ 0x005F（Write NR）——`0x0055(MASS)/0x0056(OTA_TX)/0x0057(OTA_RX)` 不是独立 GATT 特征，而是 **SAR L2 逻辑通道号**（lib `miwear_transport.c.o` 字符串：`"L2 received channel: MASS:%d"` / `OTA:%d` / `PB`）。MTU 变化经 `miwear_att_mtu_update` 进 SDK（`app_gatts.c:459-469`），SAR 要求 MTU>19 才更新 MPS。

## A.2 SAR L1 帧格式（高置信，反汇编 + DWARF 枚举）

| 偏移 | 长度 | 内容 |
|-|-|-|
| 0 | 2 | 同步头 `0xA5 0xA5` |
| 2 | 1 | 低 4bit = 包类型：0=NAK 1=ACK 2=CMD 3=DATA |
| 3 | 1 | seq（DATA）/ ack 号（ACK） |
| 4 | 2 | 长度 u16 LE（其后字节数；DATA 时 = 6 + 应用数据长） |
| 6 | … | 载荷 |

- CMD 命令字：`L1START_REQ=1, L1START_RSP=2, L1STOP_REQ=3, L1STOP_RSP=4, HEART_BEAT=5, SUSPEND=6, RESUME=7`。
- 协商配置项：`VERSION=1, MPS=2, TX_WIN=3, SEND_TIMEOUT=4, DEVICE_TYPE=5, DEVICE_NAME=6, OS_VERSION=7`。
- ACK 带 miss-range 累计确认 + 超时重传 + 窗口控制（strings：`"Recv ACK miss range!"`、`"Retrans packet"`、`"NO WIN"`）。

## A.3 SAR L2 格式（DATA 包载荷内，高置信）

| 偏移 | 长度 | 内容 |
|-|-|-|
| +0 | 2 | **CRC-16/IBM（Modbus）**：反射 poly 0xA001，初值 0，无末次异或，LE；覆盖其后 channel+flag+应用数据（crc16_table[1]=0xC0C1 已验证） |
| +2 | 1 | L2 逻辑通道号（= `miwear_ble_char_index_t`）：1=PROTO_TX 2=PROTO_RX 5=MASS 6=OTA_TX 7=OTA_RX |
| +3 | 1 | 标志字节（语义未完全确认） |
| +4 | … | 应用层数据（pb 或 mass 包） |

- L0 层负责跨 GATT 写的重组，手机可按 MTU 切片写。
- **注意加密**：认证完成后逻辑通道数据可能被会话密钥 CTR 加密（`"session decrypt/encrypt"`、`miwear_session_crypto_ctr encrypt/decrypt failed`）——自动化须先过绑定/认证流程（lib `miwear_auth_*`）。

## A.4 OTA 控制面（protobuf，源码可见）

信封 `WearPacket{type(tag1), id(tag2), oneof payload}`（`vendor/xiaomi/miwear/common/pb/include/wear.pb.h:62-152`），nanopb-0.3.9.6。

**1) OTA 准备握手**：`WearPacket{type=SYSTEM(2), id=PREPARE_OTA(5)}`，payload tag16 = `PrepareOta_Request`（`wear_system.pb.h:773-785`）：

| tag | 字段 | 类型/取值 |
|-|-|-|
| 1 | force | bool |
| 2 | type | ALL=0 / ROM=1 / RES=2 / SILENT=15 |
| 3 | firmware_version | string |
| 4 | file_md5 | string（32 字符 hex） |
| 5 | change_log | string |
| 6 | file_url | string |
| 7 | file_size | uint32 |
| 8 | support_transport | uint8 |

应答 payload tag17 = `PrepareOta_Response`：`prepare_status` + `expected_slice_length` + `min_battery` + `progress` + `select_transport`。  
状态枚举 `PrepareStatus`：READY=0, BUSY=1, DUPLICATED=2, LOW_STORAGE=3, LOW_BATTERY=4, DOWNGRADE=5, OP_NOT_SUPPORT=6, EXCEED_QUANTITY_LIMIT=7, NETWORK_ERROR=8, HIGH_TEMPERATURE=9, LOW_TEMPERATURE=10, FAILED=255（`wear_common.pb.h:34-47`）。  
手环侧检查：低电 <20%、静默 OTA 时通话/放音→BUSY、版本不高于当前→DOWNGRADE、md5 重复→DUPLICATED、存储不足→LOW_STORAGE（`vendor/xiaomi/miwear/apps/applications/ota/page_main.c:331-415`）。

**2) Mass 传输协商**：`WearPacket{type=MASS(22), id=PREPARE(0)}`，payload tag1 = `PrepareRequest`（`wear_mass.pb.h:45-52`）：

| tag | 字段 | 说明 |
|-|-|-|
| 1 | data_type | uint8：高 4bit 大类（**2=OTA 文件**）；低 4bit：0=全包 1=代码包 2=资源包 **15=静默升级包** |
| 2 | data_id | bytes，**16 字节文件 MD5**（`MIWEAR_MASS_ID_LEN 16`，`miwear_type.h:634`） |
| 3 | data_length | uint32 文件总大小 |
| 4 | support_compress_mode | uint8，当前手环恒选 0 不压缩 |

应答 `PrepareResponse`（tag2）：`prepare_status` + **`remained_data_length`（tag4 = 断点续传偏移）** + `expected_slice_length`（tag5）。slice 上限：BLE 4096B / 经典 BT 12288B（`miwear_mass.c:24-25, 52-62`）。  
断点续传：`MASS_PREPARE_TYPE_TRANSPORT_RESUME=0 / RESTART=1`（`miwear_type.h:636-639`）；RESUME 时按本地已收长度返回 offset 并以 `O_APPEND` 续写（`miwear_mass.c:366-404,459-461`、`miwear_mass_util.c:500-524`）。

**3) Mass 数据包格式**（走 SAR L2 通道 5；恢复自 `file_flinger_download_mass.c.o` DWARF 结构体 + 反汇编，高置信）：

```Plaintext
每包：
0   2   packet_length u16 LE = 本字段之后字节数（= 4 + N）
2   2   packet_total  u16 LE
4   2   packet_index  u16 LE（从 1 开始）
6   N   packet_data

packet_data 第 1 包前缀 22B 头：
+0   1   comp_encrypt（压缩/加密标志位）
+1   1   data_type（同 PrepareRequest）
+2   16  data_id（文件 MD5）
+18  4   data_len u32 LE（文件总大小）
```

**4) 完整性校验**：

- **CRC-32/ISO-HDLC**（init/xorout 0xFFFFFFFF，反射 poly 0xEDB88320），对整个数据流滚动计算；发送方在流末尾追加 4B LE CRC，手环最后一包比对（crc32_tab[1]=0x77073096 已验证）。失败 → `MASS_FINISH_RESULT_WRITE_ERR_CRC`。
- 整文件 **MD5** 与 data_id 比较。
- finish 结果枚举 `miwear_mass_finish_result_t`：SUCCESS=0, WRITE_FAIL=1, ERR_CRC=2, ERR_MD5=3, ERR_LENGTH_INVALID=4, ERR_TIMEOUT=5, ERR_OTHERS=6, PAUSE=7, CANCEL=8（`miwear_type.h:650-660`）。
- 控制消息 `MassControl`（id=1，tag3）：op PAUSE=1 / CANCEL=2 / ERROR=3 + data_type + data_id（`wear_mass.pb.h:28-43`）。
- 超时：45s 无数据触发清理（`MASS_DATA_TIMER_TIMEOUT_MS`，`miwear_mass_util.h:37`）。

**5) 完成与升级触发**：

- 成功后文件从 `/data/mass/tmp/` rename 到 `/data/mass/<md5hex>`（`miwear_mass.c:771-791`）。
- 进度经 `MASSFileInfo`（type/md5[16]/transfer_status/packet_len_written/packet_len_total；status SUCCESS=0/ING=1/FAIL=2，`btmsg.pb.h:127-134,659-666`）上报 OTA UI（`page_main.c:625-719`）。
- UI 将 `/data/mass/<md5>` rename 为 **`/data/ota.zip`**，2s 后 `miwear_system_recovery("ota_main")` 进 recovery 烧写（`page_main.c:566,689`；电量 <20% 拒绝，`:560-564`）。
- 强制升级：`System_SystemID_FORCE_UPGRADE=4` + `ForceUpgrade{force, firmware_version}`（`wear_system.pb.h:66`、`page_main.c:450-480`）。
- 进度上报另有 `REPORT_OTA_PROGRESS=87` / `System.ota_progress(tag55)` = `PrepareOta_Progress{code, percent, min_battery}`（`wear_system.pb.h:68,764-771`）。
- 静默 OTA（data_type 低 4bit=0xF）：不弹 UI，落 `/data/mass/silent_ota/`，满足条件（摘腕+电量足+20:00-05:00 或充电中）后自动 `miwear_system_recovery("ota_silent")`（`applications/ota/data.c:249-303,408-431`）。

## A.5 板端烧写（与 BLE 无关，供完整链路理解）

OTA 包为 **ddelta 差分 + zstd 三段压缩**（`ota-zstd-patches/README.md`），由 recovery 中 `vela_ota.bin` 执行；`vendor/bes/boards/best1503_ep/p67/src/ota.c:37-56` 只是分区钩子空实现。

---

# 结论

1. **自动化首选通道 B（lc_proto）**：无认证无加密、ASCII 命令、块 CRC 规则全部源码可考（B.1–B.5 每步均有文件:行号）。
2. 通道 A（量产）控制面 pb 定义完整可见，但需自行实现 SAR L1/L2 + 认证加密，工作量明显更大。
3. 两条通道 CRC 都是标准 CRC-32/ISO-HDLC；SAR 层另用 CRC-16/IBM(Modbus)。
4. 断点续传两通道均支持：B 靠 APPEND+文本回执偏移；A 靠 `PrepareResponse.remained_data_length`。

**遗留不确定项**（均为二进制推断，建议首测时抓包校准）：SAR L2 第 +3 字节标志语义；mass slice 内 packet_index 是否跨 slice 连续编号；L2 通道 6/7（OTA_TX/RX legacy 入口 `miwear_ota_data_recv_handler`）本固件是否仍被手机 App 使用。