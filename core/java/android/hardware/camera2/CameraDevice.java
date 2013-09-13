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

package android.hardware.camera2;

import android.view.Surface;
import android.os.Handler;
import android.util.Log;

import java.lang.AutoCloseable;
import java.util.List;

/**
 * <p>The CameraDevice class is an interface to a single camera connected to an
 * Android device, allowing for fine-grain control of image capture and
 * post-processing at high frame rates.</p>
 *
 * <p>Your application must declare the
 * {@link android.Manifest.permission#CAMERA Camera} permission in its manifest
 * in order to access camera devices.</p>
 *
 * <p>A given camera device may provide support at one of two levels: limited or
 * full. If a device only supports the limited level, then Camera2 exposes a
 * feature set that is roughly equivalent to the older
 * {@link android.hardware.Camera Camera} API, although with a cleaner and more
 * efficient interface.  Devices that implement the full level of support
 * provide substantially improved capabilities over the older camera
 * API. Applications that target the limited level devices will run unchanged on
 * the full-level devices; if your application requires a full-level device for
 * proper operation, declare the "android.hardware.camera2.full" feature in your
 * manifest.</p>
 *
 * @see CameraManager#openCamera
 * @see android.Manifest.permission#CAMERA
 */
public interface CameraDevice extends AutoCloseable {

    /**
     * Create a request suitable for a camera preview window. Specifically, this
     * means that high frame rate is given priority over the highest-quality
     * post-processing. These requests would normally be used with the
     * {@link #setRepeatingRequest} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_PREVIEW = 1;

    /**
     * Create a request suitable for still image capture. Specifically, this
     * means prioritizing image quality over frame rate. These requests would
     * commonly be used with the {@link #capture} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_STILL_CAPTURE = 2;

    /**
     * Create a request suitable for video recording. Specifically, this means
     * that a stable frame rate is used, and post-processing is set for
     * recording quality. These requests would commonly be used with the
     * {@link #setRepeatingRequest} method.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_RECORD  = 3;

    /**
     * Create a request suitable for still image capture while recording
     * video. Specifically, this means maximizing image quality without
     * disrupting the ongoing recording. These requests would commonly be used
     * with the {@link #capture} method while a request based on
     * {@link #TEMPLATE_RECORD} is is in use with {@link #setRepeatingRequest}.
     *
     * @see #createCaptureRequest
     */
    public static final int TEMPLATE_VIDEO_SNAPSHOT = 4;

    /**
     * Create a request suitable for zero shutter lag still capture. This means
     * means maximizing image quality without compromising preview frame rate.
     * AE/AWB/AF should be on auto mode.
     *
     * @see #createCaptureRequest
     * @hide
     */
    public static final int TEMPLATE_ZERO_SHUTTER_LAG = 5;

    /**
     * A basic template for direct application control of capture
     * parameters. All automatic control is disabled (auto-exposure, auto-white
     * balance, auto-focus), and post-processing parameters are set to preview
     * quality. The manual capture parameters (exposure, sensitivity, and so on)
     * are set to reasonable defaults, but should be overriden by the
     * application depending on the intended use case.
     *
     * @see #createCaptureRequest
     * @hide
     */
    public static final int TEMPLATE_MANUAL = 6;

    /**
     * Get the ID of this camera device.
     *
     * <p>This matches the ID given to {@link CameraManager#openCamera} to instantiate this
     * this camera device.</p>
     *
     * <p>This ID can be used to query the camera device's {@link
     * CameraProperties fixed properties} with {@link
     * CameraManager#getCameraProperties}.</p>
     *
     * <p>This method can be called even if the device has been closed or has encountered
     * a serious error.</p>
     *
     * @return the ID for this camera device
     *
     * @see CameraManager#getCameraProperties
     * @see CameraManager#getDeviceIdList
     */
    public String getId();

    /**
     * Get the static properties for this camera. These are identical to the
     * properties returned by {@link CameraManager#getCameraProperties}.
     *
     * @return the static properties of the camera
     *
     * @throws CameraAccessException if the camera device is no longer connected
     *
     * @see CameraManager#getCameraProperties
     */
    public CameraProperties getProperties() throws CameraAccessException;

