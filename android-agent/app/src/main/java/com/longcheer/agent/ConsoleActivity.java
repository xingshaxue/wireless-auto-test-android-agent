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
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.longcheer.agent.config.StartParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 控制台（脱网独立运营入口）：只需配一次服务器地址与设备 ID，
 * 启停服务、查看 TCP 状态与受管设备实时状态，引导蓝牙权限与省电白名单。
 *
 * <p>程序化 UI（不引 XML/第三方库）；调试与运营工具，不参与 SDD 协议面。
 * 端口固定走 {@link StartParams#DEFAULT_PORT}，虚拟 DUT 仅经 adb extra/测试路径启用。</p>
 */
public class ConsoleActivity extends Activity {

    private static final String PREFS = "agent_prefs";
    private static final long STATUS_REFRESH_MS = 2000L;

    private EditText editHost;
    private EditText editDeviceId;
    private CheckBox checkAutoStart;
    private TextView textStatus;
    private TextView deviceTitle;
    private TextView deviceEmpty;
    private RecyclerView deviceRecycler;
    private DeviceCardAdapter deviceAdapter;

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
        editDeviceId = labeledEdit(root, "设备 ID（留空自动用本机 ANDROID_ID 派生）");

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row1, "保存配置", v -> saveViewsIntoPrefs());
        addButton(row1, "启动服务", v -> startAgentService());
        addButton(row1, "停止服务", v -> stopAgentService());
        root.addView(row1);

        checkAutoStart = new CheckBox(this);
        checkAutoStart.setText("开机自动启动服务");
        root.addView(checkAutoStart);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addButton(row2, "蓝牙权限", v -> requestBlePermissions());
        addButton(row2, "省电白名单", v -> requestBatteryWhitelist());
        root.addView(row2);

        textStatus = new TextView(this);
        textStatus.setPadding(0, pad, 0, 0);
        root.addView(textStatus);

        deviceTitle = new TextView(this);
        deviceTitle.setPadding(0, pad, 0, pad / 2);
        root.addView(deviceTitle);
        deviceEmpty = new TextView(this);
        deviceEmpty.setTextSize(13f);
        deviceEmpty.setPadding(0, pad / 2, 0, pad / 2);
        deviceEmpty.setVisibility(View.GONE);
        root.addView(deviceEmpty);
        deviceAdapter = new DeviceCardAdapter();
        deviceRecycler = new RecyclerView(this);
        deviceRecycler.setLayoutManager(new LinearLayoutManager(this));
        // 外层是 ScrollView：关闭嵌套滚动，让 RecyclerView 按内容撑高。
        deviceRecycler.setNestedScrollingEnabled(false);
        deviceRecycler.setAdapter(deviceAdapter);
        root.addView(deviceRecycler);

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
        editDeviceId.setText(prefs.getString(StartParams.KEY_DEVICE_ID, ""));
        checkAutoStart.setChecked(prefs.getBoolean(StartParams.KEY_AUTO_START, false));
    }

    private void saveViewsIntoPrefs() {
        prefs.edit()
                .putString(StartParams.KEY_SERVER_HOST, editHost.getText().toString().trim())
                // 端口固定默认 10086（StartParams.resolve 空值走 DEFAULT_PORT）；
                // 清掉旧版本可能存过的端口，避免 UI 不可见时残留生效。
                .remove(StartParams.KEY_SERVER_PORT)
                .putString(StartParams.KEY_DEVICE_ID, editDeviceId.getText().toString().trim())
                // UI 不再暴露虚拟 DUT；显式写 false，防旧配置残留导致真机误进虚拟模式。
                .putBoolean(StartParams.KEY_SIMULATE_DUT, false)
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

    // ==================== 状态与受管设备 ====================

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        StartParams stored = StartParams.resolve(null, prefsToMap());
        sb.append("服务器: ").append(stored.serverHost).append(':').append(stored.serverPort)
                .append(stored.isConfigured() ? "（已配置）" : "（未配置！）");
        if (service != null) {
            Map<String, Object> s = service.statusSummary();
            sb.append("\nTCP: ").append(Boolean.TRUE.equals(s.get("tcpConnected")) ? "已连接" : "未连接")
                    .append("\n配置版本: ").append(s.get("configVersion"));
            refreshDeviceList(s);
        } else {
            sb.append("\n服务未启动");
            deviceTitle.setText("受管设备");
            showDevicePlaceholder("服务未启动");
        }
        textStatus.setText(sb.toString());
    }

    private void refreshDeviceList(Map<String, Object> summary) {
        Object managed = summary.get("devicesManaged");
        Object ready = summary.get("devicesReady");
        deviceTitle.setText("受管设备（" + managed + " 台，READY " + ready + " 台）");
        List<Map<String, Object>> devices = service.deviceStates();
        if (devices.isEmpty()) {
            showDevicePlaceholder("（暂无受管设备，等待服务器下发配置）");
            return;
        }
        deviceEmpty.setVisibility(View.GONE);
        deviceRecycler.setVisibility(View.VISIBLE);
        deviceAdapter.submitList(devices);
    }

    private void showDevicePlaceholder(String text) {
        deviceAdapter.submitList(null);
        deviceRecycler.setVisibility(View.GONE);
        deviceEmpty.setText(text);
        deviceEmpty.setVisibility(View.VISIBLE);
    }

    private Map<String, String> prefsToMap() {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() != null) {
                m.put(e.getKey(), e.getValue().toString());
            }
        }
        return m;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
