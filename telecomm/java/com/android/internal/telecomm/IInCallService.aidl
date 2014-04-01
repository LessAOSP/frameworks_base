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

package com.android.internal.telecomm;

import android.telecomm.CallAudioState;
import android.telecomm.CallInfo;

import com.android.internal.telecomm.IInCallAdapter;

/**
 * Internal remote interface for in-call services.
 *
 * @see android.telecomm.InCallService
 *
 * {@hide}
 */
oneway interface IInCallService {
    void setInCallAdapter(in IInCallAdapter inCallAdapter);

    void addCall(in CallInfo callInfo);

    void setActive(String callId);

    void setDisconnected(String callId, int disconnectCause);

    void setDialing(in String callId);

    void setOnHold(String callId);

    void onAudioStateChanged(in CallAudioState audioState);

    void setRinging(String callId);

    void setPostDial(String callId, String remaining);

    void setPostDialWait(String callId, String remaining);
}