    /**
     * <p>Set up a new output set of Surfaces for the camera device.</p>
     *
     * <p>The configuration determines the set of potential output Surfaces for
     * the camera device for each capture request. A given request may use all
     * or a only some of the outputs. This method must be called before requests
     * can be submitted to the camera with {@link #capture capture},
     * {@link #captureBurst captureBurst},
     * {@link #setRepeatingRequest setRepeatingRequest}, or
     * {@link #setRepeatingBurst setRepeatingBurst}.</p>
     *
     * <p>Surfaces suitable for inclusion as a camera output can be created for
     * various use cases and targets:</p>
     *
     * <ul>
     *
     * <li>For drawing to a {@link android.view.SurfaceView SurfaceView}: Set
     *   the size of the Surface with
     *   {@link android.view.SurfaceHolder#setFixedSize} to be one of the
     *   supported
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}
     *   before calling {@link android.view.SurfaceHolder#getSurface}.</li>
     *
     * <li>For accessing through an OpenGL texture via a
     *   {@link android.graphics.SurfaceTexture SurfaceTexture}: Set the size of
     *   the SurfaceTexture with
     *   {@link android.graphics.SurfaceTexture#setDefaultBufferSize} to be one
     *   of the supported
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}
     *   before creating a Surface from the SurfaceTexture with
     *   {@link Surface#Surface}.</li>
     *
     * <li>For recording with {@link android.media.MediaCodec}: Call
     *   {@link android.media.MediaCodec#createInputSurface} after configuring
     *   the media codec to use one of the
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}
     *   </li>
     *
     * <li>For recording with {@link android.media.MediaRecorder}: TODO</li>
     *
     * <li>For efficient YUV processing with {@link android.renderscript}:
     *   Create a RenderScript
     *   {@link android.renderscript.Allocation Allocation} with a supported YUV
     *   type, the IO_INPUT flag, and one of the supported
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed sizes}. Then
     *   obtain the Surface with
     *   {@link android.renderscript.Allocation#getSurface}.</li>
     *
     * <li>For access to uncompressed or JPEG data in the application: Create a
     *   {@link android.media.ImageReader} object with the desired
     *   {@link CameraProperties#SCALER_AVAILABLE_FORMATS image format}, and a
     *   size from the matching
     *   {@link CameraProperties#SCALER_AVAILABLE_PROCESSED_SIZES processed},
     *   {@link CameraProperties#SCALER_AVAILABLE_JPEG_SIZES jpeg}. Then obtain
     *   a Surface from it.</li>
     *
     * </ul>
     *
     * </p>
     *
     * <p>This function can take several hundred milliseconds to execute, since
     * camera hardware may need to be powered on or reconfigured.</p>
     *
     * <p>The camera device will query each Surface's size and formats upon this
     * call, so they must be set to a valid setting at this time (in particular:
     * if the format is user-visible, it must be one of android.scaler.availableFormats;
     * and the size must be one of android.scaler.available[Processed|Jpeg]Sizes).</p>
     *
     * <p>To change the output, the camera device must be idle. The device is considered
     * to be idle once all in-flight and pending capture requests have been processed,
     * and all output image buffers from the captures have been sent to their destination
     * Surfaces.</p>
     *
     * <p>To reach an idle state without cancelling any submitted captures, first
     * stop any repeating request/burst with {@link #stopRepeating}, and then
     * wait for the {@link CameraDeviceListener#onCameraIdle} callback to be
     * called. To idle as fast as possible, use {@link #flush} and wait for the
     * idle callback.</p>
     *
     * <p>Using larger resolution outputs, or more outputs, can result in slower
     * output rate from the device.</p>
     *
     * @param outputs The new set of Surfaces that should be made available as
     * targets for captured image data.
     *
     * @throws IllegalArgumentException if the set of output Surfaces do not
     * meet the requirements
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device is not idle, or has
     * encountered a fatal error
     *
     * @see CameraDeviceListener#onCameraIdle
     * @see #stopRepeating
     * @see #flush
     */
    public void configureOutputs(List<Surface> outputs) throws CameraAccessException;

    /**
     * <p>Create a {@link CaptureRequest.Builder} for new capture requests,
     * initialized with template for a target use case. The settings are chosen
     * to be the best options for the specific camera device, so it is not
     * recommended to reuse the same request for a different camera device;
     * create a builder specific for that device and template and override the
     * settings as desired, instead.</p>
     *
     * @param templateType An enumeration selecting the use case for this
     * request; one of the CameraDevice.TEMPLATE_ values.
     * @return a builder for a capture request, initialized with default
     * settings for that template, and no output streams
     *
     * @throws IllegalArgumentException if the templateType is not in the list
     * of supported templates.
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #TEMPLATE_PREVIEW
     * @see #TEMPLATE_RECORD
     * @see #TEMPLATE_STILL_CAPTURE
     * @see #TEMPLATE_VIDEO_SNAPSHOT
     * @see #TEMPLATE_MANUAL
     */
    public CaptureRequest.Builder createCaptureRequest(int templateType)
            throws CameraAccessException;

