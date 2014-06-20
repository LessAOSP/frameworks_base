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

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.utils.LongParcelable;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;

import java.io.IOError;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * This class executes requests to the {@link Camera}.
 *
 * <p>
 * The main components of this class are:
 * - A message queue of requests to the {@link Camera}.
 * - A thread that consumes requests to the {@link Camera} and executes them.
 * - A {@link GLThreadManager} that draws to the configured output {@link Surface}s.
 * - An {@link CameraDeviceState} state machine that manages the callbacks for various operations.
 * </p>
 */
public class RequestThreadManager {
    private final String TAG;
    private final int mCameraId;
    private final RequestHandlerThread mRequestThread;

    private static final boolean DEBUG = Log.isLoggable(LegacyCameraDevice.DEBUG_PROP, Log.DEBUG);
    private final Camera mCamera;

    private final CameraDeviceState mDeviceState;

    private static final int MSG_CONFIGURE_OUTPUTS = 1;
    private static final int MSG_SUBMIT_CAPTURE_REQUEST = 2;
    private static final int MSG_CLEANUP = 3;

    private static final int PREVIEW_FRAME_TIMEOUT = 300; // ms
    private static final int JPEG_FRAME_TIMEOUT = 1000; // ms

    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;
    private boolean mPreviewRunning = false;

    private volatile long mLastJpegTimestamp;
    private volatile long mLastPreviewTimestamp;
    private volatile RequestHolder mInFlightPreview;
    private volatile RequestHolder mInFlightJpeg;

    private final List<Surface> mPreviewOutputs = new ArrayList<Surface>();
    private final List<Surface> mCallbackOutputs = new ArrayList<Surface>();
    private GLThreadManager mGLThreadManager;
    private SurfaceTexture mPreviewTexture;
    private Camera.Parameters mParams;

    private Size mIntermediateBufferSize;

    private final RequestQueue mRequestQueue = new RequestQueue();
    private SurfaceTexture mDummyTexture;
    private Surface mDummySurface;

    private final FpsCounter mPrevCounter = new FpsCounter("Incoming Preview");
    private final FpsCounter mRequestCounter = new FpsCounter("Incoming Requests");

    /**
     * Container object for Configure messages.
     */
    private static class ConfigureHolder {
        public final ConditionVariable condition;
        public final Collection<Surface> surfaces;

        public ConfigureHolder(ConditionVariable condition, Collection<Surface> surfaces) {
            this.condition = condition;
            this.surfaces = surfaces;
        }
    }


    /**
     * Comparator for {@link Size} objects.
     *
     * <p>This comparator compares by rectangle area.  Tiebreaks on width.</p>
     */
    private static class SizeComparator implements Comparator<Size> {
        @Override
        public int compare(Size size, Size size2) {
            if (size == null || size2 == null) {
                throw new NullPointerException("Null argument passed to compare");
            }
            if (size.equals(size2)) return 0;
            long width = size.getWidth();
            long width2 = size2.getWidth();
            long area = width * size.getHeight();
            long area2 = width2 * size2.getHeight();
            if (area == area2) {
                return (width > width2) ? 1 : -1;
            }
            return (area > area2) ? 1 : -1;

        }
    }

    /**
     * Counter class used to calculate and log the current FPS of frame production.
     */
    public static class FpsCounter {
        //TODO: Hook this up to SystTrace?
        private static final String TAG = "FpsCounter";
        private int mFrameCount = 0;
        private long mLastTime = 0;
        private long mLastPrintTime = 0;
        private double mLastFps = 0;
        private final String mStreamType;
        private static final long NANO_PER_SECOND = 1000000000; //ns

        public FpsCounter(String streamType) {
            mStreamType = streamType;
        }

        public synchronized void countFrame() {
            mFrameCount++;
            long nextTime = SystemClock.elapsedRealtimeNanos();
            if (mLastTime == 0) {
                mLastTime = nextTime;
            }
            if (nextTime > mLastTime + NANO_PER_SECOND) {
                long elapsed = nextTime - mLastTime;
                mLastFps = mFrameCount * (NANO_PER_SECOND / (double) elapsed);
                mFrameCount = 0;
                mLastTime = nextTime;
            }
        }

