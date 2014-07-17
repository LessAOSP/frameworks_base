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

import android.net.Uri;
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an ongoing phone call that the in-call app should present to the user.
 */
public final class Call {
    /**
     * The state of a {@code Call} when newly created.
     */
    public static final int STATE_NEW = 0;

    /**
     * The state of an outgoing {@code Call} when dialing the remote number, but not yet connected.
     */
    public static final int STATE_DIALING = 1;

    /**
     * The state of an incoming {@code Call} when ringing locally, but not yet connected.
     */
    public static final int STATE_RINGING = 2;

    /**
     * The state of a {@code Call} when in a holding state.
     */
    public static final int STATE_HOLDING = 3;

    /**
     * The state of a {@code Call} when actively supporting conversation.
     */
    public static final int STATE_ACTIVE = 4;

    /**
     * The state of a {@code Call} when no further voice or other communication is being
     * transmitted, the remote side has been or will inevitably be informed that the {@code Call}
     * is no longer active, and the local data transport has or inevitably will release resources
     * associated with this {@code Call}.
     */
    public static final int STATE_DISCONNECTED = 7;

    /**
     * The state of an outgoing {@code Call}, but waiting for user input before proceeding.
     */
    public static final int STATE_PRE_DIAL_WAIT = 8;

    public static class Details {
        private final Uri mHandle;
        private final int mHandlePresentation;
        private final String mCallerDisplayName;
        private final int mCallerDisplayNamePresentation;
        private final PhoneAccount mAccount;
        private final int mCapabilities;
        private final int mDisconnectCauseCode;
        private final String mDisconnectCauseMsg;
        private final long mConnectTimeMillis;
        private final GatewayInfo mGatewayInfo;
        private final int mVideoState;
        private final StatusHints mStatusHints;

        /**
         * @return The handle (e.g., phone number) to which the {@code Call} is currently
         * connected.
         */
        public Uri getHandle() {
            return mHandle;
        }

        /**
         * @return The presentation requirements for the handle. See
         * {@link android.telecomm.CallPropertyPresentation} for valid values.
         */
        public int getHandlePresentation() {
            return mHandlePresentation;
        }

        /**
         * @return The display name for the caller.
         */
        public String getCallerDisplayName() {
            return mCallerDisplayName;
        }

        /**
         * @return The presentation requirements for the caller display name. See
         * {@link android.telecomm.CallPropertyPresentation} for valid values.
         */
        public int getCallerDisplayNamePresentation() {
            return mCallerDisplayNamePresentation;
        }

        /**
         * @return The {@code PhoneAccount} whereby the {@code Call} is currently being routed.
         */
        public PhoneAccount getAccount() {
            return mAccount;
        }

        /**
         * @return A bitmask of the capabilities of the {@code Call}, as defined in
         *         {@link CallCapabilities}.
         */
        public int getCapabilities() {
            return mCapabilities;
        }

        /**
         * @return For a {@link #STATE_DISCONNECTED} {@code Call}, the disconnect cause expressed
         * as a code chosen from among those declared in {@link DisconnectCause}.
         */
        public int getDisconnectCauseCode() {
            return mDisconnectCauseCode;
        }

        /**
         * @return For a {@link #STATE_DISCONNECTED} {@code Call}, an optional reason for
         * disconnection expressed as a free text message.
         */
        public String getDisconnectCauseMsg() {
            return mDisconnectCauseMsg;
        }

        /**
         * @return The time the {@code Call} has been connected. This information is updated
         * periodically, but user interfaces should not rely on this to display any "call time
         * clock".
         */
        public long getConnectTimeMillis() {
            return mConnectTimeMillis;
        }

        /**
         * @return Information about any calling gateway the {@code Call} may be using.
         */
        public GatewayInfo getGatewayInfo() {
            return mGatewayInfo;
        }

        /**
         * @return Returns the video state of the {@code Call}.
         */
        public int getVideoState() {
            return mVideoState;
        }

