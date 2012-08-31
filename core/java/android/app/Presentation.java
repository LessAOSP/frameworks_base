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

package android.app;

import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManagerImpl;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

/**
 * Base class for presentations.
 *
 * A presentation is a special kind of dialog whose purpose is to present
 * content on a secondary display.  A {@link Presentation} is associated with
 * the target {@link Display} at creation time and configures its context and
 * resource configuration according to the display's metrics.
 *
 * Notably, the {@link Context} of a presentation is different from the context
 * of its containing {@link Activity}.  It is important to inflate the layout
 * of a presentation and load other resources using the presentation's own context
 * to ensure that assets of the correct size and density for the target display
 * are loaded.
 *
 * A presentation is automatically canceled (see {@link Dialog#cancel()}) when
 * the display to which it is attached is removed.  An activity should take
 * care of pausing and resuming whatever content is playing within the presentation
 * whenever the activity itself is paused or resume.
 *
 * @see {@link DisplayManager} for information on how to enumerate displays.
 */
public class Presentation extends Dialog {
    private static final String TAG = "Presentation";

    private static final int MSG_CANCEL = 1;

    private final Display mDisplay;
    private final DisplayManager mDisplayManager;

    /**
     * Creates a new presentation that is attached to the specified display
     * using the default theme.
     *
     * @param outerContext The context of the application that is showing the presentation.
     * The presentation will create its own context (see {@link #getContext()}) based
     * on this context and information about the associated display.
     * @param display The display to which the presentation should be attached.
     */
    public Presentation(Context outerContext, Display display) {
        this(outerContext, display, 0);
    }

    /**
     * Creates a new presentation that is attached to the specified display
     * using the optionally specified theme.
     *
     * @param outerContext The context of the application that is showing the presentation.
     * The presentation will create its own context (see {@link #getContext()}) based
     * on this context and information about the associated display.
     * @param display The display to which the presentation should be attached.
     * @param theme A style resource describing the theme to use for the window.
     * See <a href="{@docRoot}guide/topics/resources/available-resources.html#stylesandthemes">
     * Style and Theme Resources</a> for more information about defining and using
     * styles.  This theme is applied on top of the current theme in
     * <var>outerContext</var>.  If 0, the default presentation theme will be used.
     */
    public Presentation(Context outerContext, Display display, int theme) {
        super(createPresentationContext(outerContext, display, theme), theme, false);

        mDisplay = display;
        mDisplayManager = (DisplayManager)getContext().getSystemService(Context.DISPLAY_SERVICE);

        getWindow().setGravity(Gravity.FILL);
        setCanceledOnTouchOutside(false);
    }

    /**
     * Gets the {@link Display} that this presentation appears on.
     *
     * @return The display.
     */
    public Display getDisplay() {
        return mDisplay;
    }

    /**
     * Gets the {@link Resources} that should be used to inflate the layout of this presentation.
     * This resources object has been configured according to the metrics of the
     * display that the presentation appears on.
     *
     * @return The presentation resources object.
     */
    public Resources getResources() {
        return getContext().getResources();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mDisplayManager.registerDisplayListener(mDisplayListener, null);

        // Since we were not watching for display changes until just now, there is a
        // chance that the display metrics have changed.  If so, we will need to
        // dismiss the presentation immediately.  This case is expected
        // to be rare but surprising, so we'll write a log message about it.
        if (!isConfigurationStillValid()) {
            Log.i(TAG, "Presentation is being immediately dismissed because the "
                    + "display metrics have changed since it was created.");
            mHandler.sendEmptyMessage(MSG_CANCEL);
        }
    }

    @Override
    protected void onStop() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        super.onStop();
    }

    /**
     * Called by the system when the {@link Display} to which the presentation
     * is attached has been removed.
     *
     * The system automatically calls {@link #cancel} to dismiss the presentation
     * after sending this event.
     *
     * @see #getDisplay
     */
    public void onDisplayRemoved() {
    }

    /**
     * Called by the system when the properties of the {@link Display} to which
     * the presentation is attached have changed.
     *
     * If the display metrics have changed (for example, if the display has been
     * resized or rotated), then the system automatically calls
     * {@link #cancel} to dismiss the presentation.
     *
     * @see #getDisplay
     */
    public void onDisplayChanged() {
    }

    private void handleDisplayRemoved() {
        onDisplayRemoved();
        cancel();
    }

    private void handleDisplayChanged() {
        onDisplayChanged();

        // We currently do not support configuration changes for presentations
        // (although we could add that feature with a bit more work).
        // If the display metrics have changed in any way then the current configuration
        // is invalid and the application must recreate the presentation to get
        // a new context.
        if (!isConfigurationStillValid()) {
            cancel();
        }
    }

    private boolean isConfigurationStillValid() {
        DisplayMetrics dm = new DisplayMetrics();
        mDisplay.getMetrics(dm);
        return dm.equals(getResources().getDisplayMetrics());
    }

    private static Context createPresentationContext(
            Context outerContext, Display display, int theme) {
        if (outerContext == null) {
            throw new IllegalArgumentException("outerContext must not be null");
        }
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }

        Context displayContext = outerContext.createDisplayContext(display);
        if (theme == 0) {
            TypedValue outValue = new TypedValue();
            displayContext.getTheme().resolveAttribute(
                    com.android.internal.R.attr.presentationTheme, outValue, true);
            theme = outValue.resourceId;
        }

        // Derive the display's window manager from the outer window manager.
        // We do this because the outer window manager have some extra information
        // such as the parent window, which is important if the presentation uses
        // an application window type.
        final WindowManagerImpl outerWindowManager =
                (WindowManagerImpl)outerContext.getSystemService(Context.WINDOW_SERVICE);
        final WindowManagerImpl displayWindowManager =
                outerWindowManager.createPresentationWindowManager(display);
        return new ContextThemeWrapper(displayContext, theme) {
            @Override
            public Object getSystemService(String name) {
                if (Context.WINDOW_SERVICE.equals(name)) {
                    return displayWindowManager;
                }
                return super.getSystemService(name);
            }
        };
    }

    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == mDisplay.getDisplayId()) {
                handleDisplayRemoved();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == mDisplay.getDisplayId()) {
                handleDisplayChanged();
            }
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CANCEL:
                    cancel();
                    break;
            }
        }
    };
}
