package com.longcheer.agent.dispatch;

import com.longcheer.agent.ble.BleCentralManager;
import com.longcheer.agent.log.AgentLog;
import com.longcheer.agent.model.DeviceController;
import com.longcheer.agent.config.AgentConfig;
import com.longcheer.agent.model.ConnectionRequest;
import com.longcheer.agent.model.GattCommand;
import com.longcheer.agent.model.PollingConfig;
import com.longcheer.agent.registry.DeviceRegistry;
import com.longcheer.agent.report.StateReporter;
import com.longcheer.agent.schedule.ConnectionScheduler;
import com.longcheer.agent.schedule.PollingScheduler;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CommandDispatcher 基础实现。
 * M1 阶段：解析服务器命令并转发到对应模块；完整命令处理与 ACK 细节留 TODO。
 */
public class CommandDispatcherImpl implements CommandDispatcher {

    private static final String TAG = "CommandDispatcher";

    private final BleCentralManager bleCentralManager;
    private final DeviceRegistry deviceRegistry;
    private final ConnectionScheduler connectionScheduler;
    private final PollingScheduler pollingScheduler;
    private final StateReporter stateReporter;
    private final AgentConfig config;
    private final com.longcheer.agent.poll.PollResultChain pollResultChain;
    private final com.longcheer.agent.transfer.FileTransferManager fileTransferManager;
    private final com.longcheer.agent.transfer.FileExportManager fileExportManager;

    public CommandDispatcherImpl(BleCentralManager bleCentralManager,
                                 DeviceRegistry deviceRegistry,
                                 ConnectionScheduler connectionScheduler,
                                 PollingScheduler pollingScheduler,
                                 StateReporter stateReporter,
                                 AgentConfig config,
                                 com.longcheer.agent.poll.PollResultChain pollResultChain,
                                 com.longcheer.agent.transfer.FileTransferManager fileTransferManager) {
        this(bleCentralManager, deviceRegistry, connectionScheduler, pollingScheduler,
                stateReporter, config, pollResultChain, fileTransferManager, null);
    }

    public CommandDispatcherImpl(BleCentralManager bleCentralManager,
                                 DeviceRegistry deviceRegistry,
                                 ConnectionScheduler connectionScheduler,
                                 PollingScheduler pollingScheduler,
                                 StateReporter stateReporter,
                                 AgentConfig config,
                                 com.longcheer.agent.poll.PollResultChain pollResultChain,
                                 com.longcheer.agent.transfer.FileTransferManager fileTransferManager,
                                 com.longcheer.agent.transfer.FileExportManager fileExportManager) {
        this.bleCentralManager = bleCentralManager;
        this.deviceRegistry = deviceRegistry;
        this.connectionScheduler = connectionScheduler;
        this.pollingScheduler = pollingScheduler;
        this.stateReporter = stateReporter;
        this.config = config;
        this.pollResultChain = pollResultChain;
        this.fileTransferManager = fileTransferManager;
        this.fileExportManager = fileExportManager;
    }

    @Override
    public void dispatch(Map<String, Object> command) {
        String type = commandType(command);
        String requestId = stringValue(command.get("requestId"));
        String mac = stringValue(command.get("deviceMac"));
        com.longcheer.agent.log.AgentLog.i(TAG, "dispatch " + type
                + (mac == null ? "" : " mac=" + mac)
                + (requestId == null ? "" : " reqId=" + requestId));

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
            case "SET_POLL_RULES":
                handleSetPollRules(command, requestId, mac);
                break;
            case "FILE_TRANSFER":
                handleFileTransfer(command, requestId, mac);
                break;
            case "FILE_CANCEL":
                handleFileCancel(command, requestId);
                break;
            case "FILE_EXPORT":
                handleFileExport(command, requestId, mac);
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
            case "SET_TRANSFER_CHANNEL":
                handleSetTransferChannel(command, requestId, mac);
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
        if (lazyConnect) {
            // §7.2：lazyConnect=true 仅注册不连接，立即回 ACK。
            stateReporter.reportCommandAck(requestId, 0, null);
            return;
        }
        if (controller.isReady()) {
            stateReporter.reportCommandAck(requestId, 0, null); // 已 READY，幂等（§7.2 第 9 条）
            return;
        }
        // §7.2：ACK 语义为"设备已就绪"——挂起 requestId，READY 时回 0、最终失败回 1xxx。
        if (controller instanceof com.longcheer.agent.ble.DeviceControllerImpl) {
            ((com.longcheer.agent.ble.DeviceControllerImpl) controller).setPendingConnectAck(requestId);
        } else {
            // 非真实实现（Stub）无挂起能力：维持骨架行为回 0。
            stateReporter.reportCommandAck(requestId, 0, null);
        }
        connectionScheduler.requestSlot(
                ConnectionRequest.now(mac, ConnectionRequest.PRIORITY_HIGH, ConnectionRequest.Reason.COMMAND));
    }

