# wireless-auto-test-android-agent

无限自动化测试框架的 Android 端 Agent（服务器 ↔ Android 手机 ↔ BLE 多 DUT）。

## 构建

```bash
# 本机无 wrapper；使用系统 gradle（兼容版本 8.1）与 android-sdk（local.properties 指向 sdk.dir）
gradle :app:assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest      # 全部单元测试
```

## 使用方式

### 脱网独立运营（测试机不连电脑）

1. 安装 APK 后，桌面出现「无线测试Agent」图标（控制台）；
2. 打开控制台：填服务器地址/端口/设备 ID（留空自动派生）→ 「保存配置」；
3. 按引导完成「蓝牙权限」与「省电白名单」授权；
4. 「启动服务」；需要时打开「开机自动启动服务」开关（重启后自动拉起，Android 12+ 支持）；
5. 控制台状态区实时显示 TCP 连接、设备数、READY 数与最近日志。

### ADB 拉起（联调/CI）

```bash
adb shell am startservice \
  -a com.longcheer.agent.ACTION_START \
  -n com.longcheer.agent/.AgentService \
  --es server_host <IP> --ei server_port 10409 \
  --es device_id phone-01
# 模拟器/无蓝牙环境（虚拟 DUT 模式）：追加 --ez simulateDut true
```

停止：`am startservice -a com.longcheer.agent.ACTION_STOP -n com.longcheer.agent/.AgentService`。

### Mock 服务器与模拟器冒烟

```bash
python3 ../tools/mock_server.py --port 10409 --dut-mac AA:BB:CC:DD:EE:FF   # 交互式
python3 ../tools/mock_server.py --selftest                                 # 协议自测
../tools/emulator_smoke.sh                                                 # 模拟器一键冒烟（需 KVM 权限）
```

## 结构

`com.longcheer.agent`：AgentService（前台服务入口）/ ConsoleActivity（控制台）/
AgentAssembler（生产装配）/ tcp / ble / registry / schedule / poll / transfer / report /
config / log / model。设计依据：仓库根目录《无限自动化框架SDD_V1.6.md》。
