package com.longcheer.agent;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.longcheer.agent.config.StartParams;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 控制台（脱网独立运营入口）：本机配置服务器参数、启停服务、查看状态与日志、
 * 引导蓝牙权限与省电白名单。
 *
 * <p>程序化 UI（不引 XML/第三方库）；调试与运营工具，不参与 SDD 协议面。</p>
 */
public class ConsoleActivity extends Activity {

    private static final String PREFS = "agent_prefs";
    private static final int LOG_TAIL_LINES = 15;
    private static final long STATUS_REFRESH_MS = 2000L;

    private EditText editHost;
    private EditText editPort;
    private EditText editDeviceId;
    private CheckBox checkSimulateDut;
    private CheckBox checkAutoStart;
    private TextView textStatus;
    private TextView textLog;

    private SharedPreferences prefs;
    private AgentService service;
    private boolean bound = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AgentService.AgentBinder) binder).getService();
            bound = true;
            refreshStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildContentView());
        loadPrefsIntoViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, AgentService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(statusTicker);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(statusTicker);
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    // ==================== UI ====================

    private View buildContentView() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        editHost = labeledEdit(root, "服务器地址（如 192.168.1.100）");
        editPort = labeledEdit(root, "端口（默认 10086）");
        editPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editDeviceId = labeledEdit(root, "设备 ID（留空自动用本机 ANDROID_ID 派生）");

        checkSimulateDut = new CheckBox(this);
        checkSimulateDut.setText("虚拟 DUT 模式（无蓝牙环境/CI 测试）");
        root.addView(checkSimulateDut);

        checkAutoStart = new CheckBox(this);
        checkAutoStart.setText("开机自动启动服务");
        root.addView(checkAutoStart);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row1, "保存配置", v -> saveViewsIntoPrefs());
        addButton(row1, "启动服务", v -> startAgentService());
        addButton(row1, "停止服务", v -> stopAgentService());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row2, "蓝牙权限", v -> requestBlePermissions());
        addButton(row2, "省电白名单", v -> requestBatteryWhitelist());
        root.addView(row2);

        textStatus = new TextView(this);
        textStatus.setPadding(0, pad, 0, pad);
        root.addView(textStatus);

        TextView logTitle = new TextView(this);
        logTitle.setText("最近日志");
        root.addView(logTitle);
        textLog = new TextView(this);
        textLog.setTextSize(10f);
        textLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(textLog);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private EditText labeledEdit(LinearLayout parent, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText edit = new EditText(this);
        parent.addView(edit);
        return edit;
    }

    private void addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(btn, lp);
        btn.setOnClickListener(listener);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 配置 ====================

    private void loadPrefsIntoViews() {
        editHost.setText(prefs.getString(StartParams.KEY_SERVER_HOST, ""));
        editPort.setText(prefs.getString(StartParams.KEY_SERVER_PORT, ""));
        editDeviceId.setText(prefs.getString(StartParams.KEY_DEVICE_ID, ""));
        checkSimulateDut.setChecked(prefs.getBoolean(StartParams.KEY_SIMULATE_DUT, false));
        checkAutoStart.setChecked(prefs.getBoolean(StartParams.KEY_AUTO_START, false));
    }

    private void saveViewsIntoPrefs() {
        prefs.edit()
                .putString(StartParams.KEY_SERVER_HOST, editHost.getText().toString().trim())
                .putString(StartParams.KEY_SERVER_PORT, editPort.getText().toString().trim())
                .putString(StartParams.KEY_DEVICE_ID, editDeviceId.getText().toString().trim())
                .putBoolean(StartParams.KEY_SIMULATE_DUT, checkSimulateDut.isChecked())
                .putBoolean(StartParams.KEY_AUTO_START, checkAutoStart.isChecked())
                .apply();
        toast("配置已保存");
    }

    private void startAgentService() {
        saveViewsIntoPrefs();
        Intent intent = new Intent(this, AgentService.class)
                .setAction(AgentService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        toast("服务启动中…");
    }

    private void stopAgentService() {
        startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP));
        toast("停止指令已发送");
    }

    // ==================== 权限引导 ====================

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN}, 1001);
        } else {
            toast("当前系统版本无需运行时蓝牙权限");
        }
    }

    private void requestBatteryWhitelist() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开白名单设置：" + e.getMessage());
        }
    }

    // ==================== 状态与日志 ====================

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        StartParams stored = StartParams.resolve(null, prefsToMap());
        sb.append("服务器: ").append(stored.serverHost).append(':').append(stored.serverPort)
                .append(stored.isConfigured() ? "（已配置）" : "（未配置！）");
        if (service != null) {
            Map<String, Object> s = service.statusSummary();
            sb.append("\nTCP: ").append(Boolean.TRUE.equals(s.get("tcpConnected")) ? "已连接" : "未连接")
                    .append("  设备: ").append(s.get("devicesManaged"))
                    .append(" 台（READY ").append(s.get("devicesReady")).append("）")
                    .append("\n配置版本: ").append(s.get("configVersion"))
                    .append("  模拟DUT: ").append(s.get("simulateDut"));
        } else {
            sb.append("\n服务未绑定（未运行或未授权）");
        }
        textStatus.setText(sb.toString());
        textLog.setText(readLogTail());
    }

    private Map<String, String> prefsToMap() {
        Map<String, String> m = new java.util.HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() != null) {
                m.put(e.getKey(), e.getValue().toString());
            }
        }
        return m;
    }

    private String readLogTail() {
        try {
            File logFile = new File(getFilesDir(), "logs/agent.log");
            if (!logFile.exists()) {
                return "（暂无日志）";
            }
            List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath());
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            StringBuilder sb = new StringBuilder();
            for (String line : new ArrayList<>(lines.subList(from, lines.size()))) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "（日志读取失败：" + e.getMessage() + "）";
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
