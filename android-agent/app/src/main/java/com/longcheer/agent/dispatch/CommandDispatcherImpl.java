package com.longcheer.agent.dispatch;

import com.longcheer.agent.ble.BleCentralManager;
import com.longcheer.agent.ble.DeviceController;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * CommandDispatcher 基础实现。
 * M1 阶段：解析服务器命令并转发到对应模块；完整命令处理与 ACK 细节留 TODO。
 */
public class CommandDispatcherImpl implements CommandDispatcher {

    private final BleCentralManager bleCentralManager;
    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final PollingScheduler pollingScheduler;
    private final StateReporter stateReporter;
    private final AgentConfig config;

    public CommandDispatcherImpl(BleCentralManager bleCentralManager,
                                 DeviceRegistry deviceRegistry,
                                 ConnectionScheduler connectionScheduler,
                                 PollingScheduler pollingScheduler,
                                 StateReporter stateReporter,
                                 AgentConfig config) {
        this.bleCentralManager = bleCentralManager;
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.pollingScheduler = pollingScheduler;
        this.stateReporter = stateReporter;
        this.config = config;
    }

    @Override
    public void dispatch(Map<String, Object> command) {
        String type = commandType(command);
        String requestId = stringValue(command.get("requestId"));
        String mac = stringValue(command.get("deviceMac"));

        switch (type) {
            case "CONNECT_DEVICE":
                handleConnectDevice(command, requestId, mac);
                break;
            case "DISCONNECT_DEVICE":
                handleDisconnectDevice(requestId, mac);
                break;
            case "READ_CHAR":
                handleReadChar(command, requestId, mac);
                break;
            case "WRITE_CHAR":
                handleWriteChar(command, requestId, mac);
                break;
            case "START_POLLING":
                handleStartPolling(requestId, mac);
                break;
            case "STOP_POLLING":
                handleStopPolling(requestId, mac);
                break;
            case "SET_POLLING_INTERVAL":
                handleSetPollingInterval(command, requestId, mac);
                break;
            case "GET_STATUS":
                handleGetStatus(requestId, mac);
                break;
            case "RESET":
                handleReset(requestId);
                break;
            case "SET_MAX_CONNECTIONS":
                handleSetMaxConnections(command, requestId);
                break;
            case "SET_PERSISTENT_DEVICE":
                handleSetPersistent(command, requestId, mac);
                break;
            case "PAUSE_DEVICE":
                handlePauseDevice(command, requestId, mac);
                break;
            case "RESUME_DEVICE":
                handleResumeDevice(requestId, mac);
                break;
            case "REMOVE_DEVICE":
                handleRemoveDevice(requestId, mac);
                break;
            default:
                stateReporter.reportCommandAck(requestId, 2002, "unknown command: " + type);
                break;
        }
    }

    private void handleConnectDevice(Map<String, Object> command, String requestId, String mac) {
        if (mac == null || mac.isEmpty()) {
            stateReporter.reportCommandAck(requestId, 2001, "missing deviceMac");
            return;
        }
        String deviceId = stringValue(command.get("deviceId"));
        boolean lazyConnect = booleanValue(command.get("lazyConnect"), false);
        DeviceController controller = bleCentralManager.createController(mac, deviceId);
        if (!lazyConnect) {
            connectionScheduler.requestSlot(
                    ConnectionRequest.now(mac, GattCommand.Priority.HIGH.ordinal(), ConnectionRequest.Reason.COMMAND));
        }
        stateReporter.reportCommandAck(requestId, 0, null);
        // TODO M1: CONNECT_DEVICE 的 ACK 语义为"设备已就绪"；当前骨架阶段简化回 0。
    }

