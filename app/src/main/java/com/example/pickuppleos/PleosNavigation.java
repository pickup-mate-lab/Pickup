package com.example.pickuppleos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private NaviHelper helper;
    private boolean connected;
    private volatile boolean closed;
    private RouteInfo queuedRoute;
    private boolean waitingForRoute;
    private boolean routeStarted;
    private boolean checkingDestination;
    private double requestedLatitude, requestedLongitude;
    private final NaviHelperEventListener events = new NaviHelperEventListener() {
        @Override public void onRouteStarted(RouteStartInfo info) {
            main.post(() -> { if (!closed && waitingForRoute) routeStarted = true; });
        }
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
                if (closed || !waitingForRoute || !checkingDestination || info == null) return;
                // Avoid accepting a completion event for another destination.
                if (Math.abs(info.getLatitude() - requestedLatitude) > 0.0002
                        || Math.abs(info.getLongitude() - requestedLongitude) > 0.0002) {
                    waitingForRoute = false;
                    checkingDestination = false;
                    routeStarted = false;
                    listener.onStatus("내비게이션 목적지가 승차 지점과 다릅니다.");
                    listener.onRouteFailed();
                    return;
                }
                waitingForRoute = false;
                checkingDestination = false;
                routeStarted = false;
                listener.onRouteCompleted();
            });
            // The SDK documentation does not specify remainTime's unit.
            // Never publish an unverified ETA.
        }

        @Override public void onError(NaviErrorCode error) {
            main.post(() -> { if (!closed) { waitingForRoute = false; routeStarted = false; listener.onStatus("내비게이션 오류: " + error); listener.onRouteFailed(); } });
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
        requestedLatitude = latitude;
        requestedLongitude = longitude;
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

    void refreshEta() {
        if (connected && helper != null) {
            try { helper.getDestinationInfo(); } catch (RuntimeException ignored) { }
        }
    }

    void stopRoute() {
        if (connected && helper != null) {
            try { helper.cancelRoute(); } catch (RuntimeException ignored) { }
        }
        queuedRoute = null;
        waitingForRoute = false;
        routeStarted = false;
        checkingDestination = false;
        listener.onEtaSeconds(null);
    }

    void close() {
        closed = true;
        if (helper != null) {
            try { if (connected) helper.removeListener(events); }
            catch (RuntimeException ignored) { }
            try { helper.release(); } catch (RuntimeException ignored) { }
        }
        connected = false;
    }
}