    private void handleDisconnectDevice(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        connectionScheduler.cancelRequest(mac);
        // 释放槽位 + 移出活动池 + 设备转 DISCONNECTED 由调度器联动完成（修复 M1 槽位泄漏）。
        connectionScheduler.releaseSlot(mac);
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
        enqueueCommandWithBackpressure(controller, cmd, requestId, mac);
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
        cmd.writeNoResponse = "NO_RESPONSE".equalsIgnoreCase(stringValue(command.get("writeType")));
        enqueueCommandWithBackpressure(controller, cmd, requestId, mac);
    }

    /**
     * READ_CHAR/WRITE_CHAR 入队（§7.4）：ACK 语义为"执行结果"，入队时不应答——
     * 执行完成后由 GattExecutor 结果回调回 CMD_ACK（成功携带读值，失败 1xxx+rawStatus）。
     * 此处仅处理"未执行"类即时 ACK：队列满 3001（§7.4.1）；TTL 挂到命令上，
     * 过期由执行侧丢弃并回 3002。
     */
    private void enqueueCommandWithBackpressure(DeviceController controller, GattCommand cmd,
                                                String requestId, String mac) {
        long ttlMs = config.getCommandTtlMs();
        if (ttlMs > 0) {
            cmd.expireTime = cmd.getEnqueueTime() + ttlMs;
        }
        boolean accepted = controller.enqueueCommand(cmd);
        if (!accepted) {
            // §7.4.1：队列满（或设备已终止）→ 立即回 3001 QUEUE_FULL，requestId 不悬挂。
            stateReporter.reportCommandAck(requestId, 3001, "queue full");
            return;
        }
        requestSlotIfNotReady(controller, mac);
    }

    /** §7.4：设备未连接时命令入队后转 WAITING_SLOT 并申请连接槽（HIGH 优先级）。 */
    private void requestSlotIfNotReady(DeviceController controller, String mac) {
        if (!controller.isReady()) {
            connectionScheduler.requestSlot(
                    ConnectionRequest.now(mac, ConnectionRequest.PRIORITY_HIGH, ConnectionRequest.Reason.COMMAND));
        }
    }

