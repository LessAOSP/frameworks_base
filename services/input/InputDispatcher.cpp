/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "InputDispatcher"

//#define LOG_NDEBUG 0

// Log detailed debug messages about each inbound event notification to the dispatcher.
#define DEBUG_INBOUND_EVENT_DETAILS 0

// Log detailed debug messages about each outbound event processed by the dispatcher.
#define DEBUG_OUTBOUND_EVENT_DETAILS 0

// Log debug messages about batching.
#define DEBUG_BATCHING 0

// Log debug messages about the dispatch cycle.
#define DEBUG_DISPATCH_CYCLE 0

// Log debug messages about registrations.
#define DEBUG_REGISTRATION 0

// Log debug messages about performance statistics.
#define DEBUG_PERFORMANCE_STATISTICS 0

// Log debug messages about input event injection.
#define DEBUG_INJECTION 0

// Log debug messages about input event throttling.
#define DEBUG_THROTTLING 0

// Log debug messages about input focus tracking.
#define DEBUG_FOCUS 0

// Log debug messages about the app switch latency optimization.
#define DEBUG_APP_SWITCH 0

// Log debug messages about hover events.
#define DEBUG_HOVER 0

#include "InputDispatcher.h"

#include <cutils/log.h>
#include <ui/PowerManager.h>

#include <stddef.h>
#include <unistd.h>
#include <errno.h>
#include <limits.h>

#define INDENT "  "
#define INDENT2 "    "

namespace android {

// Default input dispatching timeout if there is no focused application or paused window
// from which to determine an appropriate dispatching timeout.
const nsecs_t DEFAULT_INPUT_DISPATCHING_TIMEOUT = 5000 * 1000000LL; // 5 sec

// Amount of time to allow for all pending events to be processed when an app switch
// key is on the way.  This is used to preempt input dispatch and drop input events
// when an application takes too long to respond and the user has pressed an app switch key.
const nsecs_t APP_SWITCH_TIMEOUT = 500 * 1000000LL; // 0.5sec

// Amount of time to allow for an event to be dispatched (measured since its eventTime)
// before considering it stale and dropping it.
const nsecs_t STALE_EVENT_TIMEOUT = 10000 * 1000000LL; // 10sec

// Motion samples that are received within this amount of time are simply coalesced
// when batched instead of being appended.  This is done because some drivers update
// the location of pointers one at a time instead of all at once.
// For example, when there are 10 fingers down, the input dispatcher may receive 10
// samples in quick succession with only one finger's location changed in each sample.
//
// This value effectively imposes an upper bound on the touch sampling rate.
// Touch sensors typically have a 50Hz - 200Hz sampling rate, so we expect distinct
// samples to become available 5-20ms apart but individual finger reports can trickle
// in over a period of 2-4ms or so.
//
// Empirical testing shows that a 2ms coalescing interval (500Hz) is not enough,
// a 3ms coalescing interval (333Hz) works well most of the time and doesn't introduce
// significant quantization noise on current hardware.
const nsecs_t MOTION_SAMPLE_COALESCE_INTERVAL = 3 * 1000000LL; // 3ms, 333Hz


static inline nsecs_t now() {
    return systemTime(SYSTEM_TIME_MONOTONIC);
}

static inline const char* toString(bool value) {
    return value ? "true" : "false";
}

static inline int32_t getMotionEventActionPointerIndex(int32_t action) {
    return (action & AMOTION_EVENT_ACTION_POINTER_INDEX_MASK)
            >> AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
}

static bool isValidKeyAction(int32_t action) {
    switch (action) {
    case AKEY_EVENT_ACTION_DOWN:
    case AKEY_EVENT_ACTION_UP:
        return true;
    default:
        return false;
    }
}

static bool validateKeyEvent(int32_t action) {
    if (! isValidKeyAction(action)) {
        LOGE("Key event has invalid action code 0x%x", action);
        return false;
    }
    return true;
}

static bool isValidMotionAction(int32_t action, size_t pointerCount) {
    switch (action & AMOTION_EVENT_ACTION_MASK) {
    case AMOTION_EVENT_ACTION_DOWN:
    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_CANCEL:
    case AMOTION_EVENT_ACTION_MOVE:
    case AMOTION_EVENT_ACTION_OUTSIDE:
    case AMOTION_EVENT_ACTION_HOVER_ENTER:
    case AMOTION_EVENT_ACTION_HOVER_MOVE:
    case AMOTION_EVENT_ACTION_HOVER_EXIT:
    case AMOTION_EVENT_ACTION_SCROLL:
        return true;
    case AMOTION_EVENT_ACTION_POINTER_DOWN:
    case AMOTION_EVENT_ACTION_POINTER_UP: {
        int32_t index = getMotionEventActionPointerIndex(action);
        return index >= 0 && size_t(index) < pointerCount;
    }
    default:
        return false;
    }
}

static bool validateMotionEvent(int32_t action, size_t pointerCount,
        const PointerProperties* pointerProperties) {
    if (! isValidMotionAction(action, pointerCount)) {
        LOGE("Motion event has invalid action code 0x%x", action);
        return false;
    }
    if (pointerCount < 1 || pointerCount > MAX_POINTERS) {
        LOGE("Motion event has invalid pointer count %d; value must be between 1 and %d.",
                pointerCount, MAX_POINTERS);
        return false;
    }
    BitSet32 pointerIdBits;
    for (size_t i = 0; i < pointerCount; i++) {
        int32_t id = pointerProperties[i].id;
        if (id < 0 || id > MAX_POINTER_ID) {
            LOGE("Motion event has invalid pointer id %d; value must be between 0 and %d",
                    id, MAX_POINTER_ID);
            return false;
        }
        if (pointerIdBits.hasBit(id)) {
            LOGE("Motion event has duplicate pointer id %d", id);
            return false;
        }
        pointerIdBits.markBit(id);
    }
    return true;
}

static void scalePointerCoords(const PointerCoords* inCoords, size_t count, float scaleFactor,
        PointerCoords* outCoords) {
   for (size_t i = 0; i < count; i++) {
       outCoords[i] = inCoords[i];
       outCoords[i].scale(scaleFactor);
   }
}

static void dumpRegion(String8& dump, const SkRegion& region) {
    if (region.isEmpty()) {
        dump.append("<empty>");
        return;
    }

    bool first = true;
    for (SkRegion::Iterator it(region); !it.done(); it.next()) {
        if (first) {
            first = false;
        } else {
            dump.append("|");
        }
        const SkIRect& rect = it.rect();
        dump.appendFormat("[%d,%d][%d,%d]", rect.fLeft, rect.fTop, rect.fRight, rect.fBottom);
    }
}


// --- InputDispatcher ---

InputDispatcher::InputDispatcher(const sp<InputDispatcherPolicyInterface>& policy) :
    mPolicy(policy),
    mPendingEvent(NULL), mAppSwitchSawKeyDown(false), mAppSwitchDueTime(LONG_LONG_MAX),
    mNextUnblockedEvent(NULL),
    mDispatchEnabled(true), mDispatchFrozen(false), mInputFilterEnabled(false),
    mFocusedWindow(NULL),
    mFocusedApplication(NULL),
    mCurrentInputTargetsValid(false),
    mInputTargetWaitCause(INPUT_TARGET_WAIT_CAUSE_NONE),
    mLastHoverWindow(NULL) {
    mLooper = new Looper(false);

    mInboundQueue.headSentinel.refCount = -1;
    mInboundQueue.headSentinel.type = EventEntry::TYPE_SENTINEL;
    mInboundQueue.headSentinel.eventTime = LONG_LONG_MIN;

    mInboundQueue.tailSentinel.refCount = -1;
    mInboundQueue.tailSentinel.type = EventEntry::TYPE_SENTINEL;
    mInboundQueue.tailSentinel.eventTime = LONG_LONG_MAX;

    mKeyRepeatState.lastKeyEntry = NULL;

    policy->getDispatcherConfiguration(&mConfig);

    mThrottleState.minTimeBetweenEvents = 1000000000LL / mConfig.maxEventsPerSecond;
    mThrottleState.lastDeviceId = -1;

#if DEBUG_THROTTLING
    mThrottleState.originalSampleCount = 0;
    LOGD("Throttling - Max events per second = %d", mConfig.maxEventsPerSecond);
#endif
}

InputDispatcher::~InputDispatcher() {
    { // acquire lock
        AutoMutex _l(mLock);

        resetKeyRepeatLocked();
        releasePendingEventLocked();
        drainInboundQueueLocked();
    }

    while (mConnectionsByReceiveFd.size() != 0) {
        unregisterInputChannel(mConnectionsByReceiveFd.valueAt(0)->inputChannel);
    }
}

void InputDispatcher::dispatchOnce() {
    nsecs_t nextWakeupTime = LONG_LONG_MAX;
    { // acquire lock
        AutoMutex _l(mLock);
        dispatchOnceInnerLocked(&nextWakeupTime);

        if (runCommandsLockedInterruptible()) {
            nextWakeupTime = LONG_LONG_MIN;  // force next poll to wake up immediately
        }
    } // release lock

    // Wait for callback or timeout or wake.  (make sure we round up, not down)
    nsecs_t currentTime = now();
    int timeoutMillis = toMillisecondTimeoutDelay(currentTime, nextWakeupTime);
    mLooper->pollOnce(timeoutMillis);
}

void InputDispatcher::dispatchOnceInnerLocked(nsecs_t* nextWakeupTime) {
    nsecs_t currentTime = now();

    // Reset the key repeat timer whenever we disallow key events, even if the next event
    // is not a key.  This is to ensure that we abort a key repeat if the device is just coming
    // out of sleep.
    if (!mPolicy->isKeyRepeatEnabled()) {
        resetKeyRepeatLocked();
    }

    // If dispatching is frozen, do not process timeouts or try to deliver any new events.
    if (mDispatchFrozen) {
#if DEBUG_FOCUS
        LOGD("Dispatch frozen.  Waiting some more.");
#endif
        return;
    }

    // Optimize latency of app switches.
    // Essentially we start a short timeout when an app switch key (HOME / ENDCALL) has
    // been pressed.  When it expires, we preempt dispatch and drop all other pending events.
    bool isAppSwitchDue = mAppSwitchDueTime <= currentTime;
    if (mAppSwitchDueTime < *nextWakeupTime) {
        *nextWakeupTime = mAppSwitchDueTime;
    }

    // Ready to start a new event.
    // If we don't already have a pending event, go grab one.
    if (! mPendingEvent) {
        if (mInboundQueue.isEmpty()) {
            if (isAppSwitchDue) {
                // The inbound queue is empty so the app switch key we were waiting
                // for will never arrive.  Stop waiting for it.
                resetPendingAppSwitchLocked(false);
                isAppSwitchDue = false;
            }

            // Synthesize a key repeat if appropriate.
            if (mKeyRepeatState.lastKeyEntry) {
                if (currentTime >= mKeyRepeatState.nextRepeatTime) {
                    mPendingEvent = synthesizeKeyRepeatLocked(currentTime);
                } else {
                    if (mKeyRepeatState.nextRepeatTime < *nextWakeupTime) {
                        *nextWakeupTime = mKeyRepeatState.nextRepeatTime;
                    }
                }
            }
            if (! mPendingEvent) {
                return;
            }
        } else {
            // Inbound queue has at least one entry.
            EventEntry* entry = mInboundQueue.headSentinel.next;

            // Throttle the entry if it is a move event and there are no
            // other events behind it in the queue.  Due to movement batching, additional
            // samples may be appended to this event by the time the throttling timeout
            // expires.
            // TODO Make this smarter and consider throttling per device independently.
            if (entry->type == EventEntry::TYPE_MOTION
                    && !isAppSwitchDue
                    && mDispatchEnabled
                    && (entry->policyFlags & POLICY_FLAG_PASS_TO_USER)
                    && !entry->isInjected()) {
                MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
                int32_t deviceId = motionEntry->deviceId;
                uint32_t source = motionEntry->source;
                if (! isAppSwitchDue
                        && motionEntry->next == & mInboundQueue.tailSentinel // exactly one event
                        && (motionEntry->action == AMOTION_EVENT_ACTION_MOVE
                                || motionEntry->action == AMOTION_EVENT_ACTION_HOVER_MOVE)
                        && deviceId == mThrottleState.lastDeviceId
                        && source == mThrottleState.lastSource) {
                    nsecs_t nextTime = mThrottleState.lastEventTime
                            + mThrottleState.minTimeBetweenEvents;
                    if (currentTime < nextTime) {
                        // Throttle it!
#if DEBUG_THROTTLING
                        LOGD("Throttling - Delaying motion event for "
                                "device %d, source 0x%08x by up to %0.3fms.",
                                deviceId, source, (nextTime - currentTime) * 0.000001);
#endif
                        if (nextTime < *nextWakeupTime) {
                            *nextWakeupTime = nextTime;
                        }
                        if (mThrottleState.originalSampleCount == 0) {
                            mThrottleState.originalSampleCount =
                                    motionEntry->countSamples();
                        }
                        return;
                    }
                }

#if DEBUG_THROTTLING
                if (mThrottleState.originalSampleCount != 0) {
                    uint32_t count = motionEntry->countSamples();
                    LOGD("Throttling - Motion event sample count grew by %d from %d to %d.",
                            count - mThrottleState.originalSampleCount,
                            mThrottleState.originalSampleCount, count);
                    mThrottleState.originalSampleCount = 0;
                }
#endif

                mThrottleState.lastEventTime = currentTime;
                mThrottleState.lastDeviceId = deviceId;
                mThrottleState.lastSource = source;
            }

            mInboundQueue.dequeue(entry);
            mPendingEvent = entry;
        }

        // Poke user activity for this event.
        if (mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER) {
            pokeUserActivityLocked(mPendingEvent);
        }
    }

    // Now we have an event to dispatch.
    // All events are eventually dequeued and processed this way, even if we intend to drop them.
    LOG_ASSERT(mPendingEvent != NULL);
    bool done = false;
    DropReason dropReason = DROP_REASON_NOT_DROPPED;
    if (!(mPendingEvent->policyFlags & POLICY_FLAG_PASS_TO_USER)) {
        dropReason = DROP_REASON_POLICY;
    } else if (!mDispatchEnabled) {
        dropReason = DROP_REASON_DISABLED;
    }

    if (mNextUnblockedEvent == mPendingEvent) {
        mNextUnblockedEvent = NULL;
    }

    switch (mPendingEvent->type) {
    case EventEntry::TYPE_CONFIGURATION_CHANGED: {
        ConfigurationChangedEntry* typedEntry =
                static_cast<ConfigurationChangedEntry*>(mPendingEvent);
        done = dispatchConfigurationChangedLocked(currentTime, typedEntry);
        dropReason = DROP_REASON_NOT_DROPPED; // configuration changes are never dropped
        break;
    }

    case EventEntry::TYPE_KEY: {
        KeyEntry* typedEntry = static_cast<KeyEntry*>(mPendingEvent);
        if (isAppSwitchDue) {
            if (isAppSwitchKeyEventLocked(typedEntry)) {
                resetPendingAppSwitchLocked(true);
                isAppSwitchDue = false;
            } else if (dropReason == DROP_REASON_NOT_DROPPED) {
                dropReason = DROP_REASON_APP_SWITCH;
            }
        }
        if (dropReason == DROP_REASON_NOT_DROPPED
                && isStaleEventLocked(currentTime, typedEntry)) {
            dropReason = DROP_REASON_STALE;
        }
        if (dropReason == DROP_REASON_NOT_DROPPED && mNextUnblockedEvent) {
            dropReason = DROP_REASON_BLOCKED;
        }
        done = dispatchKeyLocked(currentTime, typedEntry, &dropReason, nextWakeupTime);
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* typedEntry = static_cast<MotionEntry*>(mPendingEvent);
        if (dropReason == DROP_REASON_NOT_DROPPED && isAppSwitchDue) {
            dropReason = DROP_REASON_APP_SWITCH;
        }
        if (dropReason == DROP_REASON_NOT_DROPPED
                && isStaleEventLocked(currentTime, typedEntry)) {
            dropReason = DROP_REASON_STALE;
        }
        if (dropReason == DROP_REASON_NOT_DROPPED && mNextUnblockedEvent) {
            dropReason = DROP_REASON_BLOCKED;
        }
        done = dispatchMotionLocked(currentTime, typedEntry,
                &dropReason, nextWakeupTime);
        break;
    }

    default:
        LOG_ASSERT(false);
        break;
    }

    if (done) {
        if (dropReason != DROP_REASON_NOT_DROPPED) {
            dropInboundEventLocked(mPendingEvent, dropReason);
        }

        releasePendingEventLocked();
        *nextWakeupTime = LONG_LONG_MIN;  // force next poll to wake up immediately
    }
}

bool InputDispatcher::enqueueInboundEventLocked(EventEntry* entry) {
    bool needWake = mInboundQueue.isEmpty();
    mInboundQueue.enqueueAtTail(entry);

    switch (entry->type) {
    case EventEntry::TYPE_KEY: {
        // Optimize app switch latency.
        // If the application takes too long to catch up then we drop all events preceding
        // the app switch key.
        KeyEntry* keyEntry = static_cast<KeyEntry*>(entry);
        if (isAppSwitchKeyEventLocked(keyEntry)) {
            if (keyEntry->action == AKEY_EVENT_ACTION_DOWN) {
                mAppSwitchSawKeyDown = true;
            } else if (keyEntry->action == AKEY_EVENT_ACTION_UP) {
                if (mAppSwitchSawKeyDown) {
#if DEBUG_APP_SWITCH
                    LOGD("App switch is pending!");
#endif
                    mAppSwitchDueTime = keyEntry->eventTime + APP_SWITCH_TIMEOUT;
                    mAppSwitchSawKeyDown = false;
                    needWake = true;
                }
            }
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        // Optimize case where the current application is unresponsive and the user
        // decides to touch a window in a different application.
        // If the application takes too long to catch up then we drop all events preceding
        // the touch into the other window.
        MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
        if (motionEntry->action == AMOTION_EVENT_ACTION_DOWN
                && (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER)
                && mInputTargetWaitCause == INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY
                && mInputTargetWaitApplication != NULL) {
            int32_t x = int32_t(motionEntry->firstSample.pointerCoords[0].
                    getAxisValue(AMOTION_EVENT_AXIS_X));
            int32_t y = int32_t(motionEntry->firstSample.pointerCoords[0].
                    getAxisValue(AMOTION_EVENT_AXIS_Y));
            const InputWindow* touchedWindow = findTouchedWindowAtLocked(x, y);
            if (touchedWindow
                    && touchedWindow->inputWindowHandle != NULL
                    && touchedWindow->inputWindowHandle->getInputApplicationHandle()
                            != mInputTargetWaitApplication) {
                // User touched a different application than the one we are waiting on.
                // Flag the event, and start pruning the input queue.
                mNextUnblockedEvent = motionEntry;
                needWake = true;
            }
        }
        break;
    }
    }

    return needWake;
}

const InputWindow* InputDispatcher::findTouchedWindowAtLocked(int32_t x, int32_t y) {
    // Traverse windows from front to back to find touched window.
    size_t numWindows = mWindows.size();
    for (size_t i = 0; i < numWindows; i++) {
        const InputWindow* window = & mWindows.editItemAt(i);
        int32_t flags = window->layoutParamsFlags;

        if (window->visible) {
            if (!(flags & InputWindow::FLAG_NOT_TOUCHABLE)) {
                bool isTouchModal = (flags & (InputWindow::FLAG_NOT_FOCUSABLE
                        | InputWindow::FLAG_NOT_TOUCH_MODAL)) == 0;
                if (isTouchModal || window->touchableRegionContainsPoint(x, y)) {
                    // Found window.
                    return window;
                }
            }
        }

        if (flags & InputWindow::FLAG_SYSTEM_ERROR) {
            // Error window is on top but not visible, so touch is dropped.
            return NULL;
        }
    }
    return NULL;
}

void InputDispatcher::dropInboundEventLocked(EventEntry* entry, DropReason dropReason) {
    const char* reason;
    switch (dropReason) {
    case DROP_REASON_POLICY:
#if DEBUG_INBOUND_EVENT_DETAILS
        LOGD("Dropped event because policy consumed it.");
#endif
        reason = "inbound event was dropped because the policy consumed it";
        break;
    case DROP_REASON_DISABLED:
        LOGI("Dropped event because input dispatch is disabled.");
        reason = "inbound event was dropped because input dispatch is disabled";
        break;
    case DROP_REASON_APP_SWITCH:
        LOGI("Dropped event because of pending overdue app switch.");
        reason = "inbound event was dropped because of pending overdue app switch";
        break;
    case DROP_REASON_BLOCKED:
        LOGI("Dropped event because the current application is not responding and the user "
                "has started interacting with a different application.");
        reason = "inbound event was dropped because the current application is not responding "
                "and the user has started interacting with a different application";
        break;
    case DROP_REASON_STALE:
        LOGI("Dropped event because it is stale.");
        reason = "inbound event was dropped because it is stale";
        break;
    default:
        LOG_ASSERT(false);
        return;
    }

    switch (entry->type) {
    case EventEntry::TYPE_KEY: {
        CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS, reason);
        synthesizeCancelationEventsForAllConnectionsLocked(options);
        break;
    }
    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
        if (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER) {
            CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS, reason);
            synthesizeCancelationEventsForAllConnectionsLocked(options);
        } else {
            CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS, reason);
            synthesizeCancelationEventsForAllConnectionsLocked(options);
        }
        break;
    }
    }
}