        /*
         * @return The current {@link android.telecomm.StatusHints}, or null if none has been set.
         */
        public StatusHints getStatusHints() {
            return mStatusHints;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Details) {
                Details d = (Details) o;
                return
                        Objects.equals(mHandle, d.mHandle) &&
                        Objects.equals(mHandlePresentation, d.mHandlePresentation) &&
                        Objects.equals(mCallerDisplayName, d.mCallerDisplayName) &&
                        Objects.equals(mCallerDisplayNamePresentation,
                                d.mCallerDisplayNamePresentation) &&
                        Objects.equals(mAccount, d.mAccount) &&
                        Objects.equals(mCapabilities, d.mCapabilities) &&
                        Objects.equals(mDisconnectCauseCode, d.mDisconnectCauseCode) &&
                        Objects.equals(mDisconnectCauseMsg, d.mDisconnectCauseMsg) &&
                        Objects.equals(mConnectTimeMillis, d.mConnectTimeMillis) &&
                        Objects.equals(mGatewayInfo, d.mGatewayInfo) &&
                        Objects.equals(mVideoState, d.mVideoState) &&
                        Objects.equals(mStatusHints, d.mStatusHints);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return
                    Objects.hashCode(mHandle) +
                    Objects.hashCode(mHandlePresentation) +
                    Objects.hashCode(mCallerDisplayName) +
                    Objects.hashCode(mCallerDisplayNamePresentation) +
                    Objects.hashCode(mAccount) +
                    Objects.hashCode(mCapabilities) +
                    Objects.hashCode(mDisconnectCauseCode) +
                    Objects.hashCode(mDisconnectCauseMsg) +
                    Objects.hashCode(mConnectTimeMillis) +
                    Objects.hashCode(mGatewayInfo) +
                    Objects.hashCode(mVideoState) +
                    Objects.hashCode(mStatusHints);
        }

        /** {@hide} */
        public Details(
                Uri handle,
                int handlePresentation,
                String callerDisplayName,
                int callerDisplayNamePresentation,
                PhoneAccount account,
                int capabilities,
                int disconnectCauseCode,
                String disconnectCauseMsg,
                long connectTimeMillis,
                GatewayInfo gatewayInfo,
                int videoState,
                StatusHints statusHints) {
            mHandle = handle;
            mHandlePresentation = handlePresentation;
            mCallerDisplayName = callerDisplayName;
            mCallerDisplayNamePresentation = callerDisplayNamePresentation;
            mAccount = account;
            mCapabilities = capabilities;
            mDisconnectCauseCode = disconnectCauseCode;
            mDisconnectCauseMsg = disconnectCauseMsg;
            mConnectTimeMillis = connectTimeMillis;
            mGatewayInfo = gatewayInfo;
            mVideoState = videoState;
            mStatusHints = statusHints;
        }
    }

    public static abstract class Listener {
        /**
         * Invoked when the state of this {@code Call} has changed. See {@link #getState()}.
         *
         * TODO(ihab): Provide previous state also?
         *
         * @param call The {@code Call} invoking this method.
         * @param state The new state of the {@code Call}.
         */
        public void onStateChanged(Call call, int state) {}

        /**
         * Invoked when the parent of this {@code Call} has changed. See {@link #getParent()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param parent The new parent of the {@code Call}.
         */
        public void onParentChanged(Call call, Call parent) {}

        /**
         * Invoked when the children of this {@code Call} have changed. See {@link #getChildren()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param children The new children of the {@code Call}.
         */
        public void onChildrenChanged(Call call, List<Call> children) {}

        /**
         * Invoked when the details of this {@code Call} have changed. See {@link #getDetails()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param details A {@code Details} object describing the {@code Call}.
         */
        public void onDetailsChanged(Call call, Details details) {}

        /**
         * Invoked when the text messages that can be used as responses to the incoming
         * {@code Call} are loaded from the relevant database.
         * See {@link #getCannedTextResponses()}.
         *
         * @param call The {@code Call} invoking this method.
         * @param cannedTextResponses The text messages useable as responses.
         */
        public void onCannedTextResponsesLoaded(Call call, List<String> cannedTextResponses) {}

        /**
         * Invoked when the outgoing {@code Call} has finished dialing but is sending DTMF signals
         * that were embedded into the outgoing number.
         *
         * @param call The {@code Call} invoking this method.
         * @param remainingPostDialSequence The post-dial characters that remain to be sent.
         */
        public void onPostDial(Call call, String remainingPostDialSequence) {}

        /**
         * Invoked when the post-dial sequence in the outgoing {@code Call} has reached a pause
         * character. This causes the post-dial signals to stop pending user confirmation. An
         * implementation should present this choice to the user and invoke
         * {@link #postDialContinue(boolean)} when the user makes the choice.
         *
         * @param call The {@code Call} invoking this method.
         * @param remainingPostDialSequence The post-dial characters that remain to be sent.
         */
        public void onPostDialWait(Call call, String remainingPostDialSequence) {}