    private void handleStartPolling(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        // TODO M1: 标记设备启用轮询；当前骨架阶段立即触发一次连接请求。
        connectionScheduler.requestSlot(
                ConnectionRequest.now(mac, ConnectionRequest.PRIORITY_NORMAL, ConnectionRequest.Reason.POLL));
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

    /** SET_POLL_RULES（§A.2 / §7.3.2）：整集替换设备规则；字段映射缺失则拒绝并回 2001。 */
    @SuppressWarnings("unchecked")
    private void handleSetPollRules(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        Object rulesRaw = command.get("rules");
        if (!(rulesRaw instanceof List)) {
            stateReporter.reportCommandAck(requestId, 2001, "missing rules");
            return;
        }
        List<com.longcheer.agent.model.PollRule> rules =
                com.longcheer.agent.poll.PollResultChainImpl.toPollRulesFromMaps((List<Object>) rulesRaw);
        boolean accepted = pollResultChain.setPollRules(mac, rules);
        if (!accepted) {
            // 校验失败已在链路上报 ERROR；ACK 回配置/协议错误码。
            stateReporter.reportCommandAck(requestId, 2001, "rule references unmapped field");
            return;
        }
        controller.setPollRules(rules);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    /**
     * FILE_TRANSFER（§7.6 / §A.2）：受理文件传输任务。
     * ACK 语义为"已受理"；下载/传输结果经 FILE_DOWNLOAD_* 与 FILE_RESULT 事件上报。
     */
    private void handleFileTransfer(Map<String, Object> command, String requestId, String mac) {
        String taskId = stringValue(command.get("taskId"));
        String fileId = stringValue(command.get("fileId"));
        Object sizeRaw = command.get("size");
        if (taskId == null || fileId == null || mac == null || !(sizeRaw instanceof Number)) {
            stateReporter.reportCommandAck(requestId, 2001, "missing taskId/fileId/deviceMac/size");
            return;
        }
        String sha256B64 = stringValue(command.get("sha256"));
        byte[] sha256 = null;
        if (sha256B64 != null) {
            try {
                sha256 = java.util.Base64.getDecoder().decode(sha256B64);
            } catch (IllegalArgumentException e) {
                stateReporter.reportCommandAck(requestId, 2001, "invalid sha256 base64");
                return;
            }
        }
        Object windowRaw = command.get("windowSize");
        int windowSize = windowRaw instanceof Number ? ((Number) windowRaw).intValue() : 64;
        Object chunkRaw = command.get("chunkSize");
        // 默认 509 = 512 MTU − 3（§7.6）；实际 MTU 协商值接入后由 BLE 里程碑修正。
        int chunkSize = chunkRaw instanceof Number ? ((Number) chunkRaw).intValue() : 509;

        int errorCode = fileTransferManager == null ? 2001
                : fileTransferManager.startTransfer(taskId, fileId, mac,
                        ((Number) sizeRaw).longValue(), sha256, chunkSize, windowSize,
                        stringValue(command.get("fileName")));
        stateReporter.reportCommandAck(requestId, errorCode, null);
    }

    /** FILE_CANCEL（§A.2）：taskId 必填；取消任意时刻生效，幂等。 */
    private void handleFileCancel(Map<String, Object> command, String requestId) {
        String taskId = stringValue(command.get("taskId"));
        if (taskId == null) {
            stateReporter.reportCommandAck(requestId, 2001, "missing taskId");
            return;
        }
        if (fileTransferManager != null) {
            fileTransferManager.cancelTransfer(taskId);
        }
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    /**
     * FILE_EXPORT（docs/02 B.6）：从设备拉取文件。remotePath 以 "/" 结尾 = 目录模式；
     * exportId 缺省生成 "export-<8位hex>"。ACK 即时（受理语义），结果走 EXPORT_RESULT 事件。
     */
    private void handleFileExport(Map<String, Object> command, String requestId, String mac) {
        String remotePath = stringValue(command.get("remotePath"));
        if (mac == null || remotePath == null || remotePath.isEmpty()) {
            stateReporter.reportCommandAck(requestId, 2001, "missing deviceMac/remotePath");
            return;
        }
        String exportId = stringValue(command.get("exportId"));
        if (exportId == null || exportId.isEmpty()) {
            exportId = "export-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        int errorCode = fileExportManager == null ? 2001
                : fileExportManager.startExport(exportId, mac, remotePath);
        Map<String, Object> result = errorCode == 0
                ? Collections.singletonMap("exportId", exportId) : null;
        stateReporter.reportCommandAck(requestId, errorCode, result);
    }

    /** PAUSE_DEVICE 与 pinned 传输的交互（§7.7）：默认挂起，abortTransfer=true 中止。 */
    private void pauseTransferIfAny(String mac, boolean abortTransfer) {
        if (fileTransferManager != null) {
            fileTransferManager.pauseTransferForDevice(mac, abortTransfer);
        }
    }

    private void handleGetStatus(String requestId, String mac) {
        // TODO M1: 组装整机 + 设备快照；当前返回简化结果。
        Map<String, Object> result = Collections.singletonMap("devicesManaged", deviceRegistry.size());
        stateReporter.reportCommandAck(requestId, 0, result);
    }

    private void handleReset(String requestId) {
        // §7.8 软重置：立即回 ACK（已受理），随后执行清理。
        stateReporter.reportCommandAck(requestId, 0, null);

        // 1) 中止全部文件传输任务（逐任务上报 FILE_RESULT cancelled）。
        if (fileTransferManager != null) {
            fileTransferManager.cancelAll();
        }
        // 2) 全部设备：断开（释放槽位）、丢弃队列前逐条回 CMD_ACK 2004（§12.9，不悬挂 requestId）。
        for (DeviceController controller : deviceRegistry.allControllers()) {
            String mac = controller.snapshot().getMac();
            connectionScheduler.releaseSlot(mac);
            for (com.longcheer.agent.model.QueuedTask task : controller.drainPendingCommands()) {
                if (task instanceof GattCommand) {
                    String cmdRequestId = ((GattCommand) task).getRequestId();
                    if (cmdRequestId != null) {
                        stateReporter.reportCommandAck(cmdRequestId, 2004, "command cancelled by RESET");
                    }
                }
                // 轮询任务无 requestId，直接丢弃（§7.7 第 4 条）
            }
            controller.reset(); // 状态机回 REGISTERED、重连计数清零
        }
        // 3) 清空连接请求队列、轮询计划重置；注册表与 AgentConfig 保留（§7.8 第 5 条）。
        connectionScheduler.cancelAllRequests();
        pollingScheduler.resetAll();
        AgentLog.i(TAG, "RESET done: all devices soft-reset");
    }

    private void handleSetMaxConnections(Map<String, Object> command, String requestId) {
        Object maxRaw = command.get("maxSlots");
        if (!(maxRaw instanceof Number)) {
            stateReporter.reportCommandAck(requestId, 2001, "missing maxSlots");
            return;
        }
        int max = ((Number) maxRaw).intValue();
        if (max < 2 || max > 5) {
            stateReporter.reportCommandAck(requestId, 3003, "maxSlots must be in [2,5]");
            return;
        }
        // §5.4：驱逐失败（新上限低于常驻 + pinned 数）拒绝调整，回 3003 并携带 required/max。
        boolean accepted = connectionScheduler.setMaxSlots(max);
        if (!accepted) {
            Map<String, Object> result = new HashMap<>();
            result.put("required", connectionScheduler.requiredSlots());
            result.put("max", max);
            stateReporter.reportCommandAck(requestId, 3003, result);
            return;
        }
        stateReporter.reportCommandAck(requestId, 0, null);
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

    /**
     * SET_TRANSFER_CHANNEL（§16.4 运行中更新）：传输通道整项替换（ble/spp/auto）。
     * 写 Controller 快照即时生效——文件传输/导出都在任务启动时从快照读通道
     * （FileTransferManager 适配器工厂 / FileExportManager 选路），进行中的任务不换通道。
     */
    private void handleSetTransferChannel(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        String channel = stringValue(command.get("channel"));
        if (!com.longcheer.agent.config.DeviceConfig.TRANSFER_CHANNEL_BLE.equals(channel)
                && !com.longcheer.agent.config.DeviceConfig.TRANSFER_CHANNEL_SPP.equals(channel)
                && !com.longcheer.agent.config.DeviceConfig.TRANSFER_CHANNEL_AUTO.equals(channel)) {
            stateReporter.reportCommandAck(requestId, 2001, "channel must be ble/spp/auto");
            return;
        }
        controller.setTransferChannel(channel);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handlePauseDevice(Map<String, Object> command, String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        boolean abortTransfer = booleanValue(command.get("abortTransfer"), false);
        pauseTransferIfAny(mac, abortTransfer);
        controller.pause(abortTransfer);
        stateReporter.reportCommandAck(requestId, 0, null);
    }

    private void handleResumeDevice(String requestId, String mac) {
        DeviceController controller = deviceRegistry.findByMac(mac);
        if (controller == null) {
            stateReporter.reportCommandAck(requestId, 2003, "device not found");
            return;
        }
        if (fileTransferManager != null) {
            fileTransferManager.resumeTransferForDevice(mac);
        }
        connectionScheduler.resetReconnectAttempts(mac);
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
        connectionScheduler.releaseSlot(mac);
        // §7.7 第 4 条：丢弃命令前逐条回 CMD_ACK 2004，保证服务器 requestId 对账不悬挂。
        for (com.longcheer.agent.model.QueuedTask task : controller.drainPendingCommands()) {
            if (task instanceof GattCommand) {
                String cmdRequestId = ((GattCommand) task).getRequestId();
                if (cmdRequestId != null) {
                    stateReporter.reportCommandAck(cmdRequestId, 2004, "command cancelled by REMOVE_DEVICE");
                }
            }
        }
        // §7.7 第 5 条：进行中文件传输中止并上报 FILE_RESULT（cancelled）。
        if (fileTransferManager != null) {
            fileTransferManager.pauseTransferForDevice(mac, true);
        }
        // terminate 统一由 destroyController 内部完成（→ TERMINATED 并上报 DEVICE_STATE、
        // 注册表注销、GATT 关闭，与 §16.4 applyConfig 移除路径对齐）；显式再调一次会
        // 触发 TERMINATED→TERMINATED 非法迁移（§4.1 终态不可迁出）。
        bleCentralManager.destroyController(mac);
        // §7.7：下线设备的轮询配置同步清理，防 tick 钳制/查找引用已移除设备。
        pollingScheduler.removeConfig(mac);
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
