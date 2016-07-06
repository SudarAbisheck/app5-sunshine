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
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatchFaceService.class.getName();

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

    private class Engine extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
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

        float mXOffset;
        float mYOffset;

        private static final String colon = ":";
        String date;
        float mDateYOffset;
        float mLineYOffset;

        float mMaxTXOffset, mMinTXOffset;
        float mTempYOffset;
        float mAmbientCenterOffset;

        float bitmapXOffset, bitmapYOffset;

        Bitmap graphic;
        String weatherDesc = "", minTemp = "", maxTemp = "";

        boolean mLowBitAmbient;
        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        private String nodeId;

        private static final String GET_WEATHER_DATA_PATH = "/get-weather-data";
        private static final String WEATHER_UPDATE_PATH = "/update";
        private static final String IMAGE_KEY = "photo";
        private static final String DESC_KEY = "desc";
        private static final String MAX_TEMP_KEY = "max-temp";
        private static final String MIN_TEMP_KEY = "min-temp";

        private SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        private static final String IMAGE_PREF_KEY = "image_string";
        private static final String MIN_TEMP_PREF_KEY = "max_string";
        private static final String MAX_TEMP_PREF_KEY = "min_string";

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setHotwordIndicatorGravity(Gravity.END | Gravity.TOP)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();

            Typeface robotoRegular = Typeface.createFromAsset(getAssets(), "Roboto-Regular.ttf");
            Typeface robotoLight = Typeface.createFromAsset(getAssets(), "Roboto-Light.ttf");

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
                mGoogleApiClient.connect();
                registerReceiver();

                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
                
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            updateTimer();
            updateDate();
            updateWeather();
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

//            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mHourPaint.setTextSize(textSize);
            mMinPaint.setTextSize(textSize);

            textSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            mDatePaint.setTextSize(textSize);

//            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
//
//            mLineYOffset = resources.getDimension(R.dimen.seper_line_y_offset);
//
            mMaxTXOffset = resources.getDimension(R.dimen.temp_max_x_offset);
            mMinTXOffset = resources.getDimension(R.dimen.temp_min_x_offset);
//            mTempYOffset = resources.getDimension(R.dimen.temp_y_offset);
            mAmbientCenterOffset = resources.getDimension(R.dimen.temp_center_offset_ambient);

            float tempSize = resources.getDimension(R.dimen.temp_text_size);
            mMaxTPaint.setTextSize(tempSize);
            mMinTPaint.setTextSize(tempSize);

//            graphic = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);
            bitmapXOffset = resources.getDimension(R.dimen.bitmap_x_offset);
//            bitmapYOffset = resources.getDimension(R.dimen.bitmap_y_offset);

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
            updateWeather();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            int centerX = bounds.centerX();
            int height = canvas.getHeight();

            mYOffset = (float)(height*0.4);
            mDateYOffset = (float)(height*0.54);
            mLineYOffset = (float)(height*0.6);
            mTempYOffset = (float)(height*0.8);
            bitmapYOffset = (float)(height*0.6);


            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                mLinePaint.setColor(getColor(R.color.digital_text));
                mDatePaint.setColor(getColor(R.color.digital_text));
                mMinTPaint.setColor(getColor(R.color.digital_text));

                if(maxTemp != null)
                    canvas.drawText(maxTemp, centerX - (mMaxTPaint.measureText(maxTemp)+mAmbientCenterOffset), mTempYOffset, mMaxTPaint);
                if(minTemp != null)
                    canvas.drawText(minTemp, centerX + mAmbientCenterOffset, mTempYOffset, mMinTPaint);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                mLinePaint.setColor(getColor(R.color.date));
                mDatePaint.setColor(getColor(R.color.date));
                mMinTPaint.setColor(getColor(R.color.date));

                if(graphic != null)
                    canvas.drawBitmap(graphic, bitmapXOffset, bitmapYOffset, null);
                if(maxTemp != null)
                    canvas.drawText(maxTemp, mMaxTXOffset, mTempYOffset, mMaxTPaint);
                if(minTemp != null)
                    canvas.drawText(minTemp, mMinTXOffset, mTempYOffset, mMinTPaint);
            }

            mTime.setToNow();

            String text = String.format("%d", mTime.hour, mTime.minute);
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
            DateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            date = dateFormat.format(calendar.getTime()).toUpperCase();
        }

        private void updateWeather(){
            maxTemp = prefs.getString(MAX_TEMP_PREF_KEY,null);
            minTemp = prefs.getString(MIN_TEMP_PREF_KEY,null);
            graphic = decodeBase64(prefs.getString(IMAGE_PREF_KEY, null));
            if(maxTemp == null || minTemp == null || graphic == null){
                Wearable.MessageApi.sendMessage(mGoogleApiClient, nodeId, GET_WEATHER_DATA_PATH, null);
            }
        }

        private void retrieveDeviceNode() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if ( !mGoogleApiClient.isConnected() )
                        return;

                    NodeApi.GetConnectedNodesResult result =
                            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

                    List<Node> nodes = result.getNodes();

                    if (nodes.size() > 0)
                        nodeId = nodes.get(0).getId();

                    Log.d(TAG, "Node ID of phone: " + nodeId);
                }
            }).start();
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



        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged: " + dataEventBuffer);
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    Uri uri = event.getDataItem().getUri();
                    String path = uri.getPath();
                    if(WEATHER_UPDATE_PATH.equals(path)){
                        DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                        DataMap dataMap = dataMapItem.getDataMap();
                        weatherDesc = dataMap.getString(DESC_KEY);
                        minTemp = dataMap.getString(MIN_TEMP_KEY);
                        maxTemp = dataMap.getString(MAX_TEMP_KEY);
                        Asset imageAsset = dataMap.getAsset(IMAGE_KEY);
                        // Loads image on background thread.
                        new LoadBitmapAsyncTask().execute(imageAsset);

                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString(MAX_TEMP_PREF_KEY, maxTemp);
                        editor.putString(MIN_TEMP_PREF_KEY, minTemp);
                        editor.apply();
                    }
                }
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.i(TAG, "onConnected: ");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            retrieveDeviceNode();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.i(TAG, "onConnectionSuspended: ");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.i(TAG, "onConnectionFailed: ");
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {
                    Log.d(TAG, "Changing bitmap . . .");

                    graphic = persistBitmap(bitmap);
                    invalidate();
                }
            }
        }

        public Bitmap persistBitmap(Bitmap image)
        {
            ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOS);
            String encStr = Base64.encodeToString(byteArrayOS.toByteArray(), Base64.DEFAULT);

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(IMAGE_PREF_KEY, encStr);
            editor.apply();
            return image;
        }

        public Bitmap decodeBase64(String input)
        {
            if(input == null) return null;
            byte[] decodedBytes = Base64.decode(input, 0);
            return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
        }
    }
}
