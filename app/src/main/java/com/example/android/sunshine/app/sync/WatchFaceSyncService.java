package com.example.android.sunshine.app.sync;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * An {@link IntentService} subclass for updating weather information on a wear watchface.
 */
public class WatchFaceSyncService extends IntentService {
    private static final String[] PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };
    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    private GoogleApiClient mGoogleApiClient;

    public WatchFaceSyncService() {
        super("WatchFaceSyncService");
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, WatchFaceSyncService.class);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Wearable.API)
                    .build();

            ConnectionResult result = mGoogleApiClient.blockingConnect(30, TimeUnit.SECONDS);

            if (result.isSuccess()) {
                // Retrieve data from provider
                String locationQuery = Utility.getPreferredLocation(this);

                Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

                Cursor cursor = getContentResolver().query(weatherUri, PROJECTION, null, null, null);

                if (cursor.moveToFirst()) {
                    int weatherId = cursor.getInt(INDEX_WEATHER_ID);
                    double high = cursor.getDouble(INDEX_MAX_TEMP);
                    double low = cursor.getDouble(INDEX_MIN_TEMP);

                    // Create JSON String
                    String json = String.format(getString(R.string.format_json),
                            weatherId,
                            Utility.formatTemperature(this, high),
                            Utility.formatTemperature(this, low));

                    // Push to Wearable
                    PutDataRequest request = PutDataRequest.create("/weather");
                    request.setData(json.getBytes());

                    Wearable.DataApi.putDataItem(mGoogleApiClient, request);
                }
            }
            else {
                // Schedule another attempt in an hour
                Calendar calendar = Calendar.getInstance();
                calendar.add(Calendar.HOUR, 1);
                Intent i = new Intent(this, WatchFaceSyncService.class);
                PendingIntent pendingIntent = PendingIntent.getService(this, 0, i, 0);
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                am.set(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
            }
        }
    }
}