bool InputDispatcher::isAppSwitchKeyCode(int32_t keyCode) {
    return keyCode == AKEYCODE_HOME || keyCode == AKEYCODE_ENDCALL;
}

bool InputDispatcher::isAppSwitchKeyEventLocked(KeyEntry* keyEntry) {
    return ! (keyEntry->flags & AKEY_EVENT_FLAG_CANCELED)
            && isAppSwitchKeyCode(keyEntry->keyCode)
            && (keyEntry->policyFlags & POLICY_FLAG_TRUSTED)
            && (keyEntry->policyFlags & POLICY_FLAG_PASS_TO_USER);
}

bool InputDispatcher::isAppSwitchPendingLocked() {
    return mAppSwitchDueTime != LONG_LONG_MAX;
}

void InputDispatcher::resetPendingAppSwitchLocked(bool handled) {
    mAppSwitchDueTime = LONG_LONG_MAX;

#if DEBUG_APP_SWITCH
    if (handled) {
        LOGD("App switch has arrived.");
    } else {
        LOGD("App switch was abandoned.");
    }
#endif
}

bool InputDispatcher::isStaleEventLocked(nsecs_t currentTime, EventEntry* entry) {
    return currentTime - entry->eventTime >= STALE_EVENT_TIMEOUT;
}

bool InputDispatcher::runCommandsLockedInterruptible() {
    if (mCommandQueue.isEmpty()) {
        return false;
    }

    do {
        CommandEntry* commandEntry = mCommandQueue.dequeueAtHead();

        Command command = commandEntry->command;
        (this->*command)(commandEntry); // commands are implicitly 'LockedInterruptible'

        commandEntry->connection.clear();
        mAllocator.releaseCommandEntry(commandEntry);
    } while (! mCommandQueue.isEmpty());
    return true;
}

InputDispatcher::CommandEntry* InputDispatcher::postCommandLocked(Command command) {
    CommandEntry* commandEntry = mAllocator.obtainCommandEntry(command);
    mCommandQueue.enqueueAtTail(commandEntry);
    return commandEntry;
}

void InputDispatcher::drainInboundQueueLocked() {
    while (! mInboundQueue.isEmpty()) {
        EventEntry* entry = mInboundQueue.dequeueAtHead();
        releaseInboundEventLocked(entry);
    }
}

void InputDispatcher::releasePendingEventLocked() {
    if (mPendingEvent) {
        releaseInboundEventLocked(mPendingEvent);
        mPendingEvent = NULL;
    }
}

void InputDispatcher::releaseInboundEventLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState && injectionState->injectionResult == INPUT_EVENT_INJECTION_PENDING) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("Injected inbound event was dropped.");
#endif
        setInjectionResultLocked(entry, INPUT_EVENT_INJECTION_FAILED);
    }
    mAllocator.releaseEventEntry(entry);
}

void InputDispatcher::resetKeyRepeatLocked() {
    if (mKeyRepeatState.lastKeyEntry) {
        mAllocator.releaseKeyEntry(mKeyRepeatState.lastKeyEntry);
        mKeyRepeatState.lastKeyEntry = NULL;
    }
}

InputDispatcher::KeyEntry* InputDispatcher::synthesizeKeyRepeatLocked(nsecs_t currentTime) {
    KeyEntry* entry = mKeyRepeatState.lastKeyEntry;

    // Reuse the repeated key entry if it is otherwise unreferenced.
    uint32_t policyFlags = (entry->policyFlags & POLICY_FLAG_RAW_MASK)
            | POLICY_FLAG_PASS_TO_USER | POLICY_FLAG_TRUSTED;
    if (entry->refCount == 1) {
        mAllocator.recycleKeyEntry(entry);
        entry->eventTime = currentTime;
        entry->policyFlags = policyFlags;
        entry->repeatCount += 1;
    } else {
        KeyEntry* newEntry = mAllocator.obtainKeyEntry(currentTime,
                entry->deviceId, entry->source, policyFlags,
                entry->action, entry->flags, entry->keyCode, entry->scanCode,
                entry->metaState, entry->repeatCount + 1, entry->downTime);

        mKeyRepeatState.lastKeyEntry = newEntry;
        mAllocator.releaseKeyEntry(entry);

        entry = newEntry;
    }
    entry->syntheticRepeat = true;

    // Increment reference count since we keep a reference to the event in
    // mKeyRepeatState.lastKeyEntry in addition to the one we return.
    entry->refCount += 1;

    mKeyRepeatState.nextRepeatTime = currentTime + mConfig.keyRepeatDelay;
    return entry;
}

bool InputDispatcher::dispatchConfigurationChangedLocked(
        nsecs_t currentTime, ConfigurationChangedEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("dispatchConfigurationChanged - eventTime=%lld", entry->eventTime);
#endif

    // Reset key repeating in case a keyboard device was added or removed or something.
    resetKeyRepeatLocked();

    // Enqueue a command to run outside the lock to tell the policy that the configuration changed.
    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyConfigurationChangedInterruptible);
    commandEntry->eventTime = entry->eventTime;
    return true;
}

bool InputDispatcher::dispatchKeyLocked(nsecs_t currentTime, KeyEntry* entry,
        DropReason* dropReason, nsecs_t* nextWakeupTime) {
    // Preprocessing.
    if (! entry->dispatchInProgress) {
        if (entry->repeatCount == 0
                && entry->action == AKEY_EVENT_ACTION_DOWN
                && (entry->policyFlags & POLICY_FLAG_TRUSTED)
                && (!(entry->policyFlags & POLICY_FLAG_DISABLE_KEY_REPEAT))) {
            if (mKeyRepeatState.lastKeyEntry
                    && mKeyRepeatState.lastKeyEntry->keyCode == entry->keyCode) {
                // We have seen two identical key downs in a row which indicates that the device
                // driver is automatically generating key repeats itself.  We take note of the
                // repeat here, but we disable our own next key repeat timer since it is clear that
                // we will not need to synthesize key repeats ourselves.
                entry->repeatCount = mKeyRepeatState.lastKeyEntry->repeatCount + 1;
                resetKeyRepeatLocked();
                mKeyRepeatState.nextRepeatTime = LONG_LONG_MAX; // don't generate repeats ourselves
            } else {
                // Not a repeat.  Save key down state in case we do see a repeat later.
                resetKeyRepeatLocked();
                mKeyRepeatState.nextRepeatTime = entry->eventTime + mConfig.keyRepeatTimeout;
            }
            mKeyRepeatState.lastKeyEntry = entry;
            entry->refCount += 1;
        } else if (! entry->syntheticRepeat) {
            resetKeyRepeatLocked();
        }

        if (entry->repeatCount == 1) {
            entry->flags |= AKEY_EVENT_FLAG_LONG_PRESS;
        } else {
            entry->flags &= ~AKEY_EVENT_FLAG_LONG_PRESS;
        }

        entry->dispatchInProgress = true;
        resetTargetsLocked();

        logOutboundKeyDetailsLocked("dispatchKey - ", entry);
    }

    // Give the policy a chance to intercept the key.
    if (entry->interceptKeyResult == KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN) {
        if (entry->policyFlags & POLICY_FLAG_PASS_TO_USER) {
            CommandEntry* commandEntry = postCommandLocked(
                    & InputDispatcher::doInterceptKeyBeforeDispatchingLockedInterruptible);
            if (mFocusedWindow) {
                commandEntry->inputWindowHandle = mFocusedWindow->inputWindowHandle;
            }
            commandEntry->keyEntry = entry;
            entry->refCount += 1;
            return false; // wait for the command to run
        } else {
            entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_CONTINUE;
        }
    } else if (entry->interceptKeyResult == KeyEntry::INTERCEPT_KEY_RESULT_SKIP) {
        if (*dropReason == DROP_REASON_NOT_DROPPED) {
            *dropReason = DROP_REASON_POLICY;
        }
    }

    // Clean up if dropping the event.
    if (*dropReason != DROP_REASON_NOT_DROPPED) {
        resetTargetsLocked();
        setInjectionResultLocked(entry, *dropReason == DROP_REASON_POLICY
                ? INPUT_EVENT_INJECTION_SUCCEEDED : INPUT_EVENT_INJECTION_FAILED);
        return true;
    }

    // Identify targets.
    if (! mCurrentInputTargetsValid) {
        int32_t injectionResult = findFocusedWindowTargetsLocked(currentTime,
                entry, nextWakeupTime);
        if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
            return false;
        }

        setInjectionResultLocked(entry, injectionResult);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return true;
        }

        addMonitoringTargetsLocked();
        commitTargetsLocked();
    }

    // Dispatch the key.
    dispatchEventToCurrentInputTargetsLocked(currentTime, entry, false);
    return true;
}

void InputDispatcher::logOutboundKeyDetailsLocked(const char* prefix, const KeyEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("%seventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, "
            "repeatCount=%d, downTime=%lld",
            prefix,
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags,
            entry->action, entry->flags, entry->keyCode, entry->scanCode, entry->metaState,
            entry->repeatCount, entry->downTime);
#endif
}

bool InputDispatcher::dispatchMotionLocked(
        nsecs_t currentTime, MotionEntry* entry, DropReason* dropReason, nsecs_t* nextWakeupTime) {
    // Preprocessing.
    if (! entry->dispatchInProgress) {
        entry->dispatchInProgress = true;
        resetTargetsLocked();

        logOutboundMotionDetailsLocked("dispatchMotion - ", entry);
    }

    // Clean up if dropping the event.
    if (*dropReason != DROP_REASON_NOT_DROPPED) {
        resetTargetsLocked();
        setInjectionResultLocked(entry, *dropReason == DROP_REASON_POLICY
                ? INPUT_EVENT_INJECTION_SUCCEEDED : INPUT_EVENT_INJECTION_FAILED);
        return true;
    }

    bool isPointerEvent = entry->source & AINPUT_SOURCE_CLASS_POINTER;

    // Identify targets.
    bool conflictingPointerActions = false;
    if (! mCurrentInputTargetsValid) {
        int32_t injectionResult;
        const MotionSample* splitBatchAfterSample = NULL;
        if (isPointerEvent) {
            // Pointer event.  (eg. touchscreen)
            injectionResult = findTouchedWindowTargetsLocked(currentTime,
                    entry, nextWakeupTime, &conflictingPointerActions, &splitBatchAfterSample);
        } else {
            // Non touch event.  (eg. trackball)
            injectionResult = findFocusedWindowTargetsLocked(currentTime,
                    entry, nextWakeupTime);
        }
        if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
            return false;
        }

        setInjectionResultLocked(entry, injectionResult);
        if (injectionResult != INPUT_EVENT_INJECTION_SUCCEEDED) {
            return true;
        }

        addMonitoringTargetsLocked();
        commitTargetsLocked();

        // Unbatch the event if necessary by splitting it into two parts after the
        // motion sample indicated by splitBatchAfterSample.
        if (splitBatchAfterSample && splitBatchAfterSample->next) {
#if DEBUG_BATCHING
            uint32_t originalSampleCount = entry->countSamples();
#endif
            MotionSample* nextSample = splitBatchAfterSample->next;
            MotionEntry* nextEntry = mAllocator.obtainMotionEntry(nextSample->eventTime,
                    entry->deviceId, entry->source, entry->policyFlags,
                    entry->action, entry->flags,
                    entry->metaState, entry->buttonState, entry->edgeFlags,
                    entry->xPrecision, entry->yPrecision, entry->downTime,
                    entry->pointerCount, entry->pointerProperties, nextSample->pointerCoords);
            if (nextSample != entry->lastSample) {
                nextEntry->firstSample.next = nextSample->next;
                nextEntry->lastSample = entry->lastSample;
            }
            mAllocator.freeMotionSample(nextSample);

            entry->lastSample = const_cast<MotionSample*>(splitBatchAfterSample);
            entry->lastSample->next = NULL;

            if (entry->injectionState) {
                nextEntry->injectionState = entry->injectionState;
                entry->injectionState->refCount += 1;
            }

#if DEBUG_BATCHING
            LOGD("Split batch of %d samples into two parts, first part has %d samples, "
                    "second part has %d samples.", originalSampleCount,
                    entry->countSamples(), nextEntry->countSamples());
#endif

            mInboundQueue.enqueueAtHead(nextEntry);
        }
    }

    // Dispatch the motion.
    if (conflictingPointerActions) {
        CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                "conflicting pointer actions");
        synthesizeCancelationEventsForAllConnectionsLocked(options);
    }
    dispatchEventToCurrentInputTargetsLocked(currentTime, entry, false);
    return true;
}


void InputDispatcher::logOutboundMotionDetailsLocked(const char* prefix, const MotionEntry* entry) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
    LOGD("%seventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, "
            "metaState=0x%x, buttonState=0x%x, "
            "edgeFlags=0x%x, xPrecision=%f, yPrecision=%f, downTime=%lld",
            prefix,
            entry->eventTime, entry->deviceId, entry->source, entry->policyFlags,
            entry->action, entry->flags,
            entry->metaState, entry->buttonState,
            entry->edgeFlags, entry->xPrecision, entry->yPrecision,
            entry->downTime);

    // Print the most recent sample that we have available, this may change due to batching.
    size_t sampleCount = 1;
    const MotionSample* sample = & entry->firstSample;
    for (; sample->next != NULL; sample = sample->next) {
        sampleCount += 1;
    }
    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        LOGD("  Pointer %d: id=%d, toolType=%d, "
                "x=%f, y=%f, pressure=%f, size=%f, "
                "touchMajor=%f, touchMinor=%f, toolMajor=%f, toolMinor=%f, "
                "orientation=%f",
                i, entry->pointerProperties[i].id,
                entry->pointerProperties[i].toolType,
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_X),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_Y),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_PRESSURE),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_SIZE),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR),
                sample->pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION));
    }

    // Keep in mind that due to batching, it is possible for the number of samples actually
    // dispatched to change before the application finally consumed them.
    if (entry->action == AMOTION_EVENT_ACTION_MOVE) {
        LOGD("  ... Total movement samples currently batched %d ...", sampleCount);
    }
#endif
}

void InputDispatcher::dispatchEventToCurrentInputTargetsLocked(nsecs_t currentTime,
        EventEntry* eventEntry, bool resumeWithAppendedMotionSample) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("dispatchEventToCurrentInputTargets - "
            "resumeWithAppendedMotionSample=%s",
            toString(resumeWithAppendedMotionSample));
#endif

    LOG_ASSERT(eventEntry->dispatchInProgress); // should already have been set to true

    pokeUserActivityLocked(eventEntry);

    for (size_t i = 0; i < mCurrentInputTargets.size(); i++) {
        const InputTarget& inputTarget = mCurrentInputTargets.itemAt(i);

        ssize_t connectionIndex = getConnectionIndexLocked(inputTarget.inputChannel);
        if (connectionIndex >= 0) {
            sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
            prepareDispatchCycleLocked(currentTime, connection, eventEntry, & inputTarget,
                    resumeWithAppendedMotionSample);
        } else {
#if DEBUG_FOCUS
            LOGD("Dropping event delivery to target with channel '%s' because it "
                    "is no longer registered with the input dispatcher.",
                    inputTarget.inputChannel->getName().string());
#endif
        }
    }
}

void InputDispatcher::resetTargetsLocked() {
    mCurrentInputTargetsValid = false;
    mCurrentInputTargets.clear();
    mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_NONE;
    mInputTargetWaitApplication.clear();
}

void InputDispatcher::commitTargetsLocked() {
    mCurrentInputTargetsValid = true;
}

int32_t InputDispatcher::handleTargetsNotReadyLocked(nsecs_t currentTime,
        const EventEntry* entry, const InputApplication* application, const InputWindow* window,
        nsecs_t* nextWakeupTime) {
    if (application == NULL && window == NULL) {
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY) {
#if DEBUG_FOCUS
            LOGD("Waiting for system to become ready for input.");
#endif
            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_SYSTEM_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            mInputTargetWaitTimeoutTime = LONG_LONG_MAX;
            mInputTargetWaitTimeoutExpired = false;
            mInputTargetWaitApplication.clear();
        }
    } else {
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
#if DEBUG_FOCUS
            LOGD("Waiting for application to become ready for input: %s",
                    getApplicationWindowLabelLocked(application, window).string());
#endif
            nsecs_t timeout = window ? window->dispatchingTimeout :
                application ? application->dispatchingTimeout : DEFAULT_INPUT_DISPATCHING_TIMEOUT;

            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            mInputTargetWaitTimeoutTime = currentTime + timeout;
            mInputTargetWaitTimeoutExpired = false;
            mInputTargetWaitApplication.clear();

            if (window && window->inputWindowHandle != NULL) {
                mInputTargetWaitApplication =
                        window->inputWindowHandle->getInputApplicationHandle();
            }
            if (mInputTargetWaitApplication == NULL && application) {
                mInputTargetWaitApplication = application->inputApplicationHandle;
            }
        }
    }

    if (mInputTargetWaitTimeoutExpired) {
        return INPUT_EVENT_INJECTION_TIMED_OUT;
    }

    if (currentTime >= mInputTargetWaitTimeoutTime) {
        onANRLocked(currentTime, application, window, entry->eventTime, mInputTargetWaitStartTime);

        // Force poll loop to wake up immediately on next iteration once we get the
        // ANR response back from the policy.
        *nextWakeupTime = LONG_LONG_MIN;
        return INPUT_EVENT_INJECTION_PENDING;
    } else {
        // Force poll loop to wake up when timeout is due.
        if (mInputTargetWaitTimeoutTime < *nextWakeupTime) {
            *nextWakeupTime = mInputTargetWaitTimeoutTime;
        }
        return INPUT_EVENT_INJECTION_PENDING;
    }
}

void InputDispatcher::resumeAfterTargetsNotReadyTimeoutLocked(nsecs_t newTimeout,
        const sp<InputChannel>& inputChannel) {
    if (newTimeout > 0) {
        // Extend the timeout.
        mInputTargetWaitTimeoutTime = now() + newTimeout;
    } else {
        // Give up.
        mInputTargetWaitTimeoutExpired = true;

        // Release the touch targets.
        mTouchState.reset();

        // Input state will not be realistic.  Mark it out of sync.
        if (inputChannel.get()) {
            ssize_t connectionIndex = getConnectionIndexLocked(inputChannel);
            if (connectionIndex >= 0) {
                sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
                if (connection->status == Connection::STATUS_NORMAL) {
                    CancelationOptions options(CancelationOptions::CANCEL_ALL_EVENTS,
                            "application not responding");
                    synthesizeCancelationEventsForConnectionLocked(connection, options);
                }
            }
        }
    }
}

nsecs_t InputDispatcher::getTimeSpentWaitingForApplicationLocked(
        nsecs_t currentTime) {
    if (mInputTargetWaitCause == INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
        return currentTime - mInputTargetWaitStartTime;
    }
    return 0;
}

void InputDispatcher::resetANRTimeoutsLocked() {
#if DEBUG_FOCUS
        LOGD("Resetting ANR timeouts.");
#endif

    // Reset input target wait timeout.
    mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_NONE;
}

int32_t InputDispatcher::findFocusedWindowTargetsLocked(nsecs_t currentTime,
        const EventEntry* entry, nsecs_t* nextWakeupTime) {
    mCurrentInputTargets.clear();

    int32_t injectionResult;

    // If there is no currently focused window and no focused application
    // then drop the event.
    if (! mFocusedWindow) {
        if (mFocusedApplication) {
#if DEBUG_FOCUS
            LOGD("Waiting because there is no focused window but there is a "
                    "focused application that may eventually add a window: %s.",
                    getApplicationWindowLabelLocked(mFocusedApplication, NULL).string());
#endif
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    mFocusedApplication, NULL, nextWakeupTime);
            goto Unresponsive;
        }

        LOGI("Dropping event because there is no focused window or focused application.");
        injectionResult = INPUT_EVENT_INJECTION_FAILED;
        goto Failed;
    }

    // Check permissions.
    if (! checkInjectionPermission(mFocusedWindow, entry->injectionState)) {
        injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
        goto Failed;
    }

    // If the currently focused window is paused then keep waiting.
    if (mFocusedWindow->paused) {
#if DEBUG_FOCUS
        LOGD("Waiting because focused window is paused.");
#endif
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplication, mFocusedWindow, nextWakeupTime);
        goto Unresponsive;
    }

    // If the currently focused window is still working on previous events then keep waiting.
    if (! isWindowFinishedWithPreviousInputLocked(mFocusedWindow)) {
#if DEBUG_FOCUS
        LOGD("Waiting because focused window still processing previous input.");
#endif
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplication, mFocusedWindow, nextWakeupTime);
        goto Unresponsive;
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
    addWindowTargetLocked(mFocusedWindow,
            InputTarget::FLAG_FOREGROUND | InputTarget::FLAG_DISPATCH_AS_IS, BitSet32(0));

    // Done.
