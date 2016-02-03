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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final String KEY_ICON = "icon";
    private static final String KEY_HIGH = "high";
    private static final String KEY_LOW = "low";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements ConnectionCallbacks,
            DataApi.DataListener {
        static final String WEATHER_PATH = "/weather";
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mIconPaint;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mStrokePaint;
        Paint mHighPaint;
        Paint mLowPaint;
        ColorMatrixColorFilter mGreyscaleFilter;
        boolean mAmbient;
        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;
        GoogleApiClient mGoogleApiClient;

        Bitmap mScaledIcon;
        int mWeatherId;
        String mHigh;
        String mLow;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };
        float mYOffset;
        float mLineHeight;

        int mBlack, mBlue;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            int white = resources.getColor(R.color.white);
            int whiteFade = resources.getColor(R.color.white_fade);

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBlack = resources.getColor(R.color.black);
            mBlue = resources.getColor(R.color.blue_500);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mIconPaint = new Paint();
            mIconPaint.setAntiAlias(true);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mBlue);

            mTextPaint = createTextPaint(white);
            mDatePaint = createTextPaint(whiteFade);
            mHighPaint = createTextPaint(white);
            mLowPaint = createTextPaint(whiteFade);

            mStrokePaint = new Paint();
            mStrokePaint.setColor(whiteFade);
            mStrokePaint.setStrokeWidth(1);

            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            mGreyscaleFilter = new ColorMatrixColorFilter(cm);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .build();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SunshineWatchFace.this);
            setIcon(prefs.getInt(KEY_ICON, 800));
            mHigh = prefs.getString(KEY_HIGH, "--\u00B0");
            mLow = prefs.getString(KEY_LOW, "--\u00B0");
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Log.i(TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i(TAG, "onConnectionSuspended");
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.i(TAG, "onDataChanged");
            //Only interested in last weather event
            for(int i = dataEventBuffer.getCount() - 1; i >= 0; i--){
                DataEvent event = dataEventBuffer.get(i);
                if(event.getType() == DataEvent.TYPE_CHANGED
                        && WEATHER_PATH.equals(event.getDataItem().getUri().getPath())){
                    Log.i(TAG, new String(event.getDataItem().getData()));
                    try {
                        JSONObject json = new JSONObject(new String(event.getDataItem().getData()));
                        setIcon(json.getInt(KEY_ICON));
                        mHigh = json.getString(KEY_HIGH);
                        mLow = json.getString(KEY_LOW);

                        //Save to shared preferences
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SunshineWatchFace.this);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putInt(KEY_ICON, mWeatherId);
                        editor.putString(KEY_HIGH, mHigh);
                        editor.putString(KEY_LOW, mLow);
                        Log.i(TAG, mWeatherId + " " + mHigh + " " + mLow);
                    } catch (JSONException e) {
                        Log.e("Watch Face Engine", e.getMessage());
                    }
                    break;
                }
            }
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                if(mGoogleApiClient.isConnected() || mGoogleApiClient.isConnecting()){
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(R.dimen.digital_text_size);
            float dateSize = resources.getDimension(R.dimen.digital_date_size);
            float temperatureSize = resources.getDimension(R.dimen.digital_temperature_size);

            mTextPaint.setTextSize(textSize);
            mDatePaint.setTextSize(dateSize);
            mHighPaint.setTextSize(temperatureSize);
            mLowPaint.setTextSize(temperatureSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;

                adjustPaintColorToCurrentMode(mBackgroundPaint, mBlue, mBlack);

                mIconPaint.setColorFilter(inAmbientMode ? mGreyscaleFilter : null);

                if (mLowBitAmbient) {
                    mIconPaint.setAntiAlias(!inAmbientMode);
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            canvas.drawColor(mBackgroundPaint.getColor());

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            int half = canvas.getWidth()/2;

            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            canvas.drawText(text, half, mYOffset, mTextPaint);

            canvas.drawText(mDateFormat.format(mDate).toUpperCase(),
                    half, mYOffset + mLineHeight, mDatePaint);

            canvas.drawLine(half - mLineHeight, mYOffset + 2 * mLineHeight,
                    half + mLineHeight, mYOffset + 2 * mLineHeight, mStrokePaint);

            canvas.drawBitmap(mScaledIcon, half - 3 * mLineHeight, mYOffset + 4 * mLineHeight - mScaledIcon.getHeight(), mIconPaint);

            canvas.drawText(mHigh, half, mYOffset + 3 * mLineHeight + mHighPaint.getTextSize()/2, mHighPaint);

            canvas.drawText(mLow, half + 2 * mLineHeight, mYOffset + 3 * mLineHeight + mLowPaint.getTextSize()/2, mLowPaint);
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat(getResources().getString(R.string.date_format), Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void setIcon(int resId){
            if(mWeatherId != resId) {
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), Utility.getIconResourceForWeatherCondition(resId));
                if(bitmap != null){
                    mScaledIcon = bitmap;
                    mWeatherId = resId;
                }
            }
        }
    }
}
