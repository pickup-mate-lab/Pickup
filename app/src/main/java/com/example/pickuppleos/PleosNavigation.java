package com.example.pickuppleos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;

/** Small wrapper around the Pleos navigation SDK. */
final class PleosNavigation {
    interface Listener {
        void onStatus(String status);
        void onEtaSeconds(Integer seconds);
        void onRouteCompleted();
        void onRouteFailed();
    }

    /** Verify on an emulator with: adb logcat -s PickupEta */
    static final String ETA_TAG = "PickupEta";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private NaviHelper helper;
    private boolean connected;
    private volatile boolean closed;
    private RouteInfo queuedRoute;
    private boolean waitingForRoute;
    private boolean routeStarted;
    private boolean checkingDestination;
    private boolean destinationVerified;
    private final Runnable routeTimeout = this::handleRouteTimeout;
    private void handleRouteTimeout() {
        if (closed) return;
        queuedRoute = null;
        waitingForRoute = false;
        routeStarted = false;
        checkingDestination = false;
        destinationVerified = false;
        listener.onEtaSeconds(null);
        listener.onStatus("내비게이션 응답이 지연되어 다시 연결합니다.");
        listener.onRouteFailed();
    }
    private double requestedLatitude, requestedLongitude;
    private boolean hasTarget;
    // remainTime is documented in seconds (navi-data DestinationInfo KDoc).
    // The first sample is compared with RouteStartInfo.duration (also seconds)
    // so a unit mismatch on a real head unit never reaches the passenger.
    private int routeDuration = -1;
    private boolean etaChecked;
    private boolean etaDisabled;
    private final NaviHelperEventListener events = new NaviHelperEventListener() {
        @Override public void onRouteStarted(RouteStartInfo info) {
            main.post(() -> {
                if (closed || !waitingForRoute) return;
                routeStarted = true;
                routeDuration = info != null ? info.getDuration() : -1;
                Log.d(ETA_TAG, "routeStarted duration=" + routeDuration);
            });
        }

        @Override public void onDestinationArrived(DestinationArrivedInfo info) {
            main.post(() -> { if (!closed && hasTarget && !etaDisabled) listener.onEtaSeconds(0); });
        }

        @Override public void onRouteCancelled() {
            main.post(() -> { if (!closed) listener.onEtaSeconds(null); });
        }
        // Pleos Connect v2.1 emits the older completion callback.
        @SuppressWarnings("deprecation")
        @Override public void onRouteEnded() { onRoutingCompleted(); }
        @Override public void onRoutingCompleted() {
            main.post(() -> {
                if (closed) return;
                if (!waitingForRoute || !routeStarted) return;
                checkingDestination = true;
                listener.onStatus("경로 탐색 완료, 승차 지점을 확인 중입니다.");
                refreshEta();
            });
        }

        @Override public void onDestinationChanged() {
            main.post(() -> { if (!closed) refreshEta(); });
        }

        @Override public void onDestinationInfo(DestinationInfo info) {
            main.post(() -> {
                if (closed || info == null) return;
                boolean matches = hasTarget
                        && Math.abs(info.getLatitude() - requestedLatitude) <= 0.0002
                        && Math.abs(info.getLongitude() - requestedLongitude) <= 0.0002;
                if (waitingForRoute && checkingDestination) {
                    waitingForRoute = false;
                    checkingDestination = false;
                    routeStarted = false;
                    if (!matches) {
                        destinationVerified = false;
                        main.removeCallbacks(routeTimeout);
                        listener.onEtaSeconds(null);
                        listener.onStatus("내비게이션 목적지가 승차 지점과 다릅니다.");
                        listener.onRouteFailed();
                        return;
                    }
                    destinationVerified = true;
                    main.removeCallbacks(routeTimeout);
                    listener.onRouteCompleted();
                }
                if (destinationVerified && !matches) {
                    destinationVerified = false;
                    listener.onEtaSeconds(null);
                    listener.onStatus("내비게이션 목적지가 변경됐습니다.");
                    return;
                }
                if (destinationVerified && matches) reportEta(info.getRemainTime());
            });
        }

        @Override public void onError(NaviErrorCode error) {
            main.post(() -> { if (!closed) { main.removeCallbacks(routeTimeout); waitingForRoute = false; routeStarted = false; listener.onStatus("내비게이션 오류: " + error); listener.onRouteFailed(); } });
        }
    };