Failed:
Unresponsive:
    nsecs_t timeSpentWaitingForApplication = getTimeSpentWaitingForApplicationLocked(currentTime);
    updateDispatchStatisticsLocked(currentTime, entry,
            injectionResult, timeSpentWaitingForApplication);
#if DEBUG_FOCUS
    LOGD("findFocusedWindow finished: injectionResult=%d, "
            "timeSpendWaitingForApplication=%0.1fms",
            injectionResult, timeSpentWaitingForApplication / 1000000.0);
#endif
    return injectionResult;
}

int32_t InputDispatcher::findTouchedWindowTargetsLocked(nsecs_t currentTime,
        const MotionEntry* entry, nsecs_t* nextWakeupTime, bool* outConflictingPointerActions,
        const MotionSample** outSplitBatchAfterSample) {
    enum InjectionPermission {
        INJECTION_PERMISSION_UNKNOWN,
        INJECTION_PERMISSION_GRANTED,
        INJECTION_PERMISSION_DENIED
    };

    mCurrentInputTargets.clear();

    nsecs_t startTime = now();

    // For security reasons, we defer updating the touch state until we are sure that
    // event injection will be allowed.
    //
    // FIXME In the original code, screenWasOff could never be set to true.
    //       The reason is that the POLICY_FLAG_WOKE_HERE
    //       and POLICY_FLAG_BRIGHT_HERE flags were set only when preprocessing raw
    //       EV_KEY, EV_REL and EV_ABS events.  As it happens, the touch event was
    //       actually enqueued using the policyFlags that appeared in the final EV_SYN
    //       events upon which no preprocessing took place.  So policyFlags was always 0.
    //       In the new native input dispatcher we're a bit more careful about event
    //       preprocessing so the touches we receive can actually have non-zero policyFlags.
    //       Unfortunately we obtain undesirable behavior.
    //
    //       Here's what happens:
    //
    //       When the device dims in anticipation of going to sleep, touches
    //       in windows which have FLAG_TOUCHABLE_WHEN_WAKING cause
    //       the device to brighten and reset the user activity timer.
    //       Touches on other windows (such as the launcher window)
    //       are dropped.  Then after a moment, the device goes to sleep.  Oops.
    //
    //       Also notice how screenWasOff was being initialized using POLICY_FLAG_BRIGHT_HERE
    //       instead of POLICY_FLAG_WOKE_HERE...
    //
    bool screenWasOff = false; // original policy: policyFlags & POLICY_FLAG_BRIGHT_HERE;

    int32_t action = entry->action;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;

    // Update the touch state as needed based on the properties of the touch event.
    int32_t injectionResult = INPUT_EVENT_INJECTION_PENDING;
    InjectionPermission injectionPermission = INJECTION_PERMISSION_UNKNOWN;
    const InputWindow* newHoverWindow = NULL;

    bool isSplit = mTouchState.split;
    bool switchedDevice = mTouchState.deviceId != entry->deviceId
            || mTouchState.source != entry->source;
    bool isHoverAction = (maskedAction == AMOTION_EVENT_ACTION_HOVER_MOVE
            || maskedAction == AMOTION_EVENT_ACTION_HOVER_ENTER
            || maskedAction == AMOTION_EVENT_ACTION_HOVER_EXIT);
    bool newGesture = (maskedAction == AMOTION_EVENT_ACTION_DOWN
            || maskedAction == AMOTION_EVENT_ACTION_SCROLL
            || isHoverAction);
    bool wrongDevice = false;
    if (newGesture) {
        bool down = maskedAction == AMOTION_EVENT_ACTION_DOWN;
        if (switchedDevice && mTouchState.down && !down) {
#if DEBUG_FOCUS
            LOGD("Dropping event because a pointer for a different device is already down.");
#endif
            mTempTouchState.copyFrom(mTouchState);
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            switchedDevice = false;
            wrongDevice = true;
            goto Failed;
        }
        mTempTouchState.reset();
        mTempTouchState.down = down;
        mTempTouchState.deviceId = entry->deviceId;
        mTempTouchState.source = entry->source;
        isSplit = false;
    } else {
        mTempTouchState.copyFrom(mTouchState);
    }

    if (newGesture || (isSplit && maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN)) {
        /* Case 1: New splittable pointer going down, or need target for hover or scroll. */

        const MotionSample* sample = &entry->firstSample;
        int32_t pointerIndex = getMotionEventActionPointerIndex(action);
        int32_t x = int32_t(sample->pointerCoords[pointerIndex].
                getAxisValue(AMOTION_EVENT_AXIS_X));
        int32_t y = int32_t(sample->pointerCoords[pointerIndex].
                getAxisValue(AMOTION_EVENT_AXIS_Y));
        const InputWindow* newTouchedWindow = NULL;
        const InputWindow* topErrorWindow = NULL;
        bool isTouchModal = false;

        // Traverse windows from front to back to find touched window and outside targets.
        size_t numWindows = mWindows.size();
        for (size_t i = 0; i < numWindows; i++) {
            const InputWindow* window = & mWindows.editItemAt(i);
            int32_t flags = window->layoutParamsFlags;

            if (flags & InputWindow::FLAG_SYSTEM_ERROR) {
                if (! topErrorWindow) {
                    topErrorWindow = window;
                }
            }

            if (window->visible) {
                if (! (flags & InputWindow::FLAG_NOT_TOUCHABLE)) {
                    isTouchModal = (flags & (InputWindow::FLAG_NOT_FOCUSABLE
                            | InputWindow::FLAG_NOT_TOUCH_MODAL)) == 0;
                    if (isTouchModal || window->touchableRegionContainsPoint(x, y)) {
                        if (! screenWasOff || flags & InputWindow::FLAG_TOUCHABLE_WHEN_WAKING) {
                            newTouchedWindow = window;
                        }
                        break; // found touched window, exit window loop
                    }
                }

                if (maskedAction == AMOTION_EVENT_ACTION_DOWN
                        && (flags & InputWindow::FLAG_WATCH_OUTSIDE_TOUCH)) {
                    int32_t outsideTargetFlags = InputTarget::FLAG_DISPATCH_AS_OUTSIDE;
                    if (isWindowObscuredAtPointLocked(window, x, y)) {
                        outsideTargetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
                    }

                    mTempTouchState.addOrUpdateWindow(window, outsideTargetFlags, BitSet32(0));
                }
            }
        }

        // If there is an error window but it is not taking focus (typically because
        // it is invisible) then wait for it.  Any other focused window may in
        // fact be in ANR state.
        if (topErrorWindow && newTouchedWindow != topErrorWindow) {
#if DEBUG_FOCUS
            LOGD("Waiting because system error window is pending.");
#endif
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    NULL, NULL, nextWakeupTime);
            injectionPermission = INJECTION_PERMISSION_UNKNOWN;
            goto Unresponsive;
        }

        // Figure out whether splitting will be allowed for this window.
        if (newTouchedWindow && newTouchedWindow->supportsSplitTouch()) {
            // New window supports splitting.
            isSplit = true;
        } else if (isSplit) {
            // New window does not support splitting but we have already split events.
            // Assign the pointer to the first foreground window we find.
            // (May be NULL which is why we put this code block before the next check.)
            newTouchedWindow = mTempTouchState.getFirstForegroundWindow();
        }

        // If we did not find a touched window then fail.
        if (! newTouchedWindow) {
            if (mFocusedApplication) {
#if DEBUG_FOCUS
                LOGD("Waiting because there is no touched window but there is a "
                        "focused application that may eventually add a new window: %s.",
                        getApplicationWindowLabelLocked(mFocusedApplication, NULL).string());
#endif
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        mFocusedApplication, NULL, nextWakeupTime);
                goto Unresponsive;
            }

            LOGI("Dropping event because there is no touched window or focused application.");
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Set target flags.
        int32_t targetFlags = InputTarget::FLAG_FOREGROUND | InputTarget::FLAG_DISPATCH_AS_IS;
        if (isSplit) {
            targetFlags |= InputTarget::FLAG_SPLIT;
        }
        if (isWindowObscuredAtPointLocked(newTouchedWindow, x, y)) {
            targetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
        }

        // Update hover state.
        if (isHoverAction) {
            newHoverWindow = newTouchedWindow;

            // Ensure all subsequent motion samples are also within the touched window.
            // Set *outSplitBatchAfterSample to the sample before the first one that is not
            // within the touched window.
            if (!isTouchModal) {
                while (sample->next) {
                    if (!newHoverWindow->touchableRegionContainsPoint(
                            sample->next->pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_X),
                            sample->next->pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_Y))) {
                        *outSplitBatchAfterSample = sample;
                        break;
                    }
                    sample = sample->next;
                }
            }
        } else if (maskedAction == AMOTION_EVENT_ACTION_SCROLL) {
            newHoverWindow = mLastHoverWindow;
        }

        // Update the temporary touch state.
        BitSet32 pointerIds;
        if (isSplit) {
            uint32_t pointerId = entry->pointerProperties[pointerIndex].id;
            pointerIds.markBit(pointerId);
        }
        mTempTouchState.addOrUpdateWindow(newTouchedWindow, targetFlags, pointerIds);
    } else {
        /* Case 2: Pointer move, up, cancel or non-splittable pointer down. */

        // If the pointer is not currently down, then ignore the event.
        if (! mTempTouchState.down) {
#if DEBUG_FOCUS
            LOGD("Dropping event because the pointer is not down or we previously "
                    "dropped the pointer down event.");
#endif
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Check whether touches should slip outside of the current foreground window.
        if (maskedAction == AMOTION_EVENT_ACTION_MOVE
                && entry->pointerCount == 1
                && mTempTouchState.isSlippery()) {
            const MotionSample* sample = &entry->firstSample;
            int32_t x = int32_t(sample->pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_X));
            int32_t y = int32_t(sample->pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_Y));

            const InputWindow* oldTouchedWindow = mTempTouchState.getFirstForegroundWindow();
            const InputWindow* newTouchedWindow = findTouchedWindowAtLocked(x, y);
            if (oldTouchedWindow != newTouchedWindow && newTouchedWindow) {
#if DEBUG_FOCUS
                LOGD("Touch is slipping out of window %s into window %s.",
                        oldTouchedWindow->name.string(), newTouchedWindow->name.string());
#endif
                // Make a slippery exit from the old window.
                mTempTouchState.addOrUpdateWindow(oldTouchedWindow,
                        InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT, BitSet32(0));

                // Make a slippery entrance into the new window.
                if (newTouchedWindow->supportsSplitTouch()) {
                    isSplit = true;
                }

                int32_t targetFlags = InputTarget::FLAG_FOREGROUND
                        | InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER;
                if (isSplit) {
                    targetFlags |= InputTarget::FLAG_SPLIT;
                }
                if (isWindowObscuredAtPointLocked(newTouchedWindow, x, y)) {
                    targetFlags |= InputTarget::FLAG_WINDOW_IS_OBSCURED;
                }

                BitSet32 pointerIds;
                if (isSplit) {
                    pointerIds.markBit(entry->pointerProperties[0].id);
                }
                mTempTouchState.addOrUpdateWindow(newTouchedWindow, targetFlags, pointerIds);

                // Split the batch here so we send exactly one sample.
                *outSplitBatchAfterSample = &entry->firstSample;
            }
        }
    }

    if (newHoverWindow != mLastHoverWindow) {
        // Split the batch here so we send exactly one sample as part of ENTER or EXIT.
        *outSplitBatchAfterSample = &entry->firstSample;

        // Let the previous window know that the hover sequence is over.
        if (mLastHoverWindow) {
#if DEBUG_HOVER
            LOGD("Sending hover exit event to window %s.", mLastHoverWindow->name.string());
#endif
            mTempTouchState.addOrUpdateWindow(mLastHoverWindow,
                    InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT, BitSet32(0));
        }

        // Let the new window know that the hover sequence is starting.
        if (newHoverWindow) {
#if DEBUG_HOVER
            LOGD("Sending hover enter event to window %s.", newHoverWindow->name.string());
#endif
            mTempTouchState.addOrUpdateWindow(newHoverWindow,
                    InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER, BitSet32(0));
        }
    }

    // Check permission to inject into all touched foreground windows and ensure there
    // is at least one touched foreground window.
    {
        bool haveForegroundWindow = false;
        for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
            if (touchedWindow.targetFlags & InputTarget::FLAG_FOREGROUND) {
                haveForegroundWindow = true;
                if (! checkInjectionPermission(touchedWindow.window, entry->injectionState)) {
                    injectionResult = INPUT_EVENT_INJECTION_PERMISSION_DENIED;
                    injectionPermission = INJECTION_PERMISSION_DENIED;
                    goto Failed;
                }
            }
        }
        if (! haveForegroundWindow) {
#if DEBUG_FOCUS
            LOGD("Dropping event because there is no touched foreground window to receive it.");
#endif
            injectionResult = INPUT_EVENT_INJECTION_FAILED;
            goto Failed;
        }

        // Permission granted to injection into all touched foreground windows.
        injectionPermission = INJECTION_PERMISSION_GRANTED;
    }

    // Check whether windows listening for outside touches are owned by the same UID. If it is
    // set the policy flag that we will not reveal coordinate information to this window.
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
        const InputWindow* foregroundWindow = mTempTouchState.getFirstForegroundWindow();
        const int32_t foregroundWindowUid = foregroundWindow->ownerUid;
        for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
            if (touchedWindow.targetFlags & InputTarget::FLAG_DISPATCH_AS_OUTSIDE) {
                const InputWindow* inputWindow = touchedWindow.window;
                if (inputWindow->ownerUid != foregroundWindowUid) {
                    mTempTouchState.addOrUpdateWindow(inputWindow,
                            InputTarget::FLAG_ZERO_COORDS, BitSet32(0));
                }
            }
        }
    }

    // Ensure all touched foreground windows are ready for new input.
    for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
        const TouchedWindow& touchedWindow = mTempTouchState.windows[i];
        if (touchedWindow.targetFlags & InputTarget::FLAG_FOREGROUND) {
            // If the touched window is paused then keep waiting.
            if (touchedWindow.window->paused) {
#if DEBUG_FOCUS
                LOGD("Waiting because touched window is paused.");
#endif
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        NULL, touchedWindow.window, nextWakeupTime);
                goto Unresponsive;
            }

            // If the touched window is still working on previous events then keep waiting.
            if (! isWindowFinishedWithPreviousInputLocked(touchedWindow.window)) {
#if DEBUG_FOCUS
                LOGD("Waiting because touched window still processing previous input.");
#endif
                injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                        NULL, touchedWindow.window, nextWakeupTime);
                goto Unresponsive;
            }
        }
    }

    // If this is the first pointer going down and the touched window has a wallpaper
    // then also add the touched wallpaper windows so they are locked in for the duration
    // of the touch gesture.
    // We do not collect wallpapers during HOVER_MOVE or SCROLL because the wallpaper
    // engine only supports touch events.  We would need to add a mechanism similar
    // to View.onGenericMotionEvent to enable wallpapers to handle these events.
    if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
        const InputWindow* foregroundWindow = mTempTouchState.getFirstForegroundWindow();
        if (foregroundWindow->hasWallpaper) {
            for (size_t i = 0; i < mWindows.size(); i++) {
                const InputWindow* window = & mWindows[i];
                if (window->layoutParamsType == InputWindow::TYPE_WALLPAPER) {
                    mTempTouchState.addOrUpdateWindow(window,
                            InputTarget::FLAG_WINDOW_IS_OBSCURED
                                    | InputTarget::FLAG_DISPATCH_AS_IS,
                            BitSet32(0));
                }
            }
        }
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;

    for (size_t i = 0; i < mTempTouchState.windows.size(); i++) {
        const TouchedWindow& touchedWindow = mTempTouchState.windows.itemAt(i);
        addWindowTargetLocked(touchedWindow.window, touchedWindow.targetFlags,
                touchedWindow.pointerIds);
    }

    // Drop the outside or hover touch windows since we will not care about them
    // in the next iteration.
    mTempTouchState.filterNonAsIsTouchWindows();

Failed:
    // Check injection permission once and for all.
    if (injectionPermission == INJECTION_PERMISSION_UNKNOWN) {
        if (checkInjectionPermission(NULL, entry->injectionState)) {
            injectionPermission = INJECTION_PERMISSION_GRANTED;
        } else {
            injectionPermission = INJECTION_PERMISSION_DENIED;
        }
    }

    // Update final pieces of touch state if the injector had permission.
    if (injectionPermission == INJECTION_PERMISSION_GRANTED) {
        if (!wrongDevice) {
            if (switchedDevice) {
#if DEBUG_FOCUS
                LOGD("Conflicting pointer actions: Switched to a different device.");
#endif
                *outConflictingPointerActions = true;
            }

            if (isHoverAction) {
                // Started hovering, therefore no longer down.
                if (mTouchState.down) {
#if DEBUG_FOCUS
                    LOGD("Conflicting pointer actions: Hover received while pointer was down.");
#endif
                    *outConflictingPointerActions = true;
                }
                mTouchState.reset();
                if (maskedAction == AMOTION_EVENT_ACTION_HOVER_ENTER
                        || maskedAction == AMOTION_EVENT_ACTION_HOVER_MOVE) {
                    mTouchState.deviceId = entry->deviceId;
                    mTouchState.source = entry->source;
                }
            } else if (maskedAction == AMOTION_EVENT_ACTION_UP
                    || maskedAction == AMOTION_EVENT_ACTION_CANCEL) {
                // All pointers up or canceled.
                mTouchState.reset();
            } else if (maskedAction == AMOTION_EVENT_ACTION_DOWN) {
                // First pointer went down.
                if (mTouchState.down) {
#if DEBUG_FOCUS
                    LOGD("Conflicting pointer actions: Down received while already down.");
#endif
                    *outConflictingPointerActions = true;
                }
                mTouchState.copyFrom(mTempTouchState);
            } else if (maskedAction == AMOTION_EVENT_ACTION_POINTER_UP) {
                // One pointer went up.
                if (isSplit) {
                    int32_t pointerIndex = getMotionEventActionPointerIndex(action);
                    uint32_t pointerId = entry->pointerProperties[pointerIndex].id;

                    for (size_t i = 0; i < mTempTouchState.windows.size(); ) {
                        TouchedWindow& touchedWindow = mTempTouchState.windows.editItemAt(i);
                        if (touchedWindow.targetFlags & InputTarget::FLAG_SPLIT) {
                            touchedWindow.pointerIds.clearBit(pointerId);
                            if (touchedWindow.pointerIds.isEmpty()) {
                                mTempTouchState.windows.removeAt(i);
                                continue;
                            }
                        }
                        i += 1;
                    }
                }
                mTouchState.copyFrom(mTempTouchState);
            } else if (maskedAction == AMOTION_EVENT_ACTION_SCROLL) {
                // Discard temporary touch state since it was only valid for this action.
            } else {
                // Save changes to touch state as-is for all other actions.
                mTouchState.copyFrom(mTempTouchState);
            }

            // Update hover state.
            mLastHoverWindow = newHoverWindow;
        }
    } else {
#if DEBUG_FOCUS
        LOGD("Not updating touch focus because injection was denied.");
#endif
    }

Unresponsive:
    // Reset temporary touch state to ensure we release unnecessary references to input channels.
    mTempTouchState.reset();

    nsecs_t timeSpentWaitingForApplication = getTimeSpentWaitingForApplicationLocked(currentTime);
    updateDispatchStatisticsLocked(currentTime, entry,
            injectionResult, timeSpentWaitingForApplication);
#if DEBUG_FOCUS
    LOGD("findTouchedWindow finished: injectionResult=%d, injectionPermission=%d, "
            "timeSpentWaitingForApplication=%0.1fms",
            injectionResult, injectionPermission, timeSpentWaitingForApplication / 1000000.0);
#endif
    return injectionResult;
}

void InputDispatcher::addWindowTargetLocked(const InputWindow* window, int32_t targetFlags,
        BitSet32 pointerIds) {
    mCurrentInputTargets.push();

    InputTarget& target = mCurrentInputTargets.editTop();
    target.inputChannel = window->inputChannel;
    target.flags = targetFlags;
    target.xOffset = - window->frameLeft;
    target.yOffset = - window->frameTop;
    target.scaleFactor = window->scaleFactor;
    target.pointerIds = pointerIds;
}

