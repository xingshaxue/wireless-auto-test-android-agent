package com.longcheer.agent.poll;

import java.util.Map;

/**
 * 复杂逻辑兜底接口（SDD §7.3.2）：声明式规则覆盖不了的逻辑
 * （多字段联合计算、时序判断、与外部状态联动）由代码实现，按 DUT 类型注册，
 * 与规则引擎串在同一处理链上（规则动作执行后调用）。
 */
public interface PollResultHandler {

    /**
     * @param mac    设备 MAC
     * @param fields Decoder 解析出的字段值
     */
    void handle(String mac, Map<String, Object> fields);
}
