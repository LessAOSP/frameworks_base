/**
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

package android.service.voice;

import android.content.Intent;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.hardware.soundtrigger.KeyphraseMetadata;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.ConfidenceLevel;
import android.hardware.soundtrigger.SoundTrigger.Keyphrase;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionExtra;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseSoundModel;
import android.hardware.soundtrigger.SoundTrigger.ModuleProperties;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.hardware.soundtrigger.SoundTrigger.RecognitionEvent;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.List;

/**
 * A class that lets a VoiceInteractionService implementation interact with
 * always-on keyphrase detection APIs.
 */
public class AlwaysOnHotwordDetector {
    //---- States of Keyphrase availability. Return codes for onAvailabilityChanged() ----//
    /**
     * Indicates that this hotword detector is no longer valid for any recognition
     * and should not be used anymore.
     */
    public static final int STATE_INVALID = -3;
    /**
     * Indicates that recognition for the given keyphrase is not available on the system
     * because of the hardware configuration.
     */
    public static final int STATE_HARDWARE_UNAVAILABLE = -2;
    /**
     * Indicates that recognition for the given keyphrase is not supported.
     */
    public static final int STATE_KEYPHRASE_UNSUPPORTED = -1;
    /**
     * Indicates that the given keyphrase is not enrolled.
     */
    public static final int STATE_KEYPHRASE_UNENROLLED = 1;
    /**
     * Indicates that the given keyphrase is currently enrolled and it's possible to start
     * recognition for it.
     */
    public static final int STATE_KEYPHRASE_ENROLLED = 2;

    /**
     * Indicates that the detector isn't ready currently.
     */
    private static final int STATE_NOT_READY = 0;

    // Keyphrase management actions. Used in getManageIntent() ----//
    /** Indicates that we need to enroll. */
    public static final int MANAGE_ACTION_ENROLL = 0;
    /** Indicates that we need to re-enroll. */
    public static final int MANAGE_ACTION_RE_ENROLL = 1;
    /** Indicates that we need to un-enroll. */
    public static final int MANAGE_ACTION_UN_ENROLL = 2;

    /**
     * Return codes for {@link #startRecognition(int)}, {@link #stopRecognition()}
     */
    public static final int STATUS_ERROR = SoundTrigger.STATUS_ERROR;
    public static final int STATUS_OK = SoundTrigger.STATUS_OK;

    //-- Flags for startRecogntion    ----//
    /** Empty flag for {@link #startRecognition(int)}. */
    public static final int RECOGNITION_FLAG_NONE = 0;
    /**
     * Recognition flag for {@link #startRecognition(int)} that indicates
     * whether the trigger audio for hotword needs to be captured.
     */
    public static final int RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO = 0x1;

    //---- Recognition mode flags. Return codes for getSupportedRecognitionModes() ----//
    // Must be kept in sync with the related attribute defined as searchKeyphraseRecognitionFlags.

    /**
     * Simple recognition of the key phrase. Returned by {@link #getSupportedRecognitionModes()}
     */
    public static final int RECOGNITION_MODE_VOICE_TRIGGER
            = SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER;
    /**
     * Trigger only if one user is identified. Returned by {@link #getSupportedRecognitionModes()}
     */
    public static final int RECOGNITION_MODE_USER_IDENTIFICATION
            = SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION;

    static final String TAG = "AlwaysOnHotwordDetector";
    // TODO: Set to false.
    static final boolean DBG = true;

    private static final int MSG_STATE_CHANGED = 1;
    private static final int MSG_HOTWORD_DETECTED = 2;
    private static final int MSG_DETECTION_STOPPED = 3;

    private final String mText;
    private final String mLocale;
    /**
     * The metadata of the Keyphrase, derived from the enrollment application.
     * This may be null if this keyphrase isn't supported by the enrollment application.
     */
    private final KeyphraseMetadata mKeyphraseMetadata;
    private final KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;
    private final IVoiceInteractionService mVoiceInteractionService;
    private final IVoiceInteractionManagerService mModelManagementService;
    private final SoundTriggerListener mInternalCallback;
    private final Callback mExternalCallback;
    private final Object mLock = new Object();
    private final Handler mHandler;