void InputDispatcher::addMonitoringTargetsLocked() {
    for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
        mCurrentInputTargets.push();

        InputTarget& target = mCurrentInputTargets.editTop();
        target.inputChannel = mMonitoringChannels[i];
        target.flags = InputTarget::FLAG_DISPATCH_AS_IS;
        target.xOffset = 0;
        target.yOffset = 0;
        target.pointerIds.clear();
        target.scaleFactor = 1.0f;
    }
}

bool InputDispatcher::checkInjectionPermission(const InputWindow* window,
        const InjectionState* injectionState) {
    if (injectionState
            && (window == NULL || window->ownerUid != injectionState->injectorUid)
            && !hasInjectionPermission(injectionState->injectorPid, injectionState->injectorUid)) {
        if (window) {
            LOGW("Permission denied: injecting event from pid %d uid %d to window "
                    "with input channel %s owned by uid %d",
                    injectionState->injectorPid, injectionState->injectorUid,
                    window->inputChannel->getName().string(),
                    window->ownerUid);
        } else {
            LOGW("Permission denied: injecting event from pid %d uid %d",
                    injectionState->injectorPid, injectionState->injectorUid);
        }
        return false;
    }
    return true;
}

bool InputDispatcher::isWindowObscuredAtPointLocked(
        const InputWindow* window, int32_t x, int32_t y) const {
    size_t numWindows = mWindows.size();
    for (size_t i = 0; i < numWindows; i++) {
        const InputWindow* other = & mWindows.itemAt(i);
        if (other == window) {
            break;
        }
        if (other->visible && ! other->isTrustedOverlay() && other->frameContainsPoint(x, y)) {
            return true;
        }
    }
    return false;
}

bool InputDispatcher::isWindowFinishedWithPreviousInputLocked(const InputWindow* window) {
    ssize_t connectionIndex = getConnectionIndexLocked(window->inputChannel);
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        return connection->outboundQueue.isEmpty();
    } else {
        return true;
    }
}

String8 InputDispatcher::getApplicationWindowLabelLocked(const InputApplication* application,
        const InputWindow* window) {
    if (application) {
        if (window) {
            String8 label(application->name);
            label.append(" - ");
            label.append(window->name);
            return label;
        } else {
            return application->name;
        }
    } else if (window) {
        return window->name;
    } else {
        return String8("<unknown application or window>");
    }
}

void InputDispatcher::pokeUserActivityLocked(const EventEntry* eventEntry) {
    int32_t eventType = POWER_MANAGER_OTHER_EVENT;
    switch (eventEntry->type) {
    case EventEntry::TYPE_MOTION: {
        const MotionEntry* motionEntry = static_cast<const MotionEntry*>(eventEntry);
        if (motionEntry->action == AMOTION_EVENT_ACTION_CANCEL) {
            return;
        }

        if (MotionEvent::isTouchEvent(motionEntry->source, motionEntry->action)) {
            eventType = POWER_MANAGER_TOUCH_EVENT;
        }
        break;
    }
    case EventEntry::TYPE_KEY: {
        const KeyEntry* keyEntry = static_cast<const KeyEntry*>(eventEntry);
        if (keyEntry->flags & AKEY_EVENT_FLAG_CANCELED) {
            return;
        }
        eventType = POWER_MANAGER_BUTTON_EVENT;
        break;
    }
    }

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doPokeUserActivityLockedInterruptible);
    commandEntry->eventTime = eventEntry->eventTime;
    commandEntry->userActivityEventType = eventType;
}

void InputDispatcher::prepareDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget,
        bool resumeWithAppendedMotionSample) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ prepareDispatchCycle - flags=%d, "
            "xOffset=%f, yOffset=%f, scaleFactor=%f"
            "pointerIds=0x%x, "
            "resumeWithAppendedMotionSample=%s",
            connection->getInputChannelName(), inputTarget->flags,
            inputTarget->xOffset, inputTarget->yOffset,
            inputTarget->scaleFactor, inputTarget->pointerIds.value,
            toString(resumeWithAppendedMotionSample));
#endif

    // Make sure we are never called for streaming when splitting across multiple windows.
    bool isSplit = inputTarget->flags & InputTarget::FLAG_SPLIT;
    LOG_ASSERT(! (resumeWithAppendedMotionSample && isSplit));

    // Skip this event if the connection status is not normal.
    // We don't want to enqueue additional outbound events if the connection is broken.
    if (connection->status != Connection::STATUS_NORMAL) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Dropping event because the channel status is %s",
                connection->getInputChannelName(), connection->getStatusLabel());
#endif
        return;
    }

    // Split a motion event if needed.
    if (isSplit) {
        LOG_ASSERT(eventEntry->type == EventEntry::TYPE_MOTION);

        MotionEntry* originalMotionEntry = static_cast<MotionEntry*>(eventEntry);
        if (inputTarget->pointerIds.count() != originalMotionEntry->pointerCount) {
            MotionEntry* splitMotionEntry = splitMotionEvent(
                    originalMotionEntry, inputTarget->pointerIds);
            if (!splitMotionEntry) {
                return; // split event was dropped
            }
#if DEBUG_FOCUS
            LOGD("channel '%s' ~ Split motion event.",
                    connection->getInputChannelName());
            logOutboundMotionDetailsLocked("  ", splitMotionEntry);
#endif
            eventEntry = splitMotionEntry;
        }
    }

    // Resume the dispatch cycle with a freshly appended motion sample.
    // First we check that the last dispatch entry in the outbound queue is for the same
    // motion event to which we appended the motion sample.  If we find such a dispatch
    // entry, and if it is currently in progress then we try to stream the new sample.
    bool wasEmpty = connection->outboundQueue.isEmpty();

    if (! wasEmpty && resumeWithAppendedMotionSample) {
        DispatchEntry* motionEventDispatchEntry =
                connection->findQueuedDispatchEntryForEvent(eventEntry);
        if (motionEventDispatchEntry) {
            // If the dispatch entry is not in progress, then we must be busy dispatching an
            // earlier event.  Not a problem, the motion event is on the outbound queue and will
            // be dispatched later.
            if (! motionEventDispatchEntry->inProgress) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Not streaming because the motion event has "
                        "not yet been dispatched.  "
                        "(Waiting for earlier events to be consumed.)",
                        connection->getInputChannelName());
#endif
                return;
            }

            // If the dispatch entry is in progress but it already has a tail of pending
            // motion samples, then it must mean that the shared memory buffer filled up.
            // Not a problem, when this dispatch cycle is finished, we will eventually start
            // a new dispatch cycle to process the tail and that tail includes the newly
            // appended motion sample.
            if (motionEventDispatchEntry->tailMotionSample) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Not streaming because no new samples can "
                        "be appended to the motion event in this dispatch cycle.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
#endif
                return;
            }

            // If the motion event was modified in flight, then we cannot stream the sample.
            if ((motionEventDispatchEntry->targetFlags & InputTarget::FLAG_DISPATCH_MASK)
                    != InputTarget::FLAG_DISPATCH_AS_IS) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Not streaming because the motion event was not "
                        "being dispatched as-is.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
#endif
                return;
            }

            // The dispatch entry is in progress and is still potentially open for streaming.
            // Try to stream the new motion sample.  This might fail if the consumer has already
            // consumed the motion event (or if the channel is broken).
            MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);
            MotionSample* appendedMotionSample = motionEntry->lastSample;
            status_t status;
            if (motionEventDispatchEntry->scaleFactor == 1.0f) {
                status = connection->inputPublisher.appendMotionSample(
                        appendedMotionSample->eventTime, appendedMotionSample->pointerCoords);
            } else {
                PointerCoords scaledCoords[MAX_POINTERS];
                for (size_t i = 0; i < motionEntry->pointerCount; i++) {
                    scaledCoords[i] = appendedMotionSample->pointerCoords[i];
                    scaledCoords[i].scale(motionEventDispatchEntry->scaleFactor);
                }
                status = connection->inputPublisher.appendMotionSample(
                        appendedMotionSample->eventTime, scaledCoords);
            }
            if (status == OK) {
#if DEBUG_BATCHING
                LOGD("channel '%s' ~ Successfully streamed new motion sample.",
                        connection->getInputChannelName());
#endif
                return;
            }

#if DEBUG_BATCHING
            if (status == NO_MEMORY) {
                LOGD("channel '%s' ~ Could not append motion sample to currently "
                        "dispatched move event because the shared memory buffer is full.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
            } else if (status == status_t(FAILED_TRANSACTION)) {
                LOGD("channel '%s' ~ Could not append motion sample to currently "
                        "dispatched move event because the event has already been consumed.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName());
            } else {
                LOGD("channel '%s' ~ Could not append motion sample to currently "
                        "dispatched move event due to an error, status=%d.  "
                        "(Waiting for next dispatch cycle to start.)",
                        connection->getInputChannelName(), status);
            }
#endif
            // Failed to stream.  Start a new tail of pending motion samples to dispatch
            // in the next cycle.
            motionEventDispatchEntry->tailMotionSample = appendedMotionSample;
            return;
        }
    }

    // Enqueue dispatch entries for the requested modes.
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            resumeWithAppendedMotionSample, InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            resumeWithAppendedMotionSample, InputTarget::FLAG_DISPATCH_AS_OUTSIDE);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            resumeWithAppendedMotionSample, InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            resumeWithAppendedMotionSample, InputTarget::FLAG_DISPATCH_AS_IS);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            resumeWithAppendedMotionSample, InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT);
    enqueueDispatchEntryLocked(connection, eventEntry, inputTarget,
            resumeWithAppendedMotionSample, InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER);

    // If the outbound queue was previously empty, start the dispatch cycle going.
    if (wasEmpty && !connection->outboundQueue.isEmpty()) {
        activateConnectionLocked(connection.get());
        startDispatchCycleLocked(currentTime, connection);
    }
}

void InputDispatcher::enqueueDispatchEntryLocked(
        const sp<Connection>& connection, EventEntry* eventEntry, const InputTarget* inputTarget,
        bool resumeWithAppendedMotionSample, int32_t dispatchMode) {
    int32_t inputTargetFlags = inputTarget->flags;
    if (!(inputTargetFlags & dispatchMode)) {
        return;
    }
    inputTargetFlags = (inputTargetFlags & ~InputTarget::FLAG_DISPATCH_MASK) | dispatchMode;

    // This is a new event.
    // Enqueue a new dispatch entry onto the outbound queue for this connection.
    DispatchEntry* dispatchEntry = mAllocator.obtainDispatchEntry(eventEntry, // increments ref
            inputTargetFlags, inputTarget->xOffset, inputTarget->yOffset,
            inputTarget->scaleFactor);
    if (dispatchEntry->hasForegroundTarget()) {
        incrementPendingForegroundDispatchesLocked(eventEntry);
    }

    // Handle the case where we could not stream a new motion sample because the consumer has
    // already consumed the motion event (otherwise the corresponding dispatch entry would
    // still be in the outbound queue for this connection).  We set the head motion sample
    // to the list starting with the newly appended motion sample.
    if (resumeWithAppendedMotionSample) {
#if DEBUG_BATCHING
        LOGD("channel '%s' ~ Preparing a new dispatch cycle for additional motion samples "
                "that cannot be streamed because the motion event has already been consumed.",
                connection->getInputChannelName());
#endif
        MotionSample* appendedMotionSample = static_cast<MotionEntry*>(eventEntry)->lastSample;
        dispatchEntry->headMotionSample = appendedMotionSample;
    }

    // Apply target flags and update the connection's input state.
    switch (eventEntry->type) {
    case EventEntry::TYPE_KEY: {
        KeyEntry* keyEntry = static_cast<KeyEntry*>(eventEntry);
        dispatchEntry->resolvedAction = keyEntry->action;
        dispatchEntry->resolvedFlags = keyEntry->flags;

        if (!connection->inputState.trackKey(keyEntry,
                dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags)) {
#if DEBUG_DISPATCH_CYCLE
            LOGD("channel '%s' ~ enqueueDispatchEntryLocked: skipping inconsistent key event",
                    connection->getInputChannelName());
#endif
            return; // skip the inconsistent event
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);
        if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_OUTSIDE) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_OUTSIDE;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_HOVER_EXIT) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_EXIT;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_HOVER_ENTER) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_ENTER;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_CANCEL;
        } else if (dispatchMode & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER) {
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_DOWN;
        } else {
            dispatchEntry->resolvedAction = motionEntry->action;
        }
        if (dispatchEntry->resolvedAction == AMOTION_EVENT_ACTION_HOVER_MOVE
                && !connection->inputState.isHovering(
                        motionEntry->deviceId, motionEntry->source)) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ enqueueDispatchEntryLocked: filling in missing hover enter event",
                connection->getInputChannelName());
#endif
            dispatchEntry->resolvedAction = AMOTION_EVENT_ACTION_HOVER_ENTER;
        }

        dispatchEntry->resolvedFlags = motionEntry->flags;
        if (dispatchEntry->targetFlags & InputTarget::FLAG_WINDOW_IS_OBSCURED) {
            dispatchEntry->resolvedFlags |= AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED;
        }

        if (!connection->inputState.trackMotion(motionEntry,
                dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags)) {
#if DEBUG_DISPATCH_CYCLE
            LOGD("channel '%s' ~ enqueueDispatchEntryLocked: skipping inconsistent motion event",
                    connection->getInputChannelName());
#endif
            return; // skip the inconsistent event
        }
        break;
    }
    }

    // Enqueue the dispatch entry.
    connection->outboundQueue.enqueueAtTail(dispatchEntry);
}

void InputDispatcher::startDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ startDispatchCycle",
            connection->getInputChannelName());
#endif

    LOG_ASSERT(connection->status == Connection::STATUS_NORMAL);
    LOG_ASSERT(! connection->outboundQueue.isEmpty());

    DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
    LOG_ASSERT(! dispatchEntry->inProgress);

    // Mark the dispatch entry as in progress.
    dispatchEntry->inProgress = true;

    // Publish the event.
    status_t status;
    EventEntry* eventEntry = dispatchEntry->eventEntry;
    switch (eventEntry->type) {
    case EventEntry::TYPE_KEY: {
        KeyEntry* keyEntry = static_cast<KeyEntry*>(eventEntry);

        // Publish the key event.
        status = connection->inputPublisher.publishKeyEvent(
                keyEntry->deviceId, keyEntry->source,
                dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags,
                keyEntry->keyCode, keyEntry->scanCode,
                keyEntry->metaState, keyEntry->repeatCount, keyEntry->downTime,
                keyEntry->eventTime);

        if (status) {
            LOGE("channel '%s' ~ Could not publish key event, "
                    "status=%d", connection->getInputChannelName(), status);
            abortBrokenDispatchCycleLocked(currentTime, connection);
            return;
        }
        break;
    }

    case EventEntry::TYPE_MOTION: {
        MotionEntry* motionEntry = static_cast<MotionEntry*>(eventEntry);

        // If headMotionSample is non-NULL, then it points to the first new sample that we
        // were unable to dispatch during the previous cycle so we resume dispatching from
        // that point in the list of motion samples.
        // Otherwise, we just start from the first sample of the motion event.
        MotionSample* firstMotionSample = dispatchEntry->headMotionSample;
        if (! firstMotionSample) {
            firstMotionSample = & motionEntry->firstSample;
        }

        PointerCoords scaledCoords[MAX_POINTERS];
        const PointerCoords* usingCoords = firstMotionSample->pointerCoords;

        // Set the X and Y offset depending on the input source.
        float xOffset, yOffset, scaleFactor;
        if (motionEntry->source & AINPUT_SOURCE_CLASS_POINTER
                && !(dispatchEntry->targetFlags & InputTarget::FLAG_ZERO_COORDS)) {
            scaleFactor = dispatchEntry->scaleFactor;
            xOffset = dispatchEntry->xOffset * scaleFactor;
            yOffset = dispatchEntry->yOffset * scaleFactor;
            if (scaleFactor != 1.0f) {
                for (size_t i = 0; i < motionEntry->pointerCount; i++) {
                    scaledCoords[i] = firstMotionSample->pointerCoords[i];
                    scaledCoords[i].scale(scaleFactor);
                }
                usingCoords = scaledCoords;
            }
        } else {
            xOffset = 0.0f;
            yOffset = 0.0f;
            scaleFactor = 1.0f;

            // We don't want the dispatch target to know.
            if (dispatchEntry->targetFlags & InputTarget::FLAG_ZERO_COORDS) {
                for (size_t i = 0; i < motionEntry->pointerCount; i++) {
                    scaledCoords[i].clear();
                }
                usingCoords = scaledCoords;
            }
        }

        // Publish the motion event and the first motion sample.
        status = connection->inputPublisher.publishMotionEvent(
                motionEntry->deviceId, motionEntry->source,
                dispatchEntry->resolvedAction, dispatchEntry->resolvedFlags,
                motionEntry->edgeFlags, motionEntry->metaState, motionEntry->buttonState,
                xOffset, yOffset,
                motionEntry->xPrecision, motionEntry->yPrecision,
                motionEntry->downTime, firstMotionSample->eventTime,
                motionEntry->pointerCount, motionEntry->pointerProperties,
                usingCoords);

        if (status) {
            LOGE("channel '%s' ~ Could not publish motion event, "
                    "status=%d", connection->getInputChannelName(), status);
            abortBrokenDispatchCycleLocked(currentTime, connection);
            return;
        }

        if (dispatchEntry->resolvedAction == AMOTION_EVENT_ACTION_MOVE
                || dispatchEntry->resolvedAction == AMOTION_EVENT_ACTION_HOVER_MOVE) {
            // Append additional motion samples.
            MotionSample* nextMotionSample = firstMotionSample->next;
            for (; nextMotionSample != NULL; nextMotionSample = nextMotionSample->next) {
                if (usingCoords == scaledCoords) {
                    if (!(dispatchEntry->targetFlags & InputTarget::FLAG_ZERO_COORDS)) {
                        for (size_t i = 0; i < motionEntry->pointerCount; i++) {
                            scaledCoords[i] = nextMotionSample->pointerCoords[i];
                            scaledCoords[i].scale(scaleFactor);
                        }
                    }
                } else {
                    usingCoords = nextMotionSample->pointerCoords;
                }
                status = connection->inputPublisher.appendMotionSample(
                        nextMotionSample->eventTime, usingCoords);
                if (status == NO_MEMORY) {
#if DEBUG_DISPATCH_CYCLE
                    LOGD("channel '%s' ~ Shared memory buffer full.  Some motion samples will "
                            "be sent in the next dispatch cycle.",
                            connection->getInputChannelName());
#endif
                    break;
                }
                if (status != OK) {
                    LOGE("channel '%s' ~ Could not append motion sample "
                            "for a reason other than out of memory, status=%d",
                            connection->getInputChannelName(), status);
                    abortBrokenDispatchCycleLocked(currentTime, connection);
                    return;
                }
            }

            // Remember the next motion sample that we could not dispatch, in case we ran out
            // of space in the shared memory buffer.
            dispatchEntry->tailMotionSample = nextMotionSample;
        }
        break;
    }

    default: {
        LOG_ASSERT(false);
    }
    }

    // Send the dispatch signal.
    status = connection->inputPublisher.sendDispatchSignal();
    if (status) {
        LOGE("channel '%s' ~ Could not send dispatch signal, status=%d",
                connection->getInputChannelName(), status);
        abortBrokenDispatchCycleLocked(currentTime, connection);
        return;
    }

    // Record information about the newly started dispatch cycle.
    connection->lastEventTime = eventEntry->eventTime;
    connection->lastDispatchTime = currentTime;

    // Notify other system components.
    onDispatchCycleStartedLocked(currentTime, connection);
}

void InputDispatcher::finishDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection, bool handled) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ finishDispatchCycle - %01.1fms since event, "
            "%01.1fms since dispatch, handled=%s",
            connection->getInputChannelName(),
            connection->getEventLatencyMillis(currentTime),
            connection->getDispatchLatencyMillis(currentTime),
            toString(handled));
#endif

    if (connection->status == Connection::STATUS_BROKEN
            || connection->status == Connection::STATUS_ZOMBIE) {
        return;
    }

    // Reset the publisher since the event has been consumed.
    // We do this now so that the publisher can release some of its internal resources
    // while waiting for the next dispatch cycle to begin.
    status_t status = connection->inputPublisher.reset();
    if (status) {
        LOGE("channel '%s' ~ Could not reset publisher, status=%d",
                connection->getInputChannelName(), status);
        abortBrokenDispatchCycleLocked(currentTime, connection);
        return;
    }

    // Notify other system components and prepare to start the next dispatch cycle.
    onDispatchCycleFinishedLocked(currentTime, connection, handled);
}

