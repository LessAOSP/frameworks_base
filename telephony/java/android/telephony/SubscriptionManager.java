/*
* Copyright (C) 2011-2014 MediaTek Inc.
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

package android.telephony;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Intent;
import android.net.Uri;
import android.provider.BaseColumns;
import android.telephony.Rlog;
import android.os.ServiceManager;
import android.os.RemoteException;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.PhoneConstants;
import java.util.List;

/**
 *@hide
 */
public class SubscriptionManager implements BaseColumns {
    private static final String LOG_TAG = "SUB";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    // An invalid phone identifier
    public static final int INVALID_PHONE_ID = -1000;

    // Indicates the caller wants the default phone id.
    public static final int DEFAULT_PHONE_ID = Integer.MAX_VALUE;

    // An invalid slot identifier
    public static final int INVALID_SLOT_ID = -1000;

    // Indicates the caller wants the default slot id.
    public static final int DEFAULT_SLOT_ID = Integer.MAX_VALUE;

    // An invalid subscription identifier
    public static final long INVALID_SUB_ID = -1000;

    // Indicates the user should be asked which sub to use.
    public static final long ASK_USER_SUB_ID = -1001;

    // Indicates the caller wants the default sub id.
    public static final long DEFAULT_SUB_ID = Long.MAX_VALUE;

    public static final Uri CONTENT_URI = Uri.parse("content://telephony/siminfo");

    public static final int DEFAULT_INT_VALUE = -100;

    public static final String DEFAULT_STRING_VALUE = "N/A";

    public static final int EXTRA_VALUE_NEW_SIM = 1;
    public static final int EXTRA_VALUE_REMOVE_SIM = 2;
    public static final int EXTRA_VALUE_REPOSITION_SIM = 3;
    public static final int EXTRA_VALUE_NOCHANGE = 4;

    public static final String INTENT_KEY_DETECT_STATUS = "simDetectStatus";
    public static final String INTENT_KEY_SIM_COUNT = "simCount";
    public static final String INTENT_KEY_NEW_SIM_SLOT = "newSIMSlot";
    public static final String INTENT_KEY_NEW_SIM_STATUS = "newSIMStatus";

    /**
     * The ICC ID of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    public static final String ICC_ID = "icc_id";

    /**
     * <P>Type: INTEGER (int)</P>
     */
    public static final String SIM_ID = "sim_id";

    public static final int SIM_NOT_INSERTED = -1;

    /**
     * The display name of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    public static final String DISPLAY_NAME = "display_name";

    public static final int DEFAULT_NAME_RES = com.android.internal.R.string.unknownName;

    /**
     * The display name source of a SIM.
     * <P>Type: INT (int)</P>
     */
    public static final String NAME_SOURCE = "name_source";

    public static final int NAME_SOURCE_UNDEFINDED = -1;

    public static final int NAME_SOURCE_DEFAULT_SOURCE = 0;

    public static final int NAME_SOURCE_SIM_SOURCE = 1;

    public static final int NAME_SOURCE_USER_INPUT = 2;

    /**
     * The color of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    public static final String COLOR = "color";

    public static final int COLOR_1 = 0;

    public static final int COLOR_2 = 1;

    public static final int COLOR_3 = 2;

    public static final int COLOR_4 = 3;

    public static final int COLOR_DEFAULT = COLOR_1;

    /**
     * The phone number of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    public static final String NUMBER = "number";

    /**
     * The number display format of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    public static final String DISPLAY_NUMBER_FORMAT = "display_number_format";

    public static final int DISPLAY_NUMBER_NONE = 0;

    public static final int DISPLAY_NUMBER_FIRST = 1;

    public static final int DISPLAY_NUMBER_LAST = 2;

    public static final int DISLPAY_NUMBER_DEFAULT = DISPLAY_NUMBER_FIRST;

    /**
     * Permission for data roaming of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    public static final String DATA_ROAMING = "data_roaming";

    public static final int DATA_ROAMING_ENABLE = 1;

    public static final int DATA_ROAMING_DISABLE = 0;

    public static final int DATA_ROAMING_DEFAULT = DATA_ROAMING_DISABLE;

    private static final int RES_TYPE_BACKGROUND_DARK = 0;

    private static final int RES_TYPE_BACKGROUND_LIGHT = 1;

    private static final int[] sSimBackgroundDarkRes = setSimResource(RES_TYPE_BACKGROUND_DARK);

    /**
     * Broadcast Action: The user has changed one of the default subs related to
     * data, phone calls, or sms</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUB_DEFAULT_CHANGED_ACTION =
        "android.intent.action.SUB_DEFAULT_CHANGED";

    public SubscriptionManager() {
        logd("SubscriptionManager created");
    }

    /**
     * Get the SubInfoRecord according to an index
     * @param subId The unique SubInfoRecord index in database
     * @return SubInfoRecord, maybe null
     */
    public static SubInfoRecord getSubInfoUsingSubId(long subId) {
        if (!isValidSubId(subId)) {
            logd("[getSubInfoUsingSubIdx]- invalid subId");
            return null;
        }

        SubInfoRecord subInfo = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subInfo = iSub.getSubInfoUsingSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subInfo;

    }

