/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.IConnectivityManager;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 */
public abstract class DataConnectionTracker extends Handler {
    protected static final boolean DBG = true;

    /**
     * IDLE: ready to start data connection setup, default state
     * INITING: state of issued setupDefaultPDP() but not finish yet
     * CONNECTING: state of issued startPppd() but not finish yet
     * SCANNING: data connection fails with one apn but other apns are available
     *           ready to start data connection on other apns (before INITING)
     * CONNECTED: IP connection is setup
     * DISCONNECTING: Connection.disconnect() has been called, but PDP
     *                context is not yet deactivated
     * FAILED: data connection fail for all apns settings
     *
     * getDataConnectionState() maps State to DataState
     *      FAILED or IDLE : DISCONNECTED
     *      INITING or CONNECTING or SCANNING: CONNECTING
     *      CONNECTED : CONNECTED or DISCONNECTING
     */
    public enum State {
        IDLE,
        INITING,
        CONNECTING,
        SCANNING,
        CONNECTED,
        DISCONNECTING,
        FAILED
    }

    public enum Activity {
        NONE,
        DATAIN,
        DATAOUT,
        DATAINANDOUT,
        DORMANT
    }

    public static String ACTION_DATA_CONNECTION_TRACKER_MESSENGER =
        "com.android.internal.telephony";
    public static String EXTRA_MESSENGER = "EXTRA_MESSENGER";

    /***** Event Codes *****/
    protected static final int EVENT_DATA_SETUP_COMPLETE = 1;
    protected static final int EVENT_RADIO_AVAILABLE = 3;
    protected static final int EVENT_RECORDS_LOADED = 4;
    protected static final int EVENT_TRY_SETUP_DATA = 5;
    protected static final int EVENT_DATA_STATE_CHANGED = 6;
    protected static final int EVENT_POLL_PDP = 7;
    protected static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 12;
    protected static final int EVENT_VOICE_CALL_STARTED = 14;
    protected static final int EVENT_VOICE_CALL_ENDED = 15;
    protected static final int EVENT_DATA_CONNECTION_DETACHED = 19;
    protected static final int EVENT_LINK_STATE_CHANGED = 20;
    protected static final int EVENT_ROAMING_ON = 21;
    protected static final int EVENT_ROAMING_OFF = 22;
    protected static final int EVENT_ENABLE_NEW_APN = 23;
    protected static final int EVENT_RESTORE_DEFAULT_APN = 24;
    protected static final int EVENT_DISCONNECT_DONE = 25;
    protected static final int EVENT_DATA_CONNECTION_ATTACHED = 26;
    protected static final int EVENT_START_NETSTAT_POLL = 27;
    protected static final int EVENT_START_RECOVERY = 28;
    protected static final int EVENT_APN_CHANGED = 29;
    protected static final int EVENT_CDMA_DATA_DETACHED = 30;
    protected static final int EVENT_NV_READY = 31;
    protected static final int EVENT_PS_RESTRICT_ENABLED = 32;
    protected static final int EVENT_PS_RESTRICT_DISABLED = 33;
    public static final int EVENT_CLEAN_UP_CONNECTION = 34;
    protected static final int EVENT_CDMA_OTA_PROVISION = 35;
    protected static final int EVENT_RESTART_RADIO = 36;
    protected static final int EVENT_SET_INTERNAL_DATA_ENABLE = 37;
    protected static final int EVENT_RESET_DONE = 38;
    public static final int CMD_SET_DATA_ENABLE = 39;
    public static final int EVENT_CLEAN_UP_ALL_CONNECTIONS = 40;
    public static final int CMD_SET_DEPENDENCY_MET = 41;

    /***** Constants *****/

    protected static final int APN_INVALID_ID = -1;
    protected static final int APN_DEFAULT_ID = 0;
    protected static final int APN_MMS_ID = 1;
    protected static final int APN_SUPL_ID = 2;
    protected static final int APN_DUN_ID = 3;
    protected static final int APN_HIPRI_ID = 4;
    protected static final int APN_IMS_ID = 5;
    protected static final int APN_FOTA_ID = 6;
    protected static final int APN_CBS_ID = 7;
    protected static final int APN_NUM_TYPES = 8;

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    public static final String APN_TYPE_KEY = "apnType";

