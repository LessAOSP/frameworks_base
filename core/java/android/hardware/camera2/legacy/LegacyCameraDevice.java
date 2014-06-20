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

package android.hardware.camera2.legacy;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.CameraRuntimeException;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import static android.hardware.camera2.utils.CameraBinderDecorator.*;

/**
 * This class emulates the functionality of a Camera2 device using a the old Camera class.
 *
 * <p>
 * There are two main components that are used to implement this:
 * - A state machine containing valid Camera2 device states ({@link CameraDeviceState}).
 * - A message-queue based pipeline that manages an old Camera class, and executes capture and
 *   configuration requests.
 * </p>
 */
public class LegacyCameraDevice implements AutoCloseable {
    public static final String DEBUG_PROP = "HAL1ShimLogging";
    private final String TAG;

    private static final boolean DEBUG = false;
    private final int mCameraId;
    private final ICameraDeviceCallbacks mDeviceCallbacks;
    private final CameraDeviceState mDeviceState = new CameraDeviceState();
    private List<Surface> mConfiguredSurfaces;

    private final ConditionVariable mIdle = new ConditionVariable(/*open*/true);

    private final HandlerThread mResultThread = new HandlerThread("ResultThread");
    private final HandlerThread mCallbackHandlerThread = new HandlerThread("CallbackThread");
    private final Handler mCallbackHandler;
    private final Handler mResultHandler;
    private static final int ILLEGAL_VALUE = -1;

    private CaptureResultExtras getExtrasFromRequest(RequestHolder holder) {
        if (holder == null) {
            return new CaptureResultExtras(ILLEGAL_VALUE, ILLEGAL_VALUE, ILLEGAL_VALUE,
                    ILLEGAL_VALUE, ILLEGAL_VALUE);
        }
        return new CaptureResultExtras(holder.getRequestId(), holder.getSubsequeceId(),
                /*afTriggerId*/0, /*precaptureTriggerId*/0, holder.getFrameNumber());
    }