    /**
     * Get the SubInfoRecord according to an IccId
     * @param iccId the IccId of SIM card
     * @return SubInfoRecord, maybe null
     */
    public static List<SubInfoRecord> getSubInfoUsingIccId(String iccId) {
        if (VDBG) logd("[getSubInfoUsingIccId]+ iccId=" + iccId);
        if (iccId == null) {
            logd("[getSubInfoUsingIccId]- null iccid");
            return null;
        }

        List<SubInfoRecord> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSubInfoUsingIccId(iccId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the SubInfoRecord according to slotId
     * @param slotId the slot which the SIM is inserted
     * @return SubInfoRecord, maybe null
     */
    public static List<SubInfoRecord> getSubInfoUsingSlotId(int slotId) {
        // FIXME: Consider never returning null
        if (!isValidSlotId(slotId)) {
            logd("[getSubInfoUsingSlotId]- invalid slotId");
            return null;
        }

        List<SubInfoRecord> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSubInfoUsingSlotId(slotId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get all the SubInfoRecord(s) in subinfo database
     * @return Array list of all SubInfoRecords in database, include thsoe that were inserted before
     */
    public static List<SubInfoRecord> getAllSubInfoList() {
        if (VDBG) logd("[getAllSubInfoList]+");

        List<SubInfoRecord> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the SubInfoRecord(s) of the currently inserted SIM(s)
     * @return Array list of currently inserted SubInfoRecord(s)
     */
    public static List<SubInfoRecord> getActiveSubInfoList() {
        List<SubInfoRecord> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the SUB count of all SUB(s) in subinfo database
     * @return all SIM count in database, include what was inserted before
     */
    public static int getAllSubInfoCount() {
        if (VDBG) logd("[getAllSubInfoCount]+");

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoCount();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the count of activated SUB(s)
     * @return activated SIM count
     */
    public static int getActivatedSubInfoCount() {
        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActivatedSubInfoCount();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Add a new SubInfoRecord to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     */
    public static Uri addSubInfoRecord(String iccId, int slotId) {
        if (VDBG) logd("[addSubInfoRecord]+ iccId:" + iccId + " slotId:" + slotId);
        if (iccId == null) {
            logd("[addSubInfoRecord]- null iccId");
        }
        if (!isValidSlotId(slotId)) {
            logd("[addSubInfoRecord]- invalid slotId");
        }

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                // FIXME: This returns 1 on success, 0 on error should should we return it?
                iSub.addSubInfoRecord(iccId, slotId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        // FIXME: Always returns null?
        return null;

    }

    /**
     * Set SIM color by simInfo index
     * @param color the color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public static int setColor(int color, long subId) {
        if (VDBG) logd("[setColor]+ color:" + color + " subId:" + subId);
        int size = sSimBackgroundDarkRes.length;
        if (!isValidSubId(subId) || color < 0 || color >= size) {
            logd("[setColor]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setColor(color, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public static int setDisplayName(String displayName, long subId) {
        return setDisplayName(displayName, subId, NAME_SOURCE_UNDEFINDED);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated or -1 if invalid subId
     */
    public static int setDisplayName(String displayName, long subId, long nameSource) {
        if (VDBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                    + " nameSource:" + nameSource);
        }
        if (!isValidSubId(subId)) {
            logd("[setDisplayName]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDisplayNameUsingSrc(displayName, subId, nameSource);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public static int setDisplayNumber(String number, long subId) {
        if (number == null || !isValidSubId(subId)) {
            logd("[setDisplayNumber]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDisplayNumber(number, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set number display format. 0: none, 1: the first four digits, 2: the last four digits
     * @param format the display format of phone number
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public static int setDisplayNumberFormat(int format, long subId) {
        if (VDBG) logd("[setDisplayNumberFormat]+ format:" + format + " subId:" + subId);
        if (format < 0 || !isValidSubId(subId)) {
            logd("[setDisplayNumberFormat]- fail, return -1");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDisplayNumberFormat(format, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public static int setDataRoaming(int roaming, long subId) {
        if (VDBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        if (roaming < 0 || !isValidSubId(subId)) {
            logd("[setDataRoaming]- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.setDataRoaming(roaming, subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    public static int getSlotId(long subId) {
        if (!isValidSubId(subId)) {
            logd("[getSlotId]- fail");
        }

        int result = INVALID_SLOT_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSlotId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    public static long[] getSubId(int slotId) {
        if (!isValidSlotId(slotId)) {
            logd("[getSubId]- fail");
            return null;
        }

        long[] subId = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getSubId(slotId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subId;
    }

    public static int getPhoneId(long subId) {
        if (!isValidSubId(subId)) {
            logd("[getPhoneId]- fail");
            return INVALID_PHONE_ID;
        }

        int result = INVALID_PHONE_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getPhoneId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("[getPhoneId]- phoneId=" + result);
        return result;

    }

    private static int[] setSimResource(int type) {
        int[] simResource = null;

        switch (type) {
            case RES_TYPE_BACKGROUND_DARK:
                simResource = new int[] {
                    com.android.internal.R.drawable.sim_dark_blue,
                    com.android.internal.R.drawable.sim_dark_orange,
                    com.android.internal.R.drawable.sim_dark_green,
                    com.android.internal.R.drawable.sim_dark_purple
                };
                break;
            case RES_TYPE_BACKGROUND_LIGHT:
                simResource = new int[] {
                    com.android.internal.R.drawable.sim_light_blue,
                    com.android.internal.R.drawable.sim_light_orange,
                    com.android.internal.R.drawable.sim_light_green,
                    com.android.internal.R.drawable.sim_light_purple
                };
                break;
        }

        return simResource;
    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, "[SubManager] " + msg);
    }

    /**
     * @return the "system" defaultSubId on a voice capable device this
     * will be getDefaultVoiceSubId() and on a data only device it will be
     * getDefaultDataSubId().
     */
    public static long getDefaultSubId() {
        long subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSubId=" + subId);
        return subId;
    }

    public static long getDefaultVoiceSubId() {
        long subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultVoiceSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultVoiceSubId, sub id = " + subId);
        return subId;
    }

    public static void setDefaultVoiceSubId(long subId) {
        if (VDBG) logd("setDefaultVoiceSubId sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultVoiceSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    public static SubInfoRecord getDefaultVoiceSubInfo() {
        return getSubInfoUsingSubId(getDefaultVoiceSubId());
    }

    public static int getDefaultVoicePhoneId() {
        return getPhoneId(getDefaultVoiceSubId());
    }

    public static long getDefaultSmsSubId() {
        long subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSmsSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSmsSubId, sub id = " + subId);
        return subId;
    }

    public static void setDefaultSmsSubId(long subId) {
        if (VDBG) logd("setDefaultSmsSubId sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultSmsSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    public static SubInfoRecord getDefaultSmsSubInfo() {
        return getSubInfoUsingSubId(getDefaultSmsSubId());
    }

    public static int getDefaultSmsPhoneId() {
        return getPhoneId(getDefaultSmsSubId());
    }

    public static long getDefaultDataSubId() {
        long subId = INVALID_SUB_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultDataSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultDataSubId, sub id = " + subId);
        return subId;
    }

    public static void setDefaultDataSubId(long subId) {
        if (VDBG) logd("setDataSubscription sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultDataSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    public static SubInfoRecord getDefaultDataSubInfo() {
        return getSubInfoUsingSubId(getDefaultDataSubId());
    }

    public static int getDefaultDataPhoneId() {
        return getPhoneId(getDefaultDataSubId());
    }

    public static void clearSubInfo() {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.clearSubInfo();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return;
    }

    //FIXME this is vulnerable to race conditions
    public static boolean allDefaultsSelected() {
        if (getDefaultDataSubId() == INVALID_SUB_ID) {
            return false;
        }
        if (getDefaultSmsSubId() == INVALID_SUB_ID) {
            return false;
        }
        if (getDefaultVoiceSubId() == INVALID_SUB_ID) {
            return false;
        }
        return true;
    }

    /**
     * If a default is set to subscription which is not active, this will reset that default back to
     * INVALID_SUB_ID.
     */
    public static void clearDefaultsForInactiveSubIds() {
        if (VDBG) logd("clearDefaultsForInactiveSubIds");
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.clearDefaultsForInactiveSubIds();
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    public static boolean isValidSubId(long subId) {
        return subId > INVALID_SUB_ID ;
    }

    public static boolean isValidSlotId(int slotId) {
        return slotId > INVALID_SLOT_ID && slotId < TelephonyManager.getDefault().getSimCount();
    }

    public static boolean isValidPhoneId(int phoneId) {
        return phoneId > INVALID_PHONE_ID
                && phoneId < TelephonyManager.getDefault().getPhoneCount();
    }

    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId) {
        //FIXME this is using phoneId and slotId interchangeably
        long[] subIds = SubscriptionManager.getSubId(phoneId);
        if (subIds != null && subIds.length > 0) {
            putPhoneIdAndSubIdExtra(intent, phoneId, subIds[0]);
        } else {
            logd("putPhoneIdAndSubIdExtra: no valid subs");
        }
    }

    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId, long subId) {
        if (VDBG) logd("putPhoneIdAndSubIdExtra: phoneId=" + phoneId + " subId=" + subId);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
        //FIXME this is using phoneId and slotId interchangeably
        //Eventually, this should be removed as it is not the slot id
        intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
    }

    /**
     * @return the list of subId's that are activated,
     *         is never null but the length maybe 0.
     */
    public static long[] getActivatedSubIdList() {
        long[] subId = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getActivatedSubIdList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (subId == null) {
            subId = new long[0];
        }

        return subId;

    }
}