    /**
     * <p>Submit a request for an image to be captured by this CameraDevice.</p>
     *
     * <p>The request defines all the parameters for capturing the single image,
     * including sensor, lens, flash, and post-processing settings.</p>
     *
     * <p>Each request will produce one {@link CaptureResult} and produce new
     * frames for one or more target Surfaces, set with the CaptureRequest
     * builder's {@link CaptureRequest.Builder#addTarget} method. The target
     * surfaces must be configured as active outputs with
     * {@link #configureOutputs} before calling this method.</p>
     *
     * <p>Multiple requests can be in progress at once. They are processed in
     * first-in, first-out order, with minimal delays between each
     * capture. Requests submitted through this method have higher priority than
     * those submitted through {@link #setRepeatingRequest} or
     * {@link #setRepeatingBurst}, and will be processed as soon as the current
     * repeat/repeatBurst processing completes.</p>
     *
     * @param request the settings for this capture
     * @param listener The callback object to notify once this request has been
     * processed. If null, no metadata will be produced for this capture,
     * although image data will still be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     * @throws IllegalArgumentException If the request targets Surfaces not
     * currently configured as outputs. Or if the handler is null, the listener
     * is not null, and the calling thread has no looper.
     *
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     */
    public void capture(CaptureRequest request, CaptureListener listener, Handler handler)
            throws CameraAccessException;

    /**
     * Submit a list of requests to be captured in sequence as a burst. The
     * burst will be captured in the minimum amount of time possible, and will
     * not be interleaved with requests submitted by other capture or repeat
     * calls.
     *
     * <p>The requests will be captured in order, each capture producing one
     * {@link CaptureResult} and image buffers for one or more target
     * {@link android.view.Surface surfaces}. The target surfaces for each
     * request (set with {@link CaptureRequest.Builder#addTarget}) must be
     * configured as active outputs with {@link #configureOutputs} before
     * calling this method.</p>
     *
     * <p>The main difference between this method and simply calling
     * {@link #capture} repeatedly is that this method guarantees that no
     * other requests will be interspersed with the burst.</p>
     *
     * @param requests the list of settings for this burst capture
     * @param listener The callback object to notify each time one of the
     * requests in the burst has been processed. If null, no metadata will be
     * produced for any requests in this burst, although image data will still
     * be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     * @throws IllegalArgumentException If the requests target Surfaces not
     * currently configured as outputs. Or if the handler is null, the listener
     * is not null, and the calling thread has no looper.
     *
     * @see #capture
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     */
    public void captureBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException;

    /**
     * Request endlessly repeating capture of images by this CameraDevice.
     *
     * <p>With this method, the CameraDevice will continually capture images
     * using the settings in the provided {@link CaptureRequest}, at the maximum
     * rate possible.</p>
     *
     * <p>Repeating requests are a simple way for an application to maintain a
     * preview or other continuous stream of frames, without having to
     * continually submit identical requests through {@link #capture}.</p>
     *
     * <p>Repeat requests have lower priority than those submitted
     * through {@link #capture} or {@link #captureBurst}, so if
     * {@link #capture} is called when a repeating request is active, the
     * capture request will be processed before any further repeating
     * requests are processed.<p>
     *
     * <p>Repeating requests are a simple way for an application to maintain a
     * preview or other continuous stream of frames, without having to submit
     * requests through {@link #capture} at video rates.</p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}. Calling
     * {@link #flush} will also clear the request.</p>
     *
     * <p>Calling this method will replace any earlier repeating request or
     * burst set up by this method or {@link #setRepeatingBurst}, although any
     * in-progress burst will be completed before the new repeat request will be
     * used.</p>
     *
     * @param request the request to repeat indefinitely
     * @param listener The callback object to notify every time the
     * request finishes processing. If null, no metadata will be
     * produced for this stream of requests, although image data will
     * still be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @throws CameraAccessException if the camera device is no longer
     * connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     * @throws IllegalArgumentException If the requests reference Surfaces not
     * currently configured as outputs. Or if the handler is null, the listener
     * is not null, and the calling thread has no looper.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingBurst
     * @see #stopRepeating
     * @see #flush
     */
    public void setRepeatingRequest(CaptureRequest request, CaptureListener listener,
            Handler handler) throws CameraAccessException;

