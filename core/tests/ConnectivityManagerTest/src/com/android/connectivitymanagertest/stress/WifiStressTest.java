/*
 * Copyright (C) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.connectivitymanagertest.stress;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.connectivitymanagertest.ConnectivityManagerStressTestRunner;
import com.android.connectivitymanagertest.ConnectivityManagerTestBase;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Stress Wi-Fi connection, scanning and reconnection after sleep.
 *
 * To run this stress test suite, type
 * adb shell am instrument -e class com.android.connectivitymanagertest.stress.WifiStressTest
 *                  -w com.android.connectivitymanagertest/.ConnectivityManagerStressTestRunner
 */
public class WifiStressTest extends ConnectivityManagerTestBase {
    private final static String TAG = "WifiStressTest";

    private final static long SCREEN_OFF_TIMER = 500; //500ms
    /**
     * Wi-Fi idle time for default sleep policy
     */
    private final static long WIFI_IDLE_MS = 15 * 1000;

    /**
     * Delay after issuing wifi shutdown.
     * The framework keep driver up for at leat 2 minutes to avoid problems
     * that a quick shutdown could cause on wext driver and protentially
     * on cfg based driver
     */
    private final static long WIFI_SHUTDOWN_DELAY = 2 * 60 * 1000;

    private final static String OUTPUT_FILE = "WifiStressTestOutput.txt";
    private int mReconnectIterations;
    private long mWifiSleepTime;
    private int mScanIterations;
    private String mSsid;
    private String mPassword;
    private ConnectivityManagerStressTestRunner mRunner;
    private BufferedWriter mOutputWriter = null;
    private boolean mWifiOnlyFlag;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mRunner = (ConnectivityManagerStressTestRunner) getInstrumentation();
        mReconnectIterations = mRunner.getReconnectIterations();
        mSsid = mRunner.getReconnectSsid();
        mPassword = mRunner.getReconnectPassword();
        mScanIterations = mRunner.getScanIterations();
        mWifiSleepTime = mRunner.getSleepTime();
        mWifiOnlyFlag = mRunner.isWifiOnly();
        log(String.format("mReconnectIterations(%d), mSsid(%s), mPassword(%s),"
            + "mScanIterations(%d), mWifiSleepTime(%d)", mReconnectIterations, mSsid,
            mPassword, mScanIterations, mWifiSleepTime));
        mOutputWriter = new BufferedWriter(new FileWriter(new File(
                Environment.getExternalStorageDirectory(), OUTPUT_FILE), true));
        turnScreenOn();
        if (!mWifiManager.isWifiEnabled()) {
            log("Enable wi-fi before stress tests.");
            if (!enableWifi()) {
                tearDown();
                fail("enable wifi failed.");
            }
            sleep(SHORT_TIMEOUT, "Interruped while waiting for wifi on");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        log("tearDown()");
        if (mOutputWriter != null) {
            mOutputWriter.close();
        }
        super.tearDown();
    }

    private void writeOutput(String s) {
        log("write message: " + s);
        if (mOutputWriter == null) {
            log("no writer attached to file " + OUTPUT_FILE);
            return;
        }
        try {
            mOutputWriter.write(s + "\n");
            mOutputWriter.flush();
        } catch (IOException e) {
            log("failed to write output.");
        }
    }

    public void log(String message) {
        Log.v(TAG, message);
    }

    private void sleep(long sometime, String errorMsg) {
        try {
            Thread.sleep(sometime);
        } catch (InterruptedException e) {
            fail(errorMsg);
        }
    }

    /**
     *  Stress Wifi Scanning
     *  TODO: test the scanning quality for each frequency band
     */
    @LargeTest
    public void testWifiScanning() {
        long scanTimeSum = 0, i, averageScanTime = -1;
        int ssidAppearInScanResultsCount = 0; // count times of given ssid appear in scan results.
        for (i = 1; i <= mScanIterations; i++) {
            log("testWifiScanning: iteration: " + i);
            averageScanTime = scanTimeSum / i;
            writeOutput(String.format("iteration %d out of %d", i, mScanIterations));
            writeOutput(String.format("average scanning time is %d", averageScanTime));
            writeOutput(String.format("ssid appear %d out of %d scan iterations",
                    ssidAppearInScanResultsCount, i));
            List<ScanResult> scanResultLocal = null;
            // wait for a scan result
            long start = 0;
            synchronized (mWifiScanResultLock) {
                start = SystemClock.uptimeMillis();
                assertTrue("start scan failed", mWifiManager.startScan());
                try {
                    mWifiScanResultLock.wait(WAIT_FOR_SCAN_RESULT);
                } catch (InterruptedException e) {
                    // ignore
                }
                scanTimeSum += SystemClock.uptimeMillis() - start;
                // save the scan result while in lock
                scanResultLocal = mLastScanResult;
            }
            if (scanResultLocal == null || scanResultLocal.isEmpty()) {
                fail("Scan results are empty ");
            }
            log("size of scan result list: " + scanResultLocal.size());
            for (ScanResult sr : scanResultLocal) {
                log(String.format("scan result: " + sr.toString()));
                if (mSsid.equals(sr.SSID)) {
                    ssidAppearInScanResultsCount += 1;
                    break;
                }
            }
        }
        Bundle result = new Bundle();
        result.putLong("actual-iterations", i - 1);
        result.putLong("avg-scan-time", averageScanTime);
        result.putInt("ap-discovered", ssidAppearInScanResultsCount);
        getInstrumentation().sendStatus(Activity.RESULT_FIRST_USER, result);
        if (i == mScanIterations + 1) {
            writeOutput(String.format("iteration %d out of %d", i, mScanIterations));
            writeOutput(String.format("average scanning time is %d", scanTimeSum / (i - 1)));
            writeOutput(String.format("ssid appear %d out of %d scan iterations",
                    ssidAppearInScanResultsCount, i));
        }
    }

