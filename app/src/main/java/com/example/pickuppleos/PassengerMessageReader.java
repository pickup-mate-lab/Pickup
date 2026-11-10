package com.example.pickuppleos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.constant.ErrorType;
import ai.pleos.playground.tts.listener.OnInitializeListener;
import ai.pleos.playground.tts.listener.EventListener;
import java.util.Locale;
import java.util.ArrayDeque;

final class PassengerMessageReader {
    interface Status { void onStatus(String text); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextToSpeech tts;
    private final Status status;
    private boolean ready, closed, failed;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private boolean speaking;
    private final Runnable finished = () -> { speaking = false; drain(); };
    private void scheduleNext() { main.removeCallbacks(finished); main.postDelayed(finished, 200); }
    private final EventListener events = new EventListener() {
        @Override public void onReady() { }
        @Override public void onStart() { }
        @Override public void onUpdatedRms(double rms) { }
        @Override public void onDone() { main.post(PassengerMessageReader.this::scheduleNext); }
        @Override public void onStop() { main.post(PassengerMessageReader.this::scheduleNext); }
        @Override public void onError(ErrorType error) { main.post(() -> {
            speaking = false;
            if (!closed) { status.onStatus("메시지 음성 읽기 오류: " + error); drain(); }
        }); }
    };
    PassengerMessageReader(Context context, Status status) {
        this.status = status;
        tts = new TextToSpeech(context.getApplicationContext(), Mode.ON_DEVICE);
        tts.initialize(new OnInitializeListener() {
            @Override public void onSuccess() { main.post(() -> {
                if (!closed) { tts.setLocale(Locale.KOREA); tts.addEventListener(events); ready = true; drain(); }
            }); }
            @Override public void onFailure(ErrorType error) { main.post(() -> {
                failed = true; pending.clear();
                if (!closed) status.onStatus("메시지 음성 읽기 연결 실패: " + error);
            }); }
        });
    }
    void read(String text) {
        if (failed) { status.onStatus("메시지가 도착했지만 음성 읽기를 사용할 수 없습니다."); return; }
        if (pending.size() >= 50) pending.removeFirst();
        pending.addLast(text);
        if (!ready) { status.onStatus("메시지가 도착했습니다. 음성 읽기를 연결 중입니다."); return; }
        drain();
    }
    private void drain() {
        if (closed || !ready || speaking || pending.isEmpty()) return;
        String text = pending.removeFirst();
        try { speaking = true; tts.speak("탑승자 메시지입니다. " + text, true); }
        catch (RuntimeException e) { speaking = false; status.onStatus("메시지를 음성으로 읽지 못했습니다."); }
    }
    void close() { closed = true; pending.clear(); main.removeCallbacks(finished); try { tts.stop(); tts.removeEventListener(events); tts.release(); } catch (RuntimeException ignored) { } }
}
