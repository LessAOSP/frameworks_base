/*
 * Copyright (C) 2010 The Android Open Source Project
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


package android.hardware;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;

/**
 * This class allows you to access the state of USB, both in host and device mode.
 *
 * <p>You can obtain an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String) Context.getSystemService()}.
 *
 * {@samplecode
 * UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
 * }
 */
public class UsbManager {
    private static final String TAG = "UsbManager";

   /**
     * Broadcast Action:  A sticky broadcast for USB state change events when in device mode.
     *
     * This is a sticky broadcast for clients that includes USB connected/disconnected state,
     * <ul>
     * <li> {@link #USB_CONNECTED} boolean indicating whether USB is connected or disconnected.
     * <li> {@link #USB_CONFIGURATION} a Bundle containing name/value pairs where the name
     * is the name of a USB function and the value is either {@link #USB_FUNCTION_ENABLED}
     * or {@link #USB_FUNCTION_DISABLED}.  The possible function names include
     * {@link #USB_FUNCTION_MASS_STORAGE}, {@link #USB_FUNCTION_ADB}, {@link #USB_FUNCTION_RNDIS},
     * {@link #USB_FUNCTION_MTP} and {@link #USB_FUNCTION_ACCESSORY}.
     * </ul>
     */
    public static final String ACTION_USB_STATE =
            "android.hardware.action.USB_STATE";

   /**
     * Broadcast Action:  A broadcast for USB device attached event.
     *
     * This intent is sent when a USB device is attached to the USB bus when in host mode.
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the {@link android.hardware.UsbDevice}
     * for the attached device
     * </ul>
     */
    public static final String ACTION_USB_DEVICE_ATTACHED =
            "android.hardware.action.USB_DEVICE_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for USB device detached event.
     *
     * This intent is sent when a USB device is detached from the USB bus when in host mode.
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the {@link android.hardware.UsbDevice}
     * for the detached device
     * </ul>
     */
    public static final String ACTION_USB_DEVICE_DETACHED =
            "android.hardware.action.USB_DEVICE_DETACHED";

   /**
     * Broadcast Action:  A broadcast for USB accessory attached event.
     *
     * This intent is sent when a USB accessory is attached.
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the {@link android.hardware.UsbAccessory}
     * for the attached accessory
     * </ul>
     */
    public static final String ACTION_USB_ACCESSORY_ATTACHED =
            "android.hardware.action.USB_ACCESSORY_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for USB accessory detached event.
     *
     * This intent is sent when a USB accessory is detached.
     * <ul>
      * <li> {@link #EXTRA_ACCESSORY} containing the {@link android.hardware.UsbAccessory}
     * for the attached accessory that was detached
     * </ul>
     */
    public static final String ACTION_USB_ACCESSORY_DETACHED =
            "android.hardware.action.USB_ACCESSORY_DETACHED";

    /**
     * Boolean extra indicating whether USB is connected or disconnected.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     */
    public static final String USB_CONNECTED = "connected";

    /**
     * Integer extra containing currently set USB configuration.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     */
    public static final String USB_CONFIGURATION = "configuration";

    /**
     * Name of the USB mass storage USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_MASS_STORAGE = "mass_storage";

    /**
     * Name of the adb USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_ADB = "adb";

    /**
     * Name of the RNDIS ethernet USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_RNDIS = "rndis";

    /**
     * Name of the MTP USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_MTP = "mtp";

    /**
     * Name of the Accessory USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_ACCESSORY = "accessory";

    /**
     * Value indicating that a USB function is enabled.
     * Used in {@link #USB_CONFIGURATION} extras bundle for the
     * {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_ENABLED = "enabled";

    /**
     * Value indicating that a USB function is disabled.
     * Used in {@link #USB_CONFIGURATION} extras bundle for the
     * {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_DISABLED = "disabled";

    /**
     * Name of extra for {@link #ACTION_USB_DEVICE_ATTACHED} and
     * {@link #ACTION_USB_DEVICE_DETACHED} broadcasts.
     * containing the UsbDevice object for the device.
     */

    public static final String EXTRA_DEVICE = "device";

    /**
     * Name of extra for {@link #ACTION_USB_ACCESSORY_ATTACHED} broadcast
     * containing the UsbAccessory object for the accessory.
     */
    public static final String EXTRA_ACCESSORY = "accessory";

    private IUsbManager mService;

    /**
     * {@hide}
     */
    public UsbManager(IUsbManager service) {
        mService = service;
    }

    /**
     * Returns a HashMap containing all USB devices currently attached.
     * USB device name is the key for the returned HashMap.
     * The result will be empty if no devices are attached, or if
     * USB host mode is inactive or unsupported.
     *
     * @return HashMap containing all connected USB devices.
     */
    public HashMap<String,UsbDevice> getDeviceList() {
        Bundle bundle = new Bundle();
        try {
            mService.getDeviceList(bundle);
            HashMap<String,UsbDevice> result = new HashMap<String,UsbDevice>();
            for (String name : bundle.keySet()) {
                result.put(name, (UsbDevice)bundle.get(name));
            }
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getDeviceList", e);
            return null;
        }
    }

    /**
     * Opens the device so it can be used to send and receive
     * data using {@link android.hardware.UsbRequest}.
     *
     * @param device the device to open
     * @return true if we successfully opened the device
     */
    public boolean openDevice(UsbDevice device) {
        try {
            ParcelFileDescriptor pfd = mService.openDevice(device.getDeviceName());
            if (pfd == null) {
                return false;
            }
            boolean result = device.open(pfd);
            pfd.close();
            return result;
        } catch (Exception e) {
            Log.e(TAG, "exception in UsbManager.openDevice", e);
            return false;
        }
    }

    /**
     * Returns a list of currently attached USB accessories.
     * (in the current implementation there can be at most one)
     *
     * @return list of USB accessories, or null if none are attached.
     */
    public UsbAccessory[] getAccessoryList() {
        try {
            UsbAccessory accessory = mService.getCurrentAccessory();
            if (accessory == null) {
                return null;
            } else {
                return new UsbAccessory[] { accessory };
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openAccessory" , e);
            return null;
        }
    }

    /**
     * Opens a file descriptor for reading and writing data to the USB accessory.
     *
     * @param accessory the USB accessory to open
     * @return file descriptor, or null if the accessor could not be opened.
     */
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        try {
            return mService.openAccessory();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openAccessory" , e);
            return null;
        }
    }

    private static File getFunctionEnableFile(String function) {
        return new File("/sys/class/usb_composite/" + function + "/enable");
    }

    /**
     * Returns true if the specified USB function is supported by the kernel.
     * Note that a USB function maybe supported but disabled.
     *
     * @param function name of the USB function
     * @return true if the USB function is supported.
     */
    public static boolean isFunctionSupported(String function) {
        return getFunctionEnableFile(function).exists();
    }

    /**
     * Returns true if the specified USB function is currently enabled.
     *
     * @param function name of the USB function
     * @return true if the USB function is enabled.
     */
    public static boolean isFunctionEnabled(String function) {
        try {
            FileInputStream stream = new FileInputStream(getFunctionEnableFile(function));
            boolean enabled = (stream.read() == '1');
            stream.close();
            return enabled;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Enables or disables a USB function.
     *
     * @hide
     */
    public static boolean setFunctionEnabled(String function, boolean enable) {
        try {
            FileOutputStream stream = new FileOutputStream(getFunctionEnableFile(function));
            stream.write(enable ? '1' : '0');
            stream.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
