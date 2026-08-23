package com.longcheer.agent.dispatch;

import java.util.Map;

/**
 * 服务器命令分发器接口（SDD §3.1 / §7.4）。
 */
public interface CommandDispatcher {

    void dispatch(Map<String, Object> command);
}
