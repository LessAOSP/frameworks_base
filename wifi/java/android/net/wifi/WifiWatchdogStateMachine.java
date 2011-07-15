/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.DnsPinger;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;

/**
 * {@link WifiWatchdogStateMachine} monitors the initial connection to a Wi-Fi
 * network with multiple access points. After the framework successfully
 * connects to an access point, the watchdog verifies connectivity by 'pinging'
 * the configured DNS server using {@link DnsPinger}.
 * <p>
 * On DNS check failure, the BSSID is blacklisted if it is reasonably likely
 * that another AP might have internet access; otherwise the SSID is disabled.
 * <p>
 * On DNS success, the WatchdogService initiates a walled garden check via an
 * http get. A browser window is activated if a walled garden is detected.
 *
 * @hide
 */
public class WifiWatchdogStateMachine extends StateMachine {

    private static final boolean VDBG = false;
    private static final boolean DBG = true;
    private static final String WWSM_TAG = "WifiWatchdogStateMachine";

    private static final int WIFI_SIGNAL_LEVELS = 4;
    /**
     * Low signal is defined as less than or equal to cut off
     */
    private static final int LOW_SIGNAL_CUTOFF = 1;

    private static final long MIN_LOW_SIGNAL_CHECK_INTERVAL_MS = 2 * 60 * 1000;
    private static final long MIN_SINGLE_DNS_CHECK_INTERVAL_MS = 10 * 60 * 1000;
    private static final long MIN_WALLED_GARDEN_INTERVAL_MS = 30 * 60 * 1000;

    private static final int MAX_CHECKS_PER_SSID = 7;
    private static final int NUM_DNS_PINGS = 5;
    private static final double MIN_DNS_RESPONSE_RATE = 0.50;

    private static final int DNS_PING_TIMEOUT_MS = 800;
    private static final long DNS_PING_INTERVAL_MS = 100;

    private static final long BLACKLIST_FOLLOWUP_INTERVAL_MS = 15 * 1000;

    private static final int BASE = Protocol.BASE_WIFI_WATCHDOG;

    /**
     * Indicates the enable setting of WWS may have changed
     */
    private static final int EVENT_WATCHDOG_TOGGLED = BASE + 1;

    /**
     * Indicates the wifi network state has changed. Passed w/ original intent
     * which has a non-null networkInfo object
     */
    private static final int EVENT_NETWORK_STATE_CHANGE = BASE + 2;
    /**
     * Indicates the signal has changed. Passed with arg1
     * {@link #mNetEventCounter} and arg2 [raw signal strength]
     */
    private static final int EVENT_RSSI_CHANGE = BASE + 3;
    private static final int EVENT_SCAN_RESULTS_AVAILABLE = BASE + 4;
    private static final int EVENT_WIFI_RADIO_STATE_CHANGE = BASE + 5;

    private static final int MESSAGE_CHECK_STEP = BASE + 100;
    private static final int MESSAGE_HANDLE_WALLED_GARDEN = BASE + 101;
    private static final int MESSAGE_HANDLE_BAD_AP = BASE + 102;
    /**
     * arg1 == mOnlineWatchState.checkCount
     */
    private static final int MESSAGE_SINGLE_DNS_CHECK = BASE + 103;
    private static final int MESSAGE_NETWORK_FOLLOWUP = BASE + 104;

    private Context mContext;
    private ContentResolver mContentResolver;
    private WifiManager mWifiManager;
    private DnsPinger mDnsPinger;
    private IntentFilter mIntentFilter;
    private BroadcastReceiver mBroadcastReceiver;

    private DefaultState mDefaultState = new DefaultState();
    private WatchdogDisabledState mWatchdogDisabledState = new WatchdogDisabledState();
    private WatchdogEnabledState mWatchdogEnabledState = new WatchdogEnabledState();
    private NotConnectedState mNotConnectedState = new NotConnectedState();
    private ConnectedState mConnectedState = new ConnectedState();
    private DnsCheckingState mDnsCheckingState = new DnsCheckingState();
    private OnlineWatchState mOnlineWatchState = new OnlineWatchState();
    private DnsCheckFailureState mDnsCheckFailureState = new DnsCheckFailureState();
    private WalledGardenState mWalledGardenState = new WalledGardenState();
    private BlacklistedApState mBlacklistedApState = new BlacklistedApState();