        public synchronized double checkFps() {
            return mLastFps;
        }

        public synchronized void staggeredLog() {
            if (mLastTime > mLastPrintTime + 5 * NANO_PER_SECOND) {
                mLastPrintTime = mLastTime;
                Log.d(TAG, "FPS for " + mStreamType + " stream: " + mLastFps );
            }
        }

        public synchronized void countAndLog() {
            countFrame();
            staggeredLog();
        }
    }
    /**
     * Fake preview for jpeg captures when there is no active preview
     */
    private void createDummySurface() {
        if (mDummyTexture == null || mDummySurface == null) {
            mDummyTexture = new SurfaceTexture(/*ignored*/0);
            // TODO: use smallest default sizes
            mDummyTexture.setDefaultBufferSize(640, 480);
            mDummySurface = new Surface(mDummyTexture);
        }
    }

    private final ConditionVariable mReceivedJpeg = new ConditionVariable(false);
    private final ConditionVariable mReceivedPreview = new ConditionVariable(false);

    private final Camera.PictureCallback mJpegCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            Log.i(TAG, "Received jpeg.");
            RequestHolder holder = mInFlightJpeg;
            if (holder == null) {
                Log.w(TAG, "Dropping jpeg frame.");
                mInFlightJpeg = null;
                return;
            }
            for (Surface s : holder.getHolderTargets()) {
                if (RequestHolder.jpegType(s)) {
                    Log.i(TAG, "Producing jpeg buffer...");
                    LegacyCameraDevice.nativeSetSurfaceDimens(s, data.length, /*height*/1);
                    LegacyCameraDevice.nativeProduceFrame(s, data, data.length, /*height*/1,
                            CameraMetadataNative.NATIVE_JPEG_FORMAT);
                }
            }
            mReceivedJpeg.open();
        }
    };

    private final Camera.ShutterCallback mJpegShutterCallback = new Camera.ShutterCallback() {
        @Override
        public void onShutter() {
            mLastJpegTimestamp = SystemClock.elapsedRealtimeNanos();
        }
    };

    private final SurfaceTexture.OnFrameAvailableListener mPreviewCallback =
            new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    RequestHolder holder = mInFlightPreview;
                    if (holder == null) {
                        mGLThreadManager.queueNewFrame(null);
                        Log.w(TAG, "Dropping preview frame.");
                        return;
                    }

                    if (DEBUG) {
                        mPrevCounter.countAndLog();
                    }
                    mInFlightPreview = null;

                    if (holder.hasPreviewTargets()) {
                        mGLThreadManager.queueNewFrame(holder.getHolderTargets());
                    }

                    mLastPreviewTimestamp = surfaceTexture.getTimestamp();
                    mReceivedPreview.open();
                }
            };

    private void stopPreview() {
        if (mPreviewRunning) {
            mCamera.stopPreview();
            mPreviewRunning = false;
        }
    }

    private void startPreview() {
        if (!mPreviewRunning) {
            mCamera.startPreview();
            mPreviewRunning = true;
        }
    }

    private void doJpegCapture(RequestHolder request) throws IOException {
        if (!mPreviewRunning) {
            createDummySurface();
            mCamera.setPreviewTexture(mDummyTexture);
            startPreview();
        }
        mInFlightJpeg = request;
        // TODO: Hook up shutter callback to CameraDeviceStateListener#onCaptureStarted
        mCamera.takePicture(mJpegShutterCallback, /*raw*/null, mJpegCallback);
        mPreviewRunning = false;
    }

    private void doPreviewCapture(RequestHolder request) throws IOException {
        mInFlightPreview = request;
        if (mPreviewRunning) {
            return; // Already running
        }

        if (mPreviewTexture == null) {
            throw new IllegalStateException(
                    "Preview capture called with no preview surfaces configured.");
        }

        mPreviewTexture.setDefaultBufferSize(mIntermediateBufferSize.getWidth(),
                mIntermediateBufferSize.getHeight());
        mCamera.setPreviewTexture(mPreviewTexture);
        Camera.Parameters params = mCamera.getParameters();
        List<int[]> supportedFpsRanges = params.getSupportedPreviewFpsRange();
        int[] bestRange = getPhotoPreviewFpsRange(supportedFpsRanges);
        if (DEBUG) {
            Log.d(TAG, "doPreviewCapture - Selected range [" +
                    bestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] + "," +
                    bestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] + "]");
        }
        params.setPreviewFpsRange(bestRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                bestRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        params.setRecordingHint(true);
        mCamera.setParameters(params);

        startPreview();
    }


    private void configureOutputs(Collection<Surface> outputs) throws IOException {
        stopPreview();
        if (mGLThreadManager != null) {
            mGLThreadManager.waitUntilStarted();
            mGLThreadManager.ignoreNewFrames();
            mGLThreadManager.waitUntilIdle();
        }
        mPreviewOutputs.clear();
        mCallbackOutputs.clear();
        mPreviewTexture = null;
        mInFlightPreview = null;
        mInFlightJpeg = null;

        if (outputs != null) {
            for (Surface s : outputs) {
                int format = LegacyCameraDevice.nativeDetectSurfaceType(s);
                switch (format) {
                    case CameraMetadataNative.NATIVE_JPEG_FORMAT:
                        mCallbackOutputs.add(s);
                        break;
                    default:
                        mPreviewOutputs.add(s);
                        break;
                }
            }
        }
        mParams = mCamera.getParameters();
        if (mPreviewOutputs.size() > 0) {
            List<Size> outputSizes = new ArrayList<>(outputs.size());
            for (Surface s : mPreviewOutputs) {
                int[] dimens = {0, 0};
                LegacyCameraDevice.nativeDetectSurfaceDimens(s, dimens);
                outputSizes.add(new Size(dimens[0], dimens[1]));
            }

            Size largestOutput = findLargestByArea(outputSizes);

            // Find largest jpeg dimension - assume to have the same aspect ratio as sensor.
            List<Size> supportedJpegSizes = convertSizeList(mParams.getSupportedPictureSizes());
            Size largestJpegDimen = findLargestByArea(supportedJpegSizes);

            List<Size> supportedPreviewSizes = convertSizeList(mParams.getSupportedPreviewSizes());

            // Use smallest preview dimension with same aspect ratio as sensor that is >= than all
            // of the configured output dimensions.  If none exists, fall back to using the largest
            // supported preview size.
            long largestOutputArea = largestOutput.getHeight() * (long) largestOutput.getWidth();
            Size bestPreviewDimen = findLargestByArea(supportedPreviewSizes);
            for (Size s : supportedPreviewSizes) {
                long currArea = s.getWidth() * s.getHeight();
                long bestArea = bestPreviewDimen.getWidth() * bestPreviewDimen.getHeight();
                if (checkAspectRatiosMatch(largestJpegDimen, s) && (currArea < bestArea &&
                        currArea >= largestOutputArea)) {
                    bestPreviewDimen = s;
                }
            }

            mIntermediateBufferSize = bestPreviewDimen;
            if (DEBUG) {
                Log.d(TAG, "Intermediate buffer selected with dimens: " +
                        bestPreviewDimen.toString());
            }
        } else {
            mIntermediateBufferSize = null;
            if (DEBUG) {
                Log.d(TAG, "No Intermediate buffer selected, no preview outputs were configured");
            }
        }

        // TODO: Detect and optimize single-output paths here to skip stream teeing.
        if (mGLThreadManager == null) {
            mGLThreadManager = new GLThreadManager(mCameraId);
            mGLThreadManager.start();
        }
        mGLThreadManager.waitUntilStarted();
        mGLThreadManager.setConfigurationAndWait(mPreviewOutputs);
        mGLThreadManager.allowNewFrames();
        mPreviewTexture = mGLThreadManager.getCurrentSurfaceTexture();
        if (mPreviewTexture != null) {
            mPreviewTexture.setOnFrameAvailableListener(mPreviewCallback);
        }
    }

    private static Size findLargestByArea(List<Size> sizes) {
        return Collections.max(sizes, new SizeComparator());
    }

    private static boolean checkAspectRatiosMatch(Size a, Size b) {
        float aAspect = a.getWidth() / (float) a.getHeight();
        float bAspect = b.getWidth() / (float) b.getHeight();

        return Math.abs(aAspect - bAspect) < ASPECT_RATIO_TOLERANCE;
    }

    private static List<Size> convertSizeList(List<Camera.Size> sizeList) {
        List<Size> sizes = new ArrayList<>(sizeList.size());
        for (Camera.Size s : sizeList) {
            sizes.add(new Size(s.width, s.height));
        }
        return sizes;
    }

    // Calculate the highest FPS range supported
    private int[] getPhotoPreviewFpsRange(List<int[]> frameRates) {
        if (frameRates.size() == 0) {
            Log.e(TAG, "No supported frame rates returned!");
            return null;
        }

        int bestMin = 0;
        int bestMax = 0;
        int bestIndex = 0;
        int index = 0;
        for (int[] rate : frameRates) {
            int minFps = rate[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int maxFps = rate[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            if (maxFps > bestMax || (maxFps == bestMax && minFps > bestMin)) {
                bestMin = minFps;
                bestMax = maxFps;
                bestIndex = index;
            }
            index++;
        }

        return frameRates.get(bestIndex);
    }

    private final Handler.Callback mRequestHandlerCb = new Handler.Callback() {
        private boolean mCleanup = false;
        private final List<RequestHolder> mRepeating = null;

        @SuppressWarnings("unchecked")
        @Override
        public boolean handleMessage(Message msg) {
            if (mCleanup) {
                return true;
            }

            if (DEBUG) {
                Log.d(TAG, "Request thread handling message:" + msg.what);
            }
            switch (msg.what) {
                case MSG_CONFIGURE_OUTPUTS:
                    ConfigureHolder config = (ConfigureHolder) msg.obj;
                    int sizes = config.surfaces != null ? config.surfaces.size() : 0;
                    Log.i(TAG, "Configure outputs: " + sizes +
                            " surfaces configured.");
                    try {
                        configureOutputs(config.surfaces);
                    } catch (IOException e) {
                        // TODO: report error to CameraDevice
                        throw new IOError(e);
                    }
                    config.condition.open();
                    break;
                case MSG_SUBMIT_CAPTURE_REQUEST:
                    Handler handler = RequestThreadManager.this.mRequestThread.getHandler();

                    // Get the next burst from the request queue.
                    Pair<BurstHolder, Long> nextBurst = mRequestQueue.getNext();
                    if (nextBurst == null) {
                        mDeviceState.setIdle();
                        stopPreview();
                        break;
                    } else {
                        // Queue another capture if we did not get the last burst.
                        handler.sendEmptyMessage(MSG_SUBMIT_CAPTURE_REQUEST);
                    }

                    // Complete each request in the burst
                    List<RequestHolder> requests =
                            nextBurst.first.produceRequestHolders(nextBurst.second);
                    for (RequestHolder holder : requests) {
                        mDeviceState.setCaptureStart(holder);
                        long timestamp = 0;
                        try {
                            if (holder.hasPreviewTargets()) {
                                mReceivedPreview.close();
                                doPreviewCapture(holder);
                                if (!mReceivedPreview.block(PREVIEW_FRAME_TIMEOUT)) {
                                    // TODO: report error to CameraDevice
                                    Log.e(TAG, "Hit timeout for preview callback!");
                                }
                                timestamp = mLastPreviewTimestamp;
                            }
                            if (holder.hasJpegTargets()) {
                                mReceivedJpeg.close();
                                doJpegCapture(holder);
                                mReceivedJpeg.block();
                                if (!mReceivedJpeg.block(JPEG_FRAME_TIMEOUT)) {
                                    // TODO: report error to CameraDevice
                                    Log.e(TAG, "Hit timeout for jpeg callback!");
                                }
                                mInFlightJpeg = null;
                                timestamp = mLastJpegTimestamp;
                            }
                        } catch (IOException e) {
                            // TODO: err handling
                            throw new IOError(e);
                        }
                        CameraMetadataNative result = convertResultMetadata(mParams,
                                holder.getRequest(), timestamp);
                        mDeviceState.setCaptureResult(holder, result);
                    }
                    if (DEBUG) {
                        mRequestCounter.countAndLog();
                    }
                    break;
                case MSG_CLEANUP:
                    mCleanup = true;
                    if (mGLThreadManager != null) {
                        mGLThreadManager.quit();
                    }
                    if (mCamera != null) {
                        mCamera.release();
                    }
                    break;
                default:
                    throw new AssertionError("Unhandled message " + msg.what +
                            " on RequestThread.");
            }
            return true;
        }
    };

    private CameraMetadataNative convertResultMetadata(Camera.Parameters params,
                                                       CaptureRequest request,
                                                       long timestamp) {
        CameraMetadataNative result = new CameraMetadataNative();
        result.set(CaptureResult.LENS_FOCAL_LENGTH, params.getFocalLength());
        result.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);

        // TODO: Remaining result metadata tags conversions.
        return result;
    }

    /**
     * Create a new RequestThreadManager.
     *
     * @param cameraId the id of the camera to use.
     * @param camera an open camera object.  The RequestThreadManager takes ownership of this camera
     *               object, and is responsible for closing it.
     * @param deviceState a {@link CameraDeviceState} state machine.
     */
    public RequestThreadManager(int cameraId, Camera camera,
                                CameraDeviceState deviceState) {
        mCamera = camera;
        mCameraId = cameraId;
        String name = String.format("RequestThread-%d", cameraId);
        TAG = name;
        mDeviceState = deviceState;
        mRequestThread = new RequestHandlerThread(name, mRequestHandlerCb);
    }

    /**
     * Start the request thread.
     */
    public void start() {
        mRequestThread.start();
    }

    /**
     * Flush the pending requests.
     */
    public void flush() {
        // TODO: Implement flush.
        Log.e(TAG, "flush not yet implemented.");
    }

    /**
     * Quit the request thread, and clean up everything.
     */
    public void quit() {
        Handler handler = mRequestThread.waitAndGetHandler();
        handler.sendMessageAtFrontOfQueue(handler.obtainMessage(MSG_CLEANUP));
        mRequestThread.quitSafely();
        try {
            mRequestThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, String.format("Thread %s (%d) interrupted while quitting.",
                    mRequestThread.getName(), mRequestThread.getId()));
        }
    }

    /**
     * Submit the given burst of requests to be captured.
     *
     * <p>If the burst is repeating, replace the current repeating burst.</p>
     *
     * @param requests the burst of requests to add to the queue.
     * @param repeating true if the burst is repeating.
     * @param frameNumber an output argument that contains either the frame number of the last frame
     *                    that will be returned for this request, or the frame number of the last
     *                    frame that will be returned for the current repeating request if this
     *                    burst is set to be repeating.
     * @return the request id.
     */
    public int submitCaptureRequests(List<CaptureRequest> requests, boolean repeating,
            /*out*/LongParcelable frameNumber) {
        Handler handler = mRequestThread.waitAndGetHandler();
        int ret = mRequestQueue.submit(requests, repeating, frameNumber);
        handler.sendEmptyMessage(MSG_SUBMIT_CAPTURE_REQUEST);
        return ret;
    }

    /**
     * Cancel a repeating request.
     *
     * @param requestId the id of the repeating request to cancel.
     * @return the last frame to be returned from the HAL for the given repeating request, or
     *          {@code INVALID_FRAME} if none exists.
     */
    public long cancelRepeating(int requestId) {
        return mRequestQueue.stopRepeating(requestId);
    }


    /**
     * Configure with the current list of output Surfaces.
     *
     * <p>
     * This operation blocks until the configuration is complete.
     * </p>
     *
     * <p>Using a {@code null} or empty {@code outputs} list is the equivalent of unconfiguring.</p>
     *
     * @param outputs a {@link java.util.Collection} of outputs to configure.
     */
    public void configure(Collection<Surface> outputs) {
        Handler handler = mRequestThread.waitAndGetHandler();
        final ConditionVariable condition = new ConditionVariable(/*closed*/false);
        ConfigureHolder holder = new ConfigureHolder(condition, outputs);
        handler.sendMessage(handler.obtainMessage(MSG_CONFIGURE_OUTPUTS, 0, 0, holder));
        condition.block();
    }
}