    /**
     * <p>Request endlessly repeating capture of a sequence of images by this
     * CameraDevice.</p>
     *
     * <p>With this method, the CameraDevice will continually capture images,
     * cycling through the settings in the provided list of
     * {@link CaptureRequest CaptureRequests}, at the maximum rate possible.</p>
     *
     * <p>If a request is submitted through {@link #capture} or
     * {@link #captureBurst}, the current repetition of the request list will be
     * completed before the higher-priority request is handled. This guarantees
     * that the application always receives a complete repeat burst captured in
     * minimal time, instead of bursts interleaved with higher-priority
     * captures, or incomplete captures.</p>
     *
     * <p>Repeating burst requests are a simple way for an application to
     * maintain a preview or other continuous stream of frames where each
     * request is different in a predicatable way, without having to continually
     * submit requests through {@link #captureBurst} .</p>
     *
     * <p>To stop the repeating capture, call {@link #stopRepeating}. Any
     * ongoing burst will still be completed, however. Calling
     * {@link #flush} will also clear the request.</p>
     *
     * <p>Calling this method will replace a previously-set repeating request or
     * burst set up by this method or {@link #setRepeatingRequest}, although any
     * in-progress burst will be completed before the new repeat burst will be
     * used.</p>
     *
     * @param requests the list of requests to cycle through indefinitely
     * @param listener The callback object to notify each time one of the
     * requests in the repeating bursts has finished processing. If null, no
     * metadata will be produced for this stream of requests, although image
     * data will still be produced.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper
     * looper}.
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     * @throws IllegalArgumentException If the requests reference Surfaces not
     * currently configured as outputs. Or if the handler is null, the listener
     * is not null, and the calling thread has no looper.
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #stopRepeating
     * @see #flush
     */
    public void setRepeatingBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException;

    /**
     * <p>Cancel any ongoing repeating capture set by either
     * {@link #setRepeatingRequest setRepeatingRequest} or
     * {@link #setRepeatingBurst}. Has no effect on requests submitted through
     * {@link #capture capture} or {@link #captureBurst captureBurst}.</p>
     *
     * <p>Any currently in-flight captures will still complete, as will any
     * burst that is mid-capture. To ensure that the device has finished
     * processing all of its capture requests and is in idle state, wait for the
     * {@link CameraDeviceListener#onCameraIdle} callback after calling this
     * method..</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed or the
     * device has encountered a fatal error.
     *
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see CameraDeviceListener#onCameraIdle
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed, the
     * device has encountered a fatal error, or if there is an active repeating
     * request or burst.
     */
    public void stopRepeating() throws CameraAccessException;

    /**
     * <p>Wait until all the submitted requests have finished processing</p>
     *
     * <p>This method blocks until all the requests that have been submitted to
     * the camera device, either through {@link #capture capture},
     * {@link #captureBurst captureBurst},
     * {@link #setRepeatingRequest setRepeatingRequest}, or
     * {@link #setRepeatingBurst setRepeatingBurst}, have completed their
     * processing.</p>
     *
     * <p>Once this call returns successfully, the device is in an idle state,
     * and can be reconfigured with {@link #configureOutputs configureOutputs}.</p>
     *
     * <p>This method cannot be used if there is an active repeating request or
     * burst, set with {@link #setRepeatingRequest setRepeatingRequest} or
     * {@link #setRepeatingBurst setRepeatingBurst}. Call
     * {@link #stopRepeating stopRepeating} before calling this method.</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @throws IllegalStateException if the camera device has been closed, the
     * device has encountered a fatal error, or if there is an active repeating
     * request or burst.
     */
    public void waitUntilIdle() throws CameraAccessException;

    /**
     * Set the listener object to call when an asynchronous device event occurs,
     * such as errors or idle notifications.
     *
     * <p>The events reported here are device-wide; notifications about
     * individual capture requests or capture results are reported through
     * {@link CaptureListener}.</p>
     *
     * <p>If the camera device is idle when the listener is set, then the
     * {@link CameraDeviceListener#onCameraIdle} method will be immediately called,
     * even if the device has never been active before.
     * </p>
     *
     * @param listener the CameraDeviceListener to send device-level event
     * notifications to. Setting this to null will stop notifications.
     * @param handler the handler on which the listener should be invoked, or
     * {@code null} to use the current thread's {@link android.os.Looper looper}.
     *
     * @throws IllegalArgumentException if handler is null, the listener is
     * not null, and the calling thread has no looper
     */
    public void setDeviceListener(CameraDeviceListener listener, Handler handler);