    // responds to the setInternalDataEnabled call - used internally to turn off data
    // for example during emergency calls
    protected boolean mInternalDataEnabled = true;

    // responds to public (user) API to enable/disable data use
    // independent of mInternalDataEnabled and requests for APN access
    // persisted
    protected boolean mDataEnabled = true;

    protected boolean[] dataEnabled = new boolean[APN_NUM_TYPES];

    protected int enabledCount = 0;

    /* Currently requested APN type (TODO: This should probably be a parameter not a member) */
    protected String mRequestedApnType = Phone.APN_TYPE_DEFAULT;

    /** Retry configuration: A doubling of retry times from 5secs to 30minutes */
    protected static final String DEFAULT_DATA_RETRY_CONFIG = "default_randomization=2000,"
        + "5000,10000,20000,40000,80000:5000,160000:5000,"
        + "320000:5000,640000:5000,1280000:5000,1800000:5000";

    /** Retry configuration for secondary networks: 4 tries in 20 sec */
    protected static final String SECONDARY_DATA_RETRY_CONFIG =
            "max_retries=3, 5000, 5000, 5000";

    /** Slow poll when attempting connection recovery. */
    protected static final int POLL_NETSTAT_SLOW_MILLIS = 5000;
    /** Default max failure count before attempting to network re-registration. */
    protected static final int DEFAULT_MAX_PDP_RESET_FAIL = 3;

    /**
     * After detecting a potential connection problem, this is the max number
     * of subsequent polls before attempting a radio reset.  At this point,
     * poll interval is 5 seconds (POLL_NETSTAT_SLOW_MILLIS), so set this to
     * poll for about 2 more minutes.
     */
    protected static final int NO_RECV_POLL_LIMIT = 24;

    // 1 sec. default polling interval when screen is on.
    protected static final int POLL_NETSTAT_MILLIS = 1000;
    // 10 min. default polling interval when screen is off.
    protected static final int POLL_NETSTAT_SCREEN_OFF_MILLIS = 1000*60*10;
    // 2 min for round trip time
    protected static final int POLL_LONGEST_RTT = 120 * 1000;
    // 10 for packets without ack
    protected static final int NUMBER_SENT_PACKETS_OF_HANG = 10;
    // how long to wait before switching back to default APN
    protected static final int RESTORE_DEFAULT_APN_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    protected static final String APN_RESTORE_DELAY_PROP_NAME = "android.telephony.apn-restore";
    // represents an invalid IP address
    protected static final String NULL_IP = "0.0.0.0";

    // TODO: See if we can remove INTENT_RECONNECT_ALARM
    //       having to have different values for GSM and
    //       CDMA. If so we can then remove the need for
    //       getActionIntentReconnectAlarm.
    protected static final String INTENT_RECONNECT_ALARM_EXTRA_REASON = "reason";

    // member variables
    protected PhoneBase mPhone;
    protected Activity mActivity = Activity.NONE;
    protected State mState = State.IDLE;
    protected Handler mDataConnectionTracker = null;


    protected long mTxPkts;
    protected long mRxPkts;
    protected long mSentSinceLastRecv;
    protected int mNetStatPollPeriod;
    protected int mNoRecvPollCount = 0;
    protected boolean mNetStatPollEnabled = false;

    // wifi connection status will be updated by sticky intent
    protected boolean mIsWifiConnected = false;

    /** Intent sent when the reconnect alarm fires. */
    protected PendingIntent mReconnectIntent = null;

    /** CID of active data connection */
    protected int mCidActive;

    /** indication of our availability (preconditions to trysetupData are met) **/
    protected boolean mAvailability = false;

    // When false we will not auto attach and manully attaching is required.
    protected boolean mAutoAttachOnCreation = false;

