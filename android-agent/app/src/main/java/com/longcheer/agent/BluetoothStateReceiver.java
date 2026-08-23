package com.longcheer.agent;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 系统蓝牙开关状态广播接收器（SDD §12.10）。
 *
 * <p>监听 {@link BluetoothAdapter#ACTION_STATE_CHANGED}，在蓝牙被关闭时通知回调，
 * 由 {@link AgentService} 执行释放连接槽、迁移设备状态等保护动作；
 * 在蓝牙恢复时通知回调恢复调度。</p>
 */
public class BluetoothStateReceiver extends BroadcastReceiver {

    private static final String TAG = "BluetoothStateReceiver";

    /**
     * 蓝牙状态变化回调接口。
     */
    public interface Callback {

        /**
         * 蓝牙已关闭或正在关闭。
         */
        void onBluetoothDisabled();

        /**
         * 蓝牙已开启或正在开启。
         */
        void onBluetoothEnabled();
    }

    private final Callback callback;

    public BluetoothStateReceiver(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
        Log.i(TAG, "bluetooth state changed: " + state);

        if (callback == null) {
            return;
        }

        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            callback.onBluetoothDisabled();
        } else if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON) {
            callback.onBluetoothEnabled();
        }
    }
}
