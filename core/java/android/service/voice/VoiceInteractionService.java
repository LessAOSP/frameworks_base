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

import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.KeyphraseEnrollmentInfo;
import android.hardware.soundtrigger.SoundTriggerHelper;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IVoiceInteractionManagerService;


/**
 * Top-level service of the current global voice interactor, which is providing
 * support for hotwording, the back-end of a {@link android.app.VoiceInteractor}, etc.
 * The current VoiceInteractionService that has been selected by the user is kept
 * always running by the system, to allow it to do things like listen for hotwords
 * in the background to instigate voice interactions.
 *
 * <p>Because this service is always running, it should be kept as lightweight as
 * possible.  Heavy-weight operations (including showing UI) should be implemented
 * in the associated {@link android.service.voice.VoiceInteractionSessionService} when
 * an actual voice interaction is taking place, and that service should run in a
 * separate process from this one.
 */
public class VoiceInteractionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_VOICE_INTERACTION} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.VoiceInteractionService";

    /**
     * Name under which a VoiceInteractionService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#VoiceInteractionService voice-interaction-service}&gt;</code> tag.
     */
    public static final String SERVICE_META_DATA = "android.voice_interaction";

    IVoiceInteractionService mInterface = new IVoiceInteractionService.Stub() {
    };

    IVoiceInteractionManagerService mSystemService;

    private KeyphraseEnrollmentInfo mKeyphraseEnrollmentInfo;
    private SoundTriggerHelper mSoundTriggerHelper;

    public void startSession(Bundle args) {
        try {
            mSystemService.startSession(mInterface, args);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSystemService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
        mKeyphraseEnrollmentInfo = new KeyphraseEnrollmentInfo(getPackageManager());
        mSoundTriggerHelper = new SoundTriggerHelper();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        return null;
    }

    /**
     * @param keyphrase The keyphrase that's being used, for example "Hello Android".
     * @param locale The locale for which the enrollment needs to be performed.
     *        This is a Java locale, for example "en_US".
     * @param callback The callback to notify of detection events.
     * @return An always-on hotword detector for the given keyphrase and locale.
     */
    public final AlwaysOnHotwordDetector getAlwaysOnHotwordDetector(
            String keyphrase, String locale, AlwaysOnHotwordDetector.Callback callback) {
        return new AlwaysOnHotwordDetector(keyphrase, locale, callback,
                mKeyphraseEnrollmentInfo, mSoundTriggerHelper, mInterface, mSystemService);
    }

    /**
     * @return Details of keyphrases available for enrollment.
     * @hide
     */
    @VisibleForTesting
    protected final KeyphraseEnrollmentInfo getKeyphraseEnrollmentInfo() {
        return mKeyphraseEnrollmentInfo;
    }
}