    /**
     * Flush all captures currently pending and in-progress as fast as
     * possible.
     *
     * <p>The camera device will discard all of its current work as fast as
     * possible. Some in-flight captures may complete successfully and call
     * {@link CaptureListener#onCaptureCompleted}, while others will trigger
     * their {@link CaptureListener#onCaptureFailed} callbacks. If a repeating
     * request or a repeating burst is set, it will be cleared by the flush.</p>
     *
     * <p>This method is the fastest way to idle the camera device for
     * reconfiguration with {@link #configureOutputs}, at the cost of discarding
     * in-progress work. Once the flush is complete, the idle callback will be
     * called.</p>
     *
     * <p>Flushing will introduce at least a brief pause in the stream of data
     * from the camera device, since once the flush is complete, the first new
     * request has to make it through the entire camera pipeline before new
     * output buffers are produced.</p>
     *
     * <p>This means that using {@code flush()} to simply remove pending
     * requests is not recommended; it's best used for quickly switching output
     * configurations, or for cancelling long in-progress requests (such as a
     * multi-second capture).</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     * @see #configureOutputs
     */
    public void flush() throws CameraAccessException;

    /**
     * Close the connection to this camera device. After this call, all calls to
     * the camera device interface will throw a {@link IllegalStateException},
     * except for calls to close().
     * @throws Exception
     */
    @Override
    public void close() throws Exception;
    // TODO: We should decide on the behavior of in-flight requests should be on close.

    /**
     * <p>A listener for tracking the progress of a {@link CaptureRequest}
     * submitted to the camera device.</p>
     *
     * <p>This listener is called when a request triggers a capture to start,
     * and when the capture is complete. In case on an error capturing an image,
     * the error method is triggered instead of the completion method.</p>
     *
     * @see #capture
     * @see #captureBurst
     * @see #setRepeatingRequest
     * @see #setRepeatingBurst
     *
     */
    public static abstract class CaptureListener {

        /**
         * This method is called when the camera device has started capturing
         * the output image for the request, at the beginning of image exposure.
         *
         * <p>This callback is invoked right as the capture of a frame begins,
         * so it is the most appropriate time for playing a shutter sound,
         * or triggering UI indicators of capture.</p>
         *
         * <p>The request that is being used for this capture is provided, along
         * with the actual timestamp for the start of exposure. This timestamp
         * matches the timestamp that will be included in
         * {@link CaptureResult#SENSOR_TIMESTAMP the result timestamp field},
         * and in the buffers sent to each output Surface. These buffer
         * timestamps are accessible through, for example,
         * {@link android.media.Image#getTimestamp() Image.getTimestamp()} or
         * {@link android.graphics.SurfaceTexture#getTimestamp()}.</p>
         *
         * <p>For the simplest way to play a shutter sound camera shutter or a
         * video recording start/stop sound, see the
         * {@link android.media.MediaActionSound} class.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param camera the CameraDevice sending the callback
         * @param request the request for the capture that just begun
         * @param timestamp the timestamp at start of capture, in nanoseconds.
         *
         * @see android.media.MediaActionSound
         */
        public void onCaptureStarted(CameraDevice camera,
                CaptureRequest request, long timestamp) {
            // default empty implementation
        }

        /**
         * This method is called when an image capture has completed and the
         * result metadata is available.
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param camera The CameraDevice sending the callback.
         * @param request The request that was given to the CameraDevice
         * @param result The output metadata from the capture, including the
         * final capture parameters and the state of the camera system during
         * capture.
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureCompleted(CameraDevice camera,
                CaptureRequest request, CaptureResult result) {
            // default empty implementation
        }

        /**
         * This method is called instead of {@link #onCaptureCompleted} when the
         * camera device failed to produce a {@link CaptureResult} for the
         * request.
         *
         * <p>Other requests are unaffected, and some or all image buffers from
         * the capture may have been pushed to their respective output
         * streams.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param camera The CameraDevice sending the callback.
         * @param request The request that was given to the CameraDevice
         *
         * @see #capture
         * @see #captureBurst
         * @see #setRepeatingRequest
         * @see #setRepeatingBurst
         */
        public void onCaptureFailed(CameraDevice camera,
                CaptureRequest request) {
            // default empty implementation
        }
    }