        /**
         * Invoked when the {@code RemoteCallVideoProvider} of the {@code Call} has changed.
         *
         * @param call The {@code Call} invoking this method.
         * @param callVideoProvider The {@code RemoteCallVideoProvider} associated with the
         * {@code Call}.
         */

        public void onCallVideoProviderChanged(Call call,
                RemoteCallVideoProvider callVideoProvider) {}

        /**
         * Invoked when the {@code Call} is destroyed. Clients should refrain from cleaning
         * up their UI for the {@code Call} in response to state transitions. Specifically,
         * clients should not assume that a {@link #onStateChanged(Call, int)} with a state of
         * {@link #STATE_DISCONNECTED} is the final notification the {@code Call} will send. Rather,
         * clients should wait for this method to be invoked.
         *
         * @param call The {@code Call} being destroyed.
         */
        public void onCallDestroyed(Call call) {}
    }

    private final Phone mPhone;
    private final String mTelecommCallId;
    private final InCallAdapter mInCallAdapter;
    private Call mParent = null;
    private int mState;
    private final List<Call> mChildren = new ArrayList<>();
    private final List<Call> mUnmodifiableChildren = Collections.unmodifiableList(mChildren);
    private List<String> mCannedTextResponses = null;
    private String mRemainingPostDialSequence;
    private RemoteCallVideoProvider mCallVideoProvider;
    private Details mDetails;
    private final List<Listener> mListeners = new ArrayList<>();

    /**
     * Obtains the post-dial sequence remaining to be emitted by this {@code Call}, if any.
     *
     * @return The remaining post-dial sequence, or {@code null} if there is no post-dial sequence
     * remaining or this {@code Call} is not in a post-dial state.
     */
    public String getRemainingPostDialSequence() {
        return mRemainingPostDialSequence;
    }

    /**
     * Instructs this {@link #STATE_RINGING} {@code Call} to answer.
     * @param videoState The video state in which to answer the call.
     */
    public void answer(int videoState) {
        mInCallAdapter.answerCall(mTelecommCallId, videoState);
    }

    /**
     * Instructs this {@link #STATE_RINGING} {@code Call} to reject.
     *
     * @param rejectWithMessage Whether to reject with a text message.
     * @param textMessage An optional text message with which to respond.
     */
    public void reject(boolean rejectWithMessage, String textMessage) {
        mInCallAdapter.rejectCall(mTelecommCallId, rejectWithMessage, textMessage);
    }

    /**
     * Instructs this {@code Call} to disconnect.
     */
    public void disconnect() {
        mInCallAdapter.disconnectCall(mTelecommCallId);
    }

    /**
     * Instructs this {@code Call} to go on hold.
     */
    public void hold() {
        mInCallAdapter.holdCall(mTelecommCallId);
    }

    /**
     * Instructs this {@link #STATE_HOLDING} call to release from hold.
     */
    public void unhold() {
        mInCallAdapter.unholdCall(mTelecommCallId);
    }

    /**
     * Instructs this {@code Call} to play a dual-tone multi-frequency signaling (DTMF) tone.
     *
     * Any other currently playing DTMF tone in the specified call is immediately stopped.
     *
     * @param digit A character representing the DTMF digit for which to play the tone. This
     *         value must be one of {@code '0'} through {@code '9'}, {@code '*'} or {@code '#'}.
     */
    public void playDtmfTone(char digit) {
        mInCallAdapter.playDtmfTone(mTelecommCallId, digit);
    }

    /**
     * Instructs this {@code Call} to stop any dual-tone multi-frequency signaling (DTMF) tone
     * currently playing.
     *
     * DTMF tones are played by calling {@link #playDtmfTone(char)}. If no DTMF tone is
     * currently playing, this method will do nothing.
     */
    public void stopDtmfTone() {
        mInCallAdapter.stopDtmfTone(mTelecommCallId);
    }

    /**
     * Instructs this {@code Call} to continue playing a post-dial DTMF string.
     *
     * A post-dial DTMF string is a string of digits entered after a phone number, when dialed,
     * that are immediately sent as DTMF tones to the recipient as soon as the connection is made.
     * While these tones are playing, this {@code Call} will notify listeners via
     * {@link Listener#onPostDial(Call, String)}.
     *
     * If the DTMF string contains a {@link TelecommConstants#DTMF_CHARACTER_PAUSE} symbol, this
     * {@code Call} will temporarily pause playing the tones for a pre-defined period of time.
     *
     * If the DTMF string contains a {@link TelecommConstants#DTMF_CHARACTER_WAIT} symbol, this
     * {@code Call} will pause playing the tones and notify listeners via
     * {@link Listener#onPostDialWait(Call, String)}. At this point, the in-call app
     * should display to the user an indication of this state and an affordance to continue
     * the postdial sequence. When the user decides to continue the postdial sequence, the in-call
     * app should invoke the {@link #postDialContinue(boolean)} method.
     *
     * @param proceed Whether or not to continue with the post-dial sequence.
     */
    public void postDialContinue(boolean proceed) {
        mInCallAdapter.postDialContinue(mTelecommCallId, proceed);
    }

