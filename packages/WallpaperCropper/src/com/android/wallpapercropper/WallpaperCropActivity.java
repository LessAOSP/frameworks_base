/*
 * Copyright (C) 2013 The Android Open Source Project
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
/* Copied from Launcher3 */
package com.android.wallpapercropper;

import android.app.ActionBar;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import com.android.gallery3d.common.Utils;
import com.android.photos.BitmapRegionTileSource;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

public class WallpaperCropActivity extends Activity {
    private static final String LOGTAG = "Launcher3.CropActivity";

    protected static final String WALLPAPER_WIDTH_KEY = "wallpaper.width";
    protected static final String WALLPAPER_HEIGHT_KEY = "wallpaper.height";
    private static final int DEFAULT_COMPRESS_QUALITY = 90;
    /**
     * The maximum bitmap size we allow to be returned through the intent.
     * Intents have a maximum of 1MB in total size. However, the Bitmap seems to
     * have some overhead to hit so that we go way below the limit here to make
     * sure the intent stays below 1MB.We should consider just returning a byte
     * array instead of a Bitmap instance to avoid overhead.
     */
    public static final int MAX_BMAP_IN_INTENT = 750000;
    private static final float WALLPAPER_SCREENS_SPAN = 2f;

    protected CropView mCropView;
    protected Uri mUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    protected void init() {
        setContentView(R.layout.wallpaper_cropper);

        mCropView = (CropView) findViewById(R.id.cropView);

        Intent cropIntent = this.getIntent();
        final Uri imageUri = cropIntent.getData();

        mCropView.setTileSource(new BitmapRegionTileSource(this, imageUri, 1024, 0), null);
        mCropView.setTouchEnabled(true);
        // Action bar
        // Show the custom action bar view
        final ActionBar actionBar = getActionBar();
        actionBar.setCustomView(R.layout.actionbar_set_wallpaper);
        actionBar.getCustomView().setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean finishActivityWhenDone = true;
                        cropImageAndSetWallpaper(imageUri, null, finishActivityWhenDone);
                    }
                });
    }

    public static String getSharedPreferencesKey() {
        return WallpaperCropActivity.class.getName();
    }

    // As a ratio of screen height, the total distance we want the parallax effect to span
    // horizontally
    private static float wallpaperTravelToScreenWidthRatio(int width, int height) {
        float aspectRatio = width / (float) height;

        // At an aspect ratio of 16/10, the wallpaper parallax effect should span 1.5 * screen width
        // At an aspect ratio of 10/16, the wallpaper parallax effect should span 1.2 * screen width
        // We will use these two data points to extrapolate how much the wallpaper parallax effect
        // to span (ie travel) at any aspect ratio:

        final float ASPECT_RATIO_LANDSCAPE = 16/10f;
        final float ASPECT_RATIO_PORTRAIT = 10/16f;
        final float WALLPAPER_WIDTH_TO_SCREEN_RATIO_LANDSCAPE = 1.5f;
        final float WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT = 1.2f;

        // To find out the desired width at different aspect ratios, we use the following two
        // formulas, where the coefficient on x is the aspect ratio (width/height):
        //   (16/10)x + y = 1.5
        //   (10/16)x + y = 1.2
        // We solve for x and y and end up with a final formula:
        final float x =
            (WALLPAPER_WIDTH_TO_SCREEN_RATIO_LANDSCAPE - WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT) /
            (ASPECT_RATIO_LANDSCAPE - ASPECT_RATIO_PORTRAIT);
        final float y = WALLPAPER_WIDTH_TO_SCREEN_RATIO_PORTRAIT - x * ASPECT_RATIO_PORTRAIT;
        return x * aspectRatio + y;
    }

    static protected Point getDefaultWallpaperSize(Resources res, WindowManager windowManager) {
        Point minDims = new Point();
        Point maxDims = new Point();
        windowManager.getDefaultDisplay().getCurrentSizeRange(minDims, maxDims);

        int maxDim = Math.max(maxDims.x, maxDims.y);
        final int minDim = Math.min(minDims.x, minDims.y);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Point realSize = new Point();
            windowManager.getDefaultDisplay().getRealSize(realSize);
            maxDim = Math.max(realSize.x, realSize.y);
        }

        // We need to ensure that there is enough extra space in the wallpaper
        // for the intended
        // parallax effects
        final int defaultWidth, defaultHeight;
        if (isScreenLarge(res)) {
            defaultWidth = (int) (maxDim * wallpaperTravelToScreenWidthRatio(maxDim, minDim));
            defaultHeight = maxDim;
        } else {
            defaultWidth = Math.max((int) (minDim * WALLPAPER_SCREENS_SPAN), maxDim);
            defaultHeight = maxDim;
        }
        return new Point(defaultWidth, defaultHeight);
    }

    protected void setWallpaper(String filePath, final boolean finishActivityWhenDone) {

        BitmapCropTask cropTask = new BitmapCropTask(this,
                filePath, null, 0, 0, true, false, null);
        final Point bounds = cropTask.getImageBounds();
        Runnable onEndCrop = new Runnable() {
            public void run() {
                updateWallpaperDimensions(bounds.x, bounds.y);
                if (finishActivityWhenDone) {
                    setResult(Activity.RESULT_OK);
                    finish();
                }
            }
        };
        cropTask.setOnEndRunnable(onEndCrop);
        cropTask.setNoCrop(true);
        cropTask.execute();
    }

    protected void cropImageAndSetWallpaper(
            Resources res, int resId, final boolean finishActivityWhenDone) {
        // crop this image and scale it down to the default wallpaper size for
        // this device
        Point inSize = mCropView.getSourceDimensions();
        Point outSize = getDefaultWallpaperSize(getResources(),
                getWindowManager());
        RectF crop = getMaxCropRect(
                inSize.x, inSize.y, outSize.x, outSize.y, false);
        Runnable onEndCrop = new Runnable() {
            public void run() {
                // Passing 0, 0 will cause launcher to revert to using the
                // default wallpaper size
                updateWallpaperDimensions(0, 0);
                if (finishActivityWhenDone) {
                    setResult(Activity.RESULT_OK);
                    finish();
                }
            }
        };
        BitmapCropTask cropTask = new BitmapCropTask(res, resId,
                crop, outSize.x, outSize.y,
                true, false, onEndCrop);
        cropTask.execute();
    }

    private static boolean isScreenLarge(Resources res) {
        Configuration config = res.getConfiguration();
        return config.smallestScreenWidthDp >= 720;
    }

    protected void cropImageAndSetWallpaper(Uri uri,
            OnBitmapCroppedHandler onBitmapCroppedHandler, final boolean finishActivityWhenDone) {
     // Get the crop
        Point inSize = mCropView.getSourceDimensions();

        Point minDims = new Point();
        Point maxDims = new Point();
        Display d = getWindowManager().getDefaultDisplay();
        d.getCurrentSizeRange(minDims, maxDims);

        Point displaySize = new Point();
        d.getSize(displaySize);

        int maxDim = Math.max(maxDims.x, maxDims.y);
        final int minDim = Math.min(minDims.x, minDims.y);
        int defaultWidth;
        if (isScreenLarge(getResources())) {
            defaultWidth = (int) (maxDim *
                    wallpaperTravelToScreenWidthRatio(maxDim, minDim));
        } else {
            defaultWidth = Math.max((int)
                    (minDim * WALLPAPER_SCREENS_SPAN), maxDim);
        }

        boolean isPortrait = displaySize.x < displaySize.y;
        int portraitHeight;
        if (isPortrait) {
            portraitHeight = mCropView.getHeight();
        } else {
            // TODO: how to actually get the proper portrait height?
            // This is not quite right:
            portraitHeight = Math.max(maxDims.x, maxDims.y);
        }
        if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Point realSize = new Point();
            d.getRealSize(realSize);
            portraitHeight = Math.max(realSize.x, realSize.y);
        }
        // Get the crop
        RectF cropRect = mCropView.getCrop();
        float cropScale = mCropView.getWidth() / (float) cropRect.width();

        // ADJUST CROP WIDTH
        // Extend the crop all the way to the right, for parallax
        float extraSpaceToRight = inSize.x - cropRect.right;
        // Cap the amount of extra width
        float maxExtraSpace = defaultWidth / cropScale - cropRect.width();
        extraSpaceToRight = Math.min(extraSpaceToRight, maxExtraSpace);

        cropRect.right += extraSpaceToRight;

        // ADJUST CROP HEIGHT
        if (isPortrait) {
            cropRect.bottom = cropRect.top + portraitHeight / cropScale;
        } else { // LANDSCAPE
            float extraPortraitHeight =
                    portraitHeight / cropScale - cropRect.height();
            float expandHeight =
                    Math.min(Math.min(inSize.y - cropRect.bottom, cropRect.top),
                            extraPortraitHeight / 2);
            cropRect.top -= expandHeight;
            cropRect.bottom += expandHeight;
        }
        final int outWidth = (int) Math.round(cropRect.width() * cropScale);
        final int outHeight = (int) Math.round(cropRect.height() * cropScale);

        Runnable onEndCrop = new Runnable() {
            public void run() {
                updateWallpaperDimensions(outWidth, outHeight);
                if (finishActivityWhenDone) {
                    setResult(Activity.RESULT_OK);
                    finish();
                }
            }
        };
        BitmapCropTask cropTask = new BitmapCropTask(uri,
                cropRect, outWidth, outHeight, true, false, onEndCrop);
        if (onBitmapCroppedHandler != null) {
            cropTask.setOnBitmapCropped(onBitmapCroppedHandler);
        }
        cropTask.execute();
    }

    public interface OnBitmapCroppedHandler {
        public void onBitmapCropped(byte[] imageBytes);
    }

    protected class BitmapCropTask extends AsyncTask<Void, Void, Boolean> {
        Uri mInUri = null;
        Context mContext;
        String mInFilePath;
        byte[] mInImageBytes;
        int mInResId = 0;
        InputStream mInStream;
        RectF mCropBounds = null;
        int mOutWidth, mOutHeight;
        int mRotation = 0; // for now
        protected final WallpaperManager mWPManager;
        String mOutputFormat = "jpg"; // for now
        boolean mSetWallpaper;
        boolean mSaveCroppedBitmap;
        Bitmap mCroppedBitmap;
        Runnable mOnEndRunnable;
        Resources mResources;
        OnBitmapCroppedHandler mOnBitmapCroppedHandler;
        boolean mNoCrop;

        public BitmapCropTask(Context c, String filePath,
                RectF cropBounds, int outWidth, int outHeight,
                boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
            mContext = c;
            mInFilePath = filePath;
            mWPManager = WallpaperManager.getInstance(getApplicationContext());
            init(cropBounds, outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
        }

        public BitmapCropTask(byte[] imageBytes,
                RectF cropBounds, int outWidth, int outHeight,
                boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
            mInImageBytes = imageBytes;
            mWPManager = WallpaperManager.getInstance(getApplicationContext());
            init(cropBounds, outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
        }

        public BitmapCropTask(Uri inUri,
                RectF cropBounds, int outWidth, int outHeight,
                boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
            mInUri = inUri;
            mWPManager = WallpaperManager.getInstance(getApplicationContext());
            init(cropBounds, outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
        }

        public BitmapCropTask(Resources res, int inResId,
                RectF cropBounds, int outWidth, int outHeight,
                boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
            mInResId = inResId;
            mResources = res;
            mWPManager = WallpaperManager.getInstance(getApplicationContext());
            init(cropBounds, outWidth, outHeight, setWallpaper, saveCroppedBitmap, onEndRunnable);
        }

        private void init(RectF cropBounds, int outWidth, int outHeight,
                boolean setWallpaper, boolean saveCroppedBitmap, Runnable onEndRunnable) {
            mCropBounds = cropBounds;
            mOutWidth = outWidth;
            mOutHeight = outHeight;
            mSetWallpaper = setWallpaper;
            mSaveCroppedBitmap = saveCroppedBitmap;
            mOnEndRunnable = onEndRunnable;
        }

        public void setOnBitmapCropped(OnBitmapCroppedHandler handler) {
            mOnBitmapCroppedHandler = handler;
        }

        public void setNoCrop(boolean value) {
            mNoCrop = value;
        }

        public void setOnEndRunnable(Runnable onEndRunnable) {
            mOnEndRunnable = onEndRunnable;
        }

        // Helper to setup input stream
        private void regenerateInputStream() {
            if (mInUri == null && mInResId == 0 && mInFilePath == null && mInImageBytes == null) {
                Log.w(LOGTAG, "cannot read original file, no input URI, resource ID, or " +
                        "image byte array given");
            } else {
                Utils.closeSilently(mInStream);
                try {
                    if (mInUri != null) {
                        mInStream = new BufferedInputStream(
                                getContentResolver().openInputStream(mInUri));
                    } else if (mInFilePath != null) {
                        mInStream = mContext.openFileInput(mInFilePath);
                    } else if (mInImageBytes != null) {
                        mInStream = new BufferedInputStream(
                                new ByteArrayInputStream(mInImageBytes));
                    } else {
                        mInStream = new BufferedInputStream(
                                mResources.openRawResource(mInResId));
                    }
                } catch (FileNotFoundException e) {
                    Log.w(LOGTAG, "cannot read file: " + mInUri.toString(), e);
                }
            }
        }

        public Point getImageBounds() {
            regenerateInputStream();
            if (mInStream != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(mInStream, null, options);
                if (options.outWidth != 0 && options.outHeight != 0) {
                    return new Point(options.outWidth, options.outHeight);
                }
            }
            return null;
        }

        public void setCropBounds(RectF cropBounds) {
            mCropBounds = cropBounds;
        }

        public Bitmap getCroppedBitmap() {
            return mCroppedBitmap;
        }
        public boolean cropBitmap() {
            boolean failure = false;

            regenerateInputStream();

            if (mNoCrop && mInStream != null) {
                try {
                    mWPManager.setStream(mInStream);
                } catch (IOException e) {
                    Log.w(LOGTAG, "cannot write stream to wallpaper", e);
                    failure = true;
                }
                if (mOnEndRunnable != null) {
                    mOnEndRunnable.run();
                }
                return !failure;
            }
            if (mInStream != null) {
                // Find crop bounds (scaled to original image size)
                Rect roundedTrueCrop = new Rect();
                mCropBounds.roundOut(roundedTrueCrop);

                if (roundedTrueCrop.width() <= 0 || roundedTrueCrop.height() <= 0) {
                    Log.w(LOGTAG, "crop has bad values for full size image");
                    failure = true;
                    return false;
                }

                // See how much we're reducing the size of the image
                int scaleDownSampleSize = Math.min(roundedTrueCrop.width() / mOutWidth,
                        roundedTrueCrop.height() / mOutHeight);

                // Attempt to open a region decoder
                BitmapRegionDecoder decoder = null;
                try {
                    decoder = BitmapRegionDecoder.newInstance(mInStream, true);
                } catch (IOException e) {
                    Log.w(LOGTAG, "cannot open region decoder for file: " + mInUri.toString(), e);
                }

                Bitmap crop = null;
                if (decoder != null) {
                    // Do region decoding to get crop bitmap
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    if (scaleDownSampleSize > 1) {
                        options.inSampleSize = scaleDownSampleSize;
                    }
                    crop = decoder.decodeRegion(roundedTrueCrop, options);
                    decoder.recycle();
                }

                if (crop == null) {
                    // BitmapRegionDecoder has failed, try to crop in-memory
                    regenerateInputStream();
                    Bitmap fullSize = null;
                    if (mInStream != null) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        if (scaleDownSampleSize > 1) {
                            options.inSampleSize = scaleDownSampleSize;
                        }
                        fullSize = BitmapFactory.decodeStream(mInStream, null, options);
                    }
                    if (fullSize != null) {
                        crop = Bitmap.createBitmap(fullSize, roundedTrueCrop.left,
                                roundedTrueCrop.top, roundedTrueCrop.width(),
                                roundedTrueCrop.height());
                    }
                }

                if (crop == null) {
                    Log.w(LOGTAG, "cannot decode file: " + mInUri.toString());
                    failure = true;
                    return false;
                }
                if (mOutWidth > 0 && mOutHeight > 0) {
                    Matrix m = new Matrix();
                    RectF cropRect = new RectF(0, 0, crop.getWidth(), crop.getHeight());
                    if (mRotation > 0) {
                        m.setRotate(mRotation);
                        m.mapRect(cropRect);
                    }
                    RectF returnRect = new RectF(0, 0, mOutWidth, mOutHeight);
                    m.setRectToRect(cropRect, returnRect, Matrix.ScaleToFit.FILL);
                    m.preRotate(mRotation);
                    Bitmap tmp = Bitmap.createBitmap((int) returnRect.width(),
                            (int) returnRect.height(), Bitmap.Config.ARGB_8888);
                    if (tmp != null) {
                        Canvas c = new Canvas(tmp);
                        c.drawBitmap(crop, m, new Paint());
                        crop = tmp;
                    }
                } else if (mRotation > 0) {
                    Matrix m = new Matrix();
                    m.setRotate(mRotation);
                    Bitmap tmp = Bitmap.createBitmap(crop, 0, 0, crop.getWidth(),
                            crop.getHeight(), m, true);
                    if (tmp != null) {
                        crop = tmp;
                    }
                }

                if (mSaveCroppedBitmap) {
                    mCroppedBitmap = crop;
                }

                // Get output compression format
                CompressFormat cf =
                        convertExtensionToCompressFormat(getFileExtension(mOutputFormat));

                // Compress to byte array
                ByteArrayOutputStream tmpOut = new ByteArrayOutputStream(2048);
                if (crop.compress(cf, DEFAULT_COMPRESS_QUALITY, tmpOut)) {
                    // If we need to set to the wallpaper, set it
                    if (mSetWallpaper && mWPManager != null) {
                        if (mWPManager == null) {
                            Log.w(LOGTAG, "no wallpaper manager");
                            failure = true;
                        } else {
                            try {
                                byte[] outByteArray = tmpOut.toByteArray();
                                mWPManager.setStream(new ByteArrayInputStream(outByteArray));
                                if (mOnBitmapCroppedHandler != null) {
                                    mOnBitmapCroppedHandler.onBitmapCropped(outByteArray);
                                }
                            } catch (IOException e) {
                                Log.w(LOGTAG, "cannot write stream to wallpaper", e);
                                failure = true;
                            }
                        }
                    }
                    if (mOnEndRunnable != null) {
                        mOnEndRunnable.run();
                    }
                } else {
                    Log.w(LOGTAG, "cannot compress bitmap");
                    failure = true;
                }
            }
            return !failure; // True if any of the operations failed
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            return cropBitmap();
        }

        @Override
        protected void onPostExecute(Boolean result) {
            setResult(Activity.RESULT_OK);
            finish();
        }
    }

    protected void updateWallpaperDimensions(int width, int height) {
        String spKey = getSharedPreferencesKey();
        SharedPreferences sp = getSharedPreferences(spKey, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        if (width != 0 && height != 0) {
            editor.putInt(WALLPAPER_WIDTH_KEY, width);
            editor.putInt(WALLPAPER_HEIGHT_KEY, height);
        } else {
            editor.remove(WALLPAPER_WIDTH_KEY);
            editor.remove(WALLPAPER_HEIGHT_KEY);
        }
        editor.commit();

        suggestWallpaperDimension(getResources(),
                sp, getWindowManager(), WallpaperManager.getInstance(this));
    }

    static public void suggestWallpaperDimension(Resources res,
            final SharedPreferences sharedPrefs,
            WindowManager windowManager,
            final WallpaperManager wallpaperManager) {
        final Point defaultWallpaperSize =
                WallpaperCropActivity.getDefaultWallpaperSize(res, windowManager);

        new Thread("suggestWallpaperDimension") {
            public void run() {
                // If we have saved a wallpaper width/height, use that instead
                int savedWidth = sharedPrefs.getInt(WALLPAPER_WIDTH_KEY, defaultWallpaperSize.x);
                int savedHeight = sharedPrefs.getInt(WALLPAPER_HEIGHT_KEY, defaultWallpaperSize.y);
                wallpaperManager.suggestDesiredDimensions(savedWidth, savedHeight);
            }
        }.start();
    }


    protected static RectF getMaxCropRect(
            int inWidth, int inHeight, int outWidth, int outHeight, boolean leftAligned) {
        RectF cropRect = new RectF();
        // Get a crop rect that will fit this
        if (inWidth / (float) inHeight > outWidth / (float) outHeight) {
             cropRect.top = 0;
             cropRect.bottom = inHeight;
             cropRect.left = (inWidth - (outWidth / (float) outHeight) * inHeight) / 2;
             cropRect.right = inWidth - cropRect.left;
             if (leftAligned) {
                 cropRect.right -= cropRect.left;
                 cropRect.left = 0;
             }
        } else {
            cropRect.left = 0;
            cropRect.right = inWidth;
            cropRect.top = (inHeight - (outHeight / (float) outWidth) * inWidth) / 2;
            cropRect.bottom = inHeight - cropRect.top;
        }
        return cropRect;
    }

    protected static CompressFormat convertExtensionToCompressFormat(String extension) {
        return extension.equals("png") ? CompressFormat.PNG : CompressFormat.JPEG;
    }

    protected static String getFileExtension(String requestFormat) {
        String outputFormat = (requestFormat == null)
                ? "jpg"
                : requestFormat;
        outputFormat = outputFormat.toLowerCase();
        return (outputFormat.equals("png") || outputFormat.equals("gif"))
                ? "png" // We don't support gif compression.
                : "jpg";
    }
}
