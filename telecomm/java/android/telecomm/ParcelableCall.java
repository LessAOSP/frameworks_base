/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telecomm;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.telephony.DisconnectCause;

import com.android.internal.telecomm.ICallVideoProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Information about a call that is used between InCallService and Telecomm.
 * @hide
 */
public final class ParcelableCall implements Parcelable {
    private final String mId;
    private final CallState mState;
    private final int mDisconnectCauseCode;
    private final String mDisconnectCauseMsg;
    private final List<String> mCannedSmsResponses;
    private final int mCapabilities;
    private final long mConnectTimeMillis;
    private final Uri mHandle;
    private final int mHandlePresentation;
    private final String mCallerDisplayName;
    private final int mCallerDisplayNamePresentation;
    private final GatewayInfo mGatewayInfo;
    private final PhoneAccountHandle mAccountHandle;
    private final ICallVideoProvider mCallVideoProvider;
    private RemoteCallVideoProvider mRemoteCallVideoProvider;
    private final String mParentCallId;
    private final List<String> mChildCallIds;
    private final StatusHints mStatusHints;
    private final int mVideoState;

    /** @hide */
    public ParcelableCall(
            String id,
            CallState state,
            int disconnectCauseCode,
            String disconnectCauseMsg,
            List<String> cannedSmsResponses,
            int capabilities,
            long connectTimeMillis,
            Uri handle,
            int handlePresentation,
            String callerDisplayName,
            int callerDisplayNamePresentation,
            GatewayInfo gatewayInfo,
            PhoneAccountHandle accountHandle,
            ICallVideoProvider callVideoProvider,
            String parentCallId,
            List<String> childCallIds,
            StatusHints statusHints,
            int videoState) {
        mId = id;
        mState = state;
        mDisconnectCauseCode = disconnectCauseCode;
        mDisconnectCauseMsg = disconnectCauseMsg;
        mCannedSmsResponses = cannedSmsResponses;
        mCapabilities = capabilities;
        mConnectTimeMillis = connectTimeMillis;
        mHandle = handle;
        mHandlePresentation = handlePresentation;
        mCallerDisplayName = callerDisplayName;
        mCallerDisplayNamePresentation = callerDisplayNamePresentation;
        mGatewayInfo = gatewayInfo;
        mAccountHandle = accountHandle;
        mCallVideoProvider = callVideoProvider;
        mParentCallId = parentCallId;
        mChildCallIds = childCallIds;
        mStatusHints = statusHints;
        mVideoState = videoState;
    }

    /** The unique ID of the call. */
    public String getId() {
        return mId;
    }

    /** The current state of the call. */
    public CallState getState() {
        return mState;
    }

    /**
     * Reason for disconnection, values are defined in {@link DisconnectCause}. Valid when call
     * state is {@link CallState#DISCONNECTED}.
     */
    public int getDisconnectCauseCode() {
        return mDisconnectCauseCode;
    }

    /**
     * Further optional textual information about the reason for disconnection. Valid when call
     * state is {@link CallState#DISCONNECTED}.
     */
    public String getDisconnectCauseMsg() {
        return mDisconnectCauseMsg;
    }

    /**
     * The set of possible text message responses when this call is incoming.
     */
    public List<String> getCannedSmsResponses() {
        return mCannedSmsResponses;
    }

    // Bit mask of actions a call supports, values are defined in {@link CallCapabilities}.
    public int getCapabilities() {
        return mCapabilities;
    }

    /** The time that the call switched to the active state. */
    public long getConnectTimeMillis() {
        return mConnectTimeMillis;
    }

    /** The endpoint to which the call is connected. */
    public Uri getHandle() {
        return mHandle;
    }

    /** The {@link CallPropertyPresentation} which controls how the handle is shown. */
    public int getHandlePresentation() {
        return mHandlePresentation;
    }

    /** The endpoint to which the call is connected. */
    public String getCallerDisplayName() {
        return mCallerDisplayName;
    }

    /** The {@link CallPropertyPresentation} which controls how the caller display name is shown. */
    public int getCallerDisplayNamePresentation() {
        return mCallerDisplayNamePresentation;
    }

    /** Gateway information for the call. */
    public GatewayInfo getGatewayInfo() {
        return mGatewayInfo;
    }