void InputDispatcher::startNextDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
    // Start the next dispatch cycle for this connection.
    while (! connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
        if (dispatchEntry->inProgress) {
             // Finish or resume current event in progress.
            if (dispatchEntry->tailMotionSample) {
                // We have a tail of undispatched motion samples.
                // Reuse the same DispatchEntry and start a new cycle.
                dispatchEntry->inProgress = false;
                dispatchEntry->headMotionSample = dispatchEntry->tailMotionSample;
                dispatchEntry->tailMotionSample = NULL;
                startDispatchCycleLocked(currentTime, connection);
                return;
            }
            // Finished.
            connection->outboundQueue.dequeueAtHead();
            if (dispatchEntry->hasForegroundTarget()) {
                decrementPendingForegroundDispatchesLocked(dispatchEntry->eventEntry);
            }
            mAllocator.releaseDispatchEntry(dispatchEntry);
        } else {
            // If the head is not in progress, then we must have already dequeued the in
            // progress event, which means we actually aborted it.
            // So just start the next event for this connection.
            startDispatchCycleLocked(currentTime, connection);
            return;
        }
    }

    // Outbound queue is empty, deactivate the connection.
    deactivateConnectionLocked(connection.get());
}

void InputDispatcher::abortBrokenDispatchCycleLocked(nsecs_t currentTime,
        const sp<Connection>& connection) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ abortBrokenDispatchCycle",
            connection->getInputChannelName());
#endif

    // Clear the outbound queue.
    drainOutboundQueueLocked(connection.get());

    // The connection appears to be unrecoverably broken.
    // Ignore already broken or zombie connections.
    if (connection->status == Connection::STATUS_NORMAL) {
        connection->status = Connection::STATUS_BROKEN;

        // Notify other system components.
        onDispatchCycleBrokenLocked(currentTime, connection);
    }
}

void InputDispatcher::drainOutboundQueueLocked(Connection* connection) {
    while (! connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.dequeueAtHead();
        if (dispatchEntry->hasForegroundTarget()) {
            decrementPendingForegroundDispatchesLocked(dispatchEntry->eventEntry);
        }
        mAllocator.releaseDispatchEntry(dispatchEntry);
    }

    deactivateConnectionLocked(connection);
}

int InputDispatcher::handleReceiveCallback(int receiveFd, int events, void* data) {
    InputDispatcher* d = static_cast<InputDispatcher*>(data);

    { // acquire lock
        AutoMutex _l(d->mLock);

        ssize_t connectionIndex = d->mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            LOGE("Received spurious receive callback for unknown input channel.  "
                    "fd=%d, events=0x%x", receiveFd, events);
            return 0; // remove the callback
        }

        nsecs_t currentTime = now();

        sp<Connection> connection = d->mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
            LOGE("channel '%s' ~ Consumer closed input channel or an error occurred.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            d->abortBrokenDispatchCycleLocked(currentTime, connection);
            d->runCommandsLockedInterruptible();
            return 0; // remove the callback
        }

        if (! (events & ALOOPER_EVENT_INPUT)) {
            LOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return 1;
        }

        bool handled = false;
        status_t status = connection->inputPublisher.receiveFinishedSignal(&handled);
        if (status) {
            LOGE("channel '%s' ~ Failed to receive finished signal.  status=%d",
                    connection->getInputChannelName(), status);
            d->abortBrokenDispatchCycleLocked(currentTime, connection);
            d->runCommandsLockedInterruptible();
            return 0; // remove the callback
        }

        d->finishDispatchCycleLocked(currentTime, connection, handled);
        d->runCommandsLockedInterruptible();
        return 1;
    } // release lock
}

void InputDispatcher::synthesizeCancelationEventsForAllConnectionsLocked(
        const CancelationOptions& options) {
    for (size_t i = 0; i < mConnectionsByReceiveFd.size(); i++) {
        synthesizeCancelationEventsForConnectionLocked(
                mConnectionsByReceiveFd.valueAt(i), options);
    }
}

void InputDispatcher::synthesizeCancelationEventsForInputChannelLocked(
        const sp<InputChannel>& channel, const CancelationOptions& options) {
    ssize_t index = getConnectionIndexLocked(channel);
    if (index >= 0) {
        synthesizeCancelationEventsForConnectionLocked(
                mConnectionsByReceiveFd.valueAt(index), options);
    }
}

void InputDispatcher::synthesizeCancelationEventsForConnectionLocked(
        const sp<Connection>& connection, const CancelationOptions& options) {
    nsecs_t currentTime = now();

    mTempCancelationEvents.clear();
    connection->inputState.synthesizeCancelationEvents(currentTime, & mAllocator,
            mTempCancelationEvents, options);

    if (! mTempCancelationEvents.isEmpty()
            && connection->status != Connection::STATUS_BROKEN) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
        LOGD("channel '%s' ~ Synthesized %d cancelation events to bring channel back in sync "
                "with reality: %s, mode=%d.",
                connection->getInputChannelName(), mTempCancelationEvents.size(),
                options.reason, options.mode);
#endif
        for (size_t i = 0; i < mTempCancelationEvents.size(); i++) {
            EventEntry* cancelationEventEntry = mTempCancelationEvents.itemAt(i);
            switch (cancelationEventEntry->type) {
            case EventEntry::TYPE_KEY:
                logOutboundKeyDetailsLocked("cancel - ",
                        static_cast<KeyEntry*>(cancelationEventEntry));
                break;
            case EventEntry::TYPE_MOTION:
                logOutboundMotionDetailsLocked("cancel - ",
                        static_cast<MotionEntry*>(cancelationEventEntry));
                break;
            }

            InputTarget target;
            const InputWindow* window = getWindowLocked(connection->inputChannel);
            if (window) {
                target.xOffset = -window->frameLeft;
                target.yOffset = -window->frameTop;
                target.scaleFactor = window->scaleFactor;
            } else {
                target.xOffset = 0;
                target.yOffset = 0;
                target.scaleFactor = 1.0f;
            }
            target.inputChannel = connection->inputChannel;
            target.flags = InputTarget::FLAG_DISPATCH_AS_IS;

            enqueueDispatchEntryLocked(connection, cancelationEventEntry, // increments ref
                    &target, false, InputTarget::FLAG_DISPATCH_AS_IS);

            mAllocator.releaseEventEntry(cancelationEventEntry);
        }

        if (!connection->outboundQueue.headSentinel.next->inProgress) {
            startDispatchCycleLocked(currentTime, connection);
        }
    }
}

InputDispatcher::MotionEntry*
InputDispatcher::splitMotionEvent(const MotionEntry* originalMotionEntry, BitSet32 pointerIds) {
    LOG_ASSERT(pointerIds.value != 0);

    uint32_t splitPointerIndexMap[MAX_POINTERS];
    PointerProperties splitPointerProperties[MAX_POINTERS];
    PointerCoords splitPointerCoords[MAX_POINTERS];

    uint32_t originalPointerCount = originalMotionEntry->pointerCount;
    uint32_t splitPointerCount = 0;

    for (uint32_t originalPointerIndex = 0; originalPointerIndex < originalPointerCount;
            originalPointerIndex++) {
        const PointerProperties& pointerProperties =
                originalMotionEntry->pointerProperties[originalPointerIndex];
        uint32_t pointerId = uint32_t(pointerProperties.id);
        if (pointerIds.hasBit(pointerId)) {
            splitPointerIndexMap[splitPointerCount] = originalPointerIndex;
            splitPointerProperties[splitPointerCount].copyFrom(pointerProperties);
            splitPointerCoords[splitPointerCount].copyFrom(
                    originalMotionEntry->firstSample.pointerCoords[originalPointerIndex]);
            splitPointerCount += 1;
        }
    }

    if (splitPointerCount != pointerIds.count()) {
        // This is bad.  We are missing some of the pointers that we expected to deliver.
        // Most likely this indicates that we received an ACTION_MOVE events that has
        // different pointer ids than we expected based on the previous ACTION_DOWN
        // or ACTION_POINTER_DOWN events that caused us to decide to split the pointers
        // in this way.
        LOGW("Dropping split motion event because the pointer count is %d but "
                "we expected there to be %d pointers.  This probably means we received "
                "a broken sequence of pointer ids from the input device.",
                splitPointerCount, pointerIds.count());
        return NULL;
    }

    int32_t action = originalMotionEntry->action;
    int32_t maskedAction = action & AMOTION_EVENT_ACTION_MASK;
    if (maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN
            || maskedAction == AMOTION_EVENT_ACTION_POINTER_UP) {
        int32_t originalPointerIndex = getMotionEventActionPointerIndex(action);
        const PointerProperties& pointerProperties =
                originalMotionEntry->pointerProperties[originalPointerIndex];
        uint32_t pointerId = uint32_t(pointerProperties.id);
        if (pointerIds.hasBit(pointerId)) {
            if (pointerIds.count() == 1) {
                // The first/last pointer went down/up.
                action = maskedAction == AMOTION_EVENT_ACTION_POINTER_DOWN
                        ? AMOTION_EVENT_ACTION_DOWN : AMOTION_EVENT_ACTION_UP;
            } else {
                // A secondary pointer went down/up.
                uint32_t splitPointerIndex = 0;
                while (pointerId != uint32_t(splitPointerProperties[splitPointerIndex].id)) {
                    splitPointerIndex += 1;
                }
                action = maskedAction | (splitPointerIndex
                        << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT);
            }
        } else {
            // An unrelated pointer changed.
            action = AMOTION_EVENT_ACTION_MOVE;
        }
    }

    MotionEntry* splitMotionEntry = mAllocator.obtainMotionEntry(
            originalMotionEntry->eventTime,
            originalMotionEntry->deviceId,
            originalMotionEntry->source,
            originalMotionEntry->policyFlags,
            action,
            originalMotionEntry->flags,
            originalMotionEntry->metaState,
            originalMotionEntry->buttonState,
            originalMotionEntry->edgeFlags,
            originalMotionEntry->xPrecision,
            originalMotionEntry->yPrecision,
            originalMotionEntry->downTime,
            splitPointerCount, splitPointerProperties, splitPointerCoords);

    for (MotionSample* originalMotionSample = originalMotionEntry->firstSample.next;
            originalMotionSample != NULL; originalMotionSample = originalMotionSample->next) {
        for (uint32_t splitPointerIndex = 0; splitPointerIndex < splitPointerCount;
                splitPointerIndex++) {
            uint32_t originalPointerIndex = splitPointerIndexMap[splitPointerIndex];
            splitPointerCoords[splitPointerIndex].copyFrom(
                    originalMotionSample->pointerCoords[originalPointerIndex]);
        }

        mAllocator.appendMotionSample(splitMotionEntry, originalMotionSample->eventTime,
                splitPointerCoords);
    }

    if (originalMotionEntry->injectionState) {
        splitMotionEntry->injectionState = originalMotionEntry->injectionState;
        splitMotionEntry->injectionState->refCount += 1;
    }

    return splitMotionEntry;
}

void InputDispatcher::notifyConfigurationChanged(nsecs_t eventTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyConfigurationChanged - eventTime=%lld", eventTime);
#endif

    bool needWake;
    { // acquire lock
        AutoMutex _l(mLock);

        ConfigurationChangedEntry* newEntry = mAllocator.obtainConfigurationChangedEntry(eventTime);
        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::notifyKey(nsecs_t eventTime, int32_t deviceId, uint32_t source,
        uint32_t policyFlags, int32_t action, int32_t flags,
        int32_t keyCode, int32_t scanCode, int32_t metaState, nsecs_t downTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyKey - eventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, action=0x%x, "
            "flags=0x%x, keyCode=0x%x, scanCode=0x%x, metaState=0x%x, downTime=%lld",
            eventTime, deviceId, source, policyFlags, action, flags,
            keyCode, scanCode, metaState, downTime);
#endif
    if (! validateKeyEvent(action)) {
        return;
    }

    if ((policyFlags & POLICY_FLAG_VIRTUAL) || (flags & AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY)) {
        policyFlags |= POLICY_FLAG_VIRTUAL;
        flags |= AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY;
    }
    if (policyFlags & POLICY_FLAG_ALT) {
        metaState |= AMETA_ALT_ON | AMETA_ALT_LEFT_ON;
    }
    if (policyFlags & POLICY_FLAG_ALT_GR) {
        metaState |= AMETA_ALT_ON | AMETA_ALT_RIGHT_ON;
    }
    if (policyFlags & POLICY_FLAG_SHIFT) {
        metaState |= AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON;
    }
    if (policyFlags & POLICY_FLAG_CAPS_LOCK) {
        metaState |= AMETA_CAPS_LOCK_ON;
    }
    if (policyFlags & POLICY_FLAG_FUNCTION) {
        metaState |= AMETA_FUNCTION_ON;
    }

    policyFlags |= POLICY_FLAG_TRUSTED;

    KeyEvent event;
    event.initialize(deviceId, source, action, flags, keyCode, scanCode,
            metaState, 0, downTime, eventTime);

    mPolicy->interceptKeyBeforeQueueing(&event, /*byref*/ policyFlags);

    if (policyFlags & POLICY_FLAG_WOKE_HERE) {
        flags |= AKEY_EVENT_FLAG_WOKE_HERE;
    }

    bool needWake;
    { // acquire lock
        mLock.lock();

        if (mInputFilterEnabled) {
            mLock.unlock();

            policyFlags |= POLICY_FLAG_FILTERED;
            if (!mPolicy->filterInputEvent(&event, policyFlags)) {
                return; // event was consumed by the filter
            }

            mLock.lock();
        }

        int32_t repeatCount = 0;
        KeyEntry* newEntry = mAllocator.obtainKeyEntry(eventTime,
                deviceId, source, policyFlags, action, flags, keyCode, scanCode,
                metaState, repeatCount, downTime);

        needWake = enqueueInboundEventLocked(newEntry);
        mLock.unlock();
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::notifyMotion(nsecs_t eventTime, int32_t deviceId, uint32_t source,
        uint32_t policyFlags, int32_t action, int32_t flags,
        int32_t metaState, int32_t buttonState, int32_t edgeFlags,
        uint32_t pointerCount, const PointerProperties* pointerProperties,
        const PointerCoords* pointerCoords,
        float xPrecision, float yPrecision, nsecs_t downTime) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifyMotion - eventTime=%lld, deviceId=%d, source=0x%x, policyFlags=0x%x, "
            "action=0x%x, flags=0x%x, metaState=0x%x, buttonState=0x%x, edgeFlags=0x%x, "
            "xPrecision=%f, yPrecision=%f, downTime=%lld",
            eventTime, deviceId, source, policyFlags, action, flags,
            metaState, buttonState, edgeFlags,
            xPrecision, yPrecision, downTime);
    for (uint32_t i = 0; i < pointerCount; i++) {
        LOGD("  Pointer %d: id=%d, toolType=%d, "
                "x=%f, y=%f, pressure=%f, size=%f, "
                "touchMajor=%f, touchMinor=%f, toolMajor=%f, toolMinor=%f, "
                "orientation=%f",
                i, pointerProperties[i].id,
                pointerProperties[i].toolType,
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_X),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_Y),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_PRESSURE),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_SIZE),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR),
                pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION));
    }
#endif
    if (! validateMotionEvent(action, pointerCount, pointerProperties)) {
        return;
    }

    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->interceptMotionBeforeQueueing(eventTime, /*byref*/ policyFlags);

    bool needWake;
    { // acquire lock
        mLock.lock();

        if (mInputFilterEnabled) {
            mLock.unlock();

            MotionEvent event;
            event.initialize(deviceId, source, action, flags, edgeFlags, metaState,
                    buttonState, 0, 0,
                    xPrecision, yPrecision, downTime, eventTime,
                    pointerCount, pointerProperties, pointerCoords);

            policyFlags |= POLICY_FLAG_FILTERED;
            if (!mPolicy->filterInputEvent(&event, policyFlags)) {
                return; // event was consumed by the filter
            }

            mLock.lock();
        }

        // Attempt batching and streaming of move events.
        if (action == AMOTION_EVENT_ACTION_MOVE
                || action == AMOTION_EVENT_ACTION_HOVER_MOVE) {
            // BATCHING CASE
            //
            // Try to append a move sample to the tail of the inbound queue for this device.
            // Give up if we encounter a non-move motion event for this device since that
            // means we cannot append any new samples until a new motion event has started.
            for (EventEntry* entry = mInboundQueue.tailSentinel.prev;
                    entry != & mInboundQueue.headSentinel; entry = entry->prev) {
                if (entry->type != EventEntry::TYPE_MOTION) {
                    // Keep looking for motion events.
                    continue;
                }

                MotionEntry* motionEntry = static_cast<MotionEntry*>(entry);
                if (motionEntry->deviceId != deviceId
                        || motionEntry->source != source) {
                    // Keep looking for this device and source.
                    continue;
                }

                if (!motionEntry->canAppendSamples(action, pointerCount, pointerProperties)) {
                    // Last motion event in the queue for this device and source is
                    // not compatible for appending new samples.  Stop here.
                    goto NoBatchingOrStreaming;
                }

                // Do the batching magic.
                batchMotionLocked(motionEntry, eventTime, metaState, pointerCoords,
                        "most recent motion event for this device and source in the inbound queue");
                mLock.unlock();
                return; // done!
            }

            // BATCHING ONTO PENDING EVENT CASE
            //
            // Try to append a move sample to the currently pending event, if there is one.
            // We can do this as long as we are still waiting to find the targets for the
            // event.  Once the targets are locked-in we can only do streaming.
            if (mPendingEvent
                    && (!mPendingEvent->dispatchInProgress || !mCurrentInputTargetsValid)
                    && mPendingEvent->type == EventEntry::TYPE_MOTION) {
                MotionEntry* motionEntry = static_cast<MotionEntry*>(mPendingEvent);
                if (motionEntry->deviceId == deviceId && motionEntry->source == source) {
                    if (!motionEntry->canAppendSamples(action, pointerCount, pointerProperties)) {
                        // Pending motion event is for this device and source but it is
                        // not compatible for appending new samples.  Stop here.
                        goto NoBatchingOrStreaming;
                    }

                    // Do the batching magic.
                    batchMotionLocked(motionEntry, eventTime, metaState, pointerCoords,
                            "pending motion event");
                    mLock.unlock();
                    return; // done!
                }
            }

            // STREAMING CASE
            //
            // There is no pending motion event (of any kind) for this device in the inbound queue.
            // Search the outbound queue for the current foreground targets to find a dispatched
            // motion event that is still in progress.  If found, then, appen the new sample to
            // that event and push it out to all current targets.  The logic in
            // prepareDispatchCycleLocked takes care of the case where some targets may
            // already have consumed the motion event by starting a new dispatch cycle if needed.
            if (mCurrentInputTargetsValid) {
                for (size_t i = 0; i < mCurrentInputTargets.size(); i++) {
                    const InputTarget& inputTarget = mCurrentInputTargets[i];
                    if ((inputTarget.flags & InputTarget::FLAG_FOREGROUND) == 0) {
                        // Skip non-foreground targets.  We only want to stream if there is at
                        // least one foreground target whose dispatch is still in progress.
                        continue;
                    }

                    ssize_t connectionIndex = getConnectionIndexLocked(inputTarget.inputChannel);
                    if (connectionIndex < 0) {
                        // Connection must no longer be valid.
                        continue;
                    }

                    sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
                    if (connection->outboundQueue.isEmpty()) {
                        // This foreground target has an empty outbound queue.
                        continue;
                    }

                    DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
                    if (! dispatchEntry->inProgress
                            || dispatchEntry->eventEntry->type != EventEntry::TYPE_MOTION
                            || dispatchEntry->isSplit()) {
                        // No motion event is being dispatched, or it is being split across
                        // windows in which case we cannot stream.
                        continue;
                    }

                    MotionEntry* motionEntry = static_cast<MotionEntry*>(
                            dispatchEntry->eventEntry);
                    if (motionEntry->action != action
                            || motionEntry->deviceId != deviceId
                            || motionEntry->source != source
                            || motionEntry->pointerCount != pointerCount
                            || motionEntry->isInjected()) {
                        // The motion event is not compatible with this move.
                        continue;
                    }

                    if (action == AMOTION_EVENT_ACTION_HOVER_MOVE) {
                        if (!mLastHoverWindow) {
#if DEBUG_BATCHING
                            LOGD("Not streaming hover move because there is no "
                                    "last hovered window.");
#endif
                            goto NoBatchingOrStreaming;
                        }

                        const InputWindow* hoverWindow = findTouchedWindowAtLocked(
                                pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_X),
                                pointerCoords[0].getAxisValue(AMOTION_EVENT_AXIS_Y));
                        if (mLastHoverWindow != hoverWindow) {
#if DEBUG_BATCHING
                            LOGD("Not streaming hover move because the last hovered window "
                                    "is '%s' but the currently hovered window is '%s'.",
                                    mLastHoverWindow->name.string(),
                                    hoverWindow ? hoverWindow->name.string() : "<null>");
#endif
                            goto NoBatchingOrStreaming;
                        }
                    }

                    // Hurray!  This foreground target is currently dispatching a move event
                    // that we can stream onto.  Append the motion sample and resume dispatch.
                    mAllocator.appendMotionSample(motionEntry, eventTime, pointerCoords);
#if DEBUG_BATCHING
                    LOGD("Appended motion sample onto batch for most recently dispatched "
                            "motion event for this device and source in the outbound queues.  "
                            "Attempting to stream the motion sample.");
#endif
                    nsecs_t currentTime = now();
                    dispatchEventToCurrentInputTargetsLocked(currentTime, motionEntry,
                            true /*resumeWithAppendedMotionSample*/);

                    runCommandsLockedInterruptible();
                    mLock.unlock();
                    return; // done!
                }
            }

NoBatchingOrStreaming:;
        }

        // Just enqueue a new motion event.
        MotionEntry* newEntry = mAllocator.obtainMotionEntry(eventTime,
                deviceId, source, policyFlags, action, flags, metaState, buttonState, edgeFlags,
                xPrecision, yPrecision, downTime,
                pointerCount, pointerProperties, pointerCoords);

        needWake = enqueueInboundEventLocked(newEntry);
        mLock.unlock();
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}

