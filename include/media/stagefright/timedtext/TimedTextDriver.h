/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef TIMED_TEXT_DRIVER_H_
#define TIMED_TEXT_DRIVER_H_

#include <media/stagefright/foundation/ABase.h> // for DISALLOW_* macro
#include <utils/Errors.h> // for status_t
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

class ALooper;
class MediaPlayerBase;
class MediaSource;
class Parcel;
class TimedTextPlayer;
class TimedTextSource;

class TimedTextDriver {
public:
    TimedTextDriver(const wp<MediaPlayerBase> &listener);

    ~TimedTextDriver();

    status_t start();
    status_t pause();
    status_t selectTrack(int32_t index);
    status_t unselectTrack(int32_t index);

    status_t seekToAsync(int64_t timeUs);

    status_t addInBandTextSource(const sp<MediaSource>& source);
    status_t addOutOfBandTextSource(const char *uri, const char *mimeType);
    // Caller owns the file desriptor and caller is responsible for closing it.
    status_t addOutOfBandTextSource(
            int fd, off64_t offset, size_t length, const char *mimeType);

    void getTrackInfo(Parcel *parcel);

private:
    Mutex mLock;

    enum State {
        UNINITIALIZED,
        PLAYING,
        PAUSED,
    };

    sp<ALooper> mLooper;
    sp<TimedTextPlayer> mPlayer;
    wp<MediaPlayerBase> mListener;

    // Variables to be guarded by mLock.
    State mState;
    int32_t mCurrentTrackIndex;
    Vector<sp<TimedTextSource> > mTextSourceVector;
    // -- End of variables to be guarded by mLock

    status_t selectTrack_l(int32_t index);

    DISALLOW_EVIL_CONSTRUCTORS(TimedTextDriver);
};

}  // namespace android

#endif  // TIMED_TEXT_DRIVER_H_
