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
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashSet;

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
    private final Handler main = new Handler(Looper.getMainLooper());
    private PleosNavigation navigation;
    private PassengerMessageReader messageReader;
    private int routedRevision = -1;
    private boolean checking;
    private long routeRetryAfter;
    private final HashSet<String> seenPassengerMessages = new HashSet<>();
    private boolean messagesInitialized;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (sessionId == null) return;
            checkPickup();
            main.postDelayed(this, 3000);
        }
    };

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
        String incomingId = intent.getStringExtra(EXTRA_ID);
        if (incomingId != null && !incomingId.equals(sessionId)) {
            routedRevision = -1;
            routeRetryAfter = 0;
            seenPassengerMessages.clear();
            messagesInitialized = false;
        }
        sessionId = incomingId;
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
        if (messageReader == null) {
            try { messageReader = new PassengerMessageReader(this, this::updateNotification); }
            catch (RuntimeException | LinkageError error) { updateNotification("메시지 음성 읽기를 사용할 수 없습니다."); }
        }
        if (navigation == null) {
            navigation = new PleosNavigation(this, new PleosNavigation.Listener() {
                @Override public void onStatus(String status) {
                    updateNotification(status);
                    if (status.contains("오류") || status.contains("실패") || status.contains("못했")) {
                        routedRevision = -1;
                        routeRetryAfter = System.currentTimeMillis() + 15000;
                    }
                }
                @Override public void onEtaSeconds(Integer seconds) { }
            });
        }
        main.removeCallbacks(poll);
        main.post(poll);
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

    private void checkPickup() {
        if (checking || sessionId == null) return;
        checking = true;
        final String currentId = sessionId, currentToken = token, currentBase = baseUrl;
        worker.execute(() -> {
            try {
                PickupApi api = new PickupApi(currentBase);
                JSONObject state = api.state(currentId, currentToken);
                JSONObject point = state.optJSONObject("pickup");
                if (point != null && !point.optBoolean("confirmed")) {
                    state = api.confirm(currentId, currentToken, point.optInt("revision", -1));
                    point = state.optJSONObject("pickup");
                }
                final JSONObject target = point;
                final String status = state.optString("status");
                JSONArray messages = state.optJSONArray("messages");
                main.post(() -> {
                    checking = false;
                    if (!currentId.equals(sessionId)) return;
                    if (messages != null) {
                        for (int i = 0; i < messages.length(); i++) {
                            JSONObject item = messages.optJSONObject(i);
                            if (item == null || !"passenger".equals(item.optString("sender"))) continue;
                            String messageId = item.optString("id", "");
                            if (!messageId.isEmpty() && seenPassengerMessages.add(messageId) && messagesInitialized) {
                                updateNotification("탑승자 메시지가 도착했습니다.");
                                if (messageReader != null) messageReader.read(item.optString("text", ""));
                            }
                        }
                    }
                    messagesInitialized = true;
                    if ("finished".equals(status) || "expired".equals(status)) {
                        stopSelf();
                        return;
                    }
                    if (target != null && target.optBoolean("confirmed")) {
                        int revision = target.optInt("revision", -1);
                        if (revision > 0 && revision != routedRevision && navigation != null
                                && System.currentTimeMillis() >= routeRetryAfter) {
                            if (routedRevision > 0) updateNotification("승차 지점이 변경되어 새 경로를 요청합니다.");
                            routedRevision = revision;
                            navigation.routeTo(target.optDouble("lat"), target.optDouble("lon"),
                                    target.optString("label", "승차 지점"));
                        }
                    }
                });
            } catch (Exception error) {
                main.post(() -> checking = false);
            }
        });
    }

    private void updateNotification(String status) {
        Intent open = new Intent(this, PickupActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("픽업메이트 위치 공유 중")
                .setContentText(status)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(42, notification);
    }

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
        sessionId = null;
        main.removeCallbacks(poll);
        if (navigation != null) navigation.close();
        if (messageReader != null) messageReader.close();
        try { manager.removeUpdates(this); } catch (SecurityException ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }
}
