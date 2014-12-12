package com.android.systemui.statusbar.policy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;

import org.mockito.Mockito;

public class NetworkControllerSignalTest extends NetworkControllerBaseTest {

    public void testNoIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, mock(AccessPointControllerImpl.class),
                mock(MobileDataControllerImpl.class));
        setupNetworkController();

        verifyLastMobileDataIndicators(false, 0, 0);
    }

    public void testNoSimsIconPresent() {
        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(true);
    }

    public void testNoSimlessIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, mock(AccessPointControllerImpl.class),
                mock(MobileDataControllerImpl.class));
        setupNetworkController();

        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(false);
    }

    public void testSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    DEFAULT_ICON);

            // Verify low inet number indexing.
            setConnectivity(0, ConnectivityManager.TYPE_MOBILE, true);
            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[0][testStrength], 0);
        }
    }

    public void testCdmaSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    TelephonyIcons.DATA_1X[1][0 /* No direction */]);
        }
    }

    public void testSignalRoaming() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setGsmRoaming(true);
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][testStrength],
                    TelephonyIcons.ROAMING_ICON);
        }
    }

    public void testCdmaSignalRoaming() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setCdmaRoaming(true);
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][testStrength],
                    TelephonyIcons.ROAMING_ICON);
        }
    }

    public void testQsSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setLevel(testStrength);

            verifyLastQsMobileDataIndicators(true,
                    TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    DEFAULT_QS_ICON, false, false);
        }
    }

    public void testCdmaQsSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setLevel(testStrength);

            verifyLastQsMobileDataIndicators(true,
                    TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    TelephonyIcons.QS_ICON_1X, false, false);
        }
    }

    public void testNoRoamingWithoutSignal() {
        setupDefaultSignal();
        setCdma();
        setCdmaRoaming(true);
        setVoiceRegState(ServiceState.STATE_OUT_OF_SERVICE);
        setDataRegState(ServiceState.STATE_OUT_OF_SERVICE);

        // This exposes the bug in b/18034542, and should be switched to the commented out
        // verification below (and pass), once the bug is fixed.
        verifyLastMobileDataIndicators(true, R.drawable.stat_sys_signal_null,
                TelephonyIcons.ROAMING_ICON);
        //verifyLastMobileDataIndicators(true, R.drawable.stat_sys_signal_null, 0 /* No Icon */);
    }

    private void setCdma() {
        setIsGsm(false);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);
        setCdmaRoaming(false);
    }

    public void testOnReceive_updateSimState_noSim() {
        Intent intent = new Intent();
        intent.setAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE, IccCardConstants.INTENT_VALUE_ICC_ABSENT);

        mNetworkController.onReceive(mContext, intent);

        assertSimStateEquals(IccCardConstants.State.ABSENT);
    }

    public void testOnReceive_stringsUpdatedAction_spn() {
        String expectedMNetworkName = "Test";
        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
                expectedMNetworkName /* spn */,
                false /* showPlmn */,
                "NotTest" /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(expectedMNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_plmn() {
        String expectedMNetworkName = "Test";

        Intent intent = createStringsUpdatedIntent(false /* showSpn */,
                "NotTest" /* spn */,
                true /* showPlmn */,
                expectedMNetworkName /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(expectedMNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_bothFalse() {
        Intent intent = createStringsUpdatedIntent(false /* showSpn */,
              "Irrelevant" /* spn */,
              false /* showPlmn */,
              "Irrelevant" /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        String defaultNetworkName = mMobileSignalController
            .getStringIfExists(
                com.android.internal.R.string.lockscreen_carrier_default);
        assertNetworkNameEquals(defaultNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_bothTrueAndNull() {
        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
            null /* spn */,
            true /* showPlmn */,
            null /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        String defaultNetworkName = mMobileSignalController.getStringIfExists(
                com.android.internal.R.string.lockscreen_carrier_default);
        assertNetworkNameEquals(defaultNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_bothTrueAndNonNull() {
        String spn = "Test1";
        String plmn = "Test2";

        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
            spn /* spn */,
            true /* showPlmn */,
            plmn /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(plmn
                + mMobileSignalController.getStringIfExists(
                        R.string.status_bar_network_name_separator)
                + spn);
    }

    private Intent createStringsUpdatedIntent(boolean showSpn, String spn,
            boolean showPlmn, String plmn) {

        Intent intent = new Intent();
        intent.setAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);

        intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, showSpn);
        intent.putExtra(TelephonyIntents.EXTRA_SPN, spn);

        intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, showPlmn);
        intent.putExtra(TelephonyIntents.EXTRA_PLMN, plmn);

        return intent;
    }

    public void testOnUpdateDataActivity_dataIn() {
        setupDefaultSignal();

        updateDataActivity(TelephonyManager.DATA_ACTIVITY_IN);

        verifyLastQsMobileDataIndicators(true /* visible */,
                TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
                DEFAULT_QS_ICON /* typeIcon */,
                true /* dataIn */,
                false /* dataOut */);

    }

    public void testOnUpdateDataActivity_dataOut() {
      setupDefaultSignal();

      updateDataActivity(TelephonyManager.DATA_ACTIVITY_OUT);

      verifyLastQsMobileDataIndicators(true /* visible */,
              TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
              DEFAULT_QS_ICON /* typeIcon */,
              false /* dataIn */,
              true /* dataOut */);

    }

    public void testOnUpdateDataActivity_dataInOut() {
      setupDefaultSignal();

      updateDataActivity(TelephonyManager.DATA_ACTIVITY_INOUT);

      verifyLastQsMobileDataIndicators(true /* visible */,
              TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
              DEFAULT_QS_ICON /* typeIcon */,
              true /* dataIn */,
              true /* dataOut */);

    }

    public void testOnUpdateDataActivity_dataActivityNone() {
      setupDefaultSignal();

      updateDataActivity(TelephonyManager.DATA_ACTIVITY_NONE);

      verifyLastQsMobileDataIndicators(true /* visible */,
              TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
              DEFAULT_QS_ICON /* typeIcon */,
              false /* dataIn */,
              false /* dataOut */);

    }
}