    private void handleDisconnectDevice(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        connectionScheduler.cancelRequest(mac);
        controller.onSlotReleased();
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleReadChar(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        UUID service = uuidValue(command.get("service"));
        UUID characteristic = uuidValue(command.get("char"));
        if (characteristic == null) {
            stateReporter.reportCommandAck(requestId, 2001, "missing char");
            return;
        }
        GattCommand cmd = GattCommand.simple(mac, requestId, GattCommand.Type.READ, service, characteristic,
                null, GattCommand.Priority.HIGH);
        controller.enqueueCommand(cmd);
        stateReporter.reportCommandAck(requestId, 0, "queued");
    }

    private void handleWriteChar(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        UUID service = uuidValue(command.get("service"));
        UUID characteristic = uuidValue(command.get("char"));
        if (characteristic == null) {
            stateReporter.reportCommandAck(requestId, 2001, "missing char");
            return;
        }
        String payloadB64 = stringValue(command.get("payload"));
        byte[] payload = payloadB64 == null ? null : java.util.Base64.getDecoder().decode(payloadB64);
        GattCommand cmd = GattCommand.simple(mac, requestId, GattCommand.Type.WRITE, service, characteristic,
                payload, GattCommand.Priority.HIGH);
        controller.enqueueCommand(cmd);
        stateReporter.reportCommandAck(requestId, 0, "queued");
    }

    private void handleStartPolling(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        // TODO M1: 标记设备启用轮询；当前骨架阶段立即触发一次连接请求。
        connectionScheduler.requestSlot(
                ConnectionRequest.now(mac, GattCommand.Priority.NORMAL.ordinal(), ConnectionRequest.Reason.POLL));
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleStopPolling(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        // TODO M1: 标记设备禁用轮询。
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleSetPollingInterval(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        Object intervalRaw = command.get("intervalMs");
        long intervalMs = intervalRaw instanceof Number ? ((Number) intervalRaw).longValue() : 0;
        if (intervalMs < 200) {
            stateReporter.reportCommandAck(requestId, 2001, "intervalMs must be >= 200");
            return;
        }
        PollingConfig old = controller.snapshot().getPollingConfig();
        PollingConfig updated = new PollingConfig(intervalMs,
                old.getReadCharacteristics(), old.getNotifyCharacteristics(), old.isReportOnlyChanged());
        controller.setPollingConfig(updated);
        pollingScheduler.updateConfig(mac, updated);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleGetStatus(String requestId, String mac) {
        // TODO M1: 组装整机 + 设备快照；当前返回简化结果。
        Map<String, Object> result = Collections.singletonMap("devicesManaged", deviceRegistry.size());
        stateReporter.reportCommandAck(requestId, 0, result);
    }

    private void handleReset(String requestId) {
        // TODO M1: 软重置完整流程（断开全部连接、清队列、状态回 REGISTERED）。
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleSetMaxConnections(Map<String, Object> command, String requestId) {
        Object maxRaw = command.get("maxSlots");
        if (!(maxRaw instanceof Number)) {
            stateReporter.reportCommandAck(requestId, 2001, "missing maxSlots");
            return;
        }
        int max = ((Number) maxRaw).intValue();
        try {
            connectionScheduler.setMaxSlots(max);
            stateReporter.reportCommandAck(requestId, 0, null);
        } catch (IllegalArgumentException e) {
            stateReporter.reportCommandAck(requestId, 3003, e.getMessage());
        }
    }

    private void handleSetPersistent(Map<String, Object> command, String requestId, String mac) {
        if (mac == null || mac.isEmpty()) {
            stateReporter.reportCommandAck(requestId, 2001, "missing deviceMac");
            return;
        }
        boolean on = booleanValue(command.get("on"), false);
        connectionScheduler.setPersistent(mac, on);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handlePauseDevice(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        boolean abortTransfer = booleanValue(command.get("abortTransfer"), false);
        controller.pause(abortTransfer);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleResumeDevice(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        controller.resume();
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleRemoveDevice(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 0, null);
            return;
        }
        connectionScheduler.cancelRequest(mac);
        controller.terminate();
        bleCentralManager.destroyController(mac);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private static String commandType(Map<String, Object> command) {
        Object type = command.get("type");
        return type instanceof String ? (String) type : "";
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return defaultValue;
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
