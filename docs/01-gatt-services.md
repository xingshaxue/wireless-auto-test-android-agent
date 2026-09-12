<title>01-gatt-services</title>

# 手环 BLE GATT 服务与特征清单（p67 / BES1503）

> 来源：手环固件仓库源码挖掘（挖掘日期 2026-09-12）。  
> 代码根：`vendor/xiaomi/miwear/bluetooth/`  
> **注意区分「源码中存在」与「本固件实际启用」**——很多标准服务（BAS/HRS/DIS 等）由 Kconfig 控制，本固件未编译。

## 0. 本固件实际启用的服务（决定性依据：根目录 `.config`）

| 服务 | UUID | 本固件状态 | 依据 |
|-|-|-|-|
| MI Service（小米核心） | 0xFE95 | ✅ 启用 | `CONFIG_MIWEAR_BLUETOOTH=y` (`.config:511`) |
| MiFind（找手机） | 0xFD2D | ✅ 启用 | `CONFIG_MIWEAR_BLUETOOTH_MIFIND=y` (`.config:518`) |
| 微信支付 | cc353442-… | ✅ 启用 | `CONFIG_WXPAY=y` (`.config:733`) |
| LC 产测（Longcheer） | 1b7e8251-… | ✅ 启用 | `CONFIG_LC_PROTO_BLE_SERVICE=y` (`.config:308`) |
| Battery 0x180F / Fake DIS 0x180A / Fake HID 0x1812 | — | ❌ 未编译 | `CONFIG_MIWEAR_BLUETOOTH_BAS` 未定义 |
| Heart Rate 0x180D | — | ❌ 未编译 | `CONFIG_MIWEAR_BLUETOOTH_HRS` 未定义 |
| Unlock 0xFDAB | — | ❌ 未编译 | `CONFIG_MIWEAR_BLUETOOTH_ENABLE_UNLOCK_SERVICE` 未定义 |
| 支付宝 0x3802 | — | ❌ 未编译 | `CONFIG_ALIPAY` 未定义 |
| GUI 自动化测试 0xFD1F | — | ❌ 未编译 | `CONFIG_MIWEAR_BLUETOOTH_ENABLE_AUTOTEST_SERVICE` 未定义 |

注册顺序（硬编码要求）：`vendor/xiaomi/miwear/bluetooth/miwear_sdk/porting/miwear_sdk_setup.c:188-249`；  
LC 服务注册：`vendor/xiaomi/miwear/bluetooth/vendor/vd_adapter.c:47-65`。

**ATT handle 是编译期固定的**：属性表里的 handle 直接来自 `vendor/xiaomi/miwear/bluetooth/app/app_gatts.h:29-110` 的枚举。properties/permissions 宏语义见 `frameworks/connectivity/bluetooth/framework/include/bt_gatt_defs.h:64-78,101-146`。

---

## 1. MI Service（0xFE95）— 手环与手机 App 的主数据通道 ✅

定义：`vendor/xiaomi/miwear/bluetooth/app/app_gatts.c:89-120`；  
UUID 常量：`vendor/xiaomi/miwear/bluetooth/miwear_sdk/include/miwear_type.h:50,56-68`。

| Handle | 条目 | UUID | 属性 | 数据格式 |
|-|-|-|-|-|
| 1 | Primary Service | **0xFE95** | — | — |
| 2 | Characteristic | **0x0050** | READ | 版本号，3 字节 |
| 3 | Characteristic | **0x005E** | WRITE_NR + NOTIFY | SAR 通道 1（双向） |
| 4 | CCCD | 0x2902 | R+W | 写 `01 00`=订阅/App 上线；`00 00`=离线 |
| 5 | Characteristic | **0x005F** | WRITE_NR + NOTIFY | SAR 通道 2（双向） |
| 6 | CCCD | 0x2902 | R+W | 订阅通知 |

### 1.1 0x0050 Service Version（READ）

- 长度 3 字节，缓冲上限 8 字节（`app_gatts.c:327-349`）。
- 典型值（反汇编取证，高置信）：hex **`03 02 07`** — uint16 LE `0x0203` = 版本 2.3，第 3 字节 `0x07` 疑为能力/配置位。来源：`libmiwear.a(miwear_sdk.c.o)` 中 `miwear_gatt_chrc_value_get` 的 `strh 0x0203` + `strb 0x07`。