void InputDispatcher::batchMotionLocked(MotionEntry* entry, nsecs_t eventTime,
        int32_t metaState, const PointerCoords* pointerCoords, const char* eventDescription) {
    // Combine meta states.
    entry->metaState |= metaState;

    // Coalesce this sample if not enough time has elapsed since the last sample was
    // initially appended to the batch.
    MotionSample* lastSample = entry->lastSample;
    long interval = eventTime - lastSample->eventTimeBeforeCoalescing;
    if (interval <= MOTION_SAMPLE_COALESCE_INTERVAL) {
        uint32_t pointerCount = entry->pointerCount;
        for (uint32_t i = 0; i < pointerCount; i++) {
            lastSample->pointerCoords[i].copyFrom(pointerCoords[i]);
        }
        lastSample->eventTime = eventTime;
#if DEBUG_BATCHING
        LOGD("Coalesced motion into last sample of batch for %s, events were %0.3f ms apart",
                eventDescription, interval * 0.000001f);
#endif
        return;
    }

    // Append the sample.
    mAllocator.appendMotionSample(entry, eventTime, pointerCoords);
#if DEBUG_BATCHING
    LOGD("Appended motion sample onto batch for %s, events were %0.3f ms apart",
            eventDescription, interval * 0.000001f);
#endif
}

void InputDispatcher::notifySwitch(nsecs_t when, int32_t switchCode, int32_t switchValue,
        uint32_t policyFlags) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("notifySwitch - switchCode=%d, switchValue=%d, policyFlags=0x%x",
            switchCode, switchValue, policyFlags);
#endif

    policyFlags |= POLICY_FLAG_TRUSTED;
    mPolicy->notifySwitch(when, switchCode, switchValue, policyFlags);
}

int32_t InputDispatcher::injectInputEvent(const InputEvent* event,
        int32_t injectorPid, int32_t injectorUid, int32_t syncMode, int32_t timeoutMillis,
        uint32_t policyFlags) {
#if DEBUG_INBOUND_EVENT_DETAILS
    LOGD("injectInputEvent - eventType=%d, injectorPid=%d, injectorUid=%d, "
            "syncMode=%d, timeoutMillis=%d, policyFlags=0x%08x",
            event->getType(), injectorPid, injectorUid, syncMode, timeoutMillis, policyFlags);
#endif

    nsecs_t endTime = now() + milliseconds_to_nanoseconds(timeoutMillis);

    policyFlags |= POLICY_FLAG_INJECTED;
    if (hasInjectionPermission(injectorPid, injectorUid)) {
        policyFlags |= POLICY_FLAG_TRUSTED;
    }

    EventEntry* injectedEntry;
    switch (event->getType()) {
    case AINPUT_EVENT_TYPE_KEY: {
        const KeyEvent* keyEvent = static_cast<const KeyEvent*>(event);
        int32_t action = keyEvent->getAction();
        if (! validateKeyEvent(action)) {
            return INPUT_EVENT_INJECTION_FAILED;
        }

        int32_t flags = keyEvent->getFlags();
        if (flags & AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY) {
            policyFlags |= POLICY_FLAG_VIRTUAL;
        }

        if (!(policyFlags & POLICY_FLAG_FILTERED)) {
            mPolicy->interceptKeyBeforeQueueing(keyEvent, /*byref*/ policyFlags);
        }

        if (policyFlags & POLICY_FLAG_WOKE_HERE) {
            flags |= AKEY_EVENT_FLAG_WOKE_HERE;
        }

        mLock.lock();
        injectedEntry = mAllocator.obtainKeyEntry(keyEvent->getEventTime(),
                keyEvent->getDeviceId(), keyEvent->getSource(),
                policyFlags, action, flags,
                keyEvent->getKeyCode(), keyEvent->getScanCode(), keyEvent->getMetaState(),
                keyEvent->getRepeatCount(), keyEvent->getDownTime());
        break;
    }

    case AINPUT_EVENT_TYPE_MOTION: {
        const MotionEvent* motionEvent = static_cast<const MotionEvent*>(event);
        int32_t action = motionEvent->getAction();
        size_t pointerCount = motionEvent->getPointerCount();
        const PointerProperties* pointerProperties = motionEvent->getPointerProperties();
        if (! validateMotionEvent(action, pointerCount, pointerProperties)) {
            return INPUT_EVENT_INJECTION_FAILED;
        }

        if (!(policyFlags & POLICY_FLAG_FILTERED)) {
            nsecs_t eventTime = motionEvent->getEventTime();
            mPolicy->interceptMotionBeforeQueueing(eventTime, /*byref*/ policyFlags);
        }

        mLock.lock();
        const nsecs_t* sampleEventTimes = motionEvent->getSampleEventTimes();
        const PointerCoords* samplePointerCoords = motionEvent->getSamplePointerCoords();
        MotionEntry* motionEntry = mAllocator.obtainMotionEntry(*sampleEventTimes,
                motionEvent->getDeviceId(), motionEvent->getSource(), policyFlags,
                action, motionEvent->getFlags(),
                motionEvent->getMetaState(), motionEvent->getButtonState(),
                motionEvent->getEdgeFlags(),
                motionEvent->getXPrecision(), motionEvent->getYPrecision(),
                motionEvent->getDownTime(), uint32_t(pointerCount),
                pointerProperties, samplePointerCoords);
        for (size_t i = motionEvent->getHistorySize(); i > 0; i--) {
            sampleEventTimes += 1;
            samplePointerCoords += pointerCount;
            mAllocator.appendMotionSample(motionEntry, *sampleEventTimes, samplePointerCoords);
        }
        injectedEntry = motionEntry;
        break;
    }

    default:
        LOGW("Cannot inject event of type %d", event->getType());
        return INPUT_EVENT_INJECTION_FAILED;
    }

    InjectionState* injectionState = mAllocator.obtainInjectionState(injectorPid, injectorUid);
    if (syncMode == INPUT_EVENT_INJECTION_SYNC_NONE) {
        injectionState->injectionIsAsync = true;
    }

    injectionState->refCount += 1;
    injectedEntry->injectionState = injectionState;

    bool needWake = enqueueInboundEventLocked(injectedEntry);
    mLock.unlock();

    if (needWake) {
        mLooper->wake();
    }

    int32_t injectionResult;
    { // acquire lock
        AutoMutex _l(mLock);

        if (syncMode == INPUT_EVENT_INJECTION_SYNC_NONE) {
            injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
        } else {
            for (;;) {
                injectionResult = injectionState->injectionResult;
                if (injectionResult != INPUT_EVENT_INJECTION_PENDING) {
                    break;
                }

                nsecs_t remainingTimeout = endTime - now();
                if (remainingTimeout <= 0) {
#if DEBUG_INJECTION
                    LOGD("injectInputEvent - Timed out waiting for injection result "
                            "to become available.");
#endif
                    injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                    break;
                }

                mInjectionResultAvailableCondition.waitRelative(mLock, remainingTimeout);
            }

            if (injectionResult == INPUT_EVENT_INJECTION_SUCCEEDED
                    && syncMode == INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISHED) {
                while (injectionState->pendingForegroundDispatches != 0) {
#if DEBUG_INJECTION
                    LOGD("injectInputEvent - Waiting for %d pending foreground dispatches.",
                            injectionState->pendingForegroundDispatches);
#endif
                    nsecs_t remainingTimeout = endTime - now();
                    if (remainingTimeout <= 0) {
#if DEBUG_INJECTION
                    LOGD("injectInputEvent - Timed out waiting for pending foreground "
                            "dispatches to finish.");
#endif
                        injectionResult = INPUT_EVENT_INJECTION_TIMED_OUT;
                        break;
                    }

                    mInjectionSyncFinishedCondition.waitRelative(mLock, remainingTimeout);
                }
            }
        }

        mAllocator.releaseInjectionState(injectionState);
    } // release lock

#if DEBUG_INJECTION
    LOGD("injectInputEvent - Finished with result %d.  "
            "injectorPid=%d, injectorUid=%d",
            injectionResult, injectorPid, injectorUid);
#endif

    return injectionResult;
}

bool InputDispatcher::hasInjectionPermission(int32_t injectorPid, int32_t injectorUid) {
    return injectorUid == 0
            || mPolicy->checkInjectEventsPermissionNonReentrant(injectorPid, injectorUid);
}

void InputDispatcher::setInjectionResultLocked(EventEntry* entry, int32_t injectionResult) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState) {
#if DEBUG_INJECTION
        LOGD("Setting input event injection result to %d.  "
                "injectorPid=%d, injectorUid=%d",
                 injectionResult, injectionState->injectorPid, injectionState->injectorUid);
#endif

        if (injectionState->injectionIsAsync
                && !(entry->policyFlags & POLICY_FLAG_FILTERED)) {
            // Log the outcome since the injector did not wait for the injection result.
            switch (injectionResult) {
            case INPUT_EVENT_INJECTION_SUCCEEDED:
                LOGV("Asynchronous input event injection succeeded.");
                break;
            case INPUT_EVENT_INJECTION_FAILED:
                LOGW("Asynchronous input event injection failed.");
                break;
            case INPUT_EVENT_INJECTION_PERMISSION_DENIED:
                LOGW("Asynchronous input event injection permission denied.");
                break;
            case INPUT_EVENT_INJECTION_TIMED_OUT:
                LOGW("Asynchronous input event injection timed out.");
                break;
            }
        }

        injectionState->injectionResult = injectionResult;
        mInjectionResultAvailableCondition.broadcast();
    }
}

void InputDispatcher::incrementPendingForegroundDispatchesLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState) {
        injectionState->pendingForegroundDispatches += 1;
    }
}

void InputDispatcher::decrementPendingForegroundDispatchesLocked(EventEntry* entry) {
    InjectionState* injectionState = entry->injectionState;
    if (injectionState) {
        injectionState->pendingForegroundDispatches -= 1;

        if (injectionState->pendingForegroundDispatches == 0) {
            mInjectionSyncFinishedCondition.broadcast();
        }
    }
}

const InputWindow* InputDispatcher::getWindowLocked(const sp<InputChannel>& inputChannel) {
    for (size_t i = 0; i < mWindows.size(); i++) {
        const InputWindow* window = & mWindows[i];
        if (window->inputChannel == inputChannel) {
            return window;
        }
    }
    return NULL;
}

void InputDispatcher::setInputWindows(const Vector<InputWindow>& inputWindows) {
#if DEBUG_FOCUS
    LOGD("setInputWindows");
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        // Clear old window pointers.
        sp<InputChannel> oldFocusedWindowChannel;
        if (mFocusedWindow) {
            oldFocusedWindowChannel = mFocusedWindow->inputChannel;
            mFocusedWindow = NULL;
        }
        sp<InputChannel> oldLastHoverWindowChannel;
        if (mLastHoverWindow) {
            oldLastHoverWindowChannel = mLastHoverWindow->inputChannel;
            mLastHoverWindow = NULL;
        }

        mWindows.clear();

        // Loop over new windows and rebuild the necessary window pointers for
        // tracking focus and touch.
        mWindows.appendVector(inputWindows);

        size_t numWindows = mWindows.size();
        for (size_t i = 0; i < numWindows; i++) {
            const InputWindow* window = & mWindows.itemAt(i);
            if (window->hasFocus) {
                mFocusedWindow = window;
                break;
            }
        }

        if (oldFocusedWindowChannel != NULL) {
            if (!mFocusedWindow || oldFocusedWindowChannel != mFocusedWindow->inputChannel) {
#if DEBUG_FOCUS
                LOGD("Focus left window: %s",
                        oldFocusedWindowChannel->getName().string());
#endif
                CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS,
                        "focus left window");
                synthesizeCancelationEventsForInputChannelLocked(oldFocusedWindowChannel, options);
                oldFocusedWindowChannel.clear();
            }
        }
        if (mFocusedWindow && oldFocusedWindowChannel == NULL) {
#if DEBUG_FOCUS
            LOGD("Focus entered window: %s",
                    mFocusedWindow->inputChannel->getName().string());
#endif
        }

        for (size_t i = 0; i < mTouchState.windows.size(); ) {
            TouchedWindow& touchedWindow = mTouchState.windows.editItemAt(i);
            const InputWindow* window = getWindowLocked(touchedWindow.channel);
            if (window) {
                touchedWindow.window = window;
                i += 1;
            } else {
#if DEBUG_FOCUS
                LOGD("Touched window was removed: %s", touchedWindow.channel->getName().string());
#endif
                CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                        "touched window was removed");
                synthesizeCancelationEventsForInputChannelLocked(touchedWindow.channel, options);
                mTouchState.windows.removeAt(i);
            }
        }

        // Recover the last hovered window.
        if (oldLastHoverWindowChannel != NULL) {
            mLastHoverWindow = getWindowLocked(oldLastHoverWindowChannel);
            oldLastHoverWindowChannel.clear();
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}

void InputDispatcher::setFocusedApplication(const InputApplication* inputApplication) {
#if DEBUG_FOCUS
    LOGD("setFocusedApplication");
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        releaseFocusedApplicationLocked();

        if (inputApplication) {
            mFocusedApplicationStorage = *inputApplication;
            mFocusedApplication = & mFocusedApplicationStorage;
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}

void InputDispatcher::releaseFocusedApplicationLocked() {
    if (mFocusedApplication) {
        mFocusedApplication = NULL;
        mFocusedApplicationStorage.inputApplicationHandle.clear();
    }
}

void InputDispatcher::setInputDispatchMode(bool enabled, bool frozen) {
#if DEBUG_FOCUS
    LOGD("setInputDispatchMode: enabled=%d, frozen=%d", enabled, frozen);
#endif

    bool changed;
    { // acquire lock
        AutoMutex _l(mLock);

        if (mDispatchEnabled != enabled || mDispatchFrozen != frozen) {
            if (mDispatchFrozen && !frozen) {
                resetANRTimeoutsLocked();
            }

            if (mDispatchEnabled && !enabled) {
                resetAndDropEverythingLocked("dispatcher is being disabled");
            }

            mDispatchEnabled = enabled;
            mDispatchFrozen = frozen;
            changed = true;
        } else {
            changed = false;
        }

#if DEBUG_FOCUS
        //logDispatchStateLocked();
#endif
    } // release lock

    if (changed) {
        // Wake up poll loop since it may need to make new input dispatching choices.
        mLooper->wake();
    }
}

void InputDispatcher::setInputFilterEnabled(bool enabled) {
#if DEBUG_FOCUS
    LOGD("setInputFilterEnabled: enabled=%d", enabled);
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        if (mInputFilterEnabled == enabled) {
            return;
        }

        mInputFilterEnabled = enabled;
        resetAndDropEverythingLocked("input filter is being enabled or disabled");
    } // release lock

    // Wake up poll loop since there might be work to do to drop everything.
    mLooper->wake();
}

bool InputDispatcher::transferTouchFocus(const sp<InputChannel>& fromChannel,
        const sp<InputChannel>& toChannel) {
#if DEBUG_FOCUS
    LOGD("transferTouchFocus: fromChannel=%s, toChannel=%s",
            fromChannel->getName().string(), toChannel->getName().string());
#endif
    { // acquire lock
        AutoMutex _l(mLock);

        const InputWindow* fromWindow = getWindowLocked(fromChannel);
        const InputWindow* toWindow = getWindowLocked(toChannel);
        if (! fromWindow || ! toWindow) {
#if DEBUG_FOCUS
            LOGD("Cannot transfer focus because from or to window not found.");
#endif
            return false;
        }
        if (fromWindow == toWindow) {
#if DEBUG_FOCUS
            LOGD("Trivial transfer to same window.");
#endif
            return true;
        }

        bool found = false;
        for (size_t i = 0; i < mTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTouchState.windows[i];
            if (touchedWindow.window == fromWindow) {
                int32_t oldTargetFlags = touchedWindow.targetFlags;
                BitSet32 pointerIds = touchedWindow.pointerIds;

                mTouchState.windows.removeAt(i);

                int32_t newTargetFlags = oldTargetFlags
                        & (InputTarget::FLAG_FOREGROUND
                                | InputTarget::FLAG_SPLIT | InputTarget::FLAG_DISPATCH_AS_IS);
                mTouchState.addOrUpdateWindow(toWindow, newTargetFlags, pointerIds);

                found = true;
                break;
            }
        }

        if (! found) {
#if DEBUG_FOCUS
            LOGD("Focus transfer failed because from window did not have focus.");
#endif
            return false;
        }

        ssize_t fromConnectionIndex = getConnectionIndexLocked(fromChannel);
        ssize_t toConnectionIndex = getConnectionIndexLocked(toChannel);
        if (fromConnectionIndex >= 0 && toConnectionIndex >= 0) {
            sp<Connection> fromConnection = mConnectionsByReceiveFd.valueAt(fromConnectionIndex);
            sp<Connection> toConnection = mConnectionsByReceiveFd.valueAt(toConnectionIndex);

            fromConnection->inputState.copyPointerStateTo(toConnection->inputState);
            CancelationOptions options(CancelationOptions::CANCEL_POINTER_EVENTS,
                    "transferring touch focus from this window to another window");
            synthesizeCancelationEventsForConnectionLocked(fromConnection, options);
        }

#if DEBUG_FOCUS
        logDispatchStateLocked();
#endif
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
    return true;
}

void InputDispatcher::resetAndDropEverythingLocked(const char* reason) {
#if DEBUG_FOCUS
    LOGD("Resetting and dropping all events (%s).", reason);
#endif

    CancelationOptions options(CancelationOptions::CANCEL_ALL_EVENTS, reason);
    synthesizeCancelationEventsForAllConnectionsLocked(options);

    resetKeyRepeatLocked();
    releasePendingEventLocked();
    drainInboundQueueLocked();
    resetTargetsLocked();

    mTouchState.reset();
    mLastHoverWindow = NULL;
}

void InputDispatcher::logDispatchStateLocked() {
    String8 dump;
    dumpDispatchStateLocked(dump);

    char* text = dump.lockBuffer(dump.size());
    char* start = text;
    while (*start != '\0') {
        char* end = strchr(start, '\n');
        if (*end == '\n') {
            *(end++) = '\0';
        }
        LOGD("%s", start);
        start = end;
    }
}

void InputDispatcher::dumpDispatchStateLocked(String8& dump) {
    dump.appendFormat(INDENT "DispatchEnabled: %d\n", mDispatchEnabled);
    dump.appendFormat(INDENT "DispatchFrozen: %d\n", mDispatchFrozen);

    if (mFocusedApplication) {
        dump.appendFormat(INDENT "FocusedApplication: name='%s', dispatchingTimeout=%0.3fms\n",
                mFocusedApplication->name.string(),
                mFocusedApplication->dispatchingTimeout / 1000000.0);
    } else {
        dump.append(INDENT "FocusedApplication: <null>\n");
    }
    dump.appendFormat(INDENT "FocusedWindow: name='%s'\n",
            mFocusedWindow != NULL ? mFocusedWindow->name.string() : "<null>");

    dump.appendFormat(INDENT "TouchDown: %s\n", toString(mTouchState.down));
    dump.appendFormat(INDENT "TouchSplit: %s\n", toString(mTouchState.split));
    dump.appendFormat(INDENT "TouchDeviceId: %d\n", mTouchState.deviceId);
    dump.appendFormat(INDENT "TouchSource: 0x%08x\n", mTouchState.source);
    if (!mTouchState.windows.isEmpty()) {
        dump.append(INDENT "TouchedWindows:\n");
        for (size_t i = 0; i < mTouchState.windows.size(); i++) {
            const TouchedWindow& touchedWindow = mTouchState.windows[i];
            dump.appendFormat(INDENT2 "%d: name='%s', pointerIds=0x%0x, targetFlags=0x%x\n",
                    i, touchedWindow.window->name.string(), touchedWindow.pointerIds.value,
                    touchedWindow.targetFlags);
        }
    } else {
        dump.append(INDENT "TouchedWindows: <none>\n");
    }

    if (!mWindows.isEmpty()) {
        dump.append(INDENT "Windows:\n");
        for (size_t i = 0; i < mWindows.size(); i++) {
            const InputWindow& window = mWindows[i];
            dump.appendFormat(INDENT2 "%d: name='%s', paused=%s, hasFocus=%s, hasWallpaper=%s, "
                    "visible=%s, canReceiveKeys=%s, flags=0x%08x, type=0x%08x, layer=%d, "
                    "frame=[%d,%d][%d,%d], scale=%f, "
                    "touchableRegion=",
                    i, window.name.string(),
                    toString(window.paused),
                    toString(window.hasFocus),
                    toString(window.hasWallpaper),
                    toString(window.visible),
                    toString(window.canReceiveKeys),
                    window.layoutParamsFlags, window.layoutParamsType,
                    window.layer,
                    window.frameLeft, window.frameTop,
                    window.frameRight, window.frameBottom,
                    window.scaleFactor);
            dumpRegion(dump, window.touchableRegion);
            dump.appendFormat(", inputFeatures=0x%08x", window.inputFeatures);
            dump.appendFormat(", ownerPid=%d, ownerUid=%d, dispatchingTimeout=%0.3fms\n",
                    window.ownerPid, window.ownerUid,
                    window.dispatchingTimeout / 1000000.0);
        }
    } else {
        dump.append(INDENT "Windows: <none>\n");
    }

    if (!mMonitoringChannels.isEmpty()) {
        dump.append(INDENT "MonitoringChannels:\n");
        for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
            const sp<InputChannel>& channel = mMonitoringChannels[i];
            dump.appendFormat(INDENT2 "%d: '%s'\n", i, channel->getName().string());
        }
    } else {
        dump.append(INDENT "MonitoringChannels: <none>\n");
    }

    dump.appendFormat(INDENT "InboundQueue: length=%u\n", mInboundQueue.count());

    if (!mActiveConnections.isEmpty()) {
        dump.append(INDENT "ActiveConnections:\n");
        for (size_t i = 0; i < mActiveConnections.size(); i++) {
            const Connection* connection = mActiveConnections[i];
            dump.appendFormat(INDENT2 "%d: '%s', status=%s, outboundQueueLength=%u, "
                    "inputState.isNeutral=%s\n",
                    i, connection->getInputChannelName(), connection->getStatusLabel(),
                    connection->outboundQueue.count(),
                    toString(connection->inputState.isNeutral()));
        }
    } else {
        dump.append(INDENT "ActiveConnections: <none>\n");
    }

    if (isAppSwitchPendingLocked()) {
        dump.appendFormat(INDENT "AppSwitch: pending, due in %01.1fms\n",
                (mAppSwitchDueTime - now()) / 1000000.0);
    } else {
        dump.append(INDENT "AppSwitch: not pending\n");
    }
}

