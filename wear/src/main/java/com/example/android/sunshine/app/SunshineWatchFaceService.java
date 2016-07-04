package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private Typeface robotoRegular;
    private Typeface robotoLight;

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHourPaint , mMinPaint, mDatePaint;
        Paint mLinePaint;
        Paint mMinTPaint, mMaxTPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        String date;
        float mDateYOffset;
        float mLineYOffset;

        float mMaxTXOffset, mMinTXOffset;
        float mTempYOffset;
        float mAmbientCenterOffset;

        float bitmapXOffset, bitmapYOffset;

        Bitmap graphic;

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            robotoRegular = Typeface.createFromAsset(getAssets(),"Roboto-Regular.ttf");
            robotoLight = Typeface.createFromAsset(getAssets(), "Roboto-Light.ttf");

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), robotoRegular);
            mMinPaint = createTextPaint(resources.getColor(R.color.digital_text), robotoLight);
            mDatePaint = createTextPaint(resources.getColor(R.color.date), robotoLight);

            mLinePaint = new Paint();
            mLinePaint.setStrokeWidth(1.0f);
            mLinePaint.setColor(resources.getColor(R.color.date));

            mMaxTPaint = createTextPaint(resources.getColor(R.color.digital_text), robotoRegular);
            mMinTPaint = createTextPaint(resources.getColor(R.color.date), robotoLight);

            mTime = new Time();
            updateDate();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            updateTimer();
            updateDate();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mHourPaint.setTextSize(textSize);
            mMinPaint.setTextSize(textSize);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);

            mLineYOffset = resources.getDimension(R.dimen.seper_line_y_offset);

            mMaxTXOffset = resources.getDimension(R.dimen.temp_max_x_offset);
            mMinTXOffset = resources.getDimension(R.dimen.temp_min_x_offset);
            mTempYOffset = resources.getDimension(R.dimen.temp_y_offset);
            mAmbientCenterOffset = resources.getDimension(R.dimen.temp_center_offset_ambient);

            float tempSize = resources.getDimension(R.dimen.temp_text_size);
            mMaxTPaint.setTextSize(tempSize);
            mMinTPaint.setTextSize(tempSize);

            graphic = BitmapFactory.decodeResource(getResources(), R.drawable.ic_clear);
            bitmapXOffset = resources.getDimension(R.dimen.bitmap_x_offset);
            bitmapYOffset = resources.getDimension(R.dimen.bitmap_y_offset);

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
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mMaxTPaint.setAntiAlias(!inAmbientMode);
                    mMinTPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            updateTimer();
            updateDate();
        }

//        @Override
//        public void onTapCommand(int tapType, int x, int y, long eventTime) {
//            Resources resources = SunshineWatchFaceService.this.getResources();
//            switch (tapType) {
//                case TAP_TYPE_TOUCH:
//                    // The user has started touching the screen.
//                    break;
//                case TAP_TYPE_TOUCH_CANCEL:
//                    // The user has started a different gesture or otherwise cancelled the tap.
//                    break;
//                case TAP_TYPE_TAP:
//                    // The user has completed the tap gesture.
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
//                    break;
//            }
//            invalidate();
//        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            int centerX = bounds.centerX();
            String maxTemp = "25°";
            String minTemp = "15°";

            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                mLinePaint.setColor(getColor(R.color.digital_text));
                mDatePaint.setColor(getColor(R.color.digital_text));
                mMinTPaint.setColor(getColor(R.color.digital_text));

                canvas.drawText(maxTemp, centerX - (mMaxTPaint.measureText(maxTemp)+mAmbientCenterOffset), mTempYOffset, mMaxTPaint);
                canvas.drawText(minTemp, centerX + mAmbientCenterOffset, mTempYOffset, mMinTPaint);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                mLinePaint.setColor(getColor(R.color.date));
                mDatePaint.setColor(getColor(R.color.date));
                mMinTPaint.setColor(getColor(R.color.date));

                canvas.drawBitmap(graphic, bitmapXOffset, bitmapYOffset, null);
                canvas.drawText("25°", mMaxTXOffset, mTempYOffset, mMaxTPaint);
                canvas.drawText("15°", mMinTXOffset, mTempYOffset, mMinTPaint);
            }

            mTime.setToNow();
//            String text = mAmbient
//                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
//                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            String text = String.format("%d", mTime.hour, mTime.minute);
            String colon = ":";
            float colonLength = mHourPaint.measureText(colon)/2;

            canvas.drawText(text, centerX - (mHourPaint.measureText(text)+colonLength), mYOffset, mHourPaint);
            canvas.drawText(colon, centerX - colonLength , mYOffset, mHourPaint);

            text = String.format("%02d", mTime.minute);
            canvas.drawText(text, centerX+colonLength, mYOffset, mMinPaint);

            canvas.drawText(date, centerX - mDatePaint.measureText(date)/2, mDateYOffset, mDatePaint);

            canvas.drawLine(centerX - 30 , mLineYOffset, centerX + 30 , mLineYOffset, mLinePaint);

        }


        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        private void updateDate(){
                Calendar calendar = GregorianCalendar.getInstance();
                DateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
                date = dateFormat.format(calendar.getTime()).toUpperCase();
        }


        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
