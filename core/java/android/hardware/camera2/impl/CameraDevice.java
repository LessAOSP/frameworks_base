/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.camera2.impl;

import static android.hardware.camera2.CameraAccessException.CAMERA_IN_USE;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraProperties;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.utils.CameraBinderDecorator;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Stack;

/**
 * HAL2.1+ implementation of CameraDevice Use CameraManager#open to instantiate
 */
public class CameraDevice implements android.hardware.camera2.CameraDevice {

    private final String TAG;
    private final boolean DEBUG;

    // TODO: guard every function with if (!mRemoteDevice) check (if it was closed)
    private ICameraDeviceUser mRemoteDevice;

    private final Object mLock = new Object();
    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    // XX: Make this a WeakReference<CaptureListener> ?
    // TODO: Convert to SparseIntArray
    private final HashMap<Integer, CaptureListenerHolder> mCaptureListenerMap =
            new HashMap<Integer, CaptureListenerHolder>();

    private final Stack<Integer> mRepeatingRequestIdStack = new Stack<Integer>();
    // Map stream IDs to Surfaces
    private final SparseArray<Surface> mConfiguredOutputs = new SparseArray<Surface>();

    private final String mCameraId;

    public CameraDevice(String cameraId) {
        mCameraId = cameraId;
        TAG = String.format("CameraDevice-%s-JV", mCameraId);
        DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    public void setRemoteDevice(ICameraDeviceUser remoteDevice) {
        // TODO: Move from decorator to direct binder-mediated exceptions
        mRemoteDevice = CameraBinderDecorator.newInstance(remoteDevice);
    }

    @Override
    public CameraProperties getProperties() throws CameraAccessException {

        CameraProperties properties = new CameraProperties();
        CameraMetadata info = new CameraMetadata();

        try {
            mRemoteDevice.getCameraInfo(/*out*/info);
        } catch(CameraRuntimeException e) {
            throw e.asChecked();
        } catch(RemoteException e) {
            // impossible
            return null;
        }

        properties.swap(info);
        return properties;
    }

    @Override
    public void configureOutputs(List<Surface> outputs) throws CameraAccessException {
        synchronized (mLock) {
            HashSet<Surface> addSet = new HashSet<Surface>(outputs);    // Streams to create
            List<Integer> deleteList = new ArrayList<Integer>();        // Streams to delete

            // Determine which streams need to be created, which to be deleted
            for (int i = 0; i < mConfiguredOutputs.size(); ++i) {
                int streamId = mConfiguredOutputs.keyAt(i);
                Surface s = mConfiguredOutputs.valueAt(i);

                if (!outputs.contains(s)) {
                    deleteList.add(streamId);
                } else {
                    addSet.remove(s);  // Don't create a stream previously created
                }
            }

            try {
                // TODO: mRemoteDevice.beginConfigure

                // Delete all streams first (to free up HW resources)
                for (Integer streamId : deleteList) {
                    mRemoteDevice.deleteStream(streamId);
                    mConfiguredOutputs.delete(streamId);
                }

                // Add all new streams
                for (Surface s : addSet) {
                    // TODO: remove width,height,format since we are ignoring
                    // it.
                    int streamId = mRemoteDevice.createStream(0, 0, 0, s);
                    mConfiguredOutputs.put(streamId, s);
                }

                // TODO: mRemoteDevice.endConfigure
            } catch (CameraRuntimeException e) {
                if (e.getReason() == CAMERA_IN_USE) {
                    throw new IllegalStateException("The camera is currently busy." +
                            " You must call waitUntilIdle before trying to reconfigure.");
                }

                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
        }
    }

    @Override
    public CaptureRequest createCaptureRequest(int templateType) throws CameraAccessException {

        synchronized (mLock) {

            CameraMetadata templatedRequest = new CameraMetadata();

            try {
                mRemoteDevice.createDefaultRequest(templateType, /* out */templatedRequest);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return null;
            }

            CaptureRequest request = new CaptureRequest();
            request.swap(templatedRequest);

            return request;

        }
    }

    @Override
    public void capture(CaptureRequest request, CaptureListener listener)
            throws CameraAccessException {
        submitCaptureRequest(request, listener, /*streaming*/false);
    }

    @Override
    public void captureBurst(List<CaptureRequest> requests, CaptureListener listener)
            throws CameraAccessException {
        if (requests.isEmpty()) {
            Log.w(TAG, "Capture burst request list is empty, do nothing!");
            return;
        }
        // TODO
        throw new UnsupportedOperationException("Burst capture implemented yet");

    }

    private void submitCaptureRequest(CaptureRequest request, CaptureListener listener,
            boolean repeating) throws CameraAccessException {

        synchronized (mLock) {

            int requestId;

            try {
                requestId = mRemoteDevice.submitRequest(request, repeating);
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }

            mCaptureListenerMap.put(requestId, new CaptureListenerHolder(listener, request,
                    repeating));

            if (repeating) {
                mRepeatingRequestIdStack.add(requestId);
            }

        }
    }

    @Override
    public void setRepeatingRequest(CaptureRequest request, CaptureListener listener)
            throws CameraAccessException {
        submitCaptureRequest(request, listener, /*streaming*/true);
    }

    @Override
    public void setRepeatingBurst(List<CaptureRequest> requests, CaptureListener listener)
            throws CameraAccessException {
        if (requests.isEmpty()) {
            Log.w(TAG, "Set Repeating burst request list is empty, do nothing!");
            return;
        }
        // TODO
        throw new UnsupportedOperationException("Burst capture implemented yet");
    }

    @Override
    public void stopRepeating() throws CameraAccessException {

        synchronized (mLock) {

            while (!mRepeatingRequestIdStack.isEmpty()) {
                int requestId = mRepeatingRequestIdStack.pop();

                try {
                    mRemoteDevice.cancelRequest(requestId);
                } catch (CameraRuntimeException e) {
                    throw e.asChecked();
                } catch (RemoteException e) {
                    // impossible
                    return;
                }
            }
        }
    }

    @Override
    public void waitUntilIdle() throws CameraAccessException {

        synchronized (mLock) {
            checkIfCameraClosed();
            if (!mRepeatingRequestIdStack.isEmpty()) {
                throw new IllegalStateException("Active repeating request ongoing");
            }

            try {
                mRemoteDevice.waitUntilIdle();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
                return;
            }
      }
    }

    @Override
    public void setErrorListener(ErrorListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void close() throws Exception {

        // TODO: every method should throw IllegalStateException after close has been called

        synchronized (mLock) {

            try {
                mRemoteDevice.disconnect();
            } catch (CameraRuntimeException e) {
                throw e.asChecked();
            } catch (RemoteException e) {
                // impossible
            }

            mRemoteDevice = null;

        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } catch (CameraRuntimeException e) {
            Log.e(TAG, "Got error while trying to finalize, ignoring: " + e.getMessage());
        }
        finally {
            super.finalize();
        }
    }

    static class CaptureListenerHolder {

        private final boolean mRepeating;
        private final CaptureListener mListener;
        private final CaptureRequest mRequest;

        CaptureListenerHolder(CaptureListener listener, CaptureRequest request, boolean repeating) {
            mRepeating = repeating;
            mRequest = request;
            mListener = listener;
        }

        public boolean isRepeating() {
            return mRepeating;
        }

        public CaptureListener getListener() {
            return mListener;
        }

        public CaptureRequest getRequest() {
            return mRequest;
        }
    }

    // TODO: unit tests
    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        @Override
        public IBinder asBinder() {
            return this;
        }

        // TODO: consider rename to onMessageReceived
        @Override
        public void notifyCallback(int msgType, int ext1, int ext2) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "Got message " + msgType + " ext1: " + ext1 + " , ext2: " + ext2);
            }
            // TODO implement rest
        }

