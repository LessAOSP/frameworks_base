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

package com.android.server.devicepolicy;

import android.app.AppGlobals;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;

/**
 * Stores and restores state for the Device and Profile owners. By definition there can be
 * only one device owner, but there may be a profile owner for each user.
 */
public class DeviceOwner {
    private static final String TAG = "DevicePolicyManagerService";

    private static final String DEVICE_OWNER_XML = "device_owner.xml";
    private static final String TAG_DEVICE_OWNER = "device-owner";
    private static final String TAG_PROFILE_OWNER = "profile-owner";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_USERID = "userId";
    private static final String ATTR_ENABLED = "profileEnabled";

    private AtomicFile fileForWriting;

    // Input/Output streams for testing.
    private InputStream mInputStreamForTest;
    private OutputStream mOutputStreamForTest;

    // Internal state for the device owner package.
    private OwnerInfo mDeviceOwner;

    // Internal state for the profile owner packages.
    private final HashMap<Integer, OwnerInfo> mProfileOwners = new HashMap<Integer, OwnerInfo>();

    // Private default constructor.
    private DeviceOwner() {
    }

    @VisibleForTesting
    DeviceOwner(InputStream in, OutputStream out) {
        mInputStreamForTest = in;
        mOutputStreamForTest = out;
    }

    /**
     * Loads the device owner state from disk.
     */
    static DeviceOwner load() {
        DeviceOwner owner = new DeviceOwner();
        if (new File(Environment.getSystemSecureDirectory(), DEVICE_OWNER_XML).exists()) {
            owner.readOwnerFile();
            return owner;
        } else {
            return null;
        }
    }

    /**
     * Creates an instance of the device owner object with the device owner set.
     */
    static DeviceOwner createWithDeviceOwner(String packageName, String ownerName) {
        DeviceOwner owner = new DeviceOwner();
        owner.mDeviceOwner = new OwnerInfo(ownerName, packageName);
        return owner;
    }

    /**
     * Creates an instance of the device owner object with the profile owner set.
     */
    static DeviceOwner createWithProfileOwner(String packageName, String ownerName, int userId) {
        DeviceOwner owner = new DeviceOwner();
        owner.mProfileOwners.put(
                userId, new OwnerInfo(ownerName, packageName, false /* disabled */));
        return owner;
    }

    String getDeviceOwnerPackageName() {
        return mDeviceOwner != null ? mDeviceOwner.packageName : null;
    }

    String getDeviceOwnerName() {
        return mDeviceOwner != null ? mDeviceOwner.name : null;
    }

    void setDeviceOwner(String packageName, String ownerName) {
        mDeviceOwner = new OwnerInfo(ownerName, packageName);
    }

    void setProfileOwner(String packageName, String ownerName, int userId) {
        mProfileOwners.put(userId, new OwnerInfo(ownerName, packageName, false /* disabled */));
    }

    void removeProfileOwner(int userId) {
        mProfileOwners.remove(userId);
    }

    String getProfileOwnerPackageName(int userId) {
        OwnerInfo profileOwner = mProfileOwners.get(userId);
        return profileOwner != null ? profileOwner.packageName : null;
    }

    String getProfileOwnerName(int userId) {
        OwnerInfo profileOwner = mProfileOwners.get(userId);
        return profileOwner != null ? profileOwner.name : null;
    }

    boolean isProfileEnabled(int userId) {
        OwnerInfo profileOwner = mProfileOwners.get(userId);
        return profileOwner != null ? profileOwner.enabled : true;
    }

    void setProfileEnabled(int userId) {
        OwnerInfo profileOwner = mProfileOwners.get(userId);
        if (profileOwner == null) {
            throw new IllegalArgumentException("No profile owner exists.");
        }
        profileOwner.enabled = true;
    }

    boolean hasDeviceOwner() {
        return mDeviceOwner != null;
    }

    static boolean isInstalled(String packageName, PackageManager pm) {
        try {
            PackageInfo pi;
            if ((pi = pm.getPackageInfo(packageName, 0)) != null) {
                if ((pi.applicationInfo.flags) != 0) {
                    return true;
                }
            }
        } catch (NameNotFoundException nnfe) {
            Slog.w(TAG, "Device Owner package " + packageName + " not installed.");
        }
        return false;
    }