    // State of screen
    // (TODO: Reconsider tying directly to screen, maybe this is
    //        really a lower power mode")
    protected boolean mIsScreenOn = true;

    /** The link properties (dns, gateway, ip, etc) */
    protected LinkProperties mLinkProperties = new LinkProperties();

    /** The link capabilities */
    protected LinkCapabilities mLinkCapabilities = new LinkCapabilities();

    /** Allows the generation of unique Id's for DataConnection objects */
    protected AtomicInteger mUniqueIdGenerator = new AtomicInteger(0);

    /** The data connections. */
    protected HashMap<Integer, DataConnection> mDataConnections =
        new HashMap<Integer, DataConnection>();

    /** Convert an ApnType string to Id (TODO: Use "enumeration" instead of String for ApnType) */
    protected HashMap<String, Integer> mApnToDataConnectionId =
                                    new HashMap<String, Integer>();

    /** Phone.APN_TYPE_* ===> ApnContext */
    protected ConcurrentHashMap<String, ApnContext> mApnContexts;

    /* Currently active APN */
    protected ApnSetting mActiveApn;

    /** allApns holds all apns */
    protected ArrayList<ApnSetting> mAllApns = null;

    /** preferred apn */
    protected ApnSetting mPreferredApn = null;

    /** Is packet service restricted by network */
    protected boolean mIsPsRestricted = false;


