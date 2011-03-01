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

#define LOG_TAG "UsbService"
#include "utils/Log.h"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "utils/Vector.h"

#include <usbhost/usbhost.h>

#include <stdio.h>
#include <asm/byteorder.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/usb/f_accessory.h>

#define DRIVER_NAME "/dev/usb_accessory"

namespace android
{

static struct file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
    jfieldID mDescriptor;
} gFileDescriptorOffsets;

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static jmethodID method_usbDeviceAdded;
static jmethodID method_usbDeviceRemoved;

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        LOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static int usb_device_added(const char *devname, void* client_data) {
    struct usb_descriptor_header* desc;
    struct usb_descriptor_iter iter;

    struct usb_device *device = usb_device_open(devname);
    if (!device) {
        LOGE("usb_device_open failed\n");
        return 0;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject thiz = (jobject)client_data;
    Vector<int> interfaceValues;
    Vector<int> endpointValues;
    const usb_device_descriptor* deviceDesc = usb_device_get_device_descriptor(device);

    uint16_t vendorId = usb_device_get_vendor_id(device);
    uint16_t productId = usb_device_get_product_id(device);
    uint8_t deviceClass = deviceDesc->bDeviceClass;
    uint8_t deviceSubClass = deviceDesc->bDeviceSubClass;
    uint8_t protocol = deviceDesc->bDeviceProtocol;

    usb_descriptor_iter_init(device, &iter);

    while ((desc = usb_descriptor_iter_next(&iter)) != NULL) {
        if (desc->bDescriptorType == USB_DT_INTERFACE) {
            struct usb_interface_descriptor *interface = (struct usb_interface_descriptor *)desc;

            // push class, subclass, protocol and number of endpoints into interfaceValues vector
            interfaceValues.add(interface->bInterfaceNumber);
            interfaceValues.add(interface->bInterfaceClass);
            interfaceValues.add(interface->bInterfaceSubClass);
            interfaceValues.add(interface->bInterfaceProtocol);
            interfaceValues.add(interface->bNumEndpoints);
        } else if (desc->bDescriptorType == USB_DT_ENDPOINT) {
            struct usb_endpoint_descriptor *endpoint = (struct usb_endpoint_descriptor *)desc;

            // push address, attributes, max packet size and interval into endpointValues vector
            endpointValues.add(endpoint->bEndpointAddress);
            endpointValues.add(endpoint->bmAttributes);
            endpointValues.add(__le16_to_cpu(endpoint->wMaxPacketSize));
            endpointValues.add(endpoint->bInterval);
        }
    }

    usb_device_close(device);

    // handle generic device notification
    int length = interfaceValues.size();
    jintArray interfaceArray = env->NewIntArray(length);
    env->SetIntArrayRegion(interfaceArray, 0, length, interfaceValues.array());

    length = endpointValues.size();
    jintArray endpointArray = env->NewIntArray(length);
    env->SetIntArrayRegion(endpointArray, 0, length, endpointValues.array());

    env->CallVoidMethod(thiz, method_usbDeviceAdded,
            env->NewStringUTF(devname), vendorId, productId, deviceClass,
            deviceSubClass, protocol, interfaceArray, endpointArray);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    return 0;
}

static int usb_device_removed(const char *devname, void* client_data) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject thiz = (jobject)client_data;

    env->CallVoidMethod(thiz, method_usbDeviceRemoved, env->NewStringUTF(devname));
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return 0;
}

static void android_server_UsbService_monitorUsbHostBus(JNIEnv *env, jobject thiz)
{
    struct usb_host_context* context = usb_host_init();
    if (!context) {
        LOGE("usb_host_init failed");
        return;
    }
    // this will never return so it is safe to pass thiz directly
    usb_host_run(context, usb_device_added, usb_device_removed, NULL, (void *)thiz);
}