### 1.2 0x005E / 0x005F — SAR 数据通道

- 手机 → 手环：WRITE_NR 写 0x005F（也接受 0x005E）；手环 → 手机：在 0x005E 上 NOTIFY。
- 写入入口 `gatts_miwear_write_request_callback`（`app_gatts.c:351-391`）→ `MIWEAR_GATTS_EVT_WRITE` 事件进 libmiwear；发送 `miwear_gatts_notify`（`app_gatts.c:535-559`）。
- CCCD 写 `01 00` 触发 App 上线事件 + 请求快速连接参数（`app_gatts.c:366-383`）。
- **重要限制**：Android 已绑定时该 BLE 服务拒绝访问（`app_gatts.c:360-364`）——Android 绑定后走经典蓝牙 SPP/RFCOMM；此 BLE 通道主要服务 iOS（`CONFIG_MIWEAR_BLUETOOTH_IOSBLE_ONLY=y`）。自动化若走 BLE 需注意绑定状态。
- SAR V2 帧头 magic = **0xA5A5**（`libmiwear.a(miwear_sar.c.o)` 反汇编，中高置信），帧内含 seqn/len/crc16，字节 2 低 4bit 为包类型（3=DATA），有 ACK/重传/窗口机制。详见 OTA 文档通道 A 部分。
- **SAR 之上跑 WearPacket protobuf 信封**：`vendor/xiaomi/miwear/common/pb/include/wear.pb.h:62-120`。`WearPacket{type, id, oneof payload}`。
- **电量、时间同步、设备信息/固件版本、找手机等"测试最常读"的数据都走这个 proto 通道，不是独立 GATT 特征**。分发表 `vendor/xiaomi/miwear/bluetooth/app/pb_dispatch.c:14-109`：

| 功能 | WearPacket 路由 | 代码位置 |
|-|-|-|
| 时间同步 | type=SYSTEM(2), id=`SET_SYSTEM_TIME` | `pb_dispatch.c:22` |
| 设备信息/固件版本 | type=SYSTEM, id=`GET_DEVICE_INFO` | `pb_dispatch.c:21` |
| 电量/基础状态 | type=SYSTEM, id=`GET_BASIC_STATUS` / `REPORT_DATA` | `pb_dispatch.c:81-82` |
| 找手机 | id=`FIND_PHONE` | `pb_dispatch.c:36` |
| OTA 准备 | id=`PREPARE_OTA` | `pb_dispatch.c:25` |
| 强制升级 | id=`FORCE_UPGRADE` | `pb_dispatch.c:24` |
| 通知 | type=NOTIFICATION(7) | `wear.pb.h` |
| 运动健康 | type=FITNESS(8) | `wear.pb.h` |
| 天气 | type=WEATHER(10) | `wear.pb.h` |
| 大文件/OTA 数据 | type=MASS(22) | `wear.pb.h` |

写入入口 `sdk_write_handler`（`vendor/xiaomi/miwear/bluetooth/app/miwear_bluetooth.c:613-628`）：PROTO 包给 pb_dispatcher，MASS/OTA 数据直进 lib 的 file_flinger。

> 备注：miwear SDK 头文件还定义了 0x0051\~0x005A（PROTO_TX/RX、FITNESS、VOICE、MASS、OTA_TX/RX、STAT、LOG、SENSOR_DATA，`miwear_type.h:57-66`），但**本固件 BLE server 侧未注册这些特征**——它们作为 SAR L2 逻辑通道号复用 0x005E/0x005F，另用于手环作 GATT client 发现对端服务（`app_gattc.c:463-503`）。不要按这些 UUID 去做服务发现。

---

## 2. MiFind（找手机）— 0xFD2D ✅

`vendor/xiaomi/miwear/bluetooth/app/app_gatts_mifind.c:14-22,117-133`