    /**
     * Notifies this {@code Call} that the phone account user interface element was touched.
     *
     * TODO(ihab): Figure out if and how we can generalize this
     */
    public void phoneAccountClicked() {
        mInCallAdapter.phoneAccountClicked(mTelecommCallId);
    }

    /**
     * Notifies this {@code Call} the a {@code PhoneAccount} has been selected and to proceed
     * with placing an outgoing call.
     */
    public void phoneAccountSelected(PhoneAccount account) {
        mInCallAdapter.phoneAccountSelected(mTelecommCallId, account);

    }

    /**
     * Instructs this {@code Call} to enter a conference.
     */
    public void conference() {
        mInCallAdapter.conference(mTelecommCallId);
    }

    /**
     * Instructs this {@code Call} to split from any conference call with which it may be
     * connected.
     */
    public void splitFromConference() {
        mInCallAdapter.splitFromConference(mTelecommCallId);
    }

    /**
     * Instructs this {@code Call} to swap itself with an existing background call, if one
     * such call exists.
     */
    public void swapWithBackgroundCall() {
        mInCallAdapter.swapWithBackgroundCall(mTelecommCallId);
    }

    /**
     * Obtains the parent of this {@code Call} in a conference, if any.
     *
     * @return The parent {@code Call}, or {@code null} if this {@code Call} is not a
     * child of any conference {@code Call}s.
     */
    public Call getParent() {
        return mParent;
    }

    /**
     * Obtains the children of this conference {@code Call}, if any.
     *
     * @return The children of this {@code Call} if this {@code Call} is a conference, or an empty
     * {@code List} otherwise.
     */
    public List<Call> getChildren() {
        return mUnmodifiableChildren;
    }

    /**
     * Obtains the state of this {@code Call}.
     *
     * @return A state value, chosen from the {@code STATE_*} constants.
     */
    public int getState() {
        return mState;
    }

    /**
     * Obtains a list of canned, pre-configured message responses to present to the user as
     * ways of rejecting this {@code Call} using via a text message.
     *
     * @see #reject(boolean, String)
     *
     * @return A list of canned text message responses.
     */
    public List<String> getCannedTextResponses() {
        return mCannedTextResponses;
    }

    /**
     * Obtains an object that can be used to display video from this {@code Call}.
     *
     * @return An {@code ICallVideoProvider}.
     */
    public RemoteCallVideoProvider getCallVideoProvider() {
        return mCallVideoProvider;
    }

    /**
     * Obtains an object containing call details.
     *
     * @return A {@link Details} object. Depending on the state of the {@code Call}, the
     * result may be {@code null}.
     */
    public Details getDetails() {
        return mDetails;
    }

    /**
     * Adds a listener to this {@code Call}.
     *
     * @param listener A {@code Listener}.
     */
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener from this {@code Call}.
     *
     * @param listener A {@code Listener}.
     */
    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    /** {@hide} */
    Call(Phone phone, String telecommCallId, InCallAdapter inCallAdapter) {
        mPhone = phone;
        mTelecommCallId = telecommCallId;
        mInCallAdapter = inCallAdapter;
        mState = STATE_NEW;
    }

    /** {@hide} */
    final String internalGetCallId() {
        return mTelecommCallId;
    }