    /* Once disposed dont handle any messages */
    protected boolean mIsDisposed = false;

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mIsScreenOn = true;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mIsScreenOn = false;
                stopNetStatPoll();
                startNetStatPoll();
            } else if (action.equals(getActionIntentReconnectAlarm())) {
                log("Reconnect alarm. Previous state was " + mState);
                onActionIntentReconnectAlarm(intent);

            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final android.net.NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                final boolean enabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;

                if (!enabled) {
                    // when WiFi got disabled, the NETWORK_STATE_CHANGED_ACTION
                    // quit and won't report disconnected until next enabling.
                    mIsWifiConnected = false;
                }
            }
        }
    };

    protected void onActionIntentReconnectAlarm(Intent intent) {
        String reason = intent.getStringExtra(INTENT_RECONNECT_ALARM_EXTRA_REASON);
        if (mState == State.FAILED) {
            Message msg = obtainMessage(EVENT_CLEAN_UP_CONNECTION);
            msg.arg1 = 0; // tearDown is false
            msg.arg2 = 0;
            msg.obj = reason;
            sendMessage(msg);
        }
        sendMessage(obtainMessage(EVENT_TRY_SETUP_DATA));
    }

    /**
     * Default constructor
     */
    protected DataConnectionTracker(PhoneBase phone) {
        super();
        mPhone = phone;

        IntentFilter filter = new IntentFilter();
        filter.addAction(getActionIntentReconnectAlarm());
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

        mDataEnabled = Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                Settings.Secure.MOBILE_DATA, 1) == 1;

        // TODO: Why is this registering the phone as the receiver of the intent
        //       and not its own handler?
        mPhone.getContext().registerReceiver(mIntentReceiver, filter, null, mPhone);

        // This preference tells us 1) initial condition for "dataEnabled",
        // and 2) whether the RIL will setup the baseband to auto-PS attach.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mPhone.getContext());
        dataEnabled[APN_DEFAULT_ID] =
                !sp.getBoolean(PhoneBase.DATA_DISABLED_ON_BOOT_KEY, false);
        if (dataEnabled[APN_DEFAULT_ID]) {
            enabledCount++;
        }
        mAutoAttachOnCreation = dataEnabled[APN_DEFAULT_ID];
    }

    public void dispose() {
        mIsDisposed = true;
        mPhone.getContext().unregisterReceiver(this.mIntentReceiver);
    }

    protected void broadcastMessenger() {
        Intent intent = new Intent(ACTION_DATA_CONNECTION_TRACKER_MESSENGER);
        intent.putExtra(EXTRA_MESSENGER, new Messenger(this));
        mPhone.getContext().sendBroadcast(intent);
    }

    public Activity getActivity() {
        return mActivity;
    }

    public boolean isApnTypeActive(String type) {
        // TODO: support simultaneous with List instead
        if (Phone.APN_TYPE_DUN.equals(type)) {
            ApnSetting dunApn = fetchDunApn();
            if (dunApn != null) {
                return ((mActiveApn != null) && (dunApn.toString().equals(mActiveApn.toString())));
            }
        }
        return mActiveApn != null && mActiveApn.canHandleType(type);
    }

    protected ApnSetting fetchDunApn() {
        Context c = mPhone.getContext();
        String apnData = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.TETHER_DUN_APN);
        ApnSetting dunSetting = ApnSetting.fromString(apnData);
        if (dunSetting != null) return dunSetting;

        apnData = c.getResources().getString(R.string.config_tether_apndata);
        return ApnSetting.fromString(apnData);
    }

    public String[] getActiveApnTypes() {
        String[] result;
        if (mActiveApn != null) {
            result = mActiveApn.types;
        } else {
            result = new String[1];
            result[0] = Phone.APN_TYPE_DEFAULT;
        }
        return result;
    }

    /** TODO: See if we can remove */
    public String getActiveApnString(String apnType) {
        String result = null;
        if (mActiveApn != null) {
            result = mActiveApn.apn;
        }
        return result;
    }

    //The data roaming setting is now located in the shared preferences.
    //  See if the requested preference value is the same as that stored in
    //  the shared values.  If it is not, then update it.
    public void setDataOnRoamingEnabled(boolean enabled) {
        if (getDataOnRoamingEnabled() != enabled) {
            Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                Settings.Secure.DATA_ROAMING, enabled ? 1 : 0);
            if (mPhone.getServiceState().getRoaming()) {
                if (enabled) {
                    resetAllRetryCounts();
                }
                sendMessage(obtainMessage(EVENT_ROAMING_ON));
            }
        }
    }

    // Retrieve the data roaming setting from the shared preferences.
    public boolean getDataOnRoamingEnabled() {
        try {
            return Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(), Settings.Secure.DATA_ROAMING) > 0;
        } catch (SettingNotFoundException snfe) {
            return false;
        }
    }

    // abstract methods
    protected abstract String getActionIntentReconnectAlarm();
    protected abstract void startNetStatPoll();
    protected abstract void stopNetStatPoll();
    protected abstract void restartRadio();
    protected abstract void log(String s);
    protected abstract void loge(String s);
    protected abstract boolean isDataAllowed();
    protected abstract boolean isApnTypeAvailable(String type);
    public    abstract State getState(String apnType);
    protected abstract void setState(State s);
    protected abstract void gotoIdleAndNotifyDataConnection(String reason);

    protected abstract boolean onTrySetupData(String reason);
    protected abstract void onRoamingOff();
    protected abstract void onRoamingOn();
    protected abstract void onRadioAvailable();
    protected abstract void onRadioOffOrNotAvailable();
    protected abstract void onDataSetupComplete(AsyncResult ar);
    protected abstract void onDisconnectDone(int connId, AsyncResult ar);
    protected abstract void onVoiceCallStarted();
    protected abstract void onVoiceCallEnded();
    protected abstract void onCleanUpConnection(boolean tearDown, int apnId, String reason);
    protected abstract void onCleanUpAllConnections(String cause);
    protected abstract boolean isDataPossible();
    protected abstract boolean isDataPossible(String apnType);

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {

            case EVENT_ENABLE_NEW_APN:
                onEnableApn(msg.arg1, msg.arg2);
                break;

            case EVENT_TRY_SETUP_DATA:
                String reason = null;
                if (msg.obj instanceof String) {
                    reason = (String) msg.obj;
                }
                onTrySetupData(reason);
                break;

            case EVENT_ROAMING_OFF:
                if (getDataOnRoamingEnabled() == false) {
                    resetAllRetryCounts();
                }
                onRoamingOff();
                break;

            case EVENT_ROAMING_ON:
                onRoamingOn();
                break;

            case EVENT_RADIO_AVAILABLE:
                onRadioAvailable();
                break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
                break;

            case EVENT_DATA_SETUP_COMPLETE:
                mCidActive = msg.arg1;
                onDataSetupComplete((AsyncResult) msg.obj);
                break;

            case EVENT_DISCONNECT_DONE:
                log("DataConnectoinTracker.handleMessage: EVENT_DISCONNECT_DONE msg=" + msg);
                onDisconnectDone(msg.arg1, (AsyncResult) msg.obj);
                break;

            case EVENT_VOICE_CALL_STARTED:
                onVoiceCallStarted();
                break;

            case EVENT_VOICE_CALL_ENDED:
                onVoiceCallEnded();
                break;

            case EVENT_CLEAN_UP_ALL_CONNECTIONS: {
                onCleanUpAllConnections((String) msg.obj);
                break;
            }
            case EVENT_CLEAN_UP_CONNECTION: {
                boolean tearDown = (msg.arg1 == 0) ? false : true;
                onCleanUpConnection(tearDown, msg.arg2, (String) msg.obj);
                break;
            }
            case EVENT_SET_INTERNAL_DATA_ENABLE: {
                boolean enabled = (msg.arg1 == ENABLED) ? true : false;
                onSetInternalDataEnabled(enabled);
                break;
            }
            case EVENT_RESET_DONE: {
                onResetDone((AsyncResult) msg.obj);
                break;
            }
            case CMD_SET_DATA_ENABLE: {
                log("CMD_SET_DATA_ENABLE msg=" + msg);
                boolean enabled = (msg.arg1 == ENABLED) ? true : false;
                onSetDataEnabled(enabled);
                break;
            }

            case CMD_SET_DEPENDENCY_MET: {
                log("CMD_SET_DEPENDENCY_MET msg=" + msg);
                boolean met = (msg.arg1 == ENABLED) ? true : false;
                Bundle bundle = msg.getData();
                if (bundle != null) {
                    String apnType = (String)bundle.get(APN_TYPE_KEY);
                    if (apnType != null) {
                        onSetDependencyMet(apnType, met);
                    }
                }
                break;
            }

            default:
                Log.e("DATA", "Unidentified event = " + msg.what);
                break;
        }
    }

    /**
     * Report on whether data connectivity is enabled
     *
     * @return {@code false} if data connectivity has been explicitly disabled,
     *         {@code true} otherwise.
     */
    public synchronized boolean getAnyDataEnabled() {
        boolean result = (mInternalDataEnabled && mDataEnabled && (enabledCount != 0));
        if (!result && DBG) log("getAnyDataEnabled " + result);
        return result;
    }

    protected int apnTypeToId(String type) {
        if (TextUtils.equals(type, Phone.APN_TYPE_DEFAULT)) {
            return APN_DEFAULT_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_MMS)) {
            return APN_MMS_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_SUPL)) {
            return APN_SUPL_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_DUN)) {
            return APN_DUN_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_HIPRI)) {
            return APN_HIPRI_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_IMS)) {
            return APN_IMS_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_FOTA)) {
            return APN_FOTA_ID;
        } else if (TextUtils.equals(type, Phone.APN_TYPE_CBS)) {
            return APN_CBS_ID;
        } else {
            return APN_INVALID_ID;
        }
    }

    protected String apnIdToType(int id) {
        switch (id) {
        case APN_DEFAULT_ID:
            return Phone.APN_TYPE_DEFAULT;
        case APN_MMS_ID:
            return Phone.APN_TYPE_MMS;
        case APN_SUPL_ID:
            return Phone.APN_TYPE_SUPL;
        case APN_DUN_ID:
            return Phone.APN_TYPE_DUN;
        case APN_HIPRI_ID:
            return Phone.APN_TYPE_HIPRI;
        case APN_IMS_ID:
            return Phone.APN_TYPE_IMS;
        case APN_FOTA_ID:
            return Phone.APN_TYPE_FOTA;
        case APN_CBS_ID:
            return Phone.APN_TYPE_CBS;
        default:
            log("Unknown id (" + id + ") in apnIdToType");
            return Phone.APN_TYPE_DEFAULT;
        }
    }

    protected LinkProperties getLinkProperties(String apnType) {
        int id = apnTypeToId(apnType);
        if (isApnIdEnabled(id)) {
            return new LinkProperties(mLinkProperties);
        } else {
            return new LinkProperties();
        }
    }

    protected LinkCapabilities getLinkCapabilities(String apnType) {
        int id = apnTypeToId(apnType);
        if (isApnIdEnabled(id)) {
            return new LinkCapabilities(mLinkCapabilities);
        } else {
            return new LinkCapabilities();
        }
    }

    /**
     * Return the LinkProperties for the connection.
     *
     * @param connection
     * @return a copy of the LinkProperties, is never null.
     */
    protected LinkProperties getLinkProperties(DataConnection connection) {
        return connection.getLinkProperties();
    }

    /**
     * A capability is an Integer/String pair, the capabilities
     * are defined in the class LinkSocket#Key.
     *
     * @param connection
     * @return a copy of this connections capabilities, may be empty but never null.
     */
    protected LinkCapabilities getLinkCapabilities(DataConnection connection) {
        return connection.getLinkCapabilities();
    }

    // tell all active apns of the current condition
    protected void notifyDataConnection(String reason) {
        for (int id = 0; id < APN_NUM_TYPES; id++) {
            if (dataEnabled[id]) {
                mPhone.notifyDataConnection(reason, apnIdToType(id));
            }
        }
        notifyDataAvailability(reason);
    }

    // a new APN has gone active and needs to send events to catch up with the
    // current condition
    private void notifyApnIdUpToCurrent(String reason, int apnId) {
        switch (mState) {
            case IDLE:
            case INITING:
                break;
            case CONNECTING:
            case SCANNING:
                mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.CONNECTING);
                break;
            case CONNECTED:
            case DISCONNECTING:
                mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.CONNECTING);
                mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.CONNECTED);
                break;
        }
    }

    // since we normally don't send info to a disconnected APN, we need to do this specially
    private void notifyApnIdDisconnected(String reason, int apnId) {
        mPhone.notifyDataConnection(reason, apnIdToType(apnId), Phone.DataState.DISCONNECTED);
    }

    // disabled apn's still need avail/unavail notificiations - send them out
    protected void notifyOffApnsOfAvailability(String reason, boolean availability) {
        if (mAvailability == availability) {
            if (DBG) {
                log("notifyOffApnsOfAvailability: no change in availability, " +
                     "not nofitying about reason='" + reason + "' availability=" + availability);
            }
            return;
        }
        mAvailability = availability;
        for (int id = 0; id < APN_NUM_TYPES; id++) {
            if (!isApnIdEnabled(id)) {
                notifyApnIdDisconnected(reason, id);
            }
        }
    }

    // we had an availability change - tell the listeners
    protected void notifyDataAvailability(String reason) {
        // note that we either just turned all off because we lost availability
        // or all were off and could now go on, so only have off apns to worry about
        notifyOffApnsOfAvailability(reason, isDataPossible());
    }

    public boolean isApnTypeEnabled(String apnType) {
        if (apnType == null) {
            return false;
        } else {
            return isApnIdEnabled(apnTypeToId(apnType));
        }
    }

    protected synchronized boolean isApnIdEnabled(int id) {
        if (id != APN_INVALID_ID) {
            return dataEnabled[id];
        }
        return false;
    }

    /**
     * Ensure that we are connected to an APN of the specified type.
     *
     * @param type the APN type (currently the only valid values are
     *            {@link Phone#APN_TYPE_MMS} and {@link Phone#APN_TYPE_SUPL})
     * @return Success is indicated by {@code Phone.APN_ALREADY_ACTIVE} or
     *         {@code Phone.APN_REQUEST_STARTED}. In the latter case, a
     *         broadcast will be sent by the ConnectivityManager when a
     *         connection to the APN has been established.
     */
    public synchronized int enableApnType(String type) {
        int id = apnTypeToId(type);
        if (id == APN_INVALID_ID) {
            return Phone.APN_REQUEST_FAILED;
        }

        if (DBG) {
            log("enableApnType(" + type + "), isApnTypeActive = " + isApnTypeActive(type)
                    + ", isApnIdEnabled =" + isApnIdEnabled(id) + " and state = " + mState);
        }

        if (!isApnTypeAvailable(type)) {
            if (DBG) log("type not available");
            return Phone.APN_TYPE_NOT_AVAILABLE;
        }

        if (isApnIdEnabled(id)) {
            return Phone.APN_ALREADY_ACTIVE;
        } else {
            setEnabled(id, true);
        }
        return Phone.APN_REQUEST_STARTED;
    }

    /**
     * The APN of the specified type is no longer needed. Ensure that if use of
     * the default APN has not been explicitly disabled, we are connected to the
     * default APN.
     *
     * @param type the APN type. The only valid values are currently
     *            {@link Phone#APN_TYPE_MMS} and {@link Phone#APN_TYPE_SUPL}.
     * @return Success is indicated by {@code Phone.APN_ALREADY_ACTIVE} or
     *         {@code Phone.APN_REQUEST_STARTED}. In the latter case, a
     *         broadcast will be sent by the ConnectivityManager when a
     *         connection to the APN has been disconnected. A {@code
     *         Phone.APN_REQUEST_FAILED} is returned if the type parameter is
     *         invalid or if the apn wasn't enabled.
     */
    public synchronized int disableApnType(String type) {
        if (DBG) log("disableApnType(" + type + ")");
        int id = apnTypeToId(type);
        if (id == APN_INVALID_ID) {
            return Phone.APN_REQUEST_FAILED;
        }
        if (isApnIdEnabled(id)) {
            setEnabled(id, false);
            if (isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                if (dataEnabled[APN_DEFAULT_ID]) {
                    return Phone.APN_ALREADY_ACTIVE;
                } else {
                    return Phone.APN_REQUEST_STARTED;
                }
            } else {
                return Phone.APN_REQUEST_STARTED;
            }
        } else {
            return Phone.APN_REQUEST_FAILED;
        }
    }

    protected void setEnabled(int id, boolean enable) {
        if (DBG) {
            log("setEnabled(" + id + ", " + enable + ") with old state = " + dataEnabled[id]
                    + " and enabledCount = " + enabledCount);
        }
        Message msg = obtainMessage(EVENT_ENABLE_NEW_APN);
        msg.arg1 = id;
        msg.arg2 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
    }

    protected void onEnableApn(int apnId, int enabled) {
        if (DBG) {
            log("EVENT_APN_ENABLE_REQUEST apnId=" + apnId + ", apnType=" + apnIdToType(apnId) +
                    ", enabled=" + enabled + ", dataEnabled = " + dataEnabled[apnId] +
                    ", enabledCount = " + enabledCount + ", isApnTypeActive = " +
                    isApnTypeActive(apnIdToType(apnId)));
        }
        if (enabled == ENABLED) {
            synchronized (this) {
                if (!dataEnabled[apnId]) {
                    dataEnabled[apnId] = true;
                    enabledCount++;
                }
            }
            String type = apnIdToType(apnId);
            if (!isApnTypeActive(type)) {
                mRequestedApnType = type;
                onEnableNewApn();
            } else {
                notifyApnIdUpToCurrent(Phone.REASON_APN_SWITCHED, apnId);
            }
        } else {
            // disable
            boolean didDisable = false;
            synchronized (this) {
                if (dataEnabled[apnId]) {
                    dataEnabled[apnId] = false;
                    enabledCount--;
                    didDisable = true;
                }
            }
            if (didDisable && enabledCount == 0) {
                onCleanUpConnection(true, apnId, Phone.REASON_DATA_DISABLED);

                // send the disconnect msg manually, since the normal route wont send
                // it (it's not enabled)
                notifyApnIdDisconnected(Phone.REASON_DATA_DISABLED, apnId);
                if (dataEnabled[APN_DEFAULT_ID] == true
                        && !isApnTypeActive(Phone.APN_TYPE_DEFAULT)) {
                    // TODO - this is an ugly way to restore the default conn - should be done
                    // by a real contention manager and policy that disconnects the lower pri
                    // stuff as enable requests come in and pops them back on as we disable back
                    // down to the lower pri stuff
                    mRequestedApnType = Phone.APN_TYPE_DEFAULT;
                    onEnableNewApn();
                }
            }
        }
    }

    /**
     * Called when we switch APNs.
     *
     * mRequestedApnType is set prior to call
     * To be overridden.
     */
    protected void onEnableNewApn() {
    }

    /**
     * Called when EVENT_RESET_DONE is received so goto
     * IDLE state and send notifications to those interested.
     *
     * TODO - currently unused.  Needs to be hooked into DataConnection cleanup
     * TODO - needs to pass some notion of which connection is reset..
     */
    protected void onResetDone(AsyncResult ar) {
        if (DBG) log("EVENT_RESET_DONE");
        String reason = null;
        if (ar.userObj instanceof String) {
            reason = (String) ar.userObj;
        }
        gotoIdleAndNotifyDataConnection(reason);
    }

    /**
     * Prevent mobile data connections from being established, or once again
     * allow mobile data connections. If the state toggles, then either tear
     * down or set up data, as appropriate to match the new state.
     *
     * @param enable indicates whether to enable ({@code true}) or disable (
     *            {@code false}) data
     * @return {@code true} if the operation succeeded
     */
    public boolean setInternalDataEnabled(boolean enable) {
        if (DBG)
            log("setInternalDataEnabled(" + enable + ")");

        Message msg = obtainMessage(EVENT_SET_INTERNAL_DATA_ENABLE);
        msg.arg1 = (enable ? ENABLED : DISABLED);
        sendMessage(msg);
        return true;
    }

    protected void onSetInternalDataEnabled(boolean enable) {
        boolean prevEnabled = getAnyDataEnabled();
        if (mInternalDataEnabled != enable) {
            synchronized (this) {
                mInternalDataEnabled = enable;
            }
            if (prevEnabled != getAnyDataEnabled()) {
                if (!prevEnabled) {
                    resetAllRetryCounts();
                    onTrySetupData(Phone.REASON_DATA_ENABLED);
                } else {
                    cleanUpAllConnections(null);
                }
            }
        }
    }

    public void cleanUpAllConnections(String cause) {
        Message msg = obtainMessage(EVENT_CLEAN_UP_ALL_CONNECTIONS);
        msg.obj = cause;
        sendMessage(msg);
    }

    public boolean isAnyActiveDataConnections() {
        // TODO: Remember if there are any connected or
        // loop asking each DC/APN?
        return true;
    }

    protected void onSetDataEnabled(boolean enable) {
        boolean prevEnabled = getAnyDataEnabled();
        if (mDataEnabled != enable) {
            synchronized (this) {
                mDataEnabled = enable;
            }
            Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    Settings.Secure.MOBILE_DATA, enable ? 1 : 0);
            if (prevEnabled != getAnyDataEnabled()) {
                if (!prevEnabled) {
                    resetAllRetryCounts();
                    onTrySetupData(Phone.REASON_DATA_ENABLED);
                } else {
                    onCleanUpConnection(true, APN_DEFAULT_ID, Phone.REASON_DATA_DISABLED);
                }
            }
        }
    }

    protected void onSetDependencyMet(String apnType, boolean met) {
    }


    protected void resetAllRetryCounts() {
        for (DataConnection dc : mDataConnections.values()) {
            dc.resetRetryCount();
        }
    }
}
