#!/usr/bin/env bash
# emulator_smoke.sh — 模拟器无头冒烟：启动模拟器 → 装 APK → 虚拟 DUT 模式起 Agent
# → mock 服务器脚本化场景 → 校验关键事件序列。
#
# 用法：tools/emulator_smoke.sh [avd_name]   （默认 agent_test）
# 退出码：0 = SMOKE PASS；非 0 = 失败。
set -u

AVD="${1:-agent_test}"
SDK="${ANDROID_SDK_ROOT:-/home/xingshaxue/android-sdk}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$REPO_ROOT/android-agent/app/build/outputs/apk/debug/app-debug.apk"
MOCK_PORT=10086
DUT_MAC="AA:BB:CC:DD:EE:FF"
SMOKE_LOG="$(mktemp /tmp/mock_smoke.XXXXXX.log)"

echo "==> 构建 APK"
(cd "$REPO_ROOT/android-agent" && /home/xingshaxue/gradle-8.1/bin/gradle :app:assembleDebug --console=plain -q) || exit 2
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 2; }

echo "==> 启动模拟器（无头；ACCEL=on 需 KVM 权限，失败可用 ACCEL=off 慢速模式）"
"$ADB" start-server >/dev/null 2>&1
ACCEL="${ACCEL:-on}"
"$EMU" -avd "$AVD" -no-window -no-audio -no-snapshot -accel "$ACCEL" -gpu swiftshader_indirect \
    -read-only >/tmp/emulator_agent.log 2>&1 &
EMU_PID=$!
cleanup() {
    echo "==> 清理：关模拟器"
    "$ADB" -s emulator-5554 shell reboot -p >/dev/null 2>&1
    kill "$EMU_PID" >/dev/null 2>&1
}
trap cleanup EXIT

echo "==> 等待开机完成（accel=$ACCEL）"
BOOT=""
for i in $(seq 1 240); do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
        echo "模拟器进程退出，启动失败："; tail -15 /tmp/emulator_agent.log; exit 3
    fi
    BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$BOOT" = "1" ] && break
    sleep 3
done
[ "$BOOT" = "1" ] || { echo "模拟器开机超时"; tail -20 /tmp/emulator_agent.log; exit 3; }
echo "    开机完成"

echo "==> 安装并授权"
"$ADB" install -r "$APK" >/dev/null || { echo "安装失败"; exit 4; }
"$ADB" shell pm grant com.longcheer.agent android.permission.BLUETOOTH_CONNECT 2>/dev/null
"$ADB" shell pm grant com.longcheer.agent android.permission.BLUETOOTH_SCAN 2>/dev/null

echo "==> 启动 mock 服务器（smoke 模式）"
python3 "$REPO_ROOT/tools/mock_server.py" --smoke --port $MOCK_PORT --dut-mac "$DUT_MAC" \
    >"$SMOKE_LOG" 2>&1 &
MOCK_PID=$!
sleep 1

echo "==> 启动 Agent 前台服务（虚拟 DUT 模式）"
"$ADB" shell am startservice \
    -n com.longcheer.agent/.AgentService \
    --es serverHost 10.0.2.2 --ei serverPort $MOCK_PORT \
    --es token smoke-token --es deviceId emulator-phone \
    --ez simulateDut true || { echo "服务启动失败"; exit 5; }

echo "==> 等待冒烟场景跑完"
wait "$MOCK_PID"
SMOKE_RC=$?

echo "==> mock 服务器日志"
cat "$SMOKE_LOG"

echo "==> Agent 落盘日志（尾部 40 行）"
"$ADB" shell run-as com.longcheer.agent cat files/logs/agent.log 2>/dev/null | tail -40 \
    || "$ADB" logcat -d -s AgentService 2>/dev/null | tail -20

exit "$SMOKE_RC"