status_t InputDispatcher::registerInputChannel(const sp<InputChannel>& inputChannel,
        const sp<InputWindowHandle>& inputWindowHandle, bool monitor) {
#if DEBUG_REGISTRATION
    LOGD("channel '%s' ~ registerInputChannel - monitor=%s", inputChannel->getName().string(),
            toString(monitor));
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        if (getConnectionIndexLocked(inputChannel) >= 0) {
            LOGW("Attempted to register already registered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = new Connection(inputChannel, inputWindowHandle);
        status_t status = connection->initialize();
        if (status) {
            LOGE("Failed to initialize input publisher for input channel '%s', status=%d",
                    inputChannel->getName().string(), status);
            return status;
        }

        int32_t receiveFd = inputChannel->getReceivePipeFd();
        mConnectionsByReceiveFd.add(receiveFd, connection);

        if (monitor) {
            mMonitoringChannels.push(inputChannel);
        }

        mLooper->addFd(receiveFd, 0, ALOOPER_EVENT_INPUT, handleReceiveCallback, this);

        runCommandsLockedInterruptible();
    } // release lock
    return OK;
}

status_t InputDispatcher::unregisterInputChannel(const sp<InputChannel>& inputChannel) {
#if DEBUG_REGISTRATION
    LOGD("channel '%s' ~ unregisterInputChannel", inputChannel->getName().string());
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        ssize_t connectionIndex = getConnectionIndexLocked(inputChannel);
        if (connectionIndex < 0) {
            LOGW("Attempted to unregister already unregistered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        mConnectionsByReceiveFd.removeItemsAt(connectionIndex);

        connection->status = Connection::STATUS_ZOMBIE;

        for (size_t i = 0; i < mMonitoringChannels.size(); i++) {
            if (mMonitoringChannels[i] == inputChannel) {
                mMonitoringChannels.removeAt(i);
                break;
            }
        }

        mLooper->removeFd(inputChannel->getReceivePipeFd());

        nsecs_t currentTime = now();
        abortBrokenDispatchCycleLocked(currentTime, connection);

        runCommandsLockedInterruptible();
    } // release lock

    // Wake the poll loop because removing the connection may have changed the current
    // synchronization state.
    mLooper->wake();
    return OK;
}

ssize_t InputDispatcher::getConnectionIndexLocked(const sp<InputChannel>& inputChannel) {
    ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(inputChannel->getReceivePipeFd());
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (connection->inputChannel.get() == inputChannel.get()) {
            return connectionIndex;
        }
    }

    return -1;
}

void InputDispatcher::activateConnectionLocked(Connection* connection) {
    for (size_t i = 0; i < mActiveConnections.size(); i++) {
        if (mActiveConnections.itemAt(i) == connection) {
            return;
        }
    }
    mActiveConnections.add(connection);
}

void InputDispatcher::deactivateConnectionLocked(Connection* connection) {
    for (size_t i = 0; i < mActiveConnections.size(); i++) {
        if (mActiveConnections.itemAt(i) == connection) {
            mActiveConnections.removeAt(i);
            return;
        }
    }
}

void InputDispatcher::onDispatchCycleStartedLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
}

void InputDispatcher::onDispatchCycleFinishedLocked(
        nsecs_t currentTime, const sp<Connection>& connection, bool handled) {
    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doDispatchCycleFinishedLockedInterruptible);
    commandEntry->connection = connection;
    commandEntry->handled = handled;
}

void InputDispatcher::onDispatchCycleBrokenLocked(
        nsecs_t currentTime, const sp<Connection>& connection) {
    LOGE("channel '%s' ~ Channel is unrecoverably broken and will be disposed!",
            connection->getInputChannelName());

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyInputChannelBrokenLockedInterruptible);
    commandEntry->connection = connection;
}

void InputDispatcher::onANRLocked(
        nsecs_t currentTime, const InputApplication* application, const InputWindow* window,
        nsecs_t eventTime, nsecs_t waitStartTime) {
    LOGI("Application is not responding: %s.  "
            "%01.1fms since event, %01.1fms since wait started",
            getApplicationWindowLabelLocked(application, window).string(),
            (currentTime - eventTime) / 1000000.0,
            (currentTime - waitStartTime) / 1000000.0);

    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyANRLockedInterruptible);
    if (application) {
        commandEntry->inputApplicationHandle = application->inputApplicationHandle;
    }
    if (window) {
        commandEntry->inputWindowHandle = window->inputWindowHandle;
        commandEntry->inputChannel = window->inputChannel;
    }
}

void InputDispatcher::doNotifyConfigurationChangedInterruptible(
        CommandEntry* commandEntry) {
    mLock.unlock();

    mPolicy->notifyConfigurationChanged(commandEntry->eventTime);

    mLock.lock();
}

void InputDispatcher::doNotifyInputChannelBrokenLockedInterruptible(
        CommandEntry* commandEntry) {
    sp<Connection> connection = commandEntry->connection;

    if (connection->status != Connection::STATUS_ZOMBIE) {
        mLock.unlock();

        mPolicy->notifyInputChannelBroken(connection->inputWindowHandle);

        mLock.lock();
    }
}

void InputDispatcher::doNotifyANRLockedInterruptible(
        CommandEntry* commandEntry) {
    mLock.unlock();

    nsecs_t newTimeout = mPolicy->notifyANR(
            commandEntry->inputApplicationHandle, commandEntry->inputWindowHandle);

    mLock.lock();

    resumeAfterTargetsNotReadyTimeoutLocked(newTimeout, commandEntry->inputChannel);
}

void InputDispatcher::doInterceptKeyBeforeDispatchingLockedInterruptible(
        CommandEntry* commandEntry) {
    KeyEntry* entry = commandEntry->keyEntry;

    KeyEvent event;
    initializeKeyEvent(&event, entry);

    mLock.unlock();

    bool consumed = mPolicy->interceptKeyBeforeDispatching(commandEntry->inputWindowHandle,
            &event, entry->policyFlags);

    mLock.lock();

    entry->interceptKeyResult = consumed
            ? KeyEntry::INTERCEPT_KEY_RESULT_SKIP
            : KeyEntry::INTERCEPT_KEY_RESULT_CONTINUE;
    mAllocator.releaseKeyEntry(entry);
}

void InputDispatcher::doDispatchCycleFinishedLockedInterruptible(
        CommandEntry* commandEntry) {
    sp<Connection> connection = commandEntry->connection;
    bool handled = commandEntry->handled;

    bool skipNext = false;
    if (!connection->outboundQueue.isEmpty()) {
        DispatchEntry* dispatchEntry = connection->outboundQueue.headSentinel.next;
        if (dispatchEntry->inProgress) {
            if (dispatchEntry->eventEntry->type == EventEntry::TYPE_KEY) {
                KeyEntry* keyEntry = static_cast<KeyEntry*>(dispatchEntry->eventEntry);
                skipNext = afterKeyEventLockedInterruptible(connection,
                        dispatchEntry, keyEntry, handled);
            } else if (dispatchEntry->eventEntry->type == EventEntry::TYPE_MOTION) {
                MotionEntry* motionEntry = static_cast<MotionEntry*>(dispatchEntry->eventEntry);
                skipNext = afterMotionEventLockedInterruptible(connection,
                        dispatchEntry, motionEntry, handled);
            }
        }
    }

    if (!skipNext) {
        startNextDispatchCycleLocked(now(), connection);
    }
}

bool InputDispatcher::afterKeyEventLockedInterruptible(const sp<Connection>& connection,
        DispatchEntry* dispatchEntry, KeyEntry* keyEntry, bool handled) {
    if (!(keyEntry->flags & AKEY_EVENT_FLAG_FALLBACK)) {
        // Get the fallback key state.
        // Clear it out after dispatching the UP.
        int32_t originalKeyCode = keyEntry->keyCode;
        int32_t fallbackKeyCode = connection->inputState.getFallbackKey(originalKeyCode);
        if (keyEntry->action == AKEY_EVENT_ACTION_UP) {
            connection->inputState.removeFallbackKey(originalKeyCode);
        }

        if (handled || !dispatchEntry->hasForegroundTarget()) {
            // If the application handles the original key for which we previously
            // generated a fallback or if the window is not a foreground window,
            // then cancel the associated fallback key, if any.
            if (fallbackKeyCode != -1) {
                if (fallbackKeyCode != AKEYCODE_UNKNOWN) {
                    CancelationOptions options(CancelationOptions::CANCEL_FALLBACK_EVENTS,
                            "application handled the original non-fallback key "
                            "or is no longer a foreground target, "
                            "canceling previously dispatched fallback key");
                    options.keyCode = fallbackKeyCode;
                    synthesizeCancelationEventsForConnectionLocked(connection, options);
                }
                connection->inputState.removeFallbackKey(originalKeyCode);
            }
        } else {
            // If the application did not handle a non-fallback key, first check
            // that we are in a good state to perform unhandled key event processing
            // Then ask the policy what to do with it.
            bool initialDown = keyEntry->action == AKEY_EVENT_ACTION_DOWN
                    && keyEntry->repeatCount == 0;
            if (fallbackKeyCode == -1 && !initialDown) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
                LOGD("Unhandled key event: Skipping unhandled key event processing "
                        "since this is not an initial down.  "
                        "keyCode=%d, action=%d, repeatCount=%d",
                        originalKeyCode, keyEntry->action, keyEntry->repeatCount);
#endif
                return false;
            }

            // Dispatch the unhandled key to the policy.
#if DEBUG_OUTBOUND_EVENT_DETAILS
            LOGD("Unhandled key event: Asking policy to perform fallback action.  "
                    "keyCode=%d, action=%d, repeatCount=%d",
                    keyEntry->keyCode, keyEntry->action, keyEntry->repeatCount);
#endif
            KeyEvent event;
            initializeKeyEvent(&event, keyEntry);

            mLock.unlock();

            bool fallback = mPolicy->dispatchUnhandledKey(connection->inputWindowHandle,
                    &event, keyEntry->policyFlags, &event);

            mLock.lock();

            if (connection->status != Connection::STATUS_NORMAL) {
                connection->inputState.removeFallbackKey(originalKeyCode);
                return true; // skip next cycle
            }

            LOG_ASSERT(connection->outboundQueue.headSentinel.next == dispatchEntry);

            // Latch the fallback keycode for this key on an initial down.
            // The fallback keycode cannot change at any other point in the lifecycle.
            if (initialDown) {
                if (fallback) {
                    fallbackKeyCode = event.getKeyCode();
                } else {
                    fallbackKeyCode = AKEYCODE_UNKNOWN;
                }
                connection->inputState.setFallbackKey(originalKeyCode, fallbackKeyCode);
            }

            LOG_ASSERT(fallbackKeyCode != -1);

            // Cancel the fallback key if the policy decides not to send it anymore.
            // We will continue to dispatch the key to the policy but we will no
            // longer dispatch a fallback key to the application.
            if (fallbackKeyCode != AKEYCODE_UNKNOWN
                    && (!fallback || fallbackKeyCode != event.getKeyCode())) {
#if DEBUG_OUTBOUND_EVENT_DETAILS
                if (fallback) {
                    LOGD("Unhandled key event: Policy requested to send key %d"
                            "as a fallback for %d, but on the DOWN it had requested "
                            "to send %d instead.  Fallback canceled.",
                            event.getKeyCode(), originalKeyCode, fallbackKeyCode);
                } else {
                    LOGD("Unhandled key event: Policy did not request fallback for %d,"
                            "but on the DOWN it had requested to send %d.  "
                            "Fallback canceled.",
                            originalKeyCode, fallbackKeyCode);
                }
#endif

                CancelationOptions options(CancelationOptions::CANCEL_FALLBACK_EVENTS,
                        "canceling fallback, policy no longer desires it");
                options.keyCode = fallbackKeyCode;
                synthesizeCancelationEventsForConnectionLocked(connection, options);

                fallback = false;
                fallbackKeyCode = AKEYCODE_UNKNOWN;
                if (keyEntry->action != AKEY_EVENT_ACTION_UP) {
                    connection->inputState.setFallbackKey(originalKeyCode,
                            fallbackKeyCode);
                }
            }

#if DEBUG_OUTBOUND_EVENT_DETAILS
            {
                String8 msg;
                const KeyedVector<int32_t, int32_t>& fallbackKeys =
                        connection->inputState.getFallbackKeys();
                for (size_t i = 0; i < fallbackKeys.size(); i++) {
                    msg.appendFormat(", %d->%d", fallbackKeys.keyAt(i),
                            fallbackKeys.valueAt(i));
                }
                LOGD("Unhandled key event: %d currently tracked fallback keys%s.",
                        fallbackKeys.size(), msg.string());
            }
#endif

            if (fallback) {
                // Restart the dispatch cycle using the fallback key.
                keyEntry->eventTime = event.getEventTime();
                keyEntry->deviceId = event.getDeviceId();
                keyEntry->source = event.getSource();
                keyEntry->flags = event.getFlags() | AKEY_EVENT_FLAG_FALLBACK;
                keyEntry->keyCode = fallbackKeyCode;
                keyEntry->scanCode = event.getScanCode();
                keyEntry->metaState = event.getMetaState();
                keyEntry->repeatCount = event.getRepeatCount();
                keyEntry->downTime = event.getDownTime();
                keyEntry->syntheticRepeat = false;

#if DEBUG_OUTBOUND_EVENT_DETAILS
                LOGD("Unhandled key event: Dispatching fallback key.  "
                        "originalKeyCode=%d, fallbackKeyCode=%d, fallbackMetaState=%08x",
                        originalKeyCode, fallbackKeyCode, keyEntry->metaState);
#endif

                dispatchEntry->inProgress = false;
                startDispatchCycleLocked(now(), connection);
                return true; // already started next cycle
            } else {
#if DEBUG_OUTBOUND_EVENT_DETAILS
                LOGD("Unhandled key event: No fallback key.");
#endif
            }
        }
    }
    return false;
}

bool InputDispatcher::afterMotionEventLockedInterruptible(const sp<Connection>& connection,
        DispatchEntry* dispatchEntry, MotionEntry* motionEntry, bool handled) {
    return false;
}

void InputDispatcher::doPokeUserActivityLockedInterruptible(CommandEntry* commandEntry) {
    mLock.unlock();

    mPolicy->pokeUserActivity(commandEntry->eventTime, commandEntry->userActivityEventType);

    mLock.lock();
}

void InputDispatcher::initializeKeyEvent(KeyEvent* event, const KeyEntry* entry) {
    event->initialize(entry->deviceId, entry->source, entry->action, entry->flags,
            entry->keyCode, entry->scanCode, entry->metaState, entry->repeatCount,
            entry->downTime, entry->eventTime);
}

void InputDispatcher::updateDispatchStatisticsLocked(nsecs_t currentTime, const EventEntry* entry,
        int32_t injectionResult, nsecs_t timeSpentWaitingForApplication) {
    // TODO Write some statistics about how long we spend waiting.
}

void InputDispatcher::dump(String8& dump) {
    dump.append("Input Dispatcher State:\n");
    dumpDispatchStateLocked(dump);

    dump.append(INDENT "Configuration:\n");
    dump.appendFormat(INDENT2 "MaxEventsPerSecond: %d\n", mConfig.maxEventsPerSecond);
    dump.appendFormat(INDENT2 "KeyRepeatDelay: %0.1fms\n", mConfig.keyRepeatDelay * 0.000001f);
    dump.appendFormat(INDENT2 "KeyRepeatTimeout: %0.1fms\n", mConfig.keyRepeatTimeout * 0.000001f);
}


// --- InputDispatcher::Queue ---

template <typename T>
uint32_t InputDispatcher::Queue<T>::count() const {
    uint32_t result = 0;
    for (const T* entry = headSentinel.next; entry != & tailSentinel; entry = entry->next) {
        result += 1;
    }
    return result;
}


// --- InputDispatcher::Allocator ---

InputDispatcher::Allocator::Allocator() {
}

InputDispatcher::InjectionState*
InputDispatcher::Allocator::obtainInjectionState(int32_t injectorPid, int32_t injectorUid) {
    InjectionState* injectionState = mInjectionStatePool.alloc();
    injectionState->refCount = 1;
    injectionState->injectorPid = injectorPid;
    injectionState->injectorUid = injectorUid;
    injectionState->injectionIsAsync = false;
    injectionState->injectionResult = INPUT_EVENT_INJECTION_PENDING;
    injectionState->pendingForegroundDispatches = 0;
    return injectionState;
}

void InputDispatcher::Allocator::initializeEventEntry(EventEntry* entry, int32_t type,
        nsecs_t eventTime, uint32_t policyFlags) {
    entry->type = type;
    entry->refCount = 1;
    entry->dispatchInProgress = false;
    entry->eventTime = eventTime;
    entry->policyFlags = policyFlags;
    entry->injectionState = NULL;
}

void InputDispatcher::Allocator::releaseEventEntryInjectionState(EventEntry* entry) {
    if (entry->injectionState) {
        releaseInjectionState(entry->injectionState);
        entry->injectionState = NULL;
    }
}

InputDispatcher::ConfigurationChangedEntry*
InputDispatcher::Allocator::obtainConfigurationChangedEntry(nsecs_t eventTime) {
    ConfigurationChangedEntry* entry = mConfigurationChangeEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_CONFIGURATION_CHANGED, eventTime, 0);
    return entry;
}

InputDispatcher::KeyEntry* InputDispatcher::Allocator::obtainKeyEntry(nsecs_t eventTime,
        int32_t deviceId, uint32_t source, uint32_t policyFlags, int32_t action,
        int32_t flags, int32_t keyCode, int32_t scanCode, int32_t metaState,
        int32_t repeatCount, nsecs_t downTime) {
    KeyEntry* entry = mKeyEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_KEY, eventTime, policyFlags);

    entry->deviceId = deviceId;
    entry->source = source;
    entry->action = action;
    entry->flags = flags;
    entry->keyCode = keyCode;
    entry->scanCode = scanCode;
    entry->metaState = metaState;
    entry->repeatCount = repeatCount;
    entry->downTime = downTime;
    entry->syntheticRepeat = false;
    entry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN;
    return entry;
}