    /**
     * A listener for notifications about the state of a camera
     * device.
     *
     * <p>These events include notifications about the device becoming idle (
     * allowing for {@link #configureOutputs} to be called), about device
     * disconnection, and about unexpected device errors.</p>
     *
     * <p>Events about the progress of specific {@link CaptureRequest
     * CaptureRequests} are provided through a {@link CaptureListener} given to
     * the {@link #capture}, {@link #captureBurst}, {@link
     * #setRepeatingRequest}, or {@link #setRepeatingBurst} methods.
     *
     * @see #setDeviceListener
     */
    public static abstract class CameraDeviceListener {

        /**
         * An error code that can be reported by {@link #onCameraError}
         * indicating that the camera device has encountered a fatal error.
         *
         * <p>The camera device needs to be re-opened to be used again.</p>
         *
         * @see #onCameraDeviceError
         */
        public static final int ERROR_CAMERA_DEVICE = 1;

        /**
         * An error code that can be reported by {@link #onCameraError}
         * indicating that the camera service has encountered a fatal error.
         *
         * <p>The Android device may need to be shut down and restarted to restore
         * camera function, or there may be a persistent hardware problem.</p>
         *
         * @see #onCameraDeviceError
         */
        public static final int ERROR_CAMERA_SERVICE = 2;

        /**
         * The method called when a camera device has finished processing all
         * submitted capture requests and has reached an idle state.
         *
         * <p>An idle camera device can have its outputs changed by calling
         * {@link CameraDevice#configureOutputs}.</p>
         *
         * <p>To idle and reconfigure outputs without cancelling any submitted
         * capture requests, the application needs to clear its repeating
         * request/burst, if set, with {@link CameraDevice#stopRepeating}, and
         * then wait for this callback to be called before calling {@link
         * CameraDevice#configureOutputs}.</p>
         *
         * <p>To idle and reconfigure a camera device as fast as possible, the
         * {@link CameraDevice#flush} method can be used, which will discard all
         * pending and in-progess capture requests. Once the {@link
         * CameraDevice#flush} method is called, the application must wait for
         * this callback to fire before calling {@link
         * CameraDevice#configureOutputs}.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param camera the camera device that has become idle
         *
         * @see CameraDevice#configureOutputs
         * @see CameraDevice#stopRepeating
         * @see CameraDevice#flush
         */
        public void onCameraIdle(CameraDevice camera) {
            // Default empty implementation
        }

        /**
         * The method called when a camera device is no longer available for
         * use.
         *
         * <p>Any attempt to call methods on this CameraDevice will throw a
         * {@link CameraAccessException}. The disconnection could be due to a
         * change in security policy or permissions; the physical disconnection
         * of a removable camera device; or the camera being needed for a
         * higher-priority use case.</p>
         *
         * <p>There may still be capture listener callbacks that are called
         * after this method is called, or new image buffers that are delivered
         * to active outputs.</p>
         *
         * <p>The default implementation logs a notice to the system log
         * about the disconnection.</p>
         *
         * @param camera the device that has been disconnected
         */
        public void onCameraDisconnected(CameraDevice camera) {
            Log.i("CameraListener",
                    String.format("Camera device %s disconnected", camera.getId()));
        }

        /**
         * The method called when a camera device has encountered a serious error.
         *
         * <p>This indicates a failure of the camera device or camera service in
         * some way. Any attempt to call methods on this CameraDevice in the
         * future will throw a {@link java.lang.IllegalStateException}.</p>
         *
         * <p>There may still be capture completion or camera stream listeners
         * that will be called after this error is received.</p>
         *
         * <p>The default implementation logs an error to the system log about
         * the camera failure.</p>
         *
         * @param camera The device reporting the error
         * @param error The error code, one of the
         *     {@code CameraDeviceListener.ERROR_*} values.
         *
         * @see #ERROR_CAMERA_DEVICE
         * @see #ERROR_CAMERA_SERVICE
         */
        public void onCameraError(CameraDevice camera, int error) {
            Log.e("CameraListener",
                    String.format("Camera device %s has encountered an error: %d",
                            camera.getId(), error));
        }
    }
}
