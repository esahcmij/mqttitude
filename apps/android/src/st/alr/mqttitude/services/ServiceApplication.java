package st.alr.mqttitude.services;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Settings.Secure;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import java.text.SimpleDateFormat;
import java.util.Date;

import de.greenrobot.event.EventBus;
import st.alr.mqttitude.ActivityMain;
import st.alr.mqttitude.R;
import st.alr.mqttitude.support.BackgroundPublishReceiver;
import st.alr.mqttitude.support.Defaults;
import st.alr.mqttitude.support.Events;
import st.alr.mqttitude.support.GeocodableLocation;
import st.alr.mqttitude.support.ReverseGeocodingTask;

public class ServiceApplication extends ServiceBindable {
    private static SharedPreferences sharedPreferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener;
    private NotificationManager notificationManager;
    private static NotificationCompat.Builder  notificationBuilder;
    private static Class<?> locatorClass;
    private GeocodableLocation lastPublishedLocation;
    private Date lastPublishedLocationTime;
    private static ServiceApplication instance;
    private boolean even = false;
    private SimpleDateFormat dateFormater;
    private Handler handler;
    private final String TAG  = "ServiceApplication";

    
    private static ServiceLocator serviceLocator;
    private static ServiceMqtt serviceMqtt;

    
    @Override
    public void onCreate(){
        super.onCreate();
        instance = this;
    }
    