    /**
     * Listener for the camera device state machine.  Calls the appropriate
     * {@link ICameraDeviceCallbacks} for each state transition.
     */
    private final CameraDeviceState.CameraDeviceStateListener mStateListener =
            new CameraDeviceState.CameraDeviceStateListener() {
        @Override
        public void onError(final int errorCode, RequestHolder holder) {
            mIdle.open();
            final CaptureResultExtras extras = getExtrasFromRequest(holder);
            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onError callback.");
                    }
                    try {
                        mDeviceCallbacks.onCameraError(errorCode, extras);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onConfiguring() {
            // Do nothing
            if (DEBUG) {
                Log.d(TAG, "doing onConfiguring callback.");
            }
        }

        @Override
        public void onIdle() {
            mIdle.open();

            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onIdle callback.");
                    }
                    try {
                        mDeviceCallbacks.onCameraIdle();
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraIdle callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onCaptureStarted(RequestHolder holder) {
            final CaptureResultExtras extras = getExtrasFromRequest(holder);

            final long timestamp = System.nanoTime();
            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onCaptureStarted callback.");
                    }
                    try {
                        // TODO: Don't fake timestamp
                        mDeviceCallbacks.onCaptureStarted(extras, timestamp);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }

        @Override
        public void onCaptureResult(final CameraMetadataNative result, RequestHolder holder) {
            final CaptureResultExtras extras = getExtrasFromRequest(holder);

            mResultHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (DEBUG) {
                        Log.d(TAG, "doing onCaptureResult callback.");
                    }
                    try {
                        // TODO: Don't fake metadata
                        mDeviceCallbacks.onResultReceived(result, extras);
                    } catch (RemoteException e) {
                        throw new IllegalStateException(
                                "Received remote exception during onCameraError callback: ", e);
                    }
                }
            });
        }
    };

    private final RequestThreadManager mRequestThreadManager;

    /**
     * Check if a given surface uses {@link ImageFormat#YUV_420_888} or format that can be readily
     * converted to this; YV12 and NV21 are the two currently supported formats.
     *
     * @param s the surface to check.
     * @return {@code true} if the surfaces uses {@link ImageFormat#YUV_420_888} or a compatible
     *          format.
     */
    static boolean needsConversion(Surface s) {
        int nativeType = LegacyCameraDevice.nativeDetectSurfaceType(s);
        return nativeType == ImageFormat.YUV_420_888 || nativeType == ImageFormat.YV12 ||
                nativeType == ImageFormat.NV21;
    }

    /**
     * Create a new emulated camera device from a given Camera 1 API camera.
     *
     * <p>
     * The {@link Camera} provided to this constructor must already have been successfully opened,
     * and ownership of the provided camera is passed to this object.  No further calls to the
     * camera methods should be made following this constructor.
     * </p>
     *
     * @param cameraId the id of the camera.
     * @param camera an open {@link Camera} device.
     * @param callbacks {@link ICameraDeviceCallbacks} callbacks to call for Camera2 API operations.
     */
    public LegacyCameraDevice(int cameraId, Camera camera, ICameraDeviceCallbacks callbacks) {
        mCameraId = cameraId;
        mDeviceCallbacks = callbacks;
        TAG = String.format("CameraDevice-%d-LE", mCameraId);

        mResultThread.start();
        mResultHandler = new Handler(mResultThread.getLooper());
        mCallbackHandlerThread.start();
        mCallbackHandler = new Handler(mCallbackHandlerThread.getLooper());
        mDeviceState.setCameraDeviceCallbacks(mCallbackHandler, mStateListener);
        mRequestThreadManager =
                new RequestThreadManager(cameraId, camera, mDeviceState);
        mRequestThreadManager.start();
    }

    /**
     * Configure the device with a set of output surfaces.
     *
     * <p>Using empty or {@code null} {@code outputs} is the same as unconfiguring.</p>
     *
     * <p>Every surface in {@code outputs} must be non-{@code null}.</p>
     *
     * @param outputs a list of surfaces to set.
     * @return an error code for this binder operation, or {@link NO_ERROR}
     *          on success.
     */
    public int configureOutputs(List<Surface> outputs) {
        if (outputs != null) {
            for (Surface output : outputs) {
                if (output == null) {
                    Log.e(TAG, "configureOutputs - null outputs are not allowed");
                    return BAD_VALUE;
                }
            }
        }

        int error = mDeviceState.setConfiguring();
        if (error == NO_ERROR) {
            mRequestThreadManager.configure(outputs);
            error = mDeviceState.setIdle();
        }

        // TODO: May also want to check the surfaces more deeply (e.g. state, formats, sizes..)
        if (error == NO_ERROR) {
            mConfiguredSurfaces = outputs != null ? new ArrayList<>(outputs) : null;
        }

        return error;
    }

    /**
     * Submit a burst of capture requests.
     *
     * @param requestList a list of capture requests to execute.
     * @param repeating {@code true} if this burst is repeating.
     * @param frameNumber an output argument that contains either the frame number of the last frame
     *                    that will be returned for this request, or the frame number of the last
     *                    frame that will be returned for the current repeating request if this
     *                    burst is set to be repeating.
     * @return the request id.
     */
    public int submitRequestList(List<CaptureRequest> requestList, boolean repeating,
            /*out*/LongParcelable frameNumber) {
        if (requestList == null || requestList.isEmpty()) {
            Log.e(TAG, "submitRequestList - Empty/null requests are not allowed");
            return BAD_VALUE;
        }

        // Make sure that there all requests have at least 1 surface; all surfaces are non-null
        for (CaptureRequest request : requestList) {
            if (request.getTargets().isEmpty()) {
                Log.e(TAG, "submitRequestList - "
                        + "Each request must have at least one Surface target");
                return BAD_VALUE;
            }

            for (Surface surface : request.getTargets()) {
                if (surface == null) {
                    Log.e(TAG, "submitRequestList - Null Surface targets are not allowed");
                    return BAD_VALUE;
                } else if (mConfiguredSurfaces == null) {
                    Log.e(TAG, "submitRequestList - must configure " +
                            " device with valid surfaces before submitting requests");
                    return INVALID_OPERATION;
                } else if (!mConfiguredSurfaces.contains(surface)) {
                    Log.e(TAG, "submitRequestList - cannot use a surface that wasn't configured");
                    return BAD_VALUE;
                }
            }
        }

        // TODO: further validation of request here
        mIdle.close();
        return mRequestThreadManager.submitCaptureRequests(requestList, repeating,
                frameNumber);
    }

    /**
     * Submit a single capture request.
     *
     * @param request the capture request to execute.
     * @param repeating {@code true} if this request is repeating.
     * @param frameNumber an output argument that contains either the frame number of the last frame
     *                    that will be returned for this request, or the frame number of the last
     *                    frame that will be returned for the current repeating request if this
     *                    request is set to be repeating.
     * @return the request id.
     */
    public int submitRequest(CaptureRequest request, boolean repeating,
            /*out*/LongParcelable frameNumber) {
        ArrayList<CaptureRequest> requestList = new ArrayList<CaptureRequest>();
        requestList.add(request);
        return submitRequestList(requestList, repeating, frameNumber);
    }

    /**
     * Cancel the repeating request with the given request id.
     *
     * @param requestId the request id of the request to cancel.
     * @return the last frame number to be returned from the HAL for the given repeating request, or
     *          {@code INVALID_FRAME} if none exists.
     */
    public long cancelRequest(int requestId) {
        return mRequestThreadManager.cancelRepeating(requestId);
    }

    /**
     * Block until the {@link ICameraDeviceCallbacks#onCameraIdle()} callback is received.
     */
    public void waitUntilIdle()  {
        mIdle.block();
    }

    @Override
    public void close() {
        mRequestThreadManager.quit();
        mCallbackHandlerThread.quitSafely();
        mResultThread.quitSafely();

        try {
            mCallbackHandlerThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("Thread %s (%d) interrupted while quitting.",
                    mCallbackHandlerThread.getName(), mCallbackHandlerThread.getId()));
        }

        try {
            mResultThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("Thread %s (%d) interrupted while quitting.",
                    mResultThread.getName(), mResultThread.getId()));
        }

        // TODO: throw IllegalStateException in every method after close has been called
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } catch (CameraRuntimeException e) {
            Log.e(TAG, "Got error while trying to finalize, ignoring: " + e.getMessage());
        } finally {
            super.finalize();
        }
    }

    protected static native int nativeDetectSurfaceType(Surface surface);

    protected static native void nativeDetectSurfaceDimens(Surface surface, int[] dimens);

    protected static native void nativeConfigureSurface(Surface surface, int width, int height,
                                                        int pixelFormat);

    protected static native void nativeProduceFrame(Surface surface, byte[] pixelBuffer, int width,
                                                    int height, int pixelFormat);

    protected static native void nativeSetSurfaceFormat(Surface surface, int pixelFormat);

    protected static native void nativeSetSurfaceDimens(Surface surface, int width, int height);

}
