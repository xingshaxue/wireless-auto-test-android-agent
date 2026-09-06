package com.longcheer.agent.ble;

import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SimulatedGattClient 单测：虚拟 DUT 的读值/写回/通知振荡/断连行为。
 */
public class SimulatedGattClientTest {

    /** 同步执行器：回调在调用线程立即执行（读/写/连接确定性）。 */
    private static final Executor DIRECT = Runnable::run;

    private static class Collector implements GattClient.Callback {
        byte[] lastRead;
        int lastReadStatus = -999;
        int lastWriteStatus = -999;
        int mtu = -1;
        boolean connected;
        boolean disconnected;
        final List<byte[]> notifies = new CopyOnWriteArrayList<>();
        final CountDownLatch notifyLatch = new CountDownLatch(2);

        @Override
        public void onConnected() {
            connected = true;
        }

        @Override
        public void onDisconnected(int status) {
            disconnected = true;
        }

        @Override
        public void onServicesDiscovered(int status) {
        }

        @Override
        public void onMtuChanged(int mtu, int status) {
            this.mtu = mtu;
        }

        @Override
        public void onRead(UUID charUuid, byte[] value, int status) {
            lastRead = value;
            lastReadStatus = status;
        }

        @Override
        public void onWrite(UUID charUuid, int status) {
            lastWriteStatus = status;
        }

        @Override
        public void onNotify(UUID charUuid, byte[] value) {
            notifies.add(value);
            notifyLatch.countDown();
        }

        @Override
        public void onNotifySubscribed(UUID charUuid, int status) {
        }
    }

    @Test
    public void readBatteryReturns85() {
        Collector cb = new Collector();
        SimulatedGattClient dut = new SimulatedGattClient("AA:01", cb, DIRECT);
        dut.readCharacteristic(SimulatedGattClient.SERVICE_BATTERY, SimulatedGattClient.CHAR_BATTERY);
        assertEquals(0, cb.lastReadStatus);
        assertArrayEquals(new byte[]{85}, cb.lastRead);
    }

    @Test
    public void writeStatusThenReadBack() {
        Collector cb = new Collector();
        SimulatedGattClient dut = new SimulatedGattClient("AA:01", cb, DIRECT);
        dut.writeCharacteristic(SimulatedGattClient.SERVICE_INFO, SimulatedGattClient.CHAR_STATUS,
                "RUNNING".getBytes(), false);
        assertEquals(0, cb.lastWriteStatus);
        dut.readCharacteristic(SimulatedGattClient.SERVICE_INFO, SimulatedGattClient.CHAR_STATUS);
        assertEquals("RUNNING", new String(cb.lastRead));
    }

    @Test
    public void mtuNegotiatedTo247() {
        Collector cb = new Collector();
        SimulatedGattClient dut = new SimulatedGattClient("AA:01", cb, DIRECT);
        dut.requestMtu(512);
        assertEquals(247, cb.mtu); // 模拟中端机型，§3.2 阶梯中间档
    }

    @Test
    public void temperatureNotifiesOscillateAcrossRuleThreshold() throws Exception {
        Collector cb = new Collector();
        // 通知任务在内部调度线程上跑，回调也用它（避免测试线程时序耦合）。
        Executor shared = Runnable::run;
        SimulatedGattClient dut = new SimulatedGattClient("AA:01", cb, shared);
        dut.connect();
        assertTrue(cb.connected);

        dut.setNotification(SimulatedGattClient.SERVICE_INFO, SimulatedGattClient.CHAR_TEMPERATURE, true);
        assertTrue("应收到至少 2 次温度通知", cb.notifyLatch.await(5, TimeUnit.SECONDS));

        // 振荡值应为 39.0 / 42.0（sint16 LE ×0.1），跨越示例规则 40℃ 阈值。
        int v1 = (cb.notifies.get(0)[0] & 0xFF) | (cb.notifies.get(0)[1] << 8);
        int v2 = (cb.notifies.get(1)[0] & 0xFF) | (cb.notifies.get(1)[1] << 8);
        assertTrue("振荡跨越 40℃", (v1 == 420 && v2 == 390) || (v1 == 390 && v2 == 420));

        dut.setNotification(SimulatedGattClient.SERVICE_INFO, SimulatedGattClient.CHAR_TEMPERATURE, false);
        dut.disconnectAndClose();
        assertTrue(cb.disconnected);
    }
}