    @Override
    protected void onStartOnce() {
        
        Log.v(this.toString(), "MQTTITUDE STARTING");
        instance = this;
        
        int resp = GooglePlayServicesUtil.isGooglePlayServicesAvailable(this);
 

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                onHandlerMessage(msg);
            }
        };

        EventBus.getDefault().registerSticky(this);

        if (resp == ConnectionResult.SUCCESS) {
            Log.v(this.toString(), "Play  services version: "
                    + GooglePlayServicesUtil.GOOGLE_PLAY_SERVICES_VERSION_CODE);
            locatorClass = ServiceLocatorFused.class;
        } else {
            // TODO: implement fallback locator
            Log.e(this.toString(),
                    "play services not available and no other locator implemented yet ");
            locatorClass = ServiceLocatorFused.class;
        }


        this.dateFormater = new SimpleDateFormat("y/M/d H:m:s",
                getResources().getConfiguration().locale);

        notificationManager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder (this);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        preferencesChangedListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreference, String key) {
                if (key.equals(Defaults.SETTINGS_KEY_NOTIFICATION_ENABLED))
                    handleNotification();
            }
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferencesChangedListener);
        handleNotification();

        ServiceConnection mqttConnection = new ServiceConnection() {
            

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceMqtt = null;                
            }
            
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceMqtt = (ServiceMqtt) ((ServiceBindable.ServiceBinder)service).getService();                
            }
        };        
        bindService(new Intent(this, ServiceMqtt.class), mqttConnection, Context.BIND_AUTO_CREATE);

        
        ServiceConnection locatorConnection = new ServiceConnection() {
            

            @Override
            public void onServiceDisconnected(ComponentName name) {
                serviceLocator = null;                
            }
            
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceLocator = (ServiceLocator) ((ServiceBindable.ServiceBinder)service).getService();                
            }
        };
        
        bindService(new Intent(this, getServiceLocatorClass()), locatorConnection, Context.BIND_AUTO_CREATE);

        updateTicker("MQTTitude service started");
        
    }

       
    public String formatDate(Date d) {
        return dateFormater.format(d);
    }

    /**
     * @category NOTIFICATION HANDLING
     */
    private void handleNotification() {
        Log.v(this.toString(), "handleNotification()");
        notificationManager.cancel(Defaults.NOTIFCATION_ID);

        if (notificationEnabled())
            createNotification();
    }

    private boolean notificationEnabled() {
        return sharedPreferences.getBoolean(Defaults.SETTINGS_KEY_NOTIFICATION_ENABLED,
                Defaults.VALUE_NOTIFICATION_ENABLED);
    }

    private void createNotification() {

        Intent resultIntent = new Intent(this, ActivityMain.class);
        resultIntent.setAction("android.intent.action.MAIN");
        resultIntent.addCategory("android.intent.category.LAUNCHER");

        resultIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // android.support.v4.app.TaskStackBuilder stackBuilder =
        // android.support.v4.app.TaskStackBuilder
        // .create(this);
        // stackBuilder.addParentStack(ActivityMain.class);
        // stackBuilder.addNextIntent(resultIntent);
        // PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0,
        // Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        //
        PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilder.setContentIntent(resultPendingIntent);

        Intent intent = new Intent(Defaults.INTENT_ACTION_PUBLISH_LASTKNOWN);
        intent.setClass(this, BackgroundPublishReceiver.class);

        PendingIntent pIntent = PendingIntent.getBroadcast(this, 0, intent, 0);

        notificationBuilder.addAction(
                R.drawable.ic_upload,
                "Publish",
                pIntent);

        updateNotification();
    }

    public void updateTicker(String text) {
        notificationBuilder.setTicker(text + ((even = even ? false : true) ? " " : ""));
        notificationBuilder.setSmallIcon(R.drawable.ic_notification);
        notificationManager.notify(Defaults.NOTIFCATION_ID, notificationBuilder.build());

        // if the notification is not enabled, the ticker will create an empty
        // one that we get rid of
        if (!notificationEnabled())
            notificationManager.cancel(Defaults.NOTIFCATION_ID);
    }

    public void updateNotification() {
        if (!notificationEnabled())
            return;

        String title = null;
        String subtitle = null;
        long time = 0;

        if (lastPublishedLocation != null
                && sharedPreferences.getBoolean("notificationLocation", true)) {
            time = lastPublishedLocationTime.getTime();

            if (lastPublishedLocation.getGeocoder() != null
                    && sharedPreferences.getBoolean("notificationGeocoder", false)) {
                title = lastPublishedLocation.toString();
            } else {
                title = lastPublishedLocation.toLatLonString();
            }
        } else {
            title = getString(R.string.app_name);
        }

        subtitle = ServiceLocator.getStateAsString() + " | " + ServiceMqtt.getStateAsString();

        notificationBuilder.setContentTitle(title);
        notificationBuilder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText(subtitle)
                .setPriority(android.support.v4.app.NotificationCompat.PRIORITY_MIN);
        if (time != 0)
            notificationBuilder.setWhen(lastPublishedLocationTime.getTime());

        notificationManager.notify(Defaults.NOTIFCATION_ID, notificationBuilder.build());
    }

    public void onEventMainThread(Events.StateChanged.ServiceMqtt e) {
        updateNotification();
    }

    public void onEventMainThread(Events.StateChanged.ServiceLocator e) {
        updateNotification();
    }

    private void onHandlerMessage(Message msg) {
        switch (msg.what) {
            case ReverseGeocodingTask.GEOCODER_RESULT:
                geocoderAvailableForLocation(((GeocodableLocation) msg.obj));
                break;
        }
    }

    private void geocoderAvailableForLocation(GeocodableLocation l) {
        if (l == lastPublishedLocation) {
            Log.v(this.toString(), "Geocoder now available for lastPublishedLocation");
            updateNotification();
        } else {
            Log.v(this.toString(), "Geocoder now available for an old location");
        }
    }

    public void onEvent(Events.PublishSuccessfull e) {
        Log.v(this.toString(), "Publish successful");
        if (e.getExtra() != null && e.getExtra() instanceof GeocodableLocation) {
            GeocodableLocation l = (GeocodableLocation) e.getExtra();

            this.lastPublishedLocation = l;
            this.lastPublishedLocationTime = e.getDate();

            if (sharedPreferences.getBoolean("notificationGeocoder", false)
                    && l.getGeocoder() == null)
                (new ReverseGeocodingTask(this, handler)).execute(new GeocodableLocation[] {
                    l
                });

            updateNotification();

            if (sharedPreferences.getBoolean(Defaults.SETTINGS_KEY_TICKER_ON_PUBLISH,
                    Defaults.VALUE_TICKER_ON_PUBLISH))
                updateTicker(getString(R.string.statePublished));

        }
    }
    
    

    public void onEvent(Events.LocationUpdated e) {
        if (e.getGeocodableLocation() == null)
            return;

        Log.v(this.toString(), "LocationUpdated: " + e.getGeocodableLocation().getLatitude() + ":"
                + e.getGeocodableLocation().getLongitude());
        
    }

    public boolean isDebugBuild() {
        return 0 != (getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE);
    }

    public static ServiceApplication getInstance() {
        return instance;
    }
    
    public static Class<?> getServiceLocatorClass() {
        return locatorClass;
    }
    
    public static ServiceLocator getServiceLocator() {
        return serviceLocator;        
    }
    
    public static ServiceMqtt getServiceMqtt() {
        return serviceMqtt;
    }

    public static String getAndroidId() {
        return Secure.getString(instance.getContentResolver(), Secure.ANDROID_ID);
    }    
}
