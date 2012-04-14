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

package android.view.accessibility;

import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseLongArray;

/**
 * Simple cache for AccessibilityNodeInfos. The cache is mapping an
 * accessibility id to an info. The cache allows storing of
 * <code>null</code> values. It also tracks accessibility events
 * and invalidates accordingly.
 *
 * @hide
 */
public class AccessibilityNodeInfoCache {

    private static final String LOG_TAG = AccessibilityNodeInfoCache.class.getSimpleName();

    private static final boolean ENABLED = true;

    private static final boolean DEBUG = false;

    private final Object mLock = new Object();

    private final LongSparseArray<AccessibilityNodeInfo> mCacheImpl;

    public AccessibilityNodeInfoCache() {
        if (ENABLED) {
            mCacheImpl = new LongSparseArray<AccessibilityNodeInfo>();
        } else {
            mCacheImpl = null;
        }
    }

    /**
     * The cache keeps track of {@link AccessibilityEvent}s and invalidates
     * cached nodes as appropriate.
     *
     * @param event An event.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (ENABLED) {
            final int eventType = event.getEventType();
            switch (eventType) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    clear();
                } break;
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_SELECTED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: {
                    final long accessibilityNodeId = event.getSourceNodeId();
                    remove(accessibilityNodeId);
                } break;
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                    final long accessibilityNodeId = event.getSourceNodeId();
                    clearSubTree(accessibilityNodeId);
                } break;
            }
        }
    }

    /**
     * Gets a cached {@link AccessibilityNodeInfo} given its accessibility node id.
     *
     * @param accessibilityNodeId The info accessibility node id.
     * @return The cached {@link AccessibilityNodeInfo} or null if such not found.
     */
    public AccessibilityNodeInfo get(long accessibilityNodeId) {
        if (ENABLED) {
            synchronized(mLock) {
                AccessibilityNodeInfo info = mCacheImpl.get(accessibilityNodeId);
                if (info != null) {
                    // Return a copy since the client calls to AccessibilityNodeInfo#recycle()
                    // will wipe the data of the cached info.
                    info = AccessibilityNodeInfo.obtain(info);
                }
                if (DEBUG) {
                    Log.i(LOG_TAG, "get(" + accessibilityNodeId + ") = " + info);
                }
                return info;
            }
        } else {
            return null;
        }
    }

    /**
     * Caches an {@link AccessibilityNodeInfo} given its accessibility node id.
     *
     * @param accessibilityNodeId The info accessibility node id.
     * @param info The {@link AccessibilityNodeInfo} to cache.
     */
    public void put(long accessibilityNodeId, AccessibilityNodeInfo info) {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "put(" + accessibilityNodeId + ", " + info + ")");
                }
                // Cache a copy since the client calls to AccessibilityNodeInfo#recycle()
                // will wipe the data of the cached info.
                AccessibilityNodeInfo clone = AccessibilityNodeInfo.obtain(info);
                mCacheImpl.put(accessibilityNodeId, clone);
            }
        }
    }

    /**
     * Returns whether the cache contains an accessibility node id key.
     *
     * @param accessibilityNodeId The key for which to check.
     * @return True if the key is in the cache.
     */
    public boolean containsKey(long accessibilityNodeId) {
        if (ENABLED) {
            synchronized(mLock) {
                return (mCacheImpl.indexOfKey(accessibilityNodeId) >= 0);
            }
        } else {
            return false;
        }
    }

    /**
     * Removes a cached {@link AccessibilityNodeInfo}.
     *
     * @param accessibilityNodeId The info accessibility node id.
     */
    public void remove(long accessibilityNodeId) {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "remove(" + accessibilityNodeId + ")");
                }
                mCacheImpl.remove(accessibilityNodeId);
            }
        }
    }

    /**
     * Clears the cache.
     */
    public void clear() {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "clear()");
                }
                // Recycle the nodes before clearing the cache.
                final int nodeCount = mCacheImpl.size();
                for (int i = 0; i < nodeCount; i++) {
                    AccessibilityNodeInfo info = mCacheImpl.valueAt(i);
                    info.recycle();
                }
                mCacheImpl.clear();
            }
        }
    }

    /**
     * Clears a subtree rooted at the node with the given id.
     *
     * @param rootNodeId The root id.
     */
    private void clearSubTree(long rootNodeId) {
        AccessibilityNodeInfo current = mCacheImpl.get(rootNodeId);
        if (current == null) {
            return;
        }
        mCacheImpl.remove(rootNodeId);
        SparseLongArray childNodeIds = current.getChildNodeIds();
        final int childCount = childNodeIds.size();
        for (int i = 0; i < childCount; i++) {
            final long childNodeId = childNodeIds.valueAt(i);
            clearSubTree(childNodeId);
        }
    }
}
