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
package android.hardware.camera2.impl;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.dispatch.BroadcastDispatcher;
import android.hardware.camera2.dispatch.Dispatchable;
import android.hardware.camera2.dispatch.DuckTypingDispatcher;
import android.hardware.camera2.dispatch.HandlerDispatcher;
import android.hardware.camera2.dispatch.InvokeDispatcher;
import android.hardware.camera2.dispatch.NullDispatcher;
import android.hardware.camera2.utils.TaskDrainer;
import android.hardware.camera2.utils.TaskSingleDrainer;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;
import java.util.List;

import static android.hardware.camera2.impl.CameraDevice.checkHandler;
import static com.android.internal.util.Preconditions.*;

public class CameraCaptureSessionImpl extends CameraCaptureSession {
    private static final String TAG = "CameraCaptureSession";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    /** User-specified set of surfaces used as the configuration outputs */
    private final List<Surface> mOutputs;
    /**
     * User-specified state listener, used for outgoing events; calls to this object will be
     * automatically {@link Handler#post(Runnable) posted} to {@code mStateHandler}.
     */
    private final CameraCaptureSession.StateListener mStateListener;
    /** User-specified state handler used for outgoing state listener events */
    private final Handler mStateHandler;

    /** Internal camera device; used to translate calls into existing deprecated API */
    private final android.hardware.camera2.impl.CameraDevice mDeviceImpl;
    /** Internal handler; used for all incoming events to preserve total order */
    private final Handler mDeviceHandler;

    /** Drain Sequence IDs which have been queued but not yet finished with aborted/completed */
    private final TaskDrainer<Integer> mSequenceDrainer;
    /** Drain state transitions from ACTIVE -> IDLE */
    private final TaskSingleDrainer mIdleDrainer;
    /** Drain state transitions from BUSY -> IDLE */
    private final TaskSingleDrainer mAbortDrainer;
    /** Drain the UNCONFIGURED state transition */
    private final TaskSingleDrainer mUnconfigureDrainer;

    /** This session is closed; all further calls will throw ISE */
    private boolean mClosed = false;
    /** Do not unconfigure if this is set; another session will overwrite configuration */
    private boolean mSkipUnconfigure = false;

    /** Is the session in the process of aborting? Pay attention to BUSY->IDLE transitions. */
    private boolean mAborting;

    /**
     * Create a new CameraCaptureSession.
     *
     * <p>The camera device must already be in the {@code IDLE} state when this is invoked.
     * There must be no pending actions
     * (e.g. no pending captures, no repeating requests, no flush).</p>
     */
    CameraCaptureSessionImpl(List<Surface> outputs,
            CameraCaptureSession.StateListener listener, Handler stateHandler,
            android.hardware.camera2.impl.CameraDevice deviceImpl,
            Handler deviceStateHandler, boolean configureSuccess) {
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalArgumentException("outputs must be a non-null, non-empty list");
        } else if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        // TODO: extra verification of outputs
        mOutputs = outputs;
        mStateHandler = checkHandler(stateHandler);
        mStateListener = createUserStateListenerProxy(mStateHandler, listener);

        mDeviceHandler = checkNotNull(deviceStateHandler, "deviceStateHandler must not be null");
        mDeviceImpl = checkNotNull(deviceImpl, "deviceImpl must not be null");

        /*
         * Use the same handler as the device's StateListener for all the internal coming events
         *
         * This ensures total ordering between CameraDevice.StateListener and
         * CameraDevice.CaptureListener events.
         */
        mSequenceDrainer = new TaskDrainer<>(mDeviceHandler, new SequenceDrainListener(),
                /*name*/"seq");
        mIdleDrainer = new TaskSingleDrainer(mDeviceHandler, new IdleDrainListener(),
                /*name*/"idle");
        mAbortDrainer = new TaskSingleDrainer(mDeviceHandler, new AbortDrainListener(),
                /*name*/"abort");
        mUnconfigureDrainer = new TaskSingleDrainer(mDeviceHandler, new UnconfigureDrainListener(),
                /*name*/"unconf");

        // CameraDevice should call configureOutputs and have it finish before constructing us

