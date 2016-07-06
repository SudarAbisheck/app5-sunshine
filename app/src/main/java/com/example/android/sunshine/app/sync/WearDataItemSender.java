package com.example.android.sunshine.app.sync;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * Created by sudar on 6/7/16.
 * Email : hey@sudar.me
 */
public class WearDataItemSender
        implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    public static final String LOG_TAG = WearDataItemSender.class.getName();

    private Context context;
    private GoogleApiClient mGoogleApiClient;


    private static final String WEATHER_UPDATE_PATH = "/update";
    private static final String IMAGE_KEY = "photo";
    private static final String DESC_KEY = "desc";
    private static final String MAX_TEMP_KEY = "max-temp";
    private static final String MIN_TEMP_KEY = "min-temp";


    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;
    private static final int INDEX_SHORT_DESC = 3;

    WearDataItemSender(Context context){
        this.context = context;
        this.mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();
    }

    public void updateWearData(){
        String locationQuery = Utility.getPreferredLocation(context);

        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        // we'll query our contentProvider, as always
        Cursor cursor = context.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);
            String desc = cursor.getString(INDEX_SHORT_DESC);

            int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
            Resources resources = context.getResources();
            int artResourceId = Utility.getArtResourceForWeatherCondition(weatherId);
            String artUrl = Utility.getArtUrlForWeatherCondition(context, weatherId);

            // On Honeycomb and higher devices, we can retrieve the size of the large icon
            // Prior to that, we use a fixed size
            @SuppressLint("InlinedApi")
            int largeIconWidth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                    ? resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
                    : resources.getDimensionPixelSize(R.dimen.notification_large_icon_default);
            @SuppressLint("InlinedApi")
            int largeIconHeight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                    ? resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
                    : resources.getDimensionPixelSize(R.dimen.notification_large_icon_default);

            // Retrieve the large icon
            Bitmap largeIcon;
            try {
                largeIcon = Glide.with(context)
                        .load(artUrl)
                        .asBitmap()
                        .error(artResourceId)
                        .fitCenter()
                        .into(largeIconWidth, largeIconHeight).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(LOG_TAG, "Error retrieving large icon from " + artUrl,e);
                largeIcon = BitmapFactory.decodeResource(resources, artResourceId);
            }

            sendDataToWear(largeIcon,desc, high, low);
        }
    }

    private void sendDataToWear(Bitmap largeIcon, String desc, double high, double low){
        Log.d(LOG_TAG, "Sending data to wear");
        //Sending data to the Android Wear Watchface


        if (null != largeIcon && mGoogleApiClient.isConnected()) {
            sendDataItems(toAsset(largeIcon), desc,
                    Utility.formatTemperature(context, high),
                    Utility.formatTemperature(context, low));
            Log.d(LOG_TAG, "sendDataToWear: "+desc+ high + low);
        }
    }


    private static Asset toAsset(Bitmap bitmap) {
        ByteArrayOutputStream byteStream = null;
        try {
            bitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, false);
            byteStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            return Asset.createFromBytes(byteStream.toByteArray());
        } finally {
            if (null != byteStream) {
                try {
                    byteStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private void sendDataItems(Asset asset, String desc, String max, String min) {
        PutDataMapRequest dataMap = PutDataMapRequest.create(WEATHER_UPDATE_PATH);
        dataMap.getDataMap().putAsset(IMAGE_KEY, asset);
        dataMap.getDataMap().putString(DESC_KEY, desc);
        dataMap.getDataMap().putString(MAX_TEMP_KEY, max);
        dataMap.getDataMap().putString(MIN_TEMP_KEY, min);
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent();

        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        Log.d(LOG_TAG, "Sending image was successful: " + dataItemResult.getStatus()
                                .isSuccess());
                    }
                });
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(LOG_TAG, "Google API Client was connected");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "Connection to Google API client was suspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.e(LOG_TAG, "Connection to Google API client has failed");
    }
}
