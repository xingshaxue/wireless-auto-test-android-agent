package com.longcheer.agent.poll;

import com.longcheer.agent.model.FieldMapping;
import com.longcheer.agent.model.PollRule;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 轮询结果处理链接口（SDD §7.3.2 / §16.1）：Decoder → RuleEngine → ActionExecutor，
 * 轮询与 GATT 通知共用（§7.3.3）。
 */
public interface PollResultChain {

    Map<String, Object> decode(String mac, Map<UUID, byte[]> raw);

    List<PollRule.RuleAction> evaluate(String mac, Map<String, Object> fields);

    void execute(String mac, List<PollRule.RuleAction> actions);

    void registerHandler(String deviceType, PollResultHandler handler);

    // ---- M3 扩展：设备配置装载与通知通道 ----

    /**
     * 装载/整项替换设备的字段映射与规则集（含设备类型与 profile，供 handler 路由与
     * ServiceResolver 解析）。规则引用的字段必须存在映射，否则拒绝装载并上报配置错误
     * （§7.3.2 / §16.4）。
     *
     * @return true = 已装载；false = 校验失败被拒绝
     */
    boolean updateDeviceConfig(String mac, String deviceType,
                               List<FieldMapping> fields, List<PollRule> rules,
                               Map<UUID, UUID> profile);

    /**
     * 整集替换设备规则集（SET_POLL_RULES，§A.2）。校验同上。
     *
     * @return true = 已装载；false = 校验失败被拒绝
     */
    boolean setPollRules(String mac, List<PollRule> rules);

    /**
     * 完整轮询处理链：decode → evaluate → execute → 兜底 handler。
     *
     * @return 解析后的字段值（供缓存与上报决策）
     */
    Map<String, Object> process(String mac, Map<UUID, byte[]> raw);

    /**
     * GATT 通知入口（§7.3.3）：设 notifyBoostUntil 保槽、走处理链、按
     * notifyMinReportIntervalMs 节流上报。
     */
    void onNotification(String mac, UUID charUuid, byte[] value);

    /**
     * 解析特征的所属服务（§16.4 profile）：无映射返回 null
     * （CONFIGURING 订阅/执行侧按配置错误 1003 处理）。
     */
    UUID resolveService(String mac, UUID charUuid);
}
