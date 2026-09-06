package com.longcheer.agent.poll;

import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.PollRule;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * ActionExecutor（SDD §7.3.2）：执行命中规则的动作。
 *
 * <p>六类动作：REPORT_EVENT / EXECUTE_GATT / SET_INTERVAL / SET_DEVICE_STATE /
 * RELEASE_SLOT / FILE_TRANSFER（经 FILE_REQUEST 上行事件取文件）。</p>
 */
public class ActionExecutor {

    private static final String TAG = "ActionExecutor";

    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final PollingScheduler pollingScheduler;
    private final StateReporter stateReporter;
    private final LongSupplier clock;

    public ActionExecutor(DeviceRegistry deviceRegistry,
                          ConnectionScheduler connectionScheduler,
                          PollingScheduler pollingScheduler,
                          StateReporter stateReporter,
                          LongSupplier clock) {
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.pollingScheduler = pollingScheduler;
        this.stateReporter = stateReporter;
        this.clock = clock;
    }

    public void execute(String mac, List<PollRule.RuleAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (PollRule.RuleAction action : actions) {
            if (action == null || action.type == null) {
                continue;
            }
            try {
                executeOne(mac, action);
            } catch (RuntimeException e) {
                // 单个动作失败不影响其余动作执行。
                AgentLog.w(TAG, "action " + action.type + " failed for " + mac + ": " + e.getMessage());
            }
        }
    }

    private void executeOne(String mac, PollRule.RuleAction action) {
        Map<String, Object> params = action.params == null
                ? new HashMap<>() : action.params;
        AgentLog.i(TAG, "execute " + action.type + " for " + mac + " params=" + params.keySet());
        switch (action.type) {
            case REPORT_EVENT:
                handleReportEvent(mac, params);
                break;
            case EXECUTE_GATT:
                handleExecuteGatt(mac, params);
                break;
            case SET_INTERVAL:
                handleSetInterval(mac, params);
                break;
            case SET_DEVICE_STATE:
                handleSetDeviceState(mac, params);
                break;
            case RELEASE_SLOT:
                connectionScheduler.releaseSlot(mac);
                break;
            case FILE_TRANSFER:
                handleFileTransfer(mac, params);
                break;
            default:
                break;
        }
    }

    /** REPORT_EVENT：上报事件/告警（params.event 为事件名）。 */
    private void handleReportEvent(String mac, Map<String, Object> params) {
        Object event = params.get("event");
        if (!(event instanceof String) || ((String) event).isEmpty()) {
            AgentLog.w(TAG, "REPORT_EVENT missing event name, mac=" + mac);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceMac", mac);
        Object message = params.get("message");
        if (message != null) {
            payload.put("message", message);
        }
        stateReporter.report((String) event, payload);
    }

    /** EXECUTE_GATT：触发一次 GATT 操作（如温度过高写入关闭命令）。 */
    private void handleExecuteGatt(String mac, Map<String, Object> params) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            AgentLog.w(TAG, "EXECUTE_GATT device not found: " + mac);
            return;
        }
        UUID service = uuidValue(params.get("service"));
        UUID characteristic = uuidValue(params.get("char"));
        if (characteristic == null) {
            AgentLog.w(TAG, "EXECUTE_GATT missing char, mac=" + mac);
            return;
        }
        GattCommand.Type type = "READ".equalsIgnoreCase(stringValue(params.get("type")))
                ? GattCommand.Type.READ : GattCommand.Type.WRITE;
        byte[] payload = null;
        String payloadB64 = stringValue(params.get("payload"));
        if (payloadB64 != null) {
            payload = java.util.Base64.getDecoder().decode(payloadB64);
        }
        // 规则触发的本地自治操作（如超温关断）需要尽快执行，用 HIGH 优先于排队轮询。
        controller.enqueueCommand(
                GattCommand.simple(mac, null, type, service, characteristic, payload,
                        GattCommand.Priority.HIGH));
        if (!controller.isReady()) {
            connectionScheduler.requestSlot(
                    ConnectionRequest.now(mac, ConnectionRequest.PRIORITY_EVENT,
                            ConnectionRequest.Reason.EVENT));
        }
    }

    /** SET_INTERVAL：动态修改本设备轮询间隔（§7.3.2，整项替换其余字段保持）。 */
    private void handleSetInterval(String mac, Map<String, Object> params) {
        Object raw = params.get("intervalMs");
        if (!(raw instanceof Number)) {
            AgentLog.w(TAG, "SET_INTERVAL missing intervalMs, mac=" + mac);
            return;
        }
        long intervalMs = ((Number) raw).longValue();
        if (intervalMs < 200) {
            AgentLog.w(TAG, "SET_INTERVAL below 200ms rejected: " + intervalMs + ", mac=" + mac);
            return;
        }
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            AgentLog.w(TAG, "SET_INTERVAL device not found: " + mac);
            return;
        }
        PollingConfig old = controller.snapshot().getPollingConfig();
        if (old == null) {
            AgentLog.w(TAG, "SET_INTERVAL no polling config, mac=" + mac);
            return;
        }
        PollingConfig updated = new PollingConfig(intervalMs,
                old.getReadCharacteristics(), old.getNotifyCharacteristics(), old.isReportOnlyChanged());
        controller.setPollingConfig(updated);
        pollingScheduler.updateConfig(mac, updated);
    }

    /** SET_DEVICE_STATE：写业务标记 stateFlag，不动状态机（§7.3.2）。 */
    private void handleSetDeviceState(String mac, Map<String, Object> params) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            AgentLog.w(TAG, "SET_DEVICE_STATE device not found: " + mac);
            return;
        }
        String flag = stringValue(params.get("state"));
        if (flag == null) {
            flag = stringValue(params.get("flag"));
        }
        controller.setStateFlag(flag);
    }

    /** FILE_TRANSFER：规则命中后上行 FILE_REQUEST 请求文件（§7.3.2 / §10.2）。 */
    private void handleFileTransfer(String mac, Map<String, Object> params) {
        String fileId = stringValue(params.get("fileId"));
        if (fileId == null || fileId.isEmpty()) {
            AgentLog.w(TAG, "FILE_TRANSFER missing fileId, mac=" + mac);
            return;
        }
        String taskId = "auto-" + mac + "-" + clock.getAsLong();
        Map<String, Object> payload = new HashMap<>();
        payload.put("fileId", fileId);
        payload.put("taskId", taskId);
        payload.put("deviceMac", mac);
        stateReporter.report("FILE_REQUEST", payload);
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static UUID uuidValue(Object value) {
        if (value == null) return null;
        if (value instanceof UUID) return (UUID) value;
        try {
            String s = value.toString().trim();
            if (s.length() <= 8) {
                return UUID.fromString(String.format("0000%s-0000-1000-8000-00805f9b34fb", s));
            }
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