    // Stress Wifi reconnection to secure net after sleep
    @LargeTest
    public void testWifiReconnectionAfterSleep() {
        // set wifi sleep policy to never on while in sleep
        Settings.Global.putInt(mRunner.getContext().getContentResolver(),
                Settings.Global.WIFI_SLEEP_POLICY, Settings.Global.WIFI_SLEEP_POLICY_NEVER);
        // set idle timeout for wifi to 15s
        Settings.Global.putLong(mRunner.getContext().getContentResolver(),
                Settings.Global.WIFI_IDLE_MS, WIFI_IDLE_MS);

        // Connect to a Wi-Fi network
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = mSsid;
        if (mPassword != null) {
            config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
            if (isHex(mPassword, 64)) {
                config.preSharedKey = mPassword;
            } else {
                config.preSharedKey = '"' + mPassword + '"';
            }
        } else {
            config.allowedKeyManagement.set(KeyMgmt.NONE);
        }
        config.setIpAssignment(IpAssignment.DHCP);
        config.setProxySettings(ProxySettings.NONE);

        assertTrue("Failed to connect to Wi-Fi network: " + mSsid,
                connectToWifiWithConfiguration(config));
        assertTrue("wifi not connected", waitForNetworkState(ConnectivityManager.TYPE_WIFI,
                State.CONNECTED, WIFI_CONNECTION_TIMEOUT));
        // Run ping test to verify the data connection
        assertTrue("Wi-Fi is connected, but no data connection.", pingTest(null));

        long i, sum = 0, avgReconnectTime = 0;
        for (i = 1; i <= mReconnectIterations; i++) {
            // 1. Put device into sleep mode
            // 2. Wait for the device to sleep for sometime, verify wi-fi is off and mobile is on.
            // 3. Maintain the sleep mode for some time,
            // 4. Verify the Wi-Fi is still off, and data is on
            // 5. Wake up the device, verify Wi-Fi is enabled and connected.
            writeOutput(String.format("iteration %d out of %d",
                    i, mReconnectIterations));
            log("iteration: " + i);
            turnScreenOff();
            // Use clock time since boot for intervals.
            long start = SystemClock.uptimeMillis();
            PowerManager pm =
                (PowerManager)mRunner.getContext().getSystemService(Context.POWER_SERVICE);
            while (pm.isInteractive() &&
                    ((SystemClock.uptimeMillis() - start) < SCREEN_OFF_TIMER)) {
                SystemClock.sleep(100);
            }
            assertFalse("screen still on", pm.isInteractive());
            // wait for WiFi timeout
            SystemClock.sleep(WIFI_IDLE_MS + WIFI_SHUTDOWN_DELAY);
            // below check temporarily disabled due to bug in ConnectivityManager return
//            assertTrue("Wait for Wi-Fi to idle timeout",
//                    waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.DISCONNECTED,
//                    6 * SHORT_TIMEOUT));
            if (mWifiOnlyFlag) {
                assertTrue("expected wifi disconnect, still has active connection",
                        waitUntilNoActiveNetworkConnection(2 * LONG_TIMEOUT));
            } else {
                // use long timeout as the pppd startup may take several retries.
                assertTrue("no fallback on mobile or wifi didn't disconnect",
                        waitForNetworkState(ConnectivityManager.TYPE_MOBILE, State.CONNECTED,
                        2 * LONG_TIMEOUT));
            }
            SystemClock.sleep(mWifiSleepTime);
            // verify the wi-fi is still off and either we have no connectivity or fallback on mobile
            if (mWifiOnlyFlag) {
                NetworkInfo ni = mCm.getActiveNetworkInfo();
                if (ni != null) {
                    Log.e(TAG, "has active network while in wifi sleep: " + ni.toString());
                    fail("active network detected");
                }
            } else {
                assertEquals("mobile not connected", State.CONNECTED,
                        mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState());
                assertTrue("no connectivity over mobile", pingTest(null));
            }

            // Turn screen on again
            turnScreenOn();
            // Measure the time for Wi-Fi to get connected
            long startTime = SystemClock.uptimeMillis();
            assertTrue("screen on: wifi not enabled before timeout",
                    waitForWifiState(WifiManager.WIFI_STATE_ENABLED, SHORT_TIMEOUT));
            assertTrue("screen on: wifi not connected before timeout",
                    waitForNetworkState(ConnectivityManager.TYPE_WIFI, State.CONNECTED,
                    LONG_TIMEOUT));
            long connectionTime = SystemClock.uptimeMillis() - startTime;
            sum += connectionTime;
            avgReconnectTime = sum / i;
            log("average reconnection time is: " + avgReconnectTime);

            assertTrue("Reconnect to Wi-Fi network, but no data connection.", pingTest(null));
        }
        Bundle result = new Bundle();
        result.putLong("actual-iterations", i - 1);
        result.putLong("avg-reconnect-time", avgReconnectTime);
        getInstrumentation().sendStatus(Activity.RESULT_FIRST_USER, result);
        if (i == mReconnectIterations + 1) {
            writeOutput(String.format("iteration %d out of %d",
                    i, mReconnectIterations));
        }
    }
}