    /** {@hide} */
    final void internalUpdate(InCallCall inCallCall) {
        // First, we update the internal state as far as possible before firing any updates.

        Details details = new Details(
                inCallCall.getHandle(),
                inCallCall.getHandlePresentation(),
                inCallCall.getCallerDisplayName(),
                inCallCall.getCallerDisplayNamePresentation(),
                inCallCall.getAccount(),
                inCallCall.getCapabilities(),
                inCallCall.getDisconnectCauseCode(),
                inCallCall.getDisconnectCauseMsg(),
                inCallCall.getConnectTimeMillis(),
                inCallCall.getGatewayInfo(),
                inCallCall.getVideoState(),
                inCallCall.getStatusHints());
        boolean detailsChanged = !Objects.equals(mDetails, details);
        if (detailsChanged) {
            mDetails = details;
        }

        boolean cannedTextResponsesChanged = false;
        if (mCannedTextResponses == null && inCallCall.getCannedSmsResponses() != null
                && !inCallCall.getCannedSmsResponses().isEmpty()) {
            mCannedTextResponses = Collections.unmodifiableList(inCallCall.getCannedSmsResponses());
        }

        boolean callVideoProviderChanged = false;
        try {
            callVideoProviderChanged =
                    !Objects.equals(mCallVideoProvider, inCallCall.getCallVideoProvider());
            if (callVideoProviderChanged) {
                mCallVideoProvider = inCallCall.getCallVideoProvider();
            }
        } catch (RemoteException e) {
        }

        int state = stateFromInCallCallState(inCallCall.getState());
        boolean stateChanged = mState != state;
        if (stateChanged) {
            mState = state;
        }

        if (inCallCall.getParentCallId() != null) {
            mParent = mPhone.internalGetCallByTelecommId(inCallCall.getParentCallId());
        }

        mChildren.clear();
        if (inCallCall.getChildCallIds() != null) {
            for (int i = 0; i < inCallCall.getChildCallIds().size(); i++) {
                mChildren.add(mPhone.internalGetCallByTelecommId(
                        inCallCall.getChildCallIds().get(i)));
            }
        }

        // Now we fire updates, ensuring that any client who listens to any of these notifications
        // gets the most up-to-date state.

        if (stateChanged) {
            fireStateChanged(mState);
        }
        if (detailsChanged) {
            fireDetailsChanged(mDetails);
        }
        if (cannedTextResponsesChanged) {
            fireCannedTextResponsesLoaded(mCannedTextResponses);
        }
        if (callVideoProviderChanged) {
            fireCallVideoProviderChanged(mCallVideoProvider);
        }

        // If we have transitioned to DISCONNECTED, that means we need to notify clients and
        // remove ourselves from the Phone. Note that we do this after completing all state updates
        // so a client can cleanly transition all their UI to the state appropriate for a
        // DISCONNECTED Call while still relying on the existence of that Call in the Phone's list.
        if (mState == STATE_DISCONNECTED) {
            fireCallDestroyed();
            mPhone.internalRemoveCall(this);
        }
    }

    /** {@hide} */
    final void internalSetPostDial(String remaining) {
        mRemainingPostDialSequence = remaining;
        firePostDial(mRemainingPostDialSequence);
    }

    /** {@hide} */
    final void internalSetPostDialWait(String remaining) {
        mRemainingPostDialSequence = remaining;
        firePostDialWait(mRemainingPostDialSequence);
    }

    private void fireStateChanged(int newState) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onStateChanged(this, newState);
        }
    }

    private void fireParentChanged(Call newParent) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onParentChanged(this, newParent);
        }
    }

    private void fireChildrenChanged(List<Call> children) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onChildrenChanged(this, children);
        }
    }

    private void fireDetailsChanged(Details details) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onDetailsChanged(this, details);
        }
    }

    private void fireCannedTextResponsesLoaded(List<String> cannedTextResponses) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onCannedTextResponsesLoaded(this, cannedTextResponses);
        }
    }

    private void fireCallVideoProviderChanged(RemoteCallVideoProvider callVideoProvider) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onCallVideoProviderChanged(this, callVideoProvider);
        }
    }

    private void firePostDial(String remainingPostDialSequence) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onPostDial(this, remainingPostDialSequence);
        }
    }

    private void firePostDialWait(String remainingPostDialSequence) {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onPostDialWait(this, remainingPostDialSequence);
        }
    }

    private void fireCallDestroyed() {
        Listener[] listeners = mListeners.toArray(new Listener[mListeners.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].onCallDestroyed(this);
        }
    }

    private int stateFromInCallCallState(CallState inCallCallState) {
        switch (inCallCallState) {
            case NEW:
                return STATE_NEW;
            case PRE_DIAL_WAIT:
                return STATE_PRE_DIAL_WAIT;
            case DIALING:
                return STATE_DIALING;
            case RINGING:
                return STATE_RINGING;
            case ACTIVE:
                return STATE_ACTIVE;
            case ON_HOLD:
                return STATE_HOLDING;
            case DISCONNECTED:
                return STATE_DISCONNECTED;
            case ABORTED:
                return STATE_DISCONNECTED;
            default:
                Log.wtf(this, "Unrecognized CallState %s", inCallCallState);
                return STATE_NEW;
        }
    }
}