| Handle | 条目 | UUID | 属性 |
|-|-|-|-|
| 10 | Primary Service | **0xFD2D**（16-bit） | — |
| 11 | Characteristic TX | **0000CF07-0000-0000-0000-000000000000** | NOTIFY + WRITE_NR |
| 12 | CCCD | 0x2902 | R+W（订阅即上报 `MiFindData_ConnStatus_CONNECTED`，`:99-102`） |
| 13 | Characteristic RX | **0000CF08-0000-0000-0000-000000000000** | NOTIFY + WRITE_NR |
| 14 | CCCD | 0x2902 | R+W |

数据为**原始字节透传**：写入 → protobuf `MiFindData{data_type=BLE_DATA, data=...}` 转发 AP 侧（`app_gatts_mifind.c:44-71,107-115`）；发送 `mifind_gatt_notify()`（`:146-155`）。  
（128-bit UUID 宏字节为小端存储序，已按 Apple ANCS 已知 UUID 反推确认转换方式，依据 `nuttx/include/nuttx/wireless/bluetooth/bt_uuid.h:51-52` 与 `app_gattc.c:696-697` 对照。）

## 3. 微信支付服务 ✅

`vendor/xiaomi/miwear/bluetooth/app/app_wxpay.c:25-34,149-160`

| 条目 | UUID（128-bit 标准文本） | 属性 |
|-|-|-|
| Primary Service（经典蓝牙也可见 OVER_BREDR） | **cc353442-be58-4ea2-876e-11d8d6976366** | — |
| Char TX/RX | **c551c36a-0377-4a29-9657-74ffb655a188** | NOTIFY + READ + WRITE |
| CCCD | 0x2902 | 订阅成功上报 `WxpayData_BLEStatus_CONNECTED`（`:122-137`） |

数据为微信支付协议原始字节透传（`:55-79`）；写入/订阅会触发 10s 快速连接参数请求（`:92-106`）。

## 4. LC 产测服务（Longcheer 工厂通道）✅ —— 自动化测试首选

`vendor/xiaomi/miwear/bluetooth/vendor/lc/app_lc_proto_gatt.h:24-29`、`app_lc_proto_gatt.c:39-55`

| Handle | 条目 | UUID | 属性 |
|-|-|-|-|
| 14 | Primary Service | **1b7e8251-2877-41c3-b46e-cf057c562023** | — |
| 15 | Char TX/RX 合一 | **8ac32d3f-5cb9-4d44-bec2-ee689169f626** | NOTIFY + WRITE_NR |
| 16 | CCCD | 0x2902 | R+W |

- 数据：**ASCII AT 指令**。写入用 Write Without Response；响应经 notify 回送字符串，如 `"AT^USERDEBUG=OK,<n>"`。
- 隐藏后门：10s 内连续写 6 次 `"00AT^USERDEBUG=ON"` → 开 userdebug 并要求重启（`app_lc_proto_gatt.c:135-191`）。
- 其余数据进 `bt_send_to_lcproto_parse_thread`，响应经 `lc_factory_gatts_notify`（`:338-349`）。
- 独立广播包：`02 01 06 03 03 02 38 09 16 02 38 <6字节MAC相关>`（`:271-296`）。
- **该通道同时承载工厂 OTA/文件传输，协议见 `02-ota-file-transfer.md` 通道 B**。

---

## 5. 源码存在但本固件未启用的服务（其他变体参考）