    /**
     * The {@link WifiInfo} object passed to WWSM on network broadcasts
     */
    private WifiInfo mInitialConnInfo;
    private int mNetEventCounter = 0;

    /**
     * Currently maintained but not used, TODO
     */
    private HashSet<String> mBssids = new HashSet<String>();
    private int mNumFullDNSchecks = 0;

    private Long mLastWalledGardenCheckTime = null;

    /**
     * This is set by the blacklisted state and reset when connected to a new AP.
     * It triggers a disableNetwork call if a DNS check fails.
     */
    public boolean mDisableAPNextFailure = false;

    /**
     * STATE MAP
     *          Default
     *         /       \
     * Disabled     Enabled
     *             /       \
     * Disconnected      Connected
     *                  /---------\
     *               (all other states)
     */
    private WifiWatchdogStateMachine(Context context) {
        super(WWSM_TAG);
        mContext = context;
        mContentResolver = context.getContentResolver();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mDnsPinger = new DnsPinger("WifiWatchdogServer.DnsPinger", context,
                ConnectivityManager.TYPE_WIFI);

        setupNetworkReceiver();

        // The content observer to listen needs a handler
        registerForSettingsChanges();
        addState(mDefaultState);
            addState(mWatchdogDisabledState, mDefaultState);
            addState(mWatchdogEnabledState, mDefaultState);
                addState(mNotConnectedState, mWatchdogEnabledState);
                addState(mConnectedState, mWatchdogEnabledState);
                    addState(mDnsCheckingState, mConnectedState);
                    addState(mDnsCheckFailureState, mConnectedState);
                    addState(mWalledGardenState, mConnectedState);
                    addState(mBlacklistedApState, mConnectedState);
                    addState(mOnlineWatchState, mConnectedState);

        setInitialState(mWatchdogDisabledState);
    }

    public static WifiWatchdogStateMachine makeWifiWatchdogStateMachine(Context context) {
        WifiWatchdogStateMachine wwsm = new WifiWatchdogStateMachine(context);
        wwsm.start();
        wwsm.sendMessage(EVENT_WATCHDOG_TOGGLED);
        return wwsm;
    }