InputDispatcher::MotionEntry* InputDispatcher::Allocator::obtainMotionEntry(nsecs_t eventTime,
        int32_t deviceId, uint32_t source, uint32_t policyFlags, int32_t action, int32_t flags,
        int32_t metaState, int32_t buttonState,
        int32_t edgeFlags, float xPrecision, float yPrecision,
        nsecs_t downTime, uint32_t pointerCount,
        const PointerProperties* pointerProperties, const PointerCoords* pointerCoords) {
    MotionEntry* entry = mMotionEntryPool.alloc();
    initializeEventEntry(entry, EventEntry::TYPE_MOTION, eventTime, policyFlags);

    entry->eventTime = eventTime;
    entry->deviceId = deviceId;
    entry->source = source;
    entry->action = action;
    entry->flags = flags;
    entry->metaState = metaState;
    entry->buttonState = buttonState;
    entry->edgeFlags = edgeFlags;
    entry->xPrecision = xPrecision;
    entry->yPrecision = yPrecision;
    entry->downTime = downTime;
    entry->pointerCount = pointerCount;
    entry->firstSample.eventTime = eventTime;
    entry->firstSample.eventTimeBeforeCoalescing = eventTime;
    entry->firstSample.next = NULL;
    entry->lastSample = & entry->firstSample;
    for (uint32_t i = 0; i < pointerCount; i++) {
        entry->pointerProperties[i].copyFrom(pointerProperties[i]);
        entry->firstSample.pointerCoords[i].copyFrom(pointerCoords[i]);
    }
    return entry;
}

InputDispatcher::DispatchEntry* InputDispatcher::Allocator::obtainDispatchEntry(
        EventEntry* eventEntry,
        int32_t targetFlags, float xOffset, float yOffset, float scaleFactor) {
    DispatchEntry* entry = mDispatchEntryPool.alloc();
    entry->eventEntry = eventEntry;
    eventEntry->refCount += 1;
    entry->targetFlags = targetFlags;
    entry->xOffset = xOffset;
    entry->yOffset = yOffset;
    entry->scaleFactor = scaleFactor;
    entry->inProgress = false;
    entry->headMotionSample = NULL;
    entry->tailMotionSample = NULL;
    return entry;
}

InputDispatcher::CommandEntry* InputDispatcher::Allocator::obtainCommandEntry(Command command) {
    CommandEntry* entry = mCommandEntryPool.alloc();
    entry->command = command;
    return entry;
}

void InputDispatcher::Allocator::releaseInjectionState(InjectionState* injectionState) {
    injectionState->refCount -= 1;
    if (injectionState->refCount == 0) {
        mInjectionStatePool.free(injectionState);
    } else {
        LOG_ASSERT(injectionState->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseEventEntry(EventEntry* entry) {
    switch (entry->type) {
    case EventEntry::TYPE_CONFIGURATION_CHANGED:
        releaseConfigurationChangedEntry(static_cast<ConfigurationChangedEntry*>(entry));
        break;
    case EventEntry::TYPE_KEY:
        releaseKeyEntry(static_cast<KeyEntry*>(entry));
        break;
    case EventEntry::TYPE_MOTION:
        releaseMotionEntry(static_cast<MotionEntry*>(entry));
        break;
    default:
        LOG_ASSERT(false);
        break;
    }
}

void InputDispatcher::Allocator::releaseConfigurationChangedEntry(
        ConfigurationChangedEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        releaseEventEntryInjectionState(entry);
        mConfigurationChangeEntryPool.free(entry);
    } else {
        LOG_ASSERT(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseKeyEntry(KeyEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        releaseEventEntryInjectionState(entry);
        mKeyEntryPool.free(entry);
    } else {
        LOG_ASSERT(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::releaseMotionEntry(MotionEntry* entry) {
    entry->refCount -= 1;
    if (entry->refCount == 0) {
        releaseEventEntryInjectionState(entry);
        for (MotionSample* sample = entry->firstSample.next; sample != NULL; ) {
            MotionSample* next = sample->next;
            mMotionSamplePool.free(sample);
            sample = next;
        }
        mMotionEntryPool.free(entry);
    } else {
        LOG_ASSERT(entry->refCount > 0);
    }
}

void InputDispatcher::Allocator::freeMotionSample(MotionSample* sample) {
    mMotionSamplePool.free(sample);
}

void InputDispatcher::Allocator::releaseDispatchEntry(DispatchEntry* entry) {
    releaseEventEntry(entry->eventEntry);
    mDispatchEntryPool.free(entry);
}

void InputDispatcher::Allocator::releaseCommandEntry(CommandEntry* entry) {
    mCommandEntryPool.free(entry);
}

void InputDispatcher::Allocator::appendMotionSample(MotionEntry* motionEntry,
        nsecs_t eventTime, const PointerCoords* pointerCoords) {
    MotionSample* sample = mMotionSamplePool.alloc();
    sample->eventTime = eventTime;
    sample->eventTimeBeforeCoalescing = eventTime;
    uint32_t pointerCount = motionEntry->pointerCount;
    for (uint32_t i = 0; i < pointerCount; i++) {
        sample->pointerCoords[i].copyFrom(pointerCoords[i]);
    }

    sample->next = NULL;
    motionEntry->lastSample->next = sample;
    motionEntry->lastSample = sample;
}

void InputDispatcher::Allocator::recycleKeyEntry(KeyEntry* keyEntry) {
    releaseEventEntryInjectionState(keyEntry);

    keyEntry->dispatchInProgress = false;
    keyEntry->syntheticRepeat = false;
    keyEntry->interceptKeyResult = KeyEntry::INTERCEPT_KEY_RESULT_UNKNOWN;
}


// --- InputDispatcher::MotionEntry ---

uint32_t InputDispatcher::MotionEntry::countSamples() const {
    uint32_t count = 1;
    for (MotionSample* sample = firstSample.next; sample != NULL; sample = sample->next) {
        count += 1;
    }
    return count;
}

bool InputDispatcher::MotionEntry::canAppendSamples(int32_t action, uint32_t pointerCount,
        const PointerProperties* pointerProperties) const {
    if (this->action != action
            || this->pointerCount != pointerCount
            || this->isInjected()) {
        return false;
    }
    for (uint32_t i = 0; i < pointerCount; i++) {
        if (this->pointerProperties[i] != pointerProperties[i]) {
            return false;
        }
    }
    return true;
}


// --- InputDispatcher::InputState ---

InputDispatcher::InputState::InputState() {
}

InputDispatcher::InputState::~InputState() {
}

bool InputDispatcher::InputState::isNeutral() const {
    return mKeyMementos.isEmpty() && mMotionMementos.isEmpty();
}

bool InputDispatcher::InputState::isHovering(int32_t deviceId, uint32_t source) const {
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (memento.deviceId == deviceId
                && memento.source == source
                && memento.hovering) {
            return true;
        }
    }
    return false;
}

bool InputDispatcher::InputState::trackKey(const KeyEntry* entry,
        int32_t action, int32_t flags) {
    switch (action) {
    case AKEY_EVENT_ACTION_UP: {
        if (entry->flags & AKEY_EVENT_FLAG_FALLBACK) {
            for (size_t i = 0; i < mFallbackKeys.size(); ) {
                if (mFallbackKeys.valueAt(i) == entry->keyCode) {
                    mFallbackKeys.removeItemsAt(i);
                } else {
                    i += 1;
                }
            }
        }
        ssize_t index = findKeyMemento(entry);
        if (index >= 0) {
            mKeyMementos.removeAt(index);
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        LOGD("Dropping inconsistent key up event: deviceId=%d, source=%08x, "
                "keyCode=%d, scanCode=%d",
                entry->deviceId, entry->source, entry->keyCode, entry->scanCode);
#endif
        return false;
    }

    case AKEY_EVENT_ACTION_DOWN: {
        ssize_t index = findKeyMemento(entry);
        if (index >= 0) {
            mKeyMementos.removeAt(index);
        }
        addKeyMemento(entry, flags);
        return true;
    }

    default:
        return true;
    }
}

bool InputDispatcher::InputState::trackMotion(const MotionEntry* entry,
        int32_t action, int32_t flags) {
    int32_t actionMasked = action & AMOTION_EVENT_ACTION_MASK;
    switch (actionMasked) {
    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_CANCEL: {
        ssize_t index = findMotionMemento(entry, false /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        LOGD("Dropping inconsistent motion up or cancel event: deviceId=%d, source=%08x, "
                "actionMasked=%d",
                entry->deviceId, entry->source, actionMasked);
#endif
        return false;
    }

    case AMOTION_EVENT_ACTION_DOWN: {
        ssize_t index = findMotionMemento(entry, false /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
        }
        addMotionMemento(entry, flags, false /*hovering*/);
        return true;
    }

    case AMOTION_EVENT_ACTION_POINTER_UP:
    case AMOTION_EVENT_ACTION_POINTER_DOWN:
    case AMOTION_EVENT_ACTION_MOVE: {
        ssize_t index = findMotionMemento(entry, false /*hovering*/);
        if (index >= 0) {
            MotionMemento& memento = mMotionMementos.editItemAt(index);
            memento.setPointers(entry);
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        LOGD("Dropping inconsistent motion pointer up/down or move event: "
                "deviceId=%d, source=%08x, actionMasked=%d",
                entry->deviceId, entry->source, actionMasked);
#endif
        return false;
    }

    case AMOTION_EVENT_ACTION_HOVER_EXIT: {
        ssize_t index = findMotionMemento(entry, true /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
            return true;
        }
#if DEBUG_OUTBOUND_EVENT_DETAILS
        LOGD("Dropping inconsistent motion hover exit event: deviceId=%d, source=%08x",
                entry->deviceId, entry->source);
#endif
        return false;
    }

    case AMOTION_EVENT_ACTION_HOVER_ENTER:
    case AMOTION_EVENT_ACTION_HOVER_MOVE: {
        ssize_t index = findMotionMemento(entry, true /*hovering*/);
        if (index >= 0) {
            mMotionMementos.removeAt(index);
        }
        addMotionMemento(entry, flags, true /*hovering*/);
        return true;
    }

    default:
        return true;
    }
}

ssize_t InputDispatcher::InputState::findKeyMemento(const KeyEntry* entry) const {
    for (size_t i = 0; i < mKeyMementos.size(); i++) {
        const KeyMemento& memento = mKeyMementos.itemAt(i);
        if (memento.deviceId == entry->deviceId
                && memento.source == entry->source
                && memento.keyCode == entry->keyCode
                && memento.scanCode == entry->scanCode) {
            return i;
        }
    }
    return -1;
}

ssize_t InputDispatcher::InputState::findMotionMemento(const MotionEntry* entry,
        bool hovering) const {
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (memento.deviceId == entry->deviceId
                && memento.source == entry->source
                && memento.hovering == hovering) {
            return i;
        }
    }
    return -1;
}

void InputDispatcher::InputState::addKeyMemento(const KeyEntry* entry, int32_t flags) {
    mKeyMementos.push();
    KeyMemento& memento = mKeyMementos.editTop();
    memento.deviceId = entry->deviceId;
    memento.source = entry->source;
    memento.keyCode = entry->keyCode;
    memento.scanCode = entry->scanCode;
    memento.flags = flags;
    memento.downTime = entry->downTime;
}

void InputDispatcher::InputState::addMotionMemento(const MotionEntry* entry,
        int32_t flags, bool hovering) {
    mMotionMementos.push();
    MotionMemento& memento = mMotionMementos.editTop();
    memento.deviceId = entry->deviceId;
    memento.source = entry->source;
    memento.flags = flags;
    memento.xPrecision = entry->xPrecision;
    memento.yPrecision = entry->yPrecision;
    memento.downTime = entry->downTime;
    memento.setPointers(entry);
    memento.hovering = hovering;
}

void InputDispatcher::InputState::MotionMemento::setPointers(const MotionEntry* entry) {
    pointerCount = entry->pointerCount;
    for (uint32_t i = 0; i < entry->pointerCount; i++) {
        pointerProperties[i].copyFrom(entry->pointerProperties[i]);
        pointerCoords[i].copyFrom(entry->lastSample->pointerCoords[i]);
    }
}

void InputDispatcher::InputState::synthesizeCancelationEvents(nsecs_t currentTime,
        Allocator* allocator, Vector<EventEntry*>& outEvents,
        const CancelationOptions& options) {
    for (size_t i = 0; i < mKeyMementos.size(); i++) {
        const KeyMemento& memento = mKeyMementos.itemAt(i);
        if (shouldCancelKey(memento, options)) {
            outEvents.push(allocator->obtainKeyEntry(currentTime,
                    memento.deviceId, memento.source, 0,
                    AKEY_EVENT_ACTION_UP, memento.flags | AKEY_EVENT_FLAG_CANCELED,
                    memento.keyCode, memento.scanCode, 0, 0, memento.downTime));
        }
    }

    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (shouldCancelMotion(memento, options)) {
            outEvents.push(allocator->obtainMotionEntry(currentTime,
                    memento.deviceId, memento.source, 0,
                    memento.hovering
                            ? AMOTION_EVENT_ACTION_HOVER_EXIT
                            : AMOTION_EVENT_ACTION_CANCEL,
                    memento.flags, 0, 0, 0,
                    memento.xPrecision, memento.yPrecision, memento.downTime,
                    memento.pointerCount, memento.pointerProperties, memento.pointerCoords));
        }
    }
}

void InputDispatcher::InputState::clear() {
    mKeyMementos.clear();
    mMotionMementos.clear();
    mFallbackKeys.clear();
}

void InputDispatcher::InputState::copyPointerStateTo(InputState& other) const {
    for (size_t i = 0; i < mMotionMementos.size(); i++) {
        const MotionMemento& memento = mMotionMementos.itemAt(i);
        if (memento.source & AINPUT_SOURCE_CLASS_POINTER) {
            for (size_t j = 0; j < other.mMotionMementos.size(); ) {
                const MotionMemento& otherMemento = other.mMotionMementos.itemAt(j);
                if (memento.deviceId == otherMemento.deviceId
                        && memento.source == otherMemento.source) {
                    other.mMotionMementos.removeAt(j);
                } else {
                    j += 1;
                }
            }
            other.mMotionMementos.push(memento);
        }
    }
}

int32_t InputDispatcher::InputState::getFallbackKey(int32_t originalKeyCode) {
    ssize_t index = mFallbackKeys.indexOfKey(originalKeyCode);
    return index >= 0 ? mFallbackKeys.valueAt(index) : -1;
}

void InputDispatcher::InputState::setFallbackKey(int32_t originalKeyCode,
        int32_t fallbackKeyCode) {
    ssize_t index = mFallbackKeys.indexOfKey(originalKeyCode);
    if (index >= 0) {
        mFallbackKeys.replaceValueAt(index, fallbackKeyCode);
    } else {
        mFallbackKeys.add(originalKeyCode, fallbackKeyCode);
    }
}

void InputDispatcher::InputState::removeFallbackKey(int32_t originalKeyCode) {
    mFallbackKeys.removeItem(originalKeyCode);
}

bool InputDispatcher::InputState::shouldCancelKey(const KeyMemento& memento,
        const CancelationOptions& options) {
    if (options.keyCode != -1 && memento.keyCode != options.keyCode) {
        return false;
    }

    switch (options.mode) {
    case CancelationOptions::CANCEL_ALL_EVENTS:
    case CancelationOptions::CANCEL_NON_POINTER_EVENTS:
        return true;
    case CancelationOptions::CANCEL_FALLBACK_EVENTS:
        return memento.flags & AKEY_EVENT_FLAG_FALLBACK;
    default:
        return false;
    }
}

bool InputDispatcher::InputState::shouldCancelMotion(const MotionMemento& memento,
        const CancelationOptions& options) {
    switch (options.mode) {
    case CancelationOptions::CANCEL_ALL_EVENTS:
        return true;
    case CancelationOptions::CANCEL_POINTER_EVENTS:
        return memento.source & AINPUT_SOURCE_CLASS_POINTER;
    case CancelationOptions::CANCEL_NON_POINTER_EVENTS:
        return !(memento.source & AINPUT_SOURCE_CLASS_POINTER);
    default:
        return false;
    }
}


// --- InputDispatcher::Connection ---

InputDispatcher::Connection::Connection(const sp<InputChannel>& inputChannel,
        const sp<InputWindowHandle>& inputWindowHandle) :
        status(STATUS_NORMAL), inputChannel(inputChannel), inputWindowHandle(inputWindowHandle),
        inputPublisher(inputChannel),
        lastEventTime(LONG_LONG_MAX), lastDispatchTime(LONG_LONG_MAX) {
}

InputDispatcher::Connection::~Connection() {
}

status_t InputDispatcher::Connection::initialize() {
    return inputPublisher.initialize();
}

const char* InputDispatcher::Connection::getStatusLabel() const {
    switch (status) {
    case STATUS_NORMAL:
        return "NORMAL";

    case STATUS_BROKEN:
        return "BROKEN";

    case STATUS_ZOMBIE:
        return "ZOMBIE";

    default:
        return "UNKNOWN";
    }
}

InputDispatcher::DispatchEntry* InputDispatcher::Connection::findQueuedDispatchEntryForEvent(
        const EventEntry* eventEntry) const {
    for (DispatchEntry* dispatchEntry = outboundQueue.tailSentinel.prev;
            dispatchEntry != & outboundQueue.headSentinel; dispatchEntry = dispatchEntry->prev) {
        if (dispatchEntry->eventEntry == eventEntry) {
            return dispatchEntry;
        }
    }
    return NULL;
}


// --- InputDispatcher::CommandEntry ---

InputDispatcher::CommandEntry::CommandEntry() :
    keyEntry(NULL) {
}

InputDispatcher::CommandEntry::~CommandEntry() {
}


// --- InputDispatcher::TouchState ---

InputDispatcher::TouchState::TouchState() :
    down(false), split(false), deviceId(-1), source(0) {
}

InputDispatcher::TouchState::~TouchState() {
}

void InputDispatcher::TouchState::reset() {
    down = false;
    split = false;
    deviceId = -1;
    source = 0;
    windows.clear();
}

void InputDispatcher::TouchState::copyFrom(const TouchState& other) {
    down = other.down;
    split = other.split;
    deviceId = other.deviceId;
    source = other.source;
    windows.clear();
    windows.appendVector(other.windows);
}

void InputDispatcher::TouchState::addOrUpdateWindow(const InputWindow* window,
        int32_t targetFlags, BitSet32 pointerIds) {
    if (targetFlags & InputTarget::FLAG_SPLIT) {
        split = true;
    }

    for (size_t i = 0; i < windows.size(); i++) {
        TouchedWindow& touchedWindow = windows.editItemAt(i);
        if (touchedWindow.window == window) {
            touchedWindow.targetFlags |= targetFlags;
            if (targetFlags & InputTarget::FLAG_DISPATCH_AS_SLIPPERY_EXIT) {
                touchedWindow.targetFlags &= ~InputTarget::FLAG_DISPATCH_AS_IS;
            }
            touchedWindow.pointerIds.value |= pointerIds.value;
            return;
        }
    }

    windows.push();

    TouchedWindow& touchedWindow = windows.editTop();
    touchedWindow.window = window;
    touchedWindow.targetFlags = targetFlags;
    touchedWindow.pointerIds = pointerIds;
    touchedWindow.channel = window->inputChannel;
}

void InputDispatcher::TouchState::filterNonAsIsTouchWindows() {
    for (size_t i = 0 ; i < windows.size(); ) {
        TouchedWindow& window = windows.editItemAt(i);
        if (window.targetFlags & (InputTarget::FLAG_DISPATCH_AS_IS
                | InputTarget::FLAG_DISPATCH_AS_SLIPPERY_ENTER)) {
            window.targetFlags &= ~InputTarget::FLAG_DISPATCH_MASK;
            window.targetFlags |= InputTarget::FLAG_DISPATCH_AS_IS;
            i += 1;
        } else {
            windows.removeAt(i);
        }
    }
}

const InputWindow* InputDispatcher::TouchState::getFirstForegroundWindow() const {
    for (size_t i = 0; i < windows.size(); i++) {
        const TouchedWindow& window = windows.itemAt(i);
        if (window.targetFlags & InputTarget::FLAG_FOREGROUND) {
            return window.window;
        }
    }
    return NULL;
}

bool InputDispatcher::TouchState::isSlippery() const {
    // Must have exactly one foreground window.
    bool haveSlipperyForegroundWindow = false;
    for (size_t i = 0; i < windows.size(); i++) {
        const TouchedWindow& window = windows.itemAt(i);
        if (window.targetFlags & InputTarget::FLAG_FOREGROUND) {
            if (haveSlipperyForegroundWindow
                    || !(window.window->layoutParamsFlags & InputWindow::FLAG_SLIPPERY)) {
                return false;
            }
            haveSlipperyForegroundWindow = true;
        }
    }
    return haveSlipperyForegroundWindow;
}


// --- InputDispatcherThread ---

InputDispatcherThread::InputDispatcherThread(const sp<InputDispatcherInterface>& dispatcher) :
        Thread(/*canCallJava*/ true), mDispatcher(dispatcher) {
}

InputDispatcherThread::~InputDispatcherThread() {
}

bool InputDispatcherThread::threadLoop() {
    mDispatcher->dispatchOnce();
    return true;
}

} // namespace android