    /**
     * Indicates if there is a sound model enrolled for the keyphrase,
     * derived from the model management service (IVoiceInteractionManagerService).
     */
    private boolean mIsEnrolledForDetection;
    private int mAvailability = STATE_NOT_READY;

    /**
     * Callbacks for always-on hotword detection.
     */
    public interface Callback {
        /**
         * Called when the hotword availability changes.
         * This indicates a change in the availability of recognition for the given keyphrase.
         * It's called at least once with the initial availability.<p/>
         *
         * Availability implies whether the hardware on this system is capable of listening for
         * the given keyphrase or not. <p/>
         * If the return code is one of {@link #STATE_HARDWARE_UNAVAILABLE} or
         * {@link #STATE_KEYPHRASE_UNSUPPORTED},
         * detection is not possible and no further interaction should be
         * performed with this detector. <br/>
         * If it is {@link #STATE_KEYPHRASE_UNENROLLED} the caller may choose to begin
         * an enrollment flow for the keyphrase. <br/>
         * and for {@link #STATE_KEYPHRASE_ENROLLED} a recognition can be started as desired. <p/>
         *
         * If the return code is {@link #STATE_INVALID}, this detector is stale.
         * A new detector should be obtained for use in the future.
         */
        void onAvailabilityChanged(int status);
        /**
         * Called when the keyphrase is spoken.
         *
         * @param data Optional trigger audio data, if it was requested during
         *        {@link AlwaysOnHotwordDetector#startRecognition(int)}.
         */
        void onDetected(byte[] data);
        /**
         * Called when the detection for the associated keyphrase stops.
         */
        void onDetectionStopped();
    }

    /**
     * @param text The keyphrase text to get the detector for.
     * @param locale The java locale for the detector.
     * @param callback A non-null Callback for receiving the recognition events.
     * @param voiceInteractionService The current voice interaction service.
     * @param modelManagementService A service that allows management of sound models.
     *
     * @hide
     */
    public AlwaysOnHotwordDetector(String text, String locale, Callback callback,
            KeyphraseEnrollmentInfo keyphraseEnrollmentInfo,
            IVoiceInteractionService voiceInteractionService,
            IVoiceInteractionManagerService modelManagementService) {
        mText = text;
        mLocale = locale;
        mKeyphraseEnrollmentInfo = keyphraseEnrollmentInfo;
        mKeyphraseMetadata = mKeyphraseEnrollmentInfo.getKeyphraseMetadata(text, locale);
        mExternalCallback = callback;
        mHandler = new MyHandler();
        mInternalCallback = new SoundTriggerListener(mHandler);
        mVoiceInteractionService = voiceInteractionService;
        mModelManagementService = modelManagementService;
        new RefreshAvailabiltyTask().execute();
    }

