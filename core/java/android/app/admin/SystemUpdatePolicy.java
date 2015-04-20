/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app.admin;

import android.annotation.IntDef;
import android.os.PersistableBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class that represents a local system update policy set by the device owner.
 *
 * @see DevicePolicyManager#setSystemUpdatePolicy
 * @see DevicePolicyManager#getSystemUpdatePolicy
 */
public class SystemUpdatePolicy {

    /** @hide */
    @IntDef({
        TYPE_INSTALL_AUTOMATIC,
        TYPE_INSTALL_WINDOWED,
        TYPE_POSTPONE})
    @Retention(RetentionPolicy.SOURCE)
    @interface SystemUpdatePolicyType {}

    /**
     * Install system update automatically as soon as one is available.
     */
    public static final int TYPE_INSTALL_AUTOMATIC = 1;

    /**
     * Install system update automatically within a daily maintenance window, for a maximum of 30
     * days. After the expiration the policy will no longer be effective and the system should
     * revert back to its normal behavior as if no policy were set. The only exception is
     * {@link #TYPE_INSTALL_AUTOMATIC} which should still take effect to install system update
     * immediately.
     */
    public static final int TYPE_INSTALL_WINDOWED = 2;

    /**
     * Incoming system update will be blocked for a maximum of 30 days, after which the system
     * should revert back to its normal behavior as if no policy were set. The only exception is
     * {@link #TYPE_INSTALL_AUTOMATIC} which should still take effect to install system update
     * immediately.
     */
    public static final int TYPE_POSTPONE = 3;

    private static final String KEY_POLICY_TYPE = "policy_type";
    private static final String KEY_INSTALL_WINDOW_START = "install_window_start";
    private static final String KEY_INSTALL_WINDOW_END = "install_window_end";

    private PersistableBundle mPolicy;

    public  SystemUpdatePolicy() {
        mPolicy = new PersistableBundle();
    }

    /**
     * Construct an SystemUpdatePolicy object from a bundle.
     * @hide
     */
    public SystemUpdatePolicy(PersistableBundle in) {
        mPolicy = new PersistableBundle(in);
    }

    /**
     * Retrieve the underlying bundle where the policy is stored.
     * @hide
     */
    public PersistableBundle getPolicyBundle() {
        return new PersistableBundle(mPolicy);
    }

    /**
     * Set the policy to: install update automatically as soon as one is available.
     *
     * @see #TYPE_INSTALL_AUTOMATIC
     */
    public void setAutomaticInstallPolicy() {
        mPolicy.clear();
        mPolicy.putInt(KEY_POLICY_TYPE, TYPE_INSTALL_AUTOMATIC);
    }

    /**
     * Set the policy to: new system update will only be installed automatically when the system
     * clock is inside a daily maintenance window. If the start and end times are the same, the
     * window is considered to include the WHOLE 24 hours, that is, updates can install at any time.
     * If the given window in invalid, a {@link SystemUpdatePolicy.InvalidWindowException} will be
     * thrown. If start time is later than end time, the window is considered spanning midnight,
     * i.e. end time donates a time on the next day. The maintenance window will last for 30 days,
     * after which the system should revert back to its normal behavior as if no policy were set.
     *
     * @param startTime the start of the maintenance window, measured as the number of minutes from
     *            midnight in the device's local time. Must be in the range of [0, 1440).
     * @param endTime the end of the maintenance window, measured as the number of minutes from
     *            midnight in the device's local time. Must be in the range of [0, 1440).
     * @see #TYPE_INSTALL_WINDOWED
     */
    public void setWindowedInstallPolicy(int startTime, int endTime) throws InvalidWindowException{
        if (startTime < 0 || startTime >= 1440 || endTime < 0 || endTime >= 1440) {
            throw new InvalidWindowException("startTime and endTime must be inside [0, 1440)");
        }
        mPolicy.clear();
        mPolicy.putInt(KEY_POLICY_TYPE, TYPE_INSTALL_WINDOWED);
        mPolicy.putInt(KEY_INSTALL_WINDOW_START, startTime);
        mPolicy.putInt(KEY_INSTALL_WINDOW_END, endTime);
    }

    /**
     * Set the policy to: block installation for a maximum period of 30 days. After expiration the
     * system should revert back to its normal behavior as if no policy were set.
     *
     * @see #TYPE_POSTPONE
     */
    public void setPostponeInstallPolicy() {
        mPolicy.clear();
        mPolicy.putInt(KEY_POLICY_TYPE, TYPE_POSTPONE);
    }

    /**
     * Returns the type of system update policy.
     *
     * @return an integer, either one of {@link #TYPE_INSTALL_AUTOMATIC},
     * {@link #TYPE_INSTALL_WINDOWED} and {@link #TYPE_POSTPONE}, or -1 if no policy has been set.
     */
    @SystemUpdatePolicyType
    public int getPolicyType() {
        return mPolicy.getInt(KEY_POLICY_TYPE, -1);
    }

    /**
     * Get the start of the maintenance window.
     *
     * @return the start of the maintenance window measured as the number of minutes from midnight,
     * or -1 if the policy does not have a maintenance window.
     */
    public int getInstallWindowStart() {
        if (getPolicyType() == TYPE_INSTALL_WINDOWED) {
            return mPolicy.getInt(KEY_INSTALL_WINDOW_START, -1);
        } else {
            return -1;
        }
    }

    /**
     * Get the end of the maintenance window.
     *
     * @return the end of the maintenance window measured as the number of minutes from midnight,
     * or -1 if the policy does not have a maintenance window.
     */
    public int getInstallWindowEnd() {
        if (getPolicyType() == TYPE_INSTALL_WINDOWED) {
            return mPolicy.getInt(KEY_INSTALL_WINDOW_END, -1);
        } else {
            return -1;
        }
    }

    @Override
    public String toString() {
        return mPolicy.toString();
    }

    /**
     * Exception thrown by {@link SystemUpdatePolicy#setWindowedInstallPolicy(int, int)} in case the
     * specified window is invalid.
     */
    public static class InvalidWindowException extends Exception {
        public InvalidWindowException(String reason) {
            super(reason);
        }
    }
}