    /** PhoneAccountHandle information for the call. */
    public PhoneAccountHandle getAccountHandle() {
        return mAccountHandle;
    }

    /**
     * Returns an object for remotely communicating through the call video provider's binder.
     * @return The call video provider.
     */
    public RemoteCallVideoProvider getCallVideoProvider() throws RemoteException {
        if (mRemoteCallVideoProvider == null && mCallVideoProvider != null) {
            try {
                mRemoteCallVideoProvider = new RemoteCallVideoProvider(mCallVideoProvider);
            } catch (RemoteException ignored) {
                // Ignore RemoteException.
            }
        }

        return mRemoteCallVideoProvider;
    }

    /**
     * The conference call to which this call is conferenced. Null if not conferenced.
     * @hide
     */
    public String getParentCallId() {
        return mParentCallId;
    }

    /**
     * The child call-IDs if this call is a conference call. Returns an empty list if this is not
     * a conference call or if the conference call contains no children.
     * @hide
     */
    public List<String> getChildCallIds() {
        return mChildCallIds;
    }

    /**
     * The status label and icon.
     *
     * @return Status hints.
     */
    public StatusHints getStatusHints() {
        return mStatusHints;
    }

    /**
     * The video state.
     * @return The video state of the call.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /** Responsible for creating ParcelableCall objects for deserialized Parcels. */
    public static final Parcelable.Creator<ParcelableCall> CREATOR =
            new Parcelable.Creator<ParcelableCall> () {
        @Override
        public ParcelableCall createFromParcel(Parcel source) {
            ClassLoader classLoader = ParcelableCall.class.getClassLoader();
            String id = source.readString();
            CallState state = CallState.valueOf(source.readString());
            int disconnectCauseCode = source.readInt();
            String disconnectCauseMsg = source.readString();
            List<String> cannedSmsResponses = new ArrayList<>();
            source.readList(cannedSmsResponses, classLoader);
            int capabilities = source.readInt();
            long connectTimeMillis = source.readLong();
            Uri handle = source.readParcelable(classLoader);
            int handlePresentation = source.readInt();
            String callerDisplayName = source.readString();
            int callerDisplayNamePresentation = source.readInt();
            GatewayInfo gatewayInfo = source.readParcelable(classLoader);
            PhoneAccountHandle accountHandle = source.readParcelable(classLoader);
            ICallVideoProvider callVideoProvider =
                    ICallVideoProvider.Stub.asInterface(source.readStrongBinder());
            String parentCallId = source.readString();
            List<String> childCallIds = new ArrayList<>();
            source.readList(childCallIds, classLoader);
            StatusHints statusHints = source.readParcelable(classLoader);
            int videoState = source.readInt();
            return new ParcelableCall(id, state, disconnectCauseCode, disconnectCauseMsg,
                    cannedSmsResponses, capabilities, connectTimeMillis, handle, handlePresentation,
                    callerDisplayName, callerDisplayNamePresentation, gatewayInfo,
                    accountHandle, callVideoProvider, parentCallId, childCallIds, statusHints,
                    videoState);
        }

        @Override
        public ParcelableCall[] newArray(int size) {
            return new ParcelableCall[size];
        }
    };

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes ParcelableCall object into a Parcel. */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeString(mId);
        destination.writeString(mState.name());
        destination.writeInt(mDisconnectCauseCode);
        destination.writeString(mDisconnectCauseMsg);
        destination.writeList(mCannedSmsResponses);
        destination.writeInt(mCapabilities);
        destination.writeLong(mConnectTimeMillis);
        destination.writeParcelable(mHandle, 0);
        destination.writeInt(mHandlePresentation);
        destination.writeString(mCallerDisplayName);
        destination.writeInt(mCallerDisplayNamePresentation);
        destination.writeParcelable(mGatewayInfo, 0);
        destination.writeParcelable(mAccountHandle, 0);
        destination.writeStrongBinder(
                mCallVideoProvider != null ? mCallVideoProvider.asBinder() : null);
        destination.writeString(mParentCallId);
        destination.writeList(mChildCallIds);
        destination.writeParcelable(mStatusHints, 0);
        destination.writeInt(mVideoState);
    }

    @Override
    public String toString() {
        return String.format("[%s, parent:%s, children:%s]", mId, mParentCallId, mChildCallIds);
    }
}
