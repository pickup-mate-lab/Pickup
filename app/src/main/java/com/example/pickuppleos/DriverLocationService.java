package com.example.pickuppleos;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the driver's location updating while the navigation app is in front. */
public final class DriverLocationService extends Service implements LocationListener {
    static final String ACTION_START = "com.example.pickuppleos.START_LOCATION";
    static final String ACTION_ETA = "com.example.pickuppleos.UPDATE_ETA";
    static final String EXTRA_BASE = "base";
    static final String EXTRA_ID = "id";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_ETA = "eta";

    private static final String CHANNEL = "pickup_location";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LocationManager manager;
    private String baseUrl;
    private String sessionId;
    private String token;
    private Integer etaSeconds;
    private Location lastLocation;
    private long lastSentAt;

    @Override
    public void onCreate() {
        super.onCreate();
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "픽업 위치 공유", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_ETA.equals(intent.getAction())) {
            int value = intent.getIntExtra(EXTRA_ETA, -1);
            etaSeconds = value >= 0 ? value : null;
            if (lastLocation != null) send(lastLocation, true);
            return START_NOT_STICKY;
        }
        baseUrl = intent.getStringExtra(EXTRA_BASE);
        sessionId = intent.getStringExtra(EXTRA_ID);
        token = intent.getStringExtra(EXTRA_TOKEN);
        if (baseUrl == null || sessionId == null || token == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Intent open = new Intent(this, PickupActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("픽업메이트 위치 공유 중")
                .setContentText("픽업이 끝나면 위치 공유가 종료됩니다")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(42, notification);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, this);
        } catch (IllegalArgumentException | SecurityException ignored) { }
        try {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 5, this);
        } catch (IllegalArgumentException | SecurityException ignored) { }
        Location last = null;
        try { last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER); }
        catch (IllegalArgumentException | SecurityException ignored) { }
        if (last == null) {
            try { last = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
            catch (IllegalArgumentException | SecurityException ignored) { }
        }
        if (last != null) send(last, true);
        return START_NOT_STICKY;
    }

    @Override
    public void onLocationChanged(Location location) { send(location, false); }

    private void send(Location location, boolean force) {
        lastLocation = location;
        long now = System.currentTimeMillis();
        if (!force && now - lastSentAt < 5000) return;
        lastSentAt = now;
        final String sendBase = baseUrl, sendId = sessionId, sendToken = token;
        final Integer sendEta = etaSeconds;
        if (sendBase == null || sendId == null || sendToken == null) return;
        worker.execute(() -> {
            try {
                new PickupApi(sendBase).location(sendId, sendToken,
                        location.getLatitude(), location.getLongitude(), sendEta);
            } catch (Exception ignored) {
                // A later location update retries when the local demo server returns.
            }
        });
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        try { manager.removeUpdates(this); } catch (SecurityException ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }
}