        if (configureSuccess) {
            mStateListener.onConfigured(this);
        } else {
            mStateListener.onConfigureFailed(this);
            mClosed = true; // do not fire any other callbacks, do not allow any other work
        }
    }

    @Override
    public CameraDevice getDevice() {
        return mDeviceImpl;
    }

    @Override
    public synchronized int capture(CaptureRequest request, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        checkNotClosed();
        checkLegalToCapture();

        handler = checkHandler(handler);

        if (VERBOSE) {
            Log.v(TAG, "capture - request " + request + ", listener " + listener + " handler" +
                    "" + handler);
        }

        return addPendingSequence(mDeviceImpl.capture(request,
                createCaptureListenerProxy(handler, listener), mDeviceHandler));
    }

    @Override
    public synchronized int captureBurst(List<CaptureRequest> requests, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        checkNotClosed();
        checkLegalToCapture();

        handler = checkHandler(handler);

        if (VERBOSE) {
            CaptureRequest[] requestArray = requests.toArray(new CaptureRequest[0]);
            Log.v(TAG, "captureBurst - requests " + Arrays.toString(requestArray) + ", listener " +
                    listener + " handler" + "" + handler);
        }

        return addPendingSequence(mDeviceImpl.captureBurst(requests,
                createCaptureListenerProxy(handler, listener), mDeviceHandler));
    }

    @Override
    public synchronized int setRepeatingRequest(CaptureRequest request, CaptureListener listener,
            Handler handler) throws CameraAccessException {
        checkNotClosed();
        checkLegalToCapture();

        handler = checkHandler(handler);

        return addPendingSequence(mDeviceImpl.setRepeatingRequest(request,
                createCaptureListenerProxy(handler, listener), mDeviceHandler));
    }

    @Override
    public synchronized int setRepeatingBurst(List<CaptureRequest> requests,
            CaptureListener listener, Handler handler) throws CameraAccessException {
        checkNotClosed();
        checkLegalToCapture();

        handler = checkHandler(handler);

        if (VERBOSE) {
            CaptureRequest[] requestArray = requests.toArray(new CaptureRequest[0]);
            Log.v(TAG, "setRepeatingBurst - requests " + Arrays.toString(requestArray) +
                    ", listener " + listener + " handler" + "" + handler);
        }

        return addPendingSequence(mDeviceImpl.setRepeatingBurst(requests,
                createCaptureListenerProxy(handler, listener), mDeviceHandler));
    }

    @Override
    public synchronized void stopRepeating() throws CameraAccessException {
        checkNotClosed();

        if (VERBOSE) {
            Log.v(TAG, "stopRepeating");
        }

        mDeviceImpl.stopRepeating();
    }

    @Override
    public synchronized void abortCaptures() throws CameraAccessException {
        checkNotClosed();

        if (VERBOSE) {
            Log.v(TAG, "abortCaptures");
        }

        if (mAborting) {
            Log.w(TAG, "abortCaptures - Session is already aborting; doing nothing");
            return;
        }

        mAborting = true;
        mAbortDrainer.taskStarted();

        mDeviceImpl.flush();
        // The next BUSY -> IDLE set of transitions will mark the end of the abort.
    }

    /**
     * Replace this session with another session.
     *
     * <p>This is an optimization to avoid unconfiguring and then immediately having to
     * reconfigure again.</p>
     *
     * <p>The semantics are identical to {@link #close}, except that unconfiguring will be skipped.
     * <p>
     *
     * @see CameraCaptureSession#close
     */
    synchronized void replaceSessionClose(CameraCaptureSession other) {
        /*
         * In order for creating new sessions to be fast, the new session should be created
         * before the old session is closed.
         *
         * Otherwise the old session will always unconfigure if there is no new session to
         * replace it.
         *
         * Unconfiguring could add hundreds of milliseconds of delay. We could race and attempt
         * to skip unconfigure if a new session is created before the captures are all drained,
         * but this would introduce nondeterministic behavior.
         */

        // #close was already called explicitly, keep going the slow route
        if (mClosed) {
            return;
        }

        mSkipUnconfigure = true;
        close();
    }

    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }

        mClosed = true;

        /*
         * Flush out any repeating request. Since camera is closed, no new requests
         * can be queued, and eventually the entire request queue will be drained.
         *
         * Once this is done, wait for camera to idle, then unconfigure the camera.
         * Once that's done, fire #onClosed.
         */
        try {
            mDeviceImpl.stopRepeating();
        } catch (CameraAccessException e) {
            // OK: close does not throw checked exceptions.
            Log.e(TAG, "Exception while stopping repeating: ", e);

            // TODO: call onError instead of onClosed if this happens
        }

        // If no sequences are pending, fire #onClosed immediately
        mSequenceDrainer.beginDrain();
    }

    /**
     * Post calls into a CameraCaptureSession.StateListener to the user-specified {@code handler}.
     */
    private StateListener createUserStateListenerProxy(Handler handler, StateListener listener) {
        InvokeDispatcher<StateListener> userListenerSink = new InvokeDispatcher<>(listener);
        HandlerDispatcher<StateListener> handlerPassthrough =
                new HandlerDispatcher<>(userListenerSink, handler);

        return new ListenerProxies.SessionStateListenerProxy(handlerPassthrough);
    }

    /**
     * Forward callbacks from
     * CameraDevice.CaptureListener to the CameraCaptureSession.CaptureListener.
     *
     * <p>In particular, all calls are automatically split to go both to our own
     * internal listener, and to the user-specified listener (by transparently posting
     * to the user-specified handler).</p>
     *
     * <p>When a capture sequence finishes, update the pending checked sequences set.</p>
     */
    @SuppressWarnings("deprecation")
    private CameraDevice.CaptureListener createCaptureListenerProxy(
            Handler handler, CaptureListener listener) {
        CameraDevice.CaptureListener localListener = new CameraDevice.CaptureListener() {
            @Override
            public void onCaptureSequenceCompleted(CameraDevice camera,
                    int sequenceId, long frameNumber) {
                finishPendingSequence(sequenceId);
            }

            @Override
            public void onCaptureSequenceAborted(CameraDevice camera,
                    int sequenceId) {
                finishPendingSequence(sequenceId);
            }
        };

        /*
         * Split the calls from the device listener into local listener and the following chain:
         * - duck type from device listener to session listener
         * - then forward the call to a handler
         * - then finally invoke the destination method on the session listener object
         */
        Dispatchable<CaptureListener> userListenerSink;
        if (listener == null) { // OK: API allows the user to not specify a listener
            userListenerSink = new NullDispatcher<>();
        } else {
            userListenerSink = new InvokeDispatcher<>(listener);
        }

        InvokeDispatcher<CameraDevice.CaptureListener> localSink =
                new InvokeDispatcher<>(localListener);
        HandlerDispatcher<CaptureListener> handlerPassthrough =
                new HandlerDispatcher<>(userListenerSink, handler);
        DuckTypingDispatcher<CameraDevice.CaptureListener, CaptureListener> duckToSessionCaptureListener
                = new DuckTypingDispatcher<>(handlerPassthrough, CaptureListener.class);

        BroadcastDispatcher<CameraDevice.CaptureListener> broadcaster =
                new BroadcastDispatcher<CameraDevice.CaptureListener>(
                        duckToSessionCaptureListener,
                        localSink);

        return new ListenerProxies.DeviceCaptureListenerProxy(broadcaster);
    }

    /**
     *
     * Create an internal state listener, to be invoked on the mDeviceHandler
     *
     * <p>It has a few behaviors:
     * <ul>
     * <li>Convert device state changes into session state changes.
     * <li>Keep track of async tasks that the session began (idle, abort).
     * </ul>
     * </p>
     * */
    CameraDevice.StateListener getDeviceStateListener() {
        final CameraCaptureSession session = this;

        return new CameraDevice.StateListener() {
            private boolean mBusy = false;
            private boolean mActive = false;

            @Override
            public void onOpened(CameraDevice camera) {
                throw new AssertionError("Camera must already be open before creating a session");
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                close();
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                // TODO: Handle errors somehow.
                Log.wtf(TAG, "Got device error " + error);
            }

            @Override
            public void onActive(CameraDevice camera) {
                mIdleDrainer.taskStarted();
                mActive = true;

                mStateListener.onActive(session);
            }

            @Override
            public void onIdle(CameraDevice camera) {
                boolean isAborting;
                synchronized (session) {
                    isAborting = mAborting;
                }

                /*
                 * Check which states we transitioned through:
                 *
                 * (ACTIVE -> IDLE)
                 * (BUSY -> IDLE)
                 *
                 * Note that this is also legal:
                 * (ACTIVE -> BUSY -> IDLE)
                 *
                 * and mark those tasks as finished
                 */
                if (mBusy && isAborting) {
                    mAbortDrainer.taskFinished();

                    synchronized (session) {
                        mAborting = false;
                    }
                }

                if (mActive) {
                    mIdleDrainer.taskFinished();
                }

                mBusy = false;
                mActive = false;

                mStateListener.onReady(session);
            }

            @Override
            public void onBusy(CameraDevice camera) {
                mBusy = true;

                // TODO: Queue captures during abort instead of failing them
                // since the app won't be able to distinguish the two actives
                Log.w(TAG, "Device is now busy; do not submit new captures (TODO: allow this)");
                mStateListener.onActive(session);
            }

            @Override
            public void onUnconfigured(CameraDevice camera) {
                mUnconfigureDrainer.taskFinished();
            }
        };

    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private void checkLegalToCapture() {
        if (mAborting) {
            throw new IllegalStateException(
                    "Session is aborting captures; new captures are not permitted");
        }
    }

    private void checkNotClosed() {
        if (mClosed) {
            throw new IllegalStateException(
                    "Session has been closed; further changes are illegal.");
        }
    }

    /**
     * Notify the session that a pending capture sequence has just been queued.
     *
     * <p>During a shutdown/close, the session waits until all pending sessions are finished
     * before taking any further steps to shut down itself.</p>
     *
     * @see #finishPendingSequence
     */
    private int addPendingSequence(int sequenceId) {
        mSequenceDrainer.taskStarted(sequenceId);
        return sequenceId;
    }

    /**
     * Notify the session that a pending capture sequence is now finished.
     *
     * <p>During a shutdown/close, once all pending sequences finish, it is safe to
     * close the camera further by unconfiguring and then firing {@code onClosed}.</p>
     */
    private void finishPendingSequence(int sequenceId) {
        mSequenceDrainer.taskFinished(sequenceId);
    }

    private class SequenceDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            /*
             * No repeating request is set; and the capture queue has fully drained.
             *
             * If no captures were queued to begin with, and an abort was queued,
             * it's still possible to get another BUSY before the last IDLE.
             *
             * If the camera is already "IDLE" and no aborts are pending,
             * then the drain immediately finishes.
             */
            mAbortDrainer.beginDrain();
        }
    }

    private class AbortDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            synchronized (CameraCaptureSessionImpl.this) {
                /*
                 * Any queued aborts have now completed.
                 *
                 * It's now safe to wait to receive the final "IDLE" event, as the camera device
                 * will no longer again transition to "ACTIVE" by itself.
                 *
                 * If the camera is already "IDLE", then the drain immediately finishes.
                 */
                mIdleDrainer.beginDrain();
            }
        }
    }

    private class IdleDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            synchronized (CameraCaptureSessionImpl.this) {
                /*
                 * The device is now IDLE, and has settled. It will not transition to
                 * ACTIVE or BUSY again by itself.
                 *
                 * It's now safe to unconfigure the outputs and after it's done invoke #onClosed.
                 *
                 * This operation is idempotent; a session will not be closed twice.
                 */

                // Fast path: A new capture session has replaced this one; don't unconfigure.
                if (mSkipUnconfigure) {
                    mStateListener.onClosed(CameraCaptureSessionImpl.this);
                    return;
                }

                // Slow path: #close was called explicitly on this session; unconfigure first

                try {
                    mUnconfigureDrainer.taskStarted();
                    mDeviceImpl.configureOutputs(null); // begin transition to unconfigured state
                } catch (CameraAccessException e) {
                    // OK: do not throw checked exceptions.
                    Log.e(TAG, "Exception while configuring outputs: ", e);

                    // TODO: call onError instead of onClosed if this happens
                }

                mUnconfigureDrainer.beginDrain();
            }
        }
    }

    private class UnconfigureDrainListener implements TaskDrainer.DrainListener {
        @Override
        public void onDrained() {
            synchronized (CameraCaptureSessionImpl.this) {
                // The device has finished unconfiguring. It's now fully closed.
                mStateListener.onClosed(CameraCaptureSessionImpl.this);
            }
        }
    }
}
