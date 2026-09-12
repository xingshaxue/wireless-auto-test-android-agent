package com.longcheer.agent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.longcheer.agent.model.DeviceState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受管设备卡片列表适配器（控制台"受管设备"区，风格 A 卡片式）。
 *
 * <p>数据源为 {@link AgentService#deviceStates()} 的只读摘要列表；规模 ≤20 台，
 * 2s ticker 驱动整体 {@link #notifyDataSetChanged()}，不引 DiffUtil。纯代码布局。</p>
 *
 * <p>卡片结构：状态着色圆点 | 两行文本（MAC / 轮询摘要） | 状态胶囊。
 * 状态色与中文文案集中在 {@link #stateColor(String)} / {@link #stateLabel(String)}。</p>
 */
public class DeviceCardAdapter extends RecyclerView.Adapter<DeviceCardAdapter.DeviceViewHolder> {

    private static final int COLOR_READY = Color.parseColor("#4CAF50");      // 绿
    private static final int COLOR_ACTIVE = Color.parseColor("#2196F3");     // 蓝
    private static final int COLOR_WORKING = Color.parseColor("#FF9800");    // 橙
    private static final int COLOR_IDLE = Color.parseColor("#9E9E9E");       // 灰
    private static final int COLOR_PAUSED = Color.parseColor("#9575CD");     // 灰紫
    private static final int COLOR_ERROR = Color.parseColor("#F44336");      // 红
    private static final int COLOR_TERMINATED = Color.parseColor("#616161"); // 深灰

    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#212121");
    private static final int COLOR_TEXT_SECONDARY = Color.parseColor("#757575");

    private final List<Map<String, Object>> devices = new ArrayList<>();

    /** 整表替换（调用频率 2s，规模小，直接全量刷新）。 */
    public void submitList(List<Map<String, Object>> newDevices) {
        devices.clear();
        if (newDevices != null) {
            devices.addAll(newDevices);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DeviceViewHolder(buildCard(parent));
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        holder.bind(devices.get(position));
    }

    // ==================== 状态映射（集中维护） ====================

    static String stateLabel(String stateName) {
        DeviceState state = parseState(stateName);
        if (state == null) {
            return stateName;
        }
        switch (state) {
            case READY: return "就绪";
            case POLLING: return "轮询中";
            case COMMANDING: return "命令执行中";
            case CONNECTING: return "连接中";
            case SERVICE_DISCOVERING: return "服务发现中";
            case CONFIGURING: return "配置中";
            case WAITING_SLOT: return "等待槽位";
            case RECONNECTING: return "重连中";
            case DISCONNECTED: return "已断开";
            case PAUSED: return "暂停";
            case ERROR: return "错误";
            case TERMINATED: return "已终止";
            case REGISTERED:
            default: return "已注册";
        }
    }

    static int stateColor(String stateName) {
        DeviceState state = parseState(stateName);
        if (state == null) {
            return COLOR_IDLE;
        }
        switch (state) {
            case READY: return COLOR_READY;
            case POLLING:
            case COMMANDING: return COLOR_ACTIVE;
            case CONNECTING:
            case SERVICE_DISCOVERING:
            case CONFIGURING:
            case WAITING_SLOT:
            case RECONNECTING: return COLOR_WORKING;
            case PAUSED: return COLOR_PAUSED;
            case ERROR: return COLOR_ERROR;
            case TERMINATED: return COLOR_TERMINATED;
            case DISCONNECTED:
            case REGISTERED:
            default: return COLOR_IDLE;
        }
    }

    private static DeviceState parseState(String stateName) {
        try {
            return DeviceState.valueOf(stateName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== 卡片布局（纯代码） ====================

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static View buildCard(ViewGroup parent) {
        Context ctx = parent.getContext();
        CardView card = new CardView(ctx);
        card.setRadius(dp(ctx, 8));
        card.setCardElevation(dp(ctx, 2));
        card.setCardBackgroundColor(Color.WHITE);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, 8)); // 卡片间距 ~8dp
        card.setLayoutParams(lp);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(ctx, 12);
        row.setPadding(pad, pad, pad, pad);
        card.addView(row);
        return card;
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {

        private final View dot;
        private final TextView macText;
        private final TextView summaryText;
        private final TextView capsule;

        DeviceViewHolder(View card) {
            super(card);
            Context ctx = card.getContext();
            LinearLayout row = (LinearLayout) ((CardView) card).getChildAt(0);

            dot = new View(ctx);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(ctx, 14), dp(ctx, 14));
            dotLp.setMarginEnd(dp(ctx, 12));
            row.addView(dot, dotLp);

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            macText = new TextView(ctx);
            macText.setTextSize(16f);
            macText.setTextColor(COLOR_TEXT_PRIMARY);
            macText.setTypeface(Typeface.MONOSPACE);
            texts.addView(macText);
            summaryText = new TextView(ctx);
            summaryText.setTextSize(12f);
            summaryText.setTextColor(COLOR_TEXT_SECONDARY);
            texts.addView(summaryText);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            capsule = new TextView(ctx);
            capsule.setTextSize(13f);
            capsule.setTextColor(Color.WHITE);
            capsule.setGravity(Gravity.CENTER);
            capsule.setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4));
            GradientDrawable capsuleBg = new GradientDrawable();
            capsuleBg.setShape(GradientDrawable.RECTANGLE);
            capsuleBg.setCornerRadius(dp(ctx, 14));
            capsule.setBackground(capsuleBg);
            LinearLayout.LayoutParams capsuleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            capsuleLp.setMarginStart(dp(ctx, 12));
            row.addView(capsule, capsuleLp);
        }

        void bind(Map<String, Object> d) {
            String stateName = String.valueOf(d.get("state"));
            int color = stateColor(stateName);
            ((GradientDrawable) dot.getBackground()).setColor(color);
            ((GradientDrawable) capsule.getBackground()).setColor(color);
            capsule.setText(stateLabel(stateName));
            macText.setText(String.valueOf(d.get("mac")));
            summaryText.setText(buildSummary(d));
        }

        private static String buildSummary(Map<String, Object> d) {
            long lastPollTime = d.get("lastPollTime") instanceof Number
                    ? ((Number) d.get("lastPollTime")).longValue() : 0L;
            if (lastPollTime <= 0) {
                return "—";
            }
            // lastPollTime 基于 SystemClock.elapsedRealtime（开机单调时钟），展示为相对时间。
            long agoSec = Math.max(0, (SystemClock.elapsedRealtime() - lastPollTime) / 1000L);
            StringBuilder sb = new StringBuilder();
            sb.append("最近轮询 ").append(agoSec).append(" 秒前");
            if (Boolean.TRUE.equals(d.get("pollDataStale"))) {
                sb.append("（数据过期）");
            }
            String summary = String.valueOf(d.get("lastPollSummary"));
            if (!summary.isEmpty() && !"null".equals(summary)) {
                sb.append(" · ").append(summary);
            }
            return sb.toString();
        }
    }
}