        @Override
        public void onResultReceived(int requestId, CameraMetadata result) throws RemoteException {
            if (DEBUG) {
                Log.d(TAG, "Received result for id " + requestId);
            }
            CaptureListenerHolder holder;

            synchronized (mLock) {
                // TODO: move this whole map into this class to make it more testable,
                //        exposing the methods necessary like subscribeToRequest, unsubscribe..
                // TODO: make class static class

                holder = CameraDevice.this.mCaptureListenerMap.get(requestId);

                // Clean up listener once we no longer expect to see it.

                // TODO: how to handle repeating listeners?
                // we probably want cancelRequest to return # of times it already enqueued and
                // keep a counter.
                if (holder != null && !holder.isRepeating()) {
                    CameraDevice.this.mCaptureListenerMap.remove(requestId);
                }
            }

            if (holder == null) {
                Log.e(TAG, "Result had no listener holder associated with it, dropping result");
                return;
            }

            CaptureResult resultAsCapture = new CaptureResult();
            resultAsCapture.swap(result);

            if (holder.getListener() != null) {
                holder.getListener().onCaptureComplete(CameraDevice.this, holder.getRequest(),
                        resultAsCapture);
            }
        }

    }

    private void checkIfCameraClosed() {
        if (mRemoteDevice == null) {
            throw new IllegalStateException("CameraDevice was already closed");
        }
    }
}
