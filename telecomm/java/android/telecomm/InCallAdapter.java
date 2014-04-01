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

package android.telecomm;

import android.os.RemoteException;

import com.android.internal.telecomm.IInCallAdapter;

/**
 * Receives commands from {@link InCallService} implementations which should be executed by
 * Telecomm. When Telecomm binds to a {@link InCallService}, an instance of this class is given to
 * the in-call service through which it can manipulate live (active, dialing, ringing) calls. When
 * the in-call service is notified of new calls ({@link InCallService#addCall}), it can use the
 * given call IDs to execute commands such as {@link #answerCall} for incoming calls or
 * {@link #disconnectCall} for active calls the user would like to end. Some commands are only
 * appropriate for calls in certain states; please consult each method for such limitations.
 * TODO(santoscordon): Needs more/better comments once the API is finalized.
 * TODO(santoscordon): Specify the adapter will stop functioning when there are no more calls.
 */
public final class InCallAdapter {
    private final IInCallAdapter mAdapter;

    /**
     * {@hide}
     */
    public InCallAdapter(IInCallAdapter adapter) {
        mAdapter = adapter;
    }

    /**
     * Instructs Telecomm to answer the specified call.
     *
     * @param callId The identifier of the call to answer.
     */
    public void answerCall(String callId) {
        try {
            mAdapter.answerCall(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to reject the specified call.
     * TODO(santoscordon): Add reject-with-text-message parameter when that feature
     * is ported over.
     *
     * @param callId The identifier of the call to reject.
     */
    public void rejectCall(String callId) {
        try {
            mAdapter.rejectCall(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to disconnect the specified call.
     *
     * @param callId The identifier of the call to disconnect.
     */
    public void disconnectCall(String callId) {
        try {
            mAdapter.disconnectCall(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to put the specified call on hold.
     *
     * @param callId The identifier of the call to put on hold.
     */
    public void holdCall(String callId) {
        try {
            mAdapter.holdCall(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to release the specified call from hold.
     *
     * @param callId The identifier of the call to release from hold.
     */
    public void unholdCall(String callId) {
        try {
            mAdapter.unholdCall(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Mute the microphone.
     *
     * @param shouldMute True if the microphone should be muted.
     */
    public void mute(boolean shouldMute) {
        try {
            mAdapter.mute(shouldMute);
        } catch (RemoteException e) {
        }
    }

    /**
     * Sets the audio route (speaker, bluetooth, etc...). See {@link CallAudioState}.
     *
     * @param route The audio route to use.
     */
    public void setAudioRoute(int route) {
        try {
            mAdapter.setAudioRoute(route);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to play a dual-tone multi-frequency signaling (DTMF) tone in a call.
     *
     * Any other currently playing DTMF tone in the specified call is immediately stopped.
     *
     * @param callId The unique ID of the call in which the tone will be played.
     * @param digit A character representing the DTMF digit for which to play the tone. This
     *         value must be one of {@code '0'} through {@code '9'}, {@code '*'} or {@code '#'}.
     */
    public void playDtmfTone(String callId, char digit) {
        try {
            mAdapter.playDtmfTone(callId, digit);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to stop any dual-tone multi-frequency signaling (DTMF) tone currently
     * playing.
     *
     * DTMF tones are played by calling {@link #playDtmfTone(String,char)}. If no DTMF tone is
     * currently playing, this method will do nothing.
     *
     * @param callId The unique ID of the call in which any currently playing tone will be stopped.
     */
    public void stopDtmfTone(String callId) {
        try {
            mAdapter.stopDtmfTone(callId);
        } catch (RemoteException e) {
        }
    }

    /**
     * Instructs Telecomm to continue playing a post-dial DTMF string.
     *
     * A post-dial DTMF string is a string of digits entered after a phone number, when dialed,
     * that are immediately sent as DTMF tones to the recipient as soon as the connection is made.
     * While these tones are playing, Telecomm will notify the {@link InCallService} that the call
     * is in the {@link InCallService#setPostDial(String)} state.
     *
     * If the DTMF string contains a {@link #DTMF_CHARACTER_PAUSE} symbol, Telecomm will temporarily
     * pause playing the tones for a pre-defined period of time.
     *
     * If the DTMF string contains a {@link #DTMF_CHARACTER_WAIT} symbol, Telecomm will pause
     * playing the tones and notify the {@link InCallService} that the call is in the
     * {@link InCallService#setPostDialWait(String)} state. When the user decides to continue the
     * postdial sequence, the {@link InCallService} should invoke the
     * {@link #postDialContinue(String)} method.
     *
     * @param callId The unique ID of the call for which postdial string playing should continue.
     */
    public void postDialContinue(String callId) {
        try {
            mAdapter.postDialContinue(callId);
        } catch (RemoteException e) {
        }
    }
}