    /**
     * Gets the recognition modes supported by the associated keyphrase.
     *
     * @throws UnsupportedOperationException if the keyphrase itself isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public int getSupportedRecognitionModes() {
        synchronized (mLock) {
            return getSupportedRecognitionModesLocked();
        }
    }

    private int getSupportedRecognitionModesLocked() {
        // This method only makes sense if we can actually support a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED
                && mAvailability != STATE_KEYPHRASE_UNENROLLED) {
            throw new UnsupportedOperationException(
                    "Getting supported recognition modes for the keyphrase is not supported");
        }

        return mKeyphraseMetadata.recognitionModeFlags;
    }

    /**
     * Starts recognition for the associated keyphrase.
     *
     * @param recognitionFlags The flags to control the recognition properties.
     *        The allowed flags are {@link #RECOGNITION_FLAG_NONE} and
     *        {@link #RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO}.
     * @return {@link #STATUS_OK} if the call succeeds, an error code otherwise.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public int startRecognition(int recognitionFlags) {
        synchronized (mLock) {
            return startRecognitionLocked(recognitionFlags);
        }
    }

    private int startRecognitionLocked(int recognitionFlags) {
        // This method only makes sense if we can start a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
            throw new UnsupportedOperationException(
                    "Recognition for the given keyphrase is not supported");
        }

        KeyphraseRecognitionExtra[] recognitionExtra = new KeyphraseRecognitionExtra[1];
        // TODO: Do we need to do something about the confidence level here?
        recognitionExtra[0] = new KeyphraseRecognitionExtra(mKeyphraseMetadata.id,
                mKeyphraseMetadata.recognitionModeFlags, new ConfidenceLevel[0]);
        boolean captureTriggerAudio =
                (recognitionFlags & RECOGNITION_FLAG_CAPTURE_TRIGGER_AUDIO) != 0;
        int code = STATUS_ERROR;
        try {
            code = mModelManagementService.startRecognition(mVoiceInteractionService,
                    mKeyphraseMetadata.id, mInternalCallback,
                    new RecognitionConfig(
                            captureTriggerAudio, recognitionExtra, null /* additional data */));
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in startRecognition!");
        }
        if (code != STATUS_OK) {
            Slog.w(TAG, "startRecognition() failed with error code " + code);
        }
        return code;
    }

    /**
     * Stops recognition for the associated keyphrase.
     *
     * @return {@link #STATUS_OK} if the call succeeds, an error code otherwise.
     * @throws UnsupportedOperationException if the recognition isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public int stopRecognition() {
        synchronized (mLock) {
            return stopRecognitionLocked();
        }
    }

    private int stopRecognitionLocked() {
        // This method only makes sense if we can start a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED) {
            throw new UnsupportedOperationException(
                    "Recognition for the given keyphrase is not supported");
        }

        int code = STATUS_ERROR;
        try {
            code = mModelManagementService.stopRecognition(
                    mVoiceInteractionService, mKeyphraseMetadata.id, mInternalCallback);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in stopRecognition!");
        }

        if (code != STATUS_OK) {
            Slog.w(TAG, "stopRecognition() failed with error code " + code);
        }
        return code;
    }

    /**
     * Gets an intent to manage the associated keyphrase.
     *
     * @param action The manage action that needs to be performed.
     *        One of {@link #MANAGE_ACTION_ENROLL}, {@link #MANAGE_ACTION_RE_ENROLL} or
     *        {@link #MANAGE_ACTION_UN_ENROLL}.
     * @return An {@link Intent} to manage the given keyphrase.
     * @throws UnsupportedOperationException if managing they keyphrase isn't supported.
     *         Callers should only call this method after a supported state callback on
     *         {@link Callback#onAvailabilityChanged(int)} to avoid this exception.
     */
    public Intent getManageIntent(int action) {
        // This method only makes sense if we can actually support a recognition.
        if (mAvailability != STATE_KEYPHRASE_ENROLLED
                && mAvailability != STATE_KEYPHRASE_UNENROLLED) {
            throw new UnsupportedOperationException(
                    "Managing the given keyphrase is not supported");
        }
        if (action != MANAGE_ACTION_ENROLL
                && action != MANAGE_ACTION_RE_ENROLL
                && action != MANAGE_ACTION_UN_ENROLL) {
            throw new IllegalArgumentException("Invalid action specified " + action);
        }

        return mKeyphraseEnrollmentInfo.getManageKeyphraseIntent(action, mText, mLocale);
    }

    /**
     * Invalidates this hotword detector so that any future calls to this result
     * in an IllegalStateException.
     *
     * @hide
     */
    void invalidate() {
        synchronized (mLock) {
            mAvailability = STATE_INVALID;
            notifyStateChangedLocked();
        }
    }

    /**
     * Reloads the sound models from the service.
     *
     * @hide
     */
    void onSoundModelsChanged() {
        synchronized (mLock) {
            // TODO: This should stop the recognition if it was using an enrolled sound model
            // that's no longer available.
            if (mAvailability == STATE_INVALID
                    || mAvailability == STATE_HARDWARE_UNAVAILABLE
                    || mAvailability == STATE_KEYPHRASE_UNSUPPORTED) {
                Slog.w(TAG, "Received onSoundModelsChanged for an unsupported keyphrase/config");
                return;
            }

            // Execute a refresh availability task - which should then notify of a change.
            new RefreshAvailabiltyTask().execute();
        }
    }

    private void notifyStateChangedLocked() {
        Message message = Message.obtain(mHandler, MSG_STATE_CHANGED);
        message.arg1 = mAvailability;
        message.sendToTarget();
    }

    /** @hide */
    static final class SoundTriggerListener extends IRecognitionStatusCallback.Stub {
        private final Handler mHandler;

        public SoundTriggerListener(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onDetected(RecognitionEvent recognitionEvent) {
            Slog.i(TAG, "onDetected");
            Message message = Message.obtain(mHandler, MSG_HOTWORD_DETECTED);
            message.obj = recognitionEvent.data;
            message.sendToTarget();
        }

        @Override
        public void onDetectionStopped() {
            Slog.i(TAG, "onDetectionStopped");
            mHandler.sendEmptyMessage(MSG_DETECTION_STOPPED);
        }
    }

    class MyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_STATE_CHANGED:
                    mExternalCallback.onAvailabilityChanged(msg.arg1);
                    break;
                case MSG_HOTWORD_DETECTED:
                    mExternalCallback.onDetected((byte[]) msg.obj);
                    break;
                case MSG_DETECTION_STOPPED:
                    mExternalCallback.onDetectionStopped();
                default:
                    super.handleMessage(msg);
            }
        }
    }

    class RefreshAvailabiltyTask extends AsyncTask<Void, Void, Void> {

        @Override
        public Void doInBackground(Void... params) {
            int availability = internalGetInitialAvailability();
            boolean enrolled = false;
            // Fetch the sound model if the availability is one of the supported ones.
            if (availability == STATE_NOT_READY
                    || availability == STATE_KEYPHRASE_UNENROLLED
                    || availability == STATE_KEYPHRASE_ENROLLED) {
                enrolled = internalGetIsEnrolled(mKeyphraseMetadata.id);
                if (!enrolled) {
                    availability = STATE_KEYPHRASE_UNENROLLED;
                } else {
                    availability = STATE_KEYPHRASE_ENROLLED;
                }
            }

            synchronized (mLock) {
                if (DBG) {
                    Slog.d(TAG, "Hotword availability changed from " + mAvailability
                            + " -> " + availability);
                }
                mIsEnrolledForDetection = enrolled;
                mAvailability = availability;
                notifyStateChangedLocked();
            }
            return null;
        }

        /**
         * @return The initial availability without checking the enrollment status.
         */
        private int internalGetInitialAvailability() {
            synchronized (mLock) {
                // This detector has already been invalidated.
                if (mAvailability == STATE_INVALID) {
                    return STATE_INVALID;
                }
            }

            ModuleProperties dspModuleProperties = null;
            try {
                dspModuleProperties =
                        mModelManagementService.getDspModuleProperties(mVoiceInteractionService);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in getDspProperties!");
            }
            // No DSP available
            if (dspModuleProperties == null) {
                return STATE_HARDWARE_UNAVAILABLE;
            }
            // No enrollment application supports this keyphrase/locale
            if (mKeyphraseMetadata == null) {
                return STATE_KEYPHRASE_UNSUPPORTED;
            }
            return STATE_NOT_READY;
        }

        /**
         * @return The corresponding {@link KeyphraseSoundModel} or null if none is found.
         */
        private boolean internalGetIsEnrolled(int keyphraseId) {
            try {
                return mModelManagementService.isEnrolledForKeyphrase(
                        mVoiceInteractionService, keyphraseId);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException in listRegisteredKeyphraseSoundModels!");
            }
            return false;
        }
    }
}
