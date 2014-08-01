/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CEC_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN;
import static android.hardware.hdmi.HdmiControlManager.TIME_RECORDING_RESULT_EXTRA_CEC_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.TIME_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.TIME_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE;

import android.content.Intent;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiRecordSources;
import android.hardware.hdmi.HdmiTimerRecordSources;
import android.hardware.hdmi.IHdmiControlCallback;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Represent a logical device of type TV residing in Android system.
 */
final class HdmiCecLocalDeviceTv extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDeviceTv";

    // Whether ARC is available or not. "true" means that ARC is established between TV and
    // AVR as audio receiver.
    @ServiceThreadOnly
    private boolean mArcEstablished = false;

    // Whether ARC feature is enabled or not.
    private boolean mArcFeatureEnabled = false;

    // Whether System audio mode is activated or not.
    // This becomes true only when all system audio sequences are finished.
    @GuardedBy("mLock")
    private boolean mSystemAudioActivated = false;

    // The previous port id (input) before switching to the new one. This is remembered in order to
    // be able to switch to it upon receiving <Inactive Source> from currently active source.
    // This remains valid only when the active source was switched via one touch play operation
    // (either by TV or source device). Manual port switching invalidates this value to
    // Constants.PORT_INVALID, for which case <Inactive Source> does not do anything.
    @GuardedBy("mLock")
    private int mPrevPortId;

    @GuardedBy("mLock")
    private int mSystemAudioVolume = Constants.UNKNOWN_VOLUME;

    @GuardedBy("mLock")
    private boolean mSystemAudioMute = false;

    // Copy of mDeviceInfos to guarantee thread-safety.
    @GuardedBy("mLock")
    private List<HdmiCecDeviceInfo> mSafeAllDeviceInfos = Collections.emptyList();
    // All external cec input(source) devices. Does not include system audio device.
    @GuardedBy("mLock")
    private List<HdmiCecDeviceInfo> mSafeExternalInputs = Collections.emptyList();

    // Map-like container of all cec devices including local ones.
    // A logical address of device is used as key of container.
    // This is not thread-safe. For external purpose use mSafeDeviceInfos.
    private final SparseArray<HdmiCecDeviceInfo> mDeviceInfos = new SparseArray<>();

    // If true, TV going to standby mode puts other devices also to standby.
    private boolean mAutoDeviceOff;

    // If true, TV wakes itself up when receiving <Text/Image View On>.
    private boolean mAutoWakeup;

    HdmiCecLocalDeviceTv(HdmiControlService service) {
        super(service, HdmiCecDeviceInfo.DEVICE_TV);
        mPrevPortId = Constants.INVALID_PORT_ID;
        mAutoDeviceOff = mService.readBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                true);
        mAutoWakeup = mService.readBooleanSetting(Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED, true);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, boolean fromBootup) {
        assertRunOnServiceThread();
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        launchRoutingControl(fromBootup);
        launchDeviceDiscovery();
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(Constants.PROPERTY_PREFERRED_ADDRESS_TV,
                Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        SystemProperties.set(Constants.PROPERTY_PREFERRED_ADDRESS_TV, String.valueOf(addr));
    }

    /**
     * Performs the action 'device select', or 'one touch play' initiated by TV.
     *
     * @param targetAddress logical address of the device to select
     * @param callback callback object to report the result with
     */
    @ServiceThreadOnly
    void deviceSelect(int targetAddress, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (targetAddress == Constants.ADDR_INTERNAL) {
            handleSelectInternalSource();
            // Switching to internal source is always successful even when CEC control is disabled.
            setActiveSource(targetAddress, mService.getPhysicalAddress());
            setActivePath(mService.getPhysicalAddress());
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        if (!mService.isControlEnabled()) {
            HdmiCecDeviceInfo info = getDeviceInfo(targetAddress);
            if (info != null) {
                setActiveSource(info);
            }
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        HdmiCecDeviceInfo targetDevice = getDeviceInfo(targetAddress);
        if (targetDevice == null) {
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        removeAction(DeviceSelectAction.class);
        addAndStartAction(new DeviceSelectAction(this, targetDevice, callback));
    }

    @ServiceThreadOnly
    private void handleSelectInternalSource() {
        assertRunOnServiceThread();
        // Seq #18
        if (mService.isControlEnabled() && mActiveSource.logicalAddress != mAddress) {
            updateActiveSource(mAddress, mService.getPhysicalAddress());
            // TODO: Check if this comes from <Text/Image View On> - if true, do nothing.
            HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                    mAddress, mService.getPhysicalAddress());
            mService.sendCecCommand(activeSource);
        }
    }

    @ServiceThreadOnly
    void updateActiveSource(int logicalAddress, int physicalAddress) {
        assertRunOnServiceThread();
        updateActiveSource(ActiveSource.of(logicalAddress, physicalAddress));
    }

    @ServiceThreadOnly
    void updateActiveSource(ActiveSource newActive) {
        assertRunOnServiceThread();
        // Seq #14
        if (mActiveSource.equals(newActive)) {
            return;
        }
        setActiveSource(newActive);
        int logicalAddress = newActive.logicalAddress;
        if (getDeviceInfo(logicalAddress) != null && logicalAddress != mAddress) {
            if (mService.pathToPortId(newActive.physicalAddress) == getActivePortId()) {
                setPrevPortId(getActivePortId());
            }
            // TODO: Show the OSD banner related to the new active source device.
        } else {
            // TODO: If displayed, remove the OSD banner related to the previous
            //       active source device.
        }
    }

    int getPortId(int physicalAddress) {
        return mService.pathToPortId(physicalAddress);
    }

    /**
     * Returns the previous port id kept to handle input switching on <Inactive Source>.
     */
    int getPrevPortId() {
        synchronized (mLock) {
            return mPrevPortId;
        }
    }

    /**
     * Sets the previous port id. INVALID_PORT_ID invalidates it, hence no actions will be
     * taken for <Inactive Source>.
     */
    void setPrevPortId(int portId) {
        synchronized (mLock) {
            mPrevPortId = portId;
        }
    }

    @ServiceThreadOnly
    void updateActiveInput(int path, boolean notifyInputChange) {
        assertRunOnServiceThread();
        // Seq #15
        if (path == getActivePath()) {
            return;
        }
        setPrevPortId(getActivePortId());
        int portId = mService.pathToPortId(path);
        setActivePath(path);
        // TODO: Handle PAP/PIP case.
        // Show OSD port change banner
        if (notifyInputChange) {
            ActiveSource activeSource = getActiveSource();
            HdmiCecDeviceInfo info = getDeviceInfo(activeSource.logicalAddress);
            if (info == null) {
                info = new HdmiCecDeviceInfo(Constants.ADDR_INVALID, path, portId,
                        HdmiCecDeviceInfo.DEVICE_RESERVED, 0, null);
            }
            mService.invokeInputChangeListener(info);
        }
    }

    @ServiceThreadOnly
    void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        // Seq #20
        if (!mService.isValidPortId(portId)) {
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        if (portId == getActivePortId()) {
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        mActiveSource.invalidate();
        if (!mService.isControlEnabled()) {
            setActivePortId(portId);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        // TODO: Return immediately if the operation is triggered by <Text/Image View On>
        // and this is the first notification about the active input after power-on
        // (switch to HDMI didn't happen so far but is expected to happen soon).
        int oldPath = mService.portIdToPath(getActivePortId());
        int newPath = mService.portIdToPath(portId);
        HdmiCecMessage routingChange =
                HdmiCecMessageBuilder.buildRoutingChange(mAddress, oldPath, newPath);
        mService.sendCecCommand(routingChange);
        removeAction(RoutingControlAction.class);
        addAndStartAction(new RoutingControlAction(this, newPath, false, callback));
    }

    int getPowerStatus() {
        return mService.getPowerStatus();
    }

    /**
     * Sends key to a target CEC device.
     *
     * @param keyCode key code to send. Defined in {@link android.view.KeyEvent}.
     * @param isPressed true if this is key press event
     */
    @Override
    @ServiceThreadOnly
    protected void sendKeyEvent(int keyCode, boolean isPressed) {
        assertRunOnServiceThread();
        List<SendKeyAction> action = getActions(SendKeyAction.class);
        if (!action.isEmpty()) {
            action.get(0).processKeyEvent(keyCode, isPressed);
        } else {
            if (isPressed) {
                int logicalAddress = getActiveSource().logicalAddress;
                addAndStartAction(new SendKeyAction(this, logicalAddress, keyCode));
            } else {
                Slog.w(TAG, "Discard key release event");
            }
        }
    }

    private static void invokeCallback(IHdmiControlCallback callback, int result) {
        if (callback == null) {
            return;
        }
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        if (getDeviceInfo(logicalAddress) == null) {
            handleNewDeviceAtTheTailOfActivePath(physicalAddress);
        } else {
            ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
            ActiveSourceHandler.create(this, null).process(activeSource);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInactiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #10

        // Ignore <Inactive Source> from non-active source device.
        if (getActiveSource().logicalAddress != message.getSource()) {
            return true;
        }
        if (isProhibitMode()) {
            return true;
        }
        int portId = getPrevPortId();
        if (portId != Constants.INVALID_PORT_ID) {
            // TODO: Do this only if TV is not showing multiview like PIP/PAP.

            HdmiCecDeviceInfo inactiveSource = getDeviceInfo(message.getSource());
            if (inactiveSource == null) {
                return true;
            }
            if (mService.pathToPortId(inactiveSource.getPhysicalAddress()) == portId) {
                return true;
            }
            // TODO: Switch the TV freeze mode off

            doManualPortSwitching(portId, null);
            setPrevPortId(Constants.INVALID_PORT_ID);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #19
        if (mAddress == getActiveSource().logicalAddress) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildActiveSource(mAddress, getActivePath()));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiCecMessage command = HdmiCecMessageBuilder.buildSetMenuLanguageCommand(
                mAddress, Locale.getDefault().getISO3Language());
        // TODO: figure out how to handle failed to get language code.
        if (command != null) {
            mService.sendCecCommand(command);
        } else {
            Slog.w(TAG, "Failed to respond to <Get Menu Language>: " + message.toString());
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Ignore if [Device Discovery Action] is going on.
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignore unrecognizable <Report Physical Address> "
                    + "because Device Discovery Action is on-going:" + message);
            return true;
        }

        int path = HdmiUtils.twoBytesToInt(message.getParams());
        int address = message.getSource();
        if (!isInDeviceList(path, address)) {
            handleNewDeviceAtTheTailOfActivePath(path);
        }
        startNewDeviceAction(ActiveSource.of(address, path));
        return true;
    }

    void startNewDeviceAction(ActiveSource activeSource) {
        for (NewDeviceAction action : getActions(NewDeviceAction.class)) {
            // If there is new device action which has the same logical address and path
            // ignore new request.
            // NewDeviceAction is created whenever it receives <Report Physical Address>.
            // And there is a chance starting NewDeviceAction for the same source.
            // Usually, new device sends <Report Physical Address> when it's plugged
            // in. However, TV can detect a new device from HotPlugDetectionAction,
            // which sends <Give Physical Address> to the source for newly detected
            // device.
            if (action.isActionOf(activeSource)) {
                return;
            }
        }

        addAndStartAction(new NewDeviceAction(this, activeSource.logicalAddress,
                activeSource.physicalAddress));
    }

    private void handleNewDeviceAtTheTailOfActivePath(int path) {
        // Seq #22
        if (isTailOfActivePath(path, getActivePath())) {
            removeAction(RoutingControlAction.class);
            int newPath = mService.portIdToPath(getActivePortId());
            setActivePath(newPath);
            mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(
                    mAddress, getActivePath(), newPath));
            addAndStartAction(new RoutingControlAction(this, newPath, false, null));
        }
    }

    /**
     * Whether the given path is located in the tail of current active path.
     *
     * @param path to be tested
     * @param activePath current active path
     * @return true if the given path is located in the tail of current active path; otherwise,
     *         false
     */
    static boolean isTailOfActivePath(int path, int activePath) {
        // If active routing path is internal source, return false.
        if (activePath == 0) {
            return false;
        }
        for (int i = 12; i >= 0; i -= 4) {
            int curActivePath = (activePath >> i) & 0xF;
            if (curActivePath == 0) {
                return true;
            } else {
                int curPath = (path >> i) & 0xF;
                if (curPath != curActivePath) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #21
        byte[] params = message.getParams();
        int currentPath = HdmiUtils.twoBytesToInt(params);
        if (HdmiUtils.isAffectingActiveRoutingPath(getActivePath(), currentPath)) {
            mActiveSource.invalidate();
            removeAction(RoutingControlAction.class);
            int newPath = HdmiUtils.twoBytesToInt(params, 2);
            addAndStartAction(new RoutingControlAction(this, newPath, true, null));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        byte params[] = message.getParams();
        int mute = params[0] & 0x80;
        int volume = params[0] & 0x7F;
        setAudioStatus(mute == 0x80, volume);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleTextViewOn(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService.isPowerStandbyOrTransient() && mAutoWakeup) {
            mService.wakeUp();
        }
        // TODO: Connect to Hardware input manager to invoke TV App with the appropriate channel
        //       that represents the source device.
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleImageViewOn(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Currently, it's the same as <Text View On>.
        return handleTextViewOn(message);
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetOsdName(HdmiCecMessage message) {
        int source = message.getSource();
        HdmiCecDeviceInfo deviceInfo = getDeviceInfo(source);
        // If the device is not in device list, ignore it.
        if (deviceInfo == null) {
            Slog.e(TAG, "No source device info for <Set Osd Name>." + message);
            return true;
        }
        String osdName = null;
        try {
            osdName = new String(message.getParams(), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            Slog.e(TAG, "Invalid <Set Osd Name> request:" + message, e);
            return true;
        }

        if (deviceInfo.getDisplayName().equals(osdName)) {
            Slog.i(TAG, "Ignore incoming <Set Osd Name> having same osd name:" + message);
            return true;
        }

        addCecDevice(new HdmiCecDeviceInfo(deviceInfo.getLogicalAddress(),
                deviceInfo.getPhysicalAddress(), deviceInfo.getPortId(),
                deviceInfo.getDeviceType(), deviceInfo.getVendorId(), osdName));
        return true;
    }

    @ServiceThreadOnly
    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        clearDeviceInfoList();
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this,
                new DeviceDiscoveryCallback() {
                    @Override
                    public void onDeviceDiscoveryDone(List<HdmiCecDeviceInfo> deviceInfos) {
                        for (HdmiCecDeviceInfo info : deviceInfos) {
                            addCecDevice(info);
                        }

                        // Since we removed all devices when it's start and
                        // device discovery action does not poll local devices,
                        // we should put device info of local device manually here
                        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
                            addCecDevice(device.getDeviceInfo());
                        }

                        addAndStartAction(new HotplugDetectionAction(HdmiCecLocalDeviceTv.this));

                        // If there is AVR, initiate System Audio Auto initiation action,
                        // which turns on and off system audio according to last system
                        // audio setting.
                        if (mSystemAudioActivated && getAvrDeviceInfo() != null) {
                            addAndStartAction(new SystemAudioAutoInitiationAction(
                                    HdmiCecLocalDeviceTv.this,
                                    getAvrDeviceInfo().getLogicalAddress()));
                            if (mArcEstablished) {
                                startArcAction(true);
                            }
                        }
                    }
                });
        addAndStartAction(action);
    }

    // Clear all device info.
    @ServiceThreadOnly
    private void clearDeviceInfoList() {
        assertRunOnServiceThread();
        for (HdmiCecDeviceInfo info : mSafeExternalInputs) {
            mService.invokeDeviceEventListeners(info, false);
        }
        mDeviceInfos.clear();
        updateSafeDeviceInfoList();
    }

    @ServiceThreadOnly
    // Seq #32
    void changeSystemAudioMode(boolean enabled, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled() || hasAction(DeviceDiscoveryAction.class)) {
            setSystemAudioMode(false, true);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            setSystemAudioMode(false, true);
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }

        addAndStartAction(
                new SystemAudioActionFromTv(this, avr.getLogicalAddress(), enabled, callback));
    }

    // # Seq 25
    void setSystemAudioMode(boolean on, boolean updateSetting) {
        if (updateSetting) {
            mService.writeBooleanSetting(Global.HDMI_SYSTEM_AUDIO_ENABLED, on);
        }
        updateAudioManagerForSystemAudio(on);
        synchronized (mLock) {
            if (mSystemAudioActivated != on) {
                mSystemAudioActivated = on;
                mService.announceSystemAudioModeChange(on);
            }
        }
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        // TODO: remove output device, once update AudioManager api.
        mService.getAudioManager().setHdmiSystemAudioSupported(on);
    }

    boolean isSystemAudioActivated() {
        if (getAvrDeviceInfo() == null) {
            return false;
        }
        synchronized (mLock) {
            return mSystemAudioActivated;
        }
    }

    boolean getSystemAudioModeSetting() {
        return mService.readBooleanSetting(Global.HDMI_SYSTEM_AUDIO_ENABLED, false);
    }

    /**
     * Change ARC status into the given {@code enabled} status.
     *
     * @return {@code true} if ARC was in "Enabled" status
     */
    @ServiceThreadOnly
    boolean setArcStatus(boolean enabled) {
        assertRunOnServiceThread();
        boolean oldStatus = mArcEstablished;
        // 1. Enable/disable ARC circuit.
        mService.setAudioReturnChannel(enabled);
        // 2. Notify arc status to audio service.
        notifyArcStatusToAudioService(enabled);
        // 3. Update arc status;
        mArcEstablished = enabled;
        return oldStatus;
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        // Note that we don't set any name to ARC.
        mService.getAudioManager().setWiredDeviceConnectionState(
                AudioSystem.DEVICE_OUT_HDMI_ARC,
                enabled ? 1 : 0, "");
    }

    /**
     * Returns whether ARC is enabled or not.
     */
    @ServiceThreadOnly
    boolean isArcEstabilished() {
        assertRunOnServiceThread();
        return mArcFeatureEnabled && mArcEstablished;
    }

    @ServiceThreadOnly
    void changeArcFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();

        if (mArcFeatureEnabled != enabled) {
            if (enabled) {
                if (!mArcEstablished) {
                    startArcAction(true);
                }
            } else {
                if (mArcEstablished) {
                    startArcAction(false);
                }
            }
            mArcFeatureEnabled = enabled;
        }
    }

    @ServiceThreadOnly
    boolean isArcFeatureEnabled() {
        assertRunOnServiceThread();
        return mArcFeatureEnabled;
    }

    @ServiceThreadOnly
    private void startArcAction(boolean enabled) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo info = getAvrDeviceInfo();
        if (info == null) {
            return;
        }
        if (!isConnectedToArcPort(info.getPhysicalAddress())) {
            return;
        }

        // Terminate opposite action and start action if not exist.
        if (enabled) {
            removeAction(RequestArcTerminationAction.class);
            if (!hasAction(RequestArcInitiationAction.class)) {
                addAndStartAction(new RequestArcInitiationAction(this, info.getLogicalAddress()));
            }
        } else {
            removeAction(RequestArcInitiationAction.class);
            if (!hasAction(RequestArcTerminationAction.class)) {
                addAndStartAction(new RequestArcTerminationAction(this, info.getLogicalAddress()));
            }
        }
    }

    void setAudioStatus(boolean mute, int volume) {
        synchronized (mLock) {
            mSystemAudioMute = mute;
            mSystemAudioVolume = volume;
            int maxVolume = mService.getAudioManager().getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC);
            mService.setAudioStatus(mute,
                    VolumeControlAction.scaleToCustomVolume(volume, maxVolume));
        }
    }

    @ServiceThreadOnly
    void changeVolume(int curVolume, int delta, int maxVolume) {
        assertRunOnServiceThread();
        if (delta == 0 || !isSystemAudioActivated()) {
            return;
        }

        int targetVolume = curVolume + delta;
        int cecVolume = VolumeControlAction.scaleToCecVolume(targetVolume, maxVolume);
        synchronized (mLock) {
            // If new volume is the same as current system audio volume, just ignore it.
            // Note that UNKNOWN_VOLUME is not in range of cec volume scale.
            if (cecVolume == mSystemAudioVolume) {
                // Update tv volume with system volume value.
                mService.setAudioStatus(false,
                        VolumeControlAction.scaleToCustomVolume(mSystemAudioVolume, maxVolume));
                return;
            }
        }

        // Remove existing volume action.
        removeAction(VolumeControlAction.class);

        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        addAndStartAction(VolumeControlAction.ofVolumeChange(this, avr.getLogicalAddress(),
                cecVolume, delta > 0));
    }

    @ServiceThreadOnly
    void changeMute(boolean mute) {
        assertRunOnServiceThread();
        if (!isSystemAudioActivated()) {
            return;
        }

        // Remove existing volume action.
        removeAction(VolumeControlAction.class);
        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        addAndStartAction(VolumeControlAction.ofMute(this, avr.getLogicalAddress(), mute));
    }

    private boolean isSystemAudioOn() {


        synchronized (mLock) {
            return mSystemAudioActivated;
        }
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // In case where <Initiate Arc> is started by <Request ARC Initiation>
        // need to clean up RequestArcInitiationAction.
        removeAction(RequestArcInitiationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), true);
        addAndStartAction(action);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleTerminateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // In case where <Terminate Arc> is started by <Request ARC Termination>
        // need to clean up RequestArcInitiationAction.
        removeAction(RequestArcTerminationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), false);
        addAndStartAction(action);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            return false;
        }
        SystemAudioActionFromAvr action = new SystemAudioActionFromAvr(this,
                message.getSource(), HdmiUtils.parseCommandParamSystemAudioStatus(message), null);
        addAndStartAction(action);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            return false;
        }
        setSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message), true);
        return true;
    }

    // Seq #53
    @Override
    @ServiceThreadOnly
    protected boolean handleRecordTvScreen(HdmiCecMessage message) {
        List<OneTouchRecordAction> actions = getActions(OneTouchRecordAction.class);
        if (!actions.isEmpty()) {
            // Assumes only one OneTouchRecordAction.
            OneTouchRecordAction action = actions.get(0);
            if (action.getRecorderAddress() != message.getSource()) {
                announceOneTouchRecordResult(
                        HdmiControlManager.ONE_TOUCH_RECORD_PREVIOUS_RECORDING_IN_PROGRESS);
            }
            return super.handleRecordTvScreen(message);
        }

        int recorderAddress = message.getSource();
        byte[] recordSource = mService.invokeRecordRequestListener(recorderAddress);
        startOneTouchRecord(recorderAddress, recordSource);
        return true;
    }

    void announceOneTouchRecordResult(int result) {
        mService.invokeOneTouchRecordResult(result);
    }

    void announceTimerRecordingResult(int result) {
        mService.invokeTimerRecordingResult(result);
    }

    private boolean isMessageForSystemAudio(HdmiCecMessage message) {
        if (message.getSource() != Constants.ADDR_AUDIO_SYSTEM
                || message.getDestination() != Constants.ADDR_TV
                || getAvrDeviceInfo() == null) {
            Slog.w(TAG, "Skip abnormal CecMessage: " + message);
            return false;
        }
        return true;
    }

    /**
     * Add a new {@link HdmiCecDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param deviceInfo a new {@link HdmiCecDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiCecDeviceInfo}
     *         that has the same logical address as new one has.
     */
    @ServiceThreadOnly
    private HdmiCecDeviceInfo addDeviceInfo(HdmiCecDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo oldDeviceInfo = getDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getLogicalAddress());
        }
        mDeviceInfos.append(deviceInfo.getLogicalAddress(), deviceInfo);
        updateSafeDeviceInfoList();
        return oldDeviceInfo;
    }

    /**
     * Remove a device info corresponding to the given {@code logicalAddress}.
     * It returns removed {@link HdmiCecDeviceInfo} if exists.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address of device to be removed
     * @return removed {@link HdmiCecDeviceInfo} it exists. Otherwise, returns {@code null}
     */
    @ServiceThreadOnly
    private HdmiCecDeviceInfo removeDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo deviceInfo = mDeviceInfos.get(logicalAddress);
        if (deviceInfo != null) {
            mDeviceInfos.remove(logicalAddress);
        }
        updateSafeDeviceInfoList();
        return deviceInfo;
    }

    /**
     * Return a list of all {@link HdmiCecDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     * This is not thread-safe. For thread safety, call {@link #getSafeDeviceInfoList(boolean)}.
     */
    @ServiceThreadOnly
    List<HdmiCecDeviceInfo> getDeviceInfoList(boolean includelLocalDevice) {
        assertRunOnServiceThread();
        if (includelLocalDevice) {
            return HdmiUtils.sparseArrayToList(mDeviceInfos);
        } else {
            ArrayList<HdmiCecDeviceInfo> infoList = new ArrayList<>();
            for (int i = 0; i < mDeviceInfos.size(); ++i) {
                HdmiCecDeviceInfo info = mDeviceInfos.valueAt(i);
                if (!isLocalDeviceAddress(info.getLogicalAddress())) {
                    infoList.add(info);
                }
            }
            return infoList;
        }
    }

    /**
     * Return external input devices.
     */
    List<HdmiCecDeviceInfo> getSafeExternalInputs() {
        synchronized (mLock) {
            return mSafeExternalInputs;
        }
    }

    @ServiceThreadOnly
    private void updateSafeDeviceInfoList() {
        assertRunOnServiceThread();
        List<HdmiCecDeviceInfo> copiedDevices = HdmiUtils.sparseArrayToList(mDeviceInfos);
        List<HdmiCecDeviceInfo> externalInputs = getInputDevices();
        synchronized (mLock) {
            mSafeAllDeviceInfos = copiedDevices;
            mSafeExternalInputs = externalInputs;
        }
    }

    /**
     * Return a list of external cec input (source) devices.
     *
     * <p>Note that this effectively excludes non-source devices like system audio,
     * secondary TV.
     */
    private List<HdmiCecDeviceInfo> getInputDevices() {
        ArrayList<HdmiCecDeviceInfo> infoList = new ArrayList<>();
        for (int i = 0; i < mDeviceInfos.size(); ++i) {
            HdmiCecDeviceInfo info = mDeviceInfos.valueAt(i);
            if (isLocalDeviceAddress(i)) {
                continue;
            }
            if (info.isSourceType()) {
                infoList.add(info);
            }
        }
        return infoList;
    }

    @ServiceThreadOnly
    private boolean isLocalDeviceAddress(int address) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
            if (device.isAddressOf(address)) {
                return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    HdmiCecDeviceInfo getAvrDeviceInfo() {
        assertRunOnServiceThread();
        return getDeviceInfo(Constants.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Return a {@link HdmiCecDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     * This is not thread-safe. For thread safety, call {@link #getSafeDeviceInfo(int)}.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    @ServiceThreadOnly
    HdmiCecDeviceInfo getDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mDeviceInfos.get(logicalAddress);
    }

    boolean hasSystemAudioDevice() {
        return getSafeAvrDeviceInfo() != null;
    }

    HdmiCecDeviceInfo getSafeAvrDeviceInfo() {
        return getSafeDeviceInfo(Constants.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Thread safe version of {@link #getDeviceInfo(int)}.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    HdmiCecDeviceInfo getSafeDeviceInfo(int logicalAddress) {
        synchronized (mLock) {
            return mSafeAllDeviceInfos.get(logicalAddress);
        }
    }

    /**
     * Called when a device is newly added or a new device is detected or
     * existing device is updated.
     *
     * @param info device info of a new device.
     */
    @ServiceThreadOnly
    final void addCecDevice(HdmiCecDeviceInfo info) {
        assertRunOnServiceThread();
        addDeviceInfo(info);
        if (info.getLogicalAddress() == mAddress) {
            // The addition of TV device itself should not be notified.
            return;
        }
        mService.invokeDeviceEventListeners(info, true);
    }

    /**
     * Called when a device is removed or removal of device is detected.
     *
     * @param address a logical address of a device to be removed
     */
    @ServiceThreadOnly
    final void removeCecDevice(int address) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo info = removeDeviceInfo(address);

        mCecMessageCache.flushMessagesFrom(address);
        mService.invokeDeviceEventListeners(info, false);
    }

    @ServiceThreadOnly
    void handleRemoveActiveRoutingPath(int path) {
        assertRunOnServiceThread();
        // Seq #23
        if (isTailOfActivePath(path, getActivePath())) {
            removeAction(RoutingControlAction.class);
            int newPath = mService.portIdToPath(getActivePortId());
            mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(
                    mAddress, getActivePath(), newPath));
            addAndStartAction(new RoutingControlAction(this, getActivePortId(), true, null));
        }
    }

    /**
     * Launch routing control process.
     *
     * @param routingForBootup true if routing control is initiated due to One Touch Play
     *        or TV power on
     */
    @ServiceThreadOnly
    void launchRoutingControl(boolean routingForBootup) {
        assertRunOnServiceThread();
        // Seq #24
        if (getActivePortId() != Constants.INVALID_PORT_ID) {
            if (!routingForBootup && !isProhibitMode()) {
                removeAction(RoutingControlAction.class);
                int newPath = mService.portIdToPath(getActivePortId());
                setActivePath(newPath);
                mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(mAddress,
                        getActivePath(), newPath));
                addAndStartAction(new RoutingControlAction(this, getActivePortId(),
                        routingForBootup, null));
            }
        } else {
            int activePath = mService.getPhysicalAddress();
            setActivePath(activePath);
            if (!routingForBootup) {
                mService.sendCecCommand(HdmiCecMessageBuilder.buildActiveSource(mAddress,
                        activePath));
            }
        }
    }

    /**
     * Returns the {@link HdmiCecDeviceInfo} instance whose physical address matches
     * the given routing path. CEC devices use routing path for its physical address to
     * describe the hierarchy of the devices in the network.
     *
     * @param path routing path or physical address
     * @return {@link HdmiCecDeviceInfo} if the matched info is found; otherwise null
     */
    @ServiceThreadOnly
    final HdmiCecDeviceInfo getDeviceInfoByPath(int path) {
        assertRunOnServiceThread();
        for (HdmiCecDeviceInfo info : getDeviceInfoList(false)) {
            if (info.getPhysicalAddress() == path) {
                return info;
            }
        }
        return null;
    }

    /**
     * Whether a device of the specified physical address and logical address exists
     * in a device info list. However, both are minimal condition and it could
     * be different device from the original one.
     *
     * @param logicalAddress logical address of a device to be searched
     * @param physicalAddress physical address of a device to be searched
     * @return true if exist; otherwise false
     */
    @ServiceThreadOnly
    boolean isInDeviceList(int logicalAddress, int physicalAddress) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo device = getDeviceInfo(logicalAddress);
        if (device == null) {
            return false;
        }
        return device.getPhysicalAddress() == physicalAddress;
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();

        // Tv device will have permanent HotplugDetectionAction.
        List<HotplugDetectionAction> hotplugActions = getActions(HotplugDetectionAction.class);
        if (!hotplugActions.isEmpty()) {
            // Note that hotplug action is single action running on a machine.
            // "pollAllDevicesNow" cleans up timer and start poll action immediately.
            // It covers seq #40, #43.
            hotplugActions.get(0).pollAllDevicesNow();
        }
    }

    @ServiceThreadOnly
    void setAutoDeviceOff(boolean enabled) {
        assertRunOnServiceThread();
        mAutoDeviceOff = enabled;
        mService.writeBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, enabled);
    }

    @ServiceThreadOnly
    void setAutoWakeup(boolean enabled) {
        assertRunOnServiceThread();
        mAutoWakeup = enabled;
        mService.writeBooleanSetting(Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED, enabled);
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);
        assertRunOnServiceThread();
        // Remove any repeated working actions.
        // HotplugDetectionAction will be reinstated during the wake up process.
        // HdmiControlService.onWakeUp() -> initializeLocalDevices() ->
        //     LocalDeviceTv.onAddressAllocated() -> launchDeviceDiscovery().
        removeAction(DeviceDiscoveryAction.class);
        removeAction(HotplugDetectionAction.class);
        // Remove one touch record action.
        removeAction(OneTouchRecordAction.class);

        disableSystemAudioIfExist();
        disableArcIfExist();
        clearDeviceInfoList();
        checkIfPendingActionsCleared();
    }

    @ServiceThreadOnly
    private void disableSystemAudioIfExist() {
        assertRunOnServiceThread();
        if (getAvrDeviceInfo() == null) {
            return;
        }

        // Seq #31.
        removeAction(SystemAudioActionFromAvr.class);
        removeAction(SystemAudioActionFromTv.class);
        removeAction(SystemAudioAutoInitiationAction.class);
        removeAction(SystemAudioStatusAction.class);
        removeAction(VolumeControlAction.class);

        // Turn off the mode but do not write it the settings, so that the next time TV powers on
        // the system audio mode setting can be restored automatically.
        setSystemAudioMode(false, false);
    }

    @ServiceThreadOnly
    private void disableArcIfExist() {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            return;
        }

        // Seq #44.
        removeAction(RequestArcInitiationAction.class);
        if (!hasAction(RequestArcTerminationAction.class) && isArcEstabilished()) {
            addAndStartAction(new RequestArcTerminationAction(this, avr.getLogicalAddress()));
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec) {
        assertRunOnServiceThread();
        // Seq #11
        if (!mService.isControlEnabled()) {
            return;
        }
        if (!initiatedByCec && mAutoDeviceOff) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildStandby(
                    mAddress, Constants.ADDR_BROADCAST));
        }
    }

    boolean isProhibitMode() {
        return mService.isProhibitMode();
    }

    boolean isPowerStandbyOrTransient() {
        return mService.isPowerStandbyOrTransient();
    }

    void displayOsd(int messageId) {
        Intent intent = new Intent(HdmiControlManager.ACTION_OSD_MESSAGE);
        intent.putExtra(HdmiControlManager.EXTRA_MESSAGE_ID, messageId);
        mService.getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                HdmiControlService.PERMISSION);
    }

    // Seq #54 and #55
    @ServiceThreadOnly
    void startOneTouchRecord(int recorderAddress, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceOneTouchRecordResult(ONE_TOUCH_RECORD_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceOneTouchRecordResult(ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
            return;
        }

        if (!checkRecordSource(recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceOneTouchRecordResult(ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN);
            return;
        }

        addAndStartAction(new OneTouchRecordAction(this, recorderAddress, recordSource));
        Slog.i(TAG, "Start new [One Touch Record]-Target:" + recorderAddress + ", recordSource:"
                + Arrays.toString(recordSource));
    }

    @ServiceThreadOnly
    void stopOneTouchRecord(int recorderAddress) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not stop one touch record. CEC control is disabled.");
            announceOneTouchRecordResult(ONE_TOUCH_RECORD_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceOneTouchRecordResult(ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
            return;
        }

        // Remove one touch record action so that other one touch record can be started.
        removeAction(OneTouchRecordAction.class);
        mService.sendCecCommand(HdmiCecMessageBuilder.buildRecordOff(mAddress, recorderAddress));
        Slog.i(TAG, "Stop [One Touch Record]-Target:" + recorderAddress);
    }

    private boolean checkRecorder(int recorderAddress) {
        HdmiCecDeviceInfo device = getDeviceInfo(recorderAddress);
        return (device != null)
                && (HdmiUtils.getTypeFromAddress(recorderAddress)
                        == HdmiCecDeviceInfo.DEVICE_RECORDER);
    }

    private boolean checkRecordSource(byte[] recordSource) {
        return (recordSource != null) && HdmiRecordSources.checkRecordSource(recordSource);
    }

    @ServiceThreadOnly
    void startTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceTimerRecordingResult(TIME_RECORDING_RESULT_EXTRA_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceTimerRecordingResult(
                    TIME_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION);
            return;
        }

        if (!checkTimerRecordingSource(sourceType, recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceTimerRecordingResult(
                    TIME_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE);
            return;
        }

        addAndStartAction(
                new TimerRecordingAction(this, recorderAddress, sourceType, recordSource));
        Slog.i(TAG, "Start [Timer Recording]-Target:" + recorderAddress + ", SourceType:"
                + sourceType + ", RecordSource:" + Arrays.toString(recordSource));
    }

    private boolean checkTimerRecordingSource(int sourceType, byte[] recordSource) {
        return (recordSource != null)
                && HdmiTimerRecordSources.checkTimerRecordSource(sourceType, recordSource);
    }

    @ServiceThreadOnly
    void clearTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
        assertRunOnServiceThread();

        // TODO: implement this.
    }
}