    /**
   *
   */
    private void setupNetworkReceiver() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    sendMessage(EVENT_NETWORK_STATE_CHANGE, intent);
                } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                    obtainMessage(EVENT_RSSI_CHANGE, mNetEventCounter,
                            intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200)).sendToTarget();
                } else if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                    sendMessage(EVENT_SCAN_RESULTS_AVAILABLE);
                } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    sendMessage(EVENT_WIFI_RADIO_STATE_CHANGE,
                            intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                    WifiManager.WIFI_STATE_UNKNOWN));
                }
            }
        };

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    }

    /**
     * Observes the watchdog on/off setting, and takes action when changed.
     */
    private void registerForSettingsChanges() {
        ContentObserver contentObserver = new ContentObserver(this.getHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                sendMessage(EVENT_WATCHDOG_TOGGLED);
            }
        };

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.WIFI_WATCHDOG_ON),
                false, contentObserver);
    }

    /**
     * DNS based detection techniques do not work at all hotspots. The one sure
     * way to check a walled garden is to see if a URL fetch on a known address
     * fetches the data we expect
     */
    private boolean isWalledGardenConnection() {
        InputStream in = null;
        HttpURLConnection urlConnection = null;
        try {
            URL url = new URL(getWalledGardenUrl());
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream());
            Scanner scanner = new Scanner(in);
            if (scanner.findInLine(getWalledGardenPattern()) != null) {
                return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
            if (urlConnection != null)
                urlConnection.disconnect();
        }
    }

    private boolean rssiStrengthAboveCutoff(int rssi) {
        return WifiManager.calculateSignalLevel(rssi, WIFI_SIGNAL_LEVELS) > LOW_SIGNAL_CUTOFF;
    }

    public void dump(PrintWriter pw) {
        pw.print("WatchdogStatus: ");
        pw.print("State " + getCurrentState());
        pw.println(", network [" + mInitialConnInfo + "]");
        pw.print("checkCount " + mNumFullDNSchecks);
        pw.println(", bssids: " + mBssids);
        pw.println("lastSingleCheck: " + mOnlineWatchState.lastCheckTime);
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED
     */
    private Boolean isWalledGardenTestEnabled() {
        return Settings.Secure.getInt(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_TEST_ENABLED, 1) == 1;
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WALLED_GARDEN_URL
     */
    private String getWalledGardenUrl() {
        String url = Settings.Secure.getString(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_URL);
        if (TextUtils.isEmpty(url))
            return "http://www.google.com/";
        return url;
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_WALLED_GARDEN_PATTERN
     */
    private String getWalledGardenPattern() {
        String pattern = Settings.Secure.getString(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_WALLED_GARDEN_PATTERN);
        if (TextUtils.isEmpty(pattern))
            return "<title>.*Google.*</title>";
        return pattern;
    }

    /**
     * @see android.provider.Settings.Secure#WIFI_WATCHDOG_ON
     */
    private boolean isWatchdogEnabled() {
        return Settings.Secure.getInt(mContentResolver,
                Settings.Secure.WIFI_WATCHDOG_ON, 1) == 1;
    }


    /**
     * Helper to return wait time left given a min interval and last run
     *
     * @param interval minimum wait interval
     * @param lastTime last time action was performed in
     *            SystemClock.elapsedRealtime(). Null if never.
     * @return non negative time to wait
     */
    private static long waitTime(long interval, Long lastTime) {
        if (lastTime == null)
            return 0;
        long wait = interval + lastTime - SystemClock.elapsedRealtime();
        return wait > 0 ? wait : 0;
    }

    private static String wifiInfoToStr(WifiInfo wifiInfo) {
        if (wifiInfo == null)
            return "null";
        return "(" + wifiInfo.getSSID() + ", " + wifiInfo.getBSSID() + ")";
    }

    /**
   *
   */
    private void resetWatchdogState() {
        mInitialConnInfo = null;
        mDisableAPNextFailure = false;
        mLastWalledGardenCheckTime = null;
        mNumFullDNSchecks = 0;
        mBssids.clear();
    }

    private void popUpBrowser() {
        Uri uri = Uri.parse("http://www.google.com");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    private void sendCheckStepMessage(long delay) {
        sendMessageDelayed(obtainMessage(MESSAGE_CHECK_STEP, mNetEventCounter, 0), delay);
    }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            if (VDBG) {
                Slog.v(WWSM_TAG, "Caught message " + msg.what + " in state " +
                        getCurrentState().getName());
            }
            return HANDLED;
        }
    }

    class WatchdogDisabledState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_TOGGLED:
                    if (isWatchdogEnabled())
                        transitionTo(mNotConnectedState);
                    return HANDLED;
            }
            return NOT_HANDLED;
        }
    }

    class WatchdogEnabledState extends State {
        @Override
        public void enter() {
            resetWatchdogState();
            mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
            Slog.i(WWSM_TAG, "WifiWatchdogService enabled");
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_WATCHDOG_TOGGLED:
                    if (!isWatchdogEnabled())
                        transitionTo(mWatchdogDisabledState);
                    return HANDLED;
                case EVENT_NETWORK_STATE_CHANGE:
                    Intent stateChangeIntent = (Intent) msg.obj;
                    NetworkInfo networkInfo = (NetworkInfo)
                            stateChangeIntent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

                    switch (networkInfo.getState()) {
                        case CONNECTED:
                            // WifiInfo wifiInfo = (WifiInfo)
                            //         stateChangeIntent
                            //                 .getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
                            // TODO : Replace with above code when API is changed
                            WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                            if (wifiInfo == null) {
                                Slog.e(WWSM_TAG, "Connected --> WifiInfo object null!");
                                return HANDLED;
                            }

                            if (wifiInfo.getSSID() == null || wifiInfo.getBSSID() == null) {
                                Slog.e(WWSM_TAG, "Received wifiInfo object with null elts: "
                                        + wifiInfoToStr(wifiInfo));
                                return HANDLED;
                            }

                            initConnection(wifiInfo);
                            transitionTo(mDnsCheckingState);
                            mNetEventCounter++;
                            return HANDLED;
                        case DISCONNECTED:
                        case DISCONNECTING:
                            mNetEventCounter++;
                            transitionTo(mNotConnectedState);
                            return HANDLED;
                    }
                    return HANDLED;
                case EVENT_WIFI_RADIO_STATE_CHANGE:
                    if ((Integer) msg.obj == WifiManager.WIFI_STATE_DISABLING) {
                        Slog.i(WWSM_TAG, "WifiStateDisabling -- Resetting WatchdogState");
                        resetWatchdogState();
                        mNetEventCounter++;
                        transitionTo(mNotConnectedState);
                    }
                    return HANDLED;
            }

            return NOT_HANDLED;
        }

        /**
         * @param wifiInfo Info object with non-null ssid and bssid
         */
        private void initConnection(WifiInfo wifiInfo) {
            if (VDBG) {
                Slog.v(WWSM_TAG, "Connected:: old " + wifiInfoToStr(mInitialConnInfo) +
                        " ==> new " + wifiInfoToStr(wifiInfo));
            }

            if (mInitialConnInfo == null || !wifiInfo.getSSID().equals(mInitialConnInfo.getSSID())) {
                resetWatchdogState();
            } else if (!wifiInfo.getBSSID().equals(mInitialConnInfo.getBSSID())) {
                mDisableAPNextFailure = false;
            }
            mInitialConnInfo = wifiInfo;
        }

        @Override
        public void exit() {
            mContext.unregisterReceiver(mBroadcastReceiver);
            Slog.i(WWSM_TAG, "WifiWatchdogService disabled");
        }
    }

    class NotConnectedState extends State {
    }

    class ConnectedState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SCAN_RESULTS_AVAILABLE:
                    String curSsid = mInitialConnInfo.getSSID();
                    List<ScanResult> results = mWifiManager.getScanResults();
                    int oldNumBssids = mBssids.size();

                    if (results == null) {
                        if (DBG) {
                            Slog.d(WWSM_TAG, "updateBssids: Got null scan results!");
                        }
                        return HANDLED;
                    }

                    for (ScanResult result : results) {
                        if (result == null || result.SSID == null) {
                            if (VDBG) {
                                Slog.v(WWSM_TAG, "Received invalid scan result: " + result);
                            }
                            continue;
                        }
                        if (curSsid.equals(result.SSID))
                            mBssids.add(result.BSSID);
                    }
                    return HANDLED;
            }
            return NOT_HANDLED;
        }

    }

    class DnsCheckingState extends State {
        int dnsCheckTries = 0;
        int dnsCheckSuccesses = 0;
        String dnsCheckLogStr = "";

        @Override
        public void enter() {
            mNumFullDNSchecks++;
            dnsCheckSuccesses = 0;
            dnsCheckTries = 0;
            if (DBG) {
                Slog.d(WWSM_TAG, "Starting DNS pings at " + SystemClock.elapsedRealtime());
                dnsCheckLogStr = String.format("Dns Check %d.  Pinging %s on ssid [%s]: ",
                        mNumFullDNSchecks, mDnsPinger.getDns(), mInitialConnInfo.getSSID());
            }

            sendCheckStepMessage(0);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_CHECK_STEP) {
                return NOT_HANDLED;
            }
            if (msg.arg1 != mNetEventCounter) {
                Slog.d(WWSM_TAG, "Check step out of sync, ignoring...");
                return HANDLED;
            }

            long pingResponseTime = mDnsPinger.pingDns(mDnsPinger.getDns(),
                    DNS_PING_TIMEOUT_MS);

            dnsCheckTries++;
            if (pingResponseTime >= 0)
                dnsCheckSuccesses++;

            if (DBG) {
                if (pingResponseTime >= 0) {
                    dnsCheckLogStr += "|" + pingResponseTime;
                } else {
                    dnsCheckLogStr += "|x";
                }
            }

            if (VDBG) {
                Slog.v(WWSM_TAG, dnsCheckLogStr);
            }

            /**
             * After a full ping count, if we have more responses than this
             * cutoff, the outcome is success; else it is 'failure'.
             */
            double pingResponseCutoff = MIN_DNS_RESPONSE_RATE * NUM_DNS_PINGS;
            int remainingChecks = NUM_DNS_PINGS - dnsCheckTries;

            /**
             * Our final success count will be at least this big, so we're
             * guaranteed to succeed.
             */
            if (dnsCheckSuccesses >= pingResponseCutoff) {
                // DNS CHECKS OK, NOW WALLED GARDEN
                if (DBG) {
                    Slog.d(WWSM_TAG, dnsCheckLogStr + "|  SUCCESS");
                }

                if (!shouldCheckWalledGarden()) {
                    transitionTo(mOnlineWatchState);
                    return HANDLED;
                }

                mLastWalledGardenCheckTime = SystemClock.elapsedRealtime();
                if (isWalledGardenConnection()) {
                    if (DBG)
                        Slog.d(WWSM_TAG,
                                "Walled garden test complete - walled garden detected");
                    transitionTo(mWalledGardenState);
                } else {
                    if (DBG)
                        Slog.d(WWSM_TAG, "Walled garden test complete - online");
                    transitionTo(mOnlineWatchState);
                }
                return HANDLED;
            }

            /**
             * Our final count will be at most the current count plus the
             * remaining pings - we're guaranteed to fail.
             */
            if (remainingChecks + dnsCheckSuccesses < pingResponseCutoff) {
                if (DBG) {
                    Slog.d(WWSM_TAG, dnsCheckLogStr + "|  FAILURE");
                }
                transitionTo(mDnsCheckFailureState);
                return HANDLED;
            }

            // Still in dns check step
            sendCheckStepMessage(DNS_PING_INTERVAL_MS);
            return HANDLED;
        }

        private boolean shouldCheckWalledGarden() {
            if (!isWalledGardenTestEnabled()) {
                if (VDBG)
                    Slog.v(WWSM_TAG, "Skipping walled garden check - disabled");
                return false;
            }
            long waitTime = waitTime(MIN_WALLED_GARDEN_INTERVAL_MS,
                    mLastWalledGardenCheckTime);
            if (waitTime > 0) {
                if (DBG) {
                    Slog.d(WWSM_TAG, "Skipping walled garden check - wait " +
                            waitTime + " ms.");
                }
                return false;
            }
            return true;
        }

    }

    class OnlineWatchState extends State {
        /**
         * Signals a short-wait message is enqueued for the current 'guard' counter
         */
        boolean unstableSignalChecks = false;

        /**
         * The signal is unstable.  We should enqueue a short-wait check, if one is enqueued
         * already
         */
        boolean signalUnstable = false;

        /**
         * A monotonic counter to ensure that at most one check message will be processed from any
         * set of check messages currently enqueued.  Avoids duplicate checks when a low-signal
         * event is observed.
         */
        int checkGuard = 0;
        Long lastCheckTime = null;

        @Override
        public void enter() {
            lastCheckTime = SystemClock.elapsedRealtime();
            signalUnstable = false;
            checkGuard++;
            unstableSignalChecks = false;
            triggerSingleDnsCheck();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RSSI_CHANGE:
                    if (msg.arg1 != mNetEventCounter) {
                        if (DBG) {
                            Slog.d(WWSM_TAG, "Rssi change message out of sync, ignoring");
                        }
                        return HANDLED;
                    }
                    int newRssi = msg.arg2;
                    signalUnstable = !rssiStrengthAboveCutoff(newRssi);
                    if (VDBG) {
                        Slog.v(WWSM_TAG, "OnlineWatchState:: new rssi " + newRssi + " --> level " +
                                WifiManager.calculateSignalLevel(newRssi, WIFI_SIGNAL_LEVELS));
                    }

                    if (signalUnstable && !unstableSignalChecks) {
                        if (VDBG) {
                            Slog.v(WWSM_TAG, "Sending triggered check msg");
                        }
                        triggerSingleDnsCheck();
                    }
                    return HANDLED;
                case MESSAGE_SINGLE_DNS_CHECK:
                    if (msg.arg1 != checkGuard) {
                        if (VDBG) {
                            Slog.v(WWSM_TAG, "Single check msg out of sync, ignoring.");
                        }
                        return HANDLED;
                    }
                    lastCheckTime = SystemClock.elapsedRealtime();
                    long responseTime = mDnsPinger.pingDns(mDnsPinger.getDns(),
                            DNS_PING_TIMEOUT_MS);
                    if (responseTime >= 0) {
                        if (VDBG) {
                            Slog.v(WWSM_TAG, "Ran a single DNS ping. Response time: "
                                    + responseTime);
                        }

                        checkGuard++;
                        unstableSignalChecks = false;
                        triggerSingleDnsCheck();
                    } else {
                        if (DBG) {
                            Slog.d(WWSM_TAG, "Single dns ping failure. Starting full checks.");
                        }
                        transitionTo(mDnsCheckingState);
                    }
                    return HANDLED;
            }
            return NOT_HANDLED;
        }

        /**
         * Times a dns check with an interval based on {@link #curSignalStable}
         */
        private void triggerSingleDnsCheck() {
            long waitInterval;
            if (signalUnstable) {
                waitInterval = MIN_LOW_SIGNAL_CHECK_INTERVAL_MS;
                unstableSignalChecks = true;
            } else {
                waitInterval = MIN_SINGLE_DNS_CHECK_INTERVAL_MS;
            }
            sendMessageDelayed(obtainMessage(MESSAGE_SINGLE_DNS_CHECK, checkGuard, 0),
                    waitTime(waitInterval, lastCheckTime));
        }
    }

    class DnsCheckFailureState extends State {
        @Override
        public void enter() {
            obtainMessage(MESSAGE_HANDLE_BAD_AP, mNetEventCounter, 0).sendToTarget();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_HANDLE_BAD_AP) {
                return NOT_HANDLED;
            }

            if (msg.arg1 != mNetEventCounter) {
                if (VDBG) {
                    Slog.v(WWSM_TAG, "Msg out of sync, ignoring...");
                }
                return HANDLED;
            }

            if (mDisableAPNextFailure || mNumFullDNSchecks >= MAX_CHECKS_PER_SSID) {
                // TODO : Unban networks if they had low signal ?
                Slog.i(WWSM_TAG, "Disabling current SSID " + wifiInfoToStr(mInitialConnInfo)
                        + ".  " +
                        "numChecks " + mNumFullDNSchecks + ", numAPs " + mBssids.size());
                mWifiManager.disableNetwork(mInitialConnInfo.getNetworkId());
                transitionTo(mNotConnectedState);
            } else {
                Slog.i(WWSM_TAG, "Blacklisting current BSSID.  " + wifiInfoToStr(mInitialConnInfo) +
                        "numChecks " + mNumFullDNSchecks + ", numAPs " + mBssids.size());

                mWifiManager.addToBlacklist(mInitialConnInfo.getBSSID());
                mWifiManager.reassociate();
                transitionTo(mBlacklistedApState);
            }
            return HANDLED;
        }
    }

    class WalledGardenState extends State {
        @Override
        public void enter() {
            obtainMessage(MESSAGE_HANDLE_WALLED_GARDEN, mNetEventCounter, 0).sendToTarget();
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_HANDLE_WALLED_GARDEN) {
                return NOT_HANDLED;
            }

            if (msg.arg1 != mNetEventCounter) {
                if (VDBG) {
                    Slog.v(WWSM_TAG, "WalledGardenState::Msg out of sync, ignoring...");
                }
                return HANDLED;
            }
            popUpBrowser();
            transitionTo(mOnlineWatchState);
            return HANDLED;
        }
    }

    class BlacklistedApState extends State {
        @Override
        public void enter() {
            mDisableAPNextFailure = true;
            sendMessageDelayed(obtainMessage(MESSAGE_NETWORK_FOLLOWUP, mNetEventCounter, 0),
                    BLACKLIST_FOLLOWUP_INTERVAL_MS);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (msg.what != MESSAGE_NETWORK_FOLLOWUP) {
                return NOT_HANDLED;
            }

            if (msg.arg1 != mNetEventCounter) {
                if (VDBG) {
                    Slog.v(WWSM_TAG, "BlacklistedApState::Msg out of sync, ignoring...");
                }
                return HANDLED;
            }

            transitionTo(mDnsCheckingState);
            return HANDLED;
        }
    }
}