    PleosNavigation(Context context, Listener listener) {
        this.listener = listener;
        try {
            helper = new NaviHelper(context.getApplicationContext());
            helper.initialize(
                    () -> {
                        main.post(() -> {
                            if (closed || helper == null) return;
                            connected = true;
                            try {
                                helper.addListener(events);
                                if (queuedRoute != null) {
                                    waitingForRoute = true;
                                    routeStarted = false;
                                    helper.requestRoute(queuedRoute);
                                    queuedRoute = null;
                                    listener.onStatus("승차 지점 경로를 요청했습니다.");
                                }
                            } catch (RuntimeException error) {
                                listener.onStatus("내비게이션 요청 실패: " + error.getMessage());
                                listener.onRouteFailed();
                            }
                        });
                        return Unit.INSTANCE;
                    },
                    error -> {
                        main.post(() -> {
                            if (!closed) { listener.onStatus("Pleos 내비게이션을 연결하지 못했습니다."); listener.onRouteFailed(); }
                        });
                        return Unit.INSTANCE;
                    });
        } catch (RuntimeException | LinkageError error) {
            helper = null;
            listener.onStatus("Pleos 내비게이션을 사용할 수 없습니다.");
            listener.onRouteFailed();
        }
    }

    void routeTo(double latitude, double longitude, String label) {
        main.removeCallbacks(routeTimeout);
        main.postDelayed(routeTimeout, 30000);
        destinationVerified = false;
        listener.onEtaSeconds(null);
        requestedLatitude = latitude;
        requestedLongitude = longitude;
        hasTarget = true;
        routeDuration = -1;
        etaChecked = false;
        etaDisabled = false;
        listener.onEtaSeconds(null);
        checkingDestination = false;
        RouteInfo route;
        try {
            route = new RouteInfo(latitude, longitude, null, null, label,
                    "", label, "0", RouteOption.RECOMMENDED, "", false);
        } catch (RuntimeException error) {
            listener.onStatus("경로 정보를 만들지 못했습니다: " + error.getMessage());
            listener.onRouteFailed();
            return;
        }
        if (!connected || helper == null) {
            queuedRoute = route;
            listener.onStatus("내비게이션 연결을 기다리는 중입니다.");
            return;
        }
        try {
            waitingForRoute = true;
            routeStarted = false;
            helper.requestRoute(route);
            listener.onStatus("승차 지점 경로를 요청했습니다.");
        } catch (RuntimeException error) {
            listener.onStatus("경로를 요청하지 못했습니다: " + error.getMessage());
            listener.onRouteFailed();
        }
    }

    private void reportEta(int remainTime) {
        if (etaDisabled) return;
        if (remainTime < 0 || remainTime > 86400) {
            listener.onEtaSeconds(null);
            return;
        }
        if (!etaChecked) {
            etaChecked = true;
            if (routeDuration > 0 && (remainTime > routeDuration * 3 + 60
                    || remainTime * 3 + 60 < routeDuration)) {
                etaDisabled = true;
                Log.w(ETA_TAG, "remainTime " + remainTime + " does not match route duration "
                        + routeDuration + "; ETA disabled for this route");
                listener.onStatus("내비게이션 남은 시간이 경로 정보와 맞지 않아 도착 예정시간 전달을 중지했습니다.");
                listener.onEtaSeconds(null);
                return;
            }
        }
        listener.onEtaSeconds(remainTime);
    }

    void refreshEta() {
        if (connected && helper != null) {
            try { helper.getDestinationInfo(); } catch (RuntimeException ignored) { }
        }
    }

    void stopRoute() {
        if (connected && helper != null) {
            try { helper.cancelRoute(); } catch (RuntimeException ignored) { }
        }
        main.removeCallbacks(routeTimeout);
        queuedRoute = null;
        waitingForRoute = false;
        routeStarted = false;
        checkingDestination = false;
        destinationVerified = false;
        hasTarget = false;
        listener.onEtaSeconds(null);
    }

    void close() {
        closed = true;
        main.removeCallbacks(routeTimeout);
        if (helper != null) {
            try { if (connected) helper.removeListener(events); }
            catch (RuntimeException ignored) { }
            try { helper.release(); } catch (RuntimeException ignored) { }
        }
        connected = false;
    }
}