static jobject android_server_UsbService_openDevice(JNIEnv *env, jobject thiz, jstring deviceName)
{
    const char *deviceNameStr = env->GetStringUTFChars(deviceName, NULL);
    struct usb_device* device = usb_device_open(deviceNameStr);
    env->ReleaseStringUTFChars(deviceName, deviceNameStr);

    if (!device)
        return NULL;

    int fd = usb_device_get_fd(device);
    if (fd < 0)
        return NULL;
    int newFD = dup(fd);
    usb_device_close(device);

    jobject fileDescriptor = env->NewObject(gFileDescriptorOffsets.mClass,
        gFileDescriptorOffsets.mConstructor);
    if (fileDescriptor != NULL) {
        env->SetIntField(fileDescriptor, gFileDescriptorOffsets.mDescriptor, newFD);
    } else {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static void set_accessory_string(JNIEnv *env, int fd, int cmd, jobjectArray strArray, int index)
{
    char buffer[256];

    buffer[0] = 0;
    int length = ioctl(fd, cmd, buffer);
    if (buffer[0]) {
        jstring obj = env->NewStringUTF(buffer);
        env->SetObjectArrayElement(strArray, index, obj);
        env->DeleteLocalRef(obj);
    }
}


static jobjectArray android_server_UsbService_getAccessoryStrings(JNIEnv *env, jobject thiz)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        LOGE("could not open %s", DRIVER_NAME);
        return NULL;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray strArray = env->NewObjectArray(4, stringClass, NULL);
    if (!strArray) goto out;
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_MANUFACTURER, strArray, 0);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_MODEL, strArray, 1);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_TYPE, strArray, 2);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_VERSION, strArray, 3);

out:
    close(fd);
    return strArray;
}

static jobject android_server_UsbService_openAccessory(JNIEnv *env, jobject thiz)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        LOGE("could not open %s", DRIVER_NAME);
        return NULL;
    }
    jobject fileDescriptor = env->NewObject(gFileDescriptorOffsets.mClass,
        gFileDescriptorOffsets.mConstructor);
    if (fileDescriptor != NULL) {
        env->SetIntField(fileDescriptor, gFileDescriptorOffsets.mDescriptor, fd);
    } else {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static JNINativeMethod method_table[] = {
    { "monitorUsbHostBus", "()V", (void*)android_server_UsbService_monitorUsbHostBus },
    { "nativeOpenDevice",  "(Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;",
                                  (void*)android_server_UsbService_openDevice },
    { "nativeGetAccessoryStrings", "()[Ljava/lang/String;",
                                  (void*)android_server_UsbService_getAccessoryStrings },
    { "nativeOpenAccessory","()Landroid/os/ParcelFileDescriptor;",
                                  (void*)android_server_UsbService_openAccessory },
};

int register_android_server_UsbService(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/usb/UsbService");
    if (clazz == NULL) {
        LOGE("Can't find com/android/server/usb/UsbService");
        return -1;
    }
    method_usbDeviceAdded = env->GetMethodID(clazz, "usbDeviceAdded", "(Ljava/lang/String;IIIII[I[I)V");
    if (method_usbDeviceAdded == NULL) {
        LOGE("Can't find usbDeviceAdded");
        return -1;
    }
    method_usbDeviceRemoved = env->GetMethodID(clazz, "usbDeviceRemoved", "(Ljava/lang/String;)V");
    if (method_usbDeviceRemoved == NULL) {
        LOGE("Can't find usbDeviceRemoved");
        return -1;
    }

   clazz = env->FindClass("java/io/FileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class java.io.FileDescriptor");
    gFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "()V");
    gFileDescriptorOffsets.mDescriptor = env->GetFieldID(clazz, "descriptor", "I");
    LOG_FATAL_IF(gFileDescriptorOffsets.mDescriptor == NULL,
                 "Unable to find descriptor field in java.io.FileDescriptor");

   clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbService",
            method_table, NELEM(method_table));
}

};