| 服务 / 特征 | UUID | 属性 | 数据格式 | 出处 |
|-|-|-|-|-|
| Battery Service / Battery Level | 0x180F / **0x2A19** | READ + NOTIFY | 1 字节 uint8，电量百分比 0-100 | `app/app_battery.c:34-44,83-91,118-121`（`CONFIG_MIWEAR_BLUETOOTH_BAS`） |
| Fake DIS / PnP ID | 0x180A / 0x2A50 | READ | 固定 7 字节 `01 00 00 00 00 00 00` | `app_battery.c:58-64,123-127` |
| Fake HID / HID Info | 0x1812 / 0x2A4A | READ | 固定 `11 01 00 00` | `app_battery.c:46-56,129-133` |
| Heart Rate / Measurement | 0x180D / **0x2A37** | NOTIFY | 2 字节 `{flags=0x06, bpm}`（flags 0x06=UINT16 格式位+接触检测位，bpm 仅 1 字节） | `app/app_hrs.c:26,37,94-97`；订阅实例 3=运动心率（`:103-114`） |
| 支付宝 | 0x3802 / 0x4A02 | NOTIFY+READ+WRITE | 透传 | `app/app_alipay.c:152-162`（`CONFIG_ALIPAY`） |
| GUI 自动化测试 | 0xFD1F / 0x00F0、0x00F1 | NOTIFY+WRITE_NR+READ | 帧头 0xA5A5、帧尾 0x5A5A | `bluetooth/test/gui_test/gui_auto_test.c:37-38,132-152` |
| Unlock（本固件关） | 0xFDAB / 0x0001\~0x0004 | 0x0002 AUTHORIZE 要求加密（`GATT_PERM_ENCRYPT_REQUIRED`，`app_gatts.c:135`） | 版本/授权/认证/佩戴状态 | `app/app_gatts.c:122-170,991-1031` |
| Fake Service Change | 03ab1846-1789-8016-7271-988da999636a / 0x0010 | READ，固定 4 字节 0 | 注册即注销以触发对端 service changed | `app/app_fscs.c:17-35,58-94` |

> **本固件电量读取替代路径**：走 MI Service proto 通道 `System_SystemID_GET_BASIC_STATUS` / `REPORT_DATA`（`pb_dispatch.c:81-82`），不是 0x180F。心率实时值也不经 GATT HRS 暴露。

---

## 6. 广播包（Central 扫描/过滤用）

- 主广播 `miwear_gap_adv_start`（`app_gatts.c:809-891`）：Flags `02 01 08`（LE only, BR/EDR not supported）+ **mibeacon service data（UUID 0xFE95 帧）**，由 lib `mibeacon_mi_service_data_get_with_config` 生成（API 文档 `miwear_sdk/include/miwear_api.h:180-197`：solicited=iOS 发起绑定、bond_new=绑新手机）。
- ScanRsp = Complete Local Name（"product.device_name" + MAC 后 4 位，`:736-759`）。
- 广播间隔：未绑定 20ms；已绑定 100ms→300ms→1000ms 退避（`:39-44,862-874`）。TxPower -10dBm。
- 米家互联 legacy 广播：dev_type 0x10=TV / 0x11=跑步机 / 0x12=跳绳 / 0x13=band tag / 0x14=音箱（`miwear_api.h:199-214`）。
- MiBeacon 广播解析 API：`miwear_beacon_parse`（`miwear_api.h:245-256`，输出 `miwear_beacon_info_t{real_addr, pub_addr, name, dev_type, pid, registered}`，`miwear_type.h:101-109`）。

## 7. 其他非 GATT 数据通道（避免混淆）

- **SPP/RFCOMM server（经典蓝牙）**：UUID = Serial Port 0x1101（`app/app_spp.c:341-366`）。Android 绑定后的主数据通道，与 BLE SAR 承载相同 WearPacket 协议。
- **ANCS/AMS**：手环作为 GATT **client** 连 iPhone（ANCS UUID `app_gattc.c:696-697`；AMS 特征 0x81D8/0xABCE/0xF38C `miwear_type.h:671-674`），不是手环对外服务。
- 标准 GAP(0x1800)/GATT(0x1801) 由底层 BLE host 栈自带，应用层无注册。

## 8. 置信度说明

- **高置信（源码直读）**：0xFE95/0x0050/0x005E/0x005F、0xFD2D/CF07/CF08、微信支付与 LC 的 128-bit UUID、各未启用服务的定义。
- **中高置信（反汇编取证）**：0x0050 读值 `03 02 07`；SAR V2 帧 magic 0xA5A5。
- **中置信**：SAR 帧头精确位域来自 strings+反汇编拼接，需抓包验证。
- libmiwear.a 中未发现任何带标准 base（…5F9B34FB）的 128-bit GATT UUID → 不存在源码之外的隐藏 GATT 服务；库内 0xFE7B/0xFE88/0xFE95/0xFEA2 等字节串是 mibeacon spec 对象 ID 表（siid/piid/eiid，`app_spec.c`），**不是 GATT UUID**。