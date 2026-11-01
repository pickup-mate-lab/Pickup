package com.example.pickuppleos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;

/** Small wrapper around the Pleos navigation SDK. */
final class PleosNavigation {
    interface Listener {
        void onStatus(String status);
        void onEtaSeconds(Integer seconds);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private NaviHelper helper;
    private boolean connected;
    private volatile boolean closed;
    private RouteInfo queuedRoute;
    private final NaviHelperEventListener events = new NaviHelperEventListener() {
        @Override public void onRoutingCompleted() {
            main.post(() -> {
                if (closed) return;
                listener.onStatus("경로 안내가 준비됐습니다.");
                refreshEta();
            });
        }

        @Override public void onDestinationChanged() {
            main.post(() -> { if (!closed) refreshEta(); });
        }

        @Override public void onDestinationInfo(DestinationInfo info) {
            // NaviHelper 3.0.5 does not document the unit of remainTime.
            // Do not publish it as seconds until a real route confirms the unit.
        }

        @Override public void onError(NaviErrorCode error) {
            main.post(() -> { if (!closed) listener.onStatus("내비게이션 오류: " + error); });
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
                                    helper.requestRoute(queuedRoute);
                                    queuedRoute = null;
                                    listener.onStatus("승차 지점 경로를 요청했습니다.");
                                }
                            } catch (RuntimeException error) {
                                listener.onStatus("내비게이션 요청 실패: " + error.getMessage());
                            }
                        });
                        return Unit.INSTANCE;
                    },
                    error -> {
                        main.post(() -> {
                            if (!closed) listener.onStatus("Pleos 내비게이션을 연결하지 못했습니다.");
                        });
                        return Unit.INSTANCE;
                    });
        } catch (RuntimeException | LinkageError error) {
            helper = null;
            listener.onStatus("Pleos 내비게이션을 사용할 수 없습니다.");
        }
    }

    void routeTo(double latitude, double longitude, String label) {
        RouteInfo route = new RouteInfo(latitude, longitude, null, null, label,
                "", label, "0", RouteOption.RECOMMENDED, null, false);
        if (!connected || helper == null) {
            queuedRoute = route;
            listener.onStatus("내비게이션 연결을 기다리는 중입니다.");
            return;
        }
        try {
            helper.requestRoute(route);
            listener.onStatus("승차 지점 경로를 요청했습니다.");
        } catch (RuntimeException error) {
            listener.onStatus("경로를 요청하지 못했습니다: " + error.getMessage());
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