    static boolean isInstalledForUser(String packageName, int userHandle) {
        try {
            PackageInfo pi = (AppGlobals.getPackageManager())
                    .getPackageInfo(packageName, 0, userHandle);
            if (pi != null && pi.applicationInfo.flags != 0) {
                return true;
            }
        } catch (RemoteException re) {
            throw new RuntimeException("Package manager has died", re);
        }

        return false;
    }

    void readOwnerFile() {
        try {
            InputStream input = openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type!=XmlPullParser.START_TAG) {
                    continue;
                }

                String tag = parser.getName();
                if (tag.equals(TAG_DEVICE_OWNER)) {
                    mDeviceOwner = new OwnerInfo(
                            parser.getAttributeValue(null, ATTR_NAME),
                            parser.getAttributeValue(null, ATTR_PACKAGE));
                } else if (tag.equals(TAG_PROFILE_OWNER)) {
                    String profileOwnerPackageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    String profileOwnerName = parser.getAttributeValue(null, ATTR_NAME);
                    Boolean profileEnabled = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_ENABLED));
                    int userId = Integer.parseInt(parser.getAttributeValue(null, ATTR_USERID));
                    mProfileOwners.put(userId,
                            new OwnerInfo(
                                    profileOwnerPackageName, profileOwnerName, profileEnabled));
                } else {
                    throw new XmlPullParserException(
                            "Unexpected tag in device owner file: " + tag);
                }
            }
            input.close();
        } catch (XmlPullParserException xppe) {
            Slog.e(TAG, "Error parsing device-owner file\n" + xppe);
        } catch (IOException ioe) {
            Slog.e(TAG, "IO Exception when reading device-owner file\n" + ioe);
        }
    }

    void writeOwnerFile() {
        synchronized (this) {
            writeOwnerFileLocked();
        }
    }

    private void writeOwnerFileLocked() {
        try {
            OutputStream outputStream = startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(outputStream, "utf-8");
            out.startDocument(null, true);

            // Write device owner tag
            if (mDeviceOwner != null) {
                out.startTag(null, TAG_DEVICE_OWNER);
                out.attribute(null, ATTR_PACKAGE, mDeviceOwner.packageName);
                if (mDeviceOwner.packageName != null) {
                    out.attribute(null, ATTR_NAME, mDeviceOwner.packageName);
                }
                out.endTag(null, TAG_DEVICE_OWNER);
            }

            // Write profile owner tags
            if (mProfileOwners.size() > 0) {
                for (HashMap.Entry<Integer, OwnerInfo> owner : mProfileOwners.entrySet()) {
                    out.startTag(null, TAG_PROFILE_OWNER);
                    out.attribute(null, ATTR_PACKAGE, owner.getValue().packageName);
                    out.attribute(null, ATTR_NAME, owner.getValue().name);
                    out.attribute(null, ATTR_ENABLED, String.valueOf(owner.getValue().enabled));
                    out.attribute(null, ATTR_USERID, Integer.toString(owner.getKey()));
                    out.endTag(null, TAG_PROFILE_OWNER);
                }
            }
            out.endDocument();
            out.flush();
            finishWrite(outputStream);
        } catch (IOException ioe) {
            Slog.e(TAG, "IO Exception when writing device-owner file\n" + ioe);
        }
    }

    private InputStream openRead() throws IOException {
        if (mInputStreamForTest != null) {
            return mInputStreamForTest;
        }

        return new AtomicFile(new File(Environment.getSystemSecureDirectory(),
                DEVICE_OWNER_XML)).openRead();
    }

    private OutputStream startWrite() throws IOException {
        if (mOutputStreamForTest != null) {
            return mOutputStreamForTest;
        }

        fileForWriting = new AtomicFile(new File(Environment.getSystemSecureDirectory(),
                DEVICE_OWNER_XML));
        return fileForWriting.startWrite();
    }

    private void finishWrite(OutputStream stream) {
        if (fileForWriting != null) {
            fileForWriting.finishWrite((FileOutputStream) stream);
        }
    }

    static class OwnerInfo {
        public String name;
        public String packageName;
        public boolean enabled = true; // only makes sense for managed profiles

        public OwnerInfo(String name, String packageName, boolean enabled) {
            this(name, packageName);
            this.enabled = enabled;
        }

        public OwnerInfo(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }
    }
}