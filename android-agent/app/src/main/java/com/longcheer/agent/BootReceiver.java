package com.longcheer.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.longcheer.agent.config.StartParams;
import com.longcheer.agent.log.AgentLog;

import java.util.HashMap;
import java.util.Map;

/**
 * 开机自启（脱网独立运营）：BOOT_COMPLETED 时若已配置服务器参数且开启自启开关，
 * 直接拉起前台服务（Android 12+ 对开机广播启动前台服务有豁免）。
 *
 * <p>厂商阉割（部分 ROM 拦截开机自启）属真机验证项。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE);
        Map<String, String> stored = new HashMap<>();
        for (String key : new String[]{StartParams.KEY_SERVER_HOST, StartParams.KEY_SERVER_PORT,
                StartParams.KEY_DEVICE_ID}) {
            String v = prefs.getString(key, null);
            if (v != null) {
                stored.put(key, v);
            }
        }
        stored.put(StartParams.KEY_SIMULATE_DUT,
                String.valueOf(prefs.getBoolean(StartParams.KEY_SIMULATE_DUT, false)));
        stored.put(StartParams.KEY_AUTO_START,
                String.valueOf(prefs.getBoolean(StartParams.KEY_AUTO_START, false)));

        StartParams params = StartParams.resolve(null, stored);
        if (!params.autoStart || !params.isConfigured()) {
            AgentLog.i(TAG, "skip boot start: autoStart=" + params.autoStart
                    + " configured=" + params.isConfigured());
            return;
        }
        AgentLog.i(TAG, "boot auto-start agent -> " + params.serverHost + ":" + params.serverPort);
        Intent service = new Intent(context, AgentService.class).setAction(AgentService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
