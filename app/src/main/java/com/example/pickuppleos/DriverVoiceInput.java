package com.example.pickuppleos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.listener.OnInitializeListener;
import ai.pleos.playground.stt.listener.ResultListener;
import java.util.Locale;

final class DriverVoiceInput {
    interface Callback { void onText(String text); void onStatus(String status); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final SpeechToText stt;
    private boolean ready, listening, closed, pending, failed;
    private final Runnable timeout = this::handleTimeout;
    private final Runnable ended = this::handleEnded;
    private final ResultListener listener = new ResultListener() {
        @Override public void onReady() { }
        @Override public void onStartedRecognition() { }
        @Override public void onUpdated(String text, boolean completed) {
            if (completed && text != null && !text.trim().isEmpty()) main.post(() -> {
                if (!closed && listening) { listening = false; main.removeCallbacks(timeout); main.removeCallbacks(ended);
                    try { stt.stop(); } catch (RuntimeException ignored) { }
                    callback.onText(text.trim()); }
            });
        }
        @Override public void onUpdatedEpdData(long on, long off) { }
        @Override public void onUpdatedRms(double rms) { }
        @Override public void onEndedRecognition() {
            // The SDK may deliver the final transcript after the end-of-speech event.
            main.postDelayed(ended, 1500);
        }
        @Override public void onError(ErrorType error) { main.post(() -> {
            if (!closed && listening) { listening = false; main.removeCallbacks(timeout);
                callback.onStatus("음성 인식 오류: " + error); }
        }); }
    };
    DriverVoiceInput(Context context, Callback callback) {
        this.callback = callback;
        stt = new SpeechToText(context.getApplicationContext(), Mode.ON_DEVICE);
        stt.initialize(new OnInitializeListener() {
            @Override public void onSuccess() { main.post(() -> {
                if (!closed) { stt.setLocale(Locale.KOREA); stt.addListener(listener); ready = true; if (pending) { pending = false; listen(); } }
            }); }
            @Override public void onFailure(ErrorType error) { main.post(() -> {
                failed = true; pending = false;
                if (!closed) callback.onStatus("음성 인식 연결 실패: " + error);
            }); }
        });
    }
    void listen() {
        if (failed) { callback.onStatus("음성 인식을 사용할 수 없습니다. 앱을 다시 실행해 주세요."); return; }
        if (!ready) { pending = true; callback.onStatus("음성 인식을 연결하는 중입니다."); return; }
        try { main.removeCallbacks(ended); listening = true; stt.request(); main.removeCallbacks(timeout); main.postDelayed(timeout, 15000);
            callback.onStatus("답장할 내용을 말씀해 주세요."); }
        catch (RuntimeException e) { listening = false; callback.onStatus("음성 인식을 시작하지 못했습니다."); }
    }
    private void handleEnded() {
        if (!closed && listening) {
            listening = false;
            main.removeCallbacks(timeout);
            callback.onStatus("\uc74c\uc131\uc744 \uc778\uc2dd\ud558\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4. \ub2e4\uc2dc \uc2dc\ub3c4\ud574 \uc8fc\uc138\uc694.");
        }
    }
    private void handleTimeout() {
        if (!closed && listening) {
            listening = false;
            try { stt.stop(); } catch (RuntimeException ignored) { }
            callback.onStatus("음성 입력 시간이 끝났습니다. 마이크를 확인하고 다시 눌러 주세요.");
        }
    }
    void cancel() { pending = false; listening = false; main.removeCallbacks(timeout); main.removeCallbacks(ended); try { stt.stop(); } catch (RuntimeException ignored) { } }
    void close() {
        closed = true; main.removeCallbacks(timeout); main.removeCallbacks(ended);
        try { stt.stop(); stt.removeListener(listener); stt.release(); } catch (RuntimeException ignored) { }
    }
}
