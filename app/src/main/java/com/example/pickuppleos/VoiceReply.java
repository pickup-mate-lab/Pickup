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

/** A short, explicitly initiated conversation; it never listens in the background. */
final class VoiceReply {
    interface Host { void status(String text); void send(String text); }
    private enum Step { IDLE, MESSAGE, CONFIRM }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Host host;
    private final DriverVoiceInput input;
    private final TextToSpeech tts;
    private Step step = Step.IDLE;
    private boolean ready, failed, closed;
    private String prompt, draft;
    private boolean listenAfterSpeech;
    private final Runnable beginListening = this::beginListening;
    private final EventListener events = new EventListener() {
        public void onReady() { }
        public void onStart() { }
        public void onUpdatedRms(double rms) { }
        public void onDone() { main.post(() -> {
            if (listenAfterSpeech && !closed && step != Step.IDLE) {
                listenAfterSpeech = false;
                main.postDelayed(beginListening, 350);
            }
        }); }
        public void onStop() { }
        public void onError(ErrorType error) { main.post(() -> fail("음성 안내를 재생하지 못했습니다. 다시 시도해 주세요.")); }
    };
    VoiceReply(Context context, Host host) {
        this.host = host;
        input = new DriverVoiceInput(context, new DriverVoiceInput.Callback() {
            public void onStatus(String status) {
                if (step == Step.IDLE) return;
                host.status(status);
                if (!status.equals("답장할 내용을 말씀해 주세요.") && !status.equals("음성 인식을 연결하는 중입니다."))
                    fail(status);
            }
            public void onText(String text) { recognized(text); }
        });
        tts = new TextToSpeech(context.getApplicationContext(), Mode.ON_DEVICE);
        tts.initialize(new OnInitializeListener() {
            public void onSuccess() { main.post(() -> {
                if (!closed) { ready = true; tts.setLocale(Locale.KOREA); tts.addEventListener(events); playPending(); }
            }); }
            public void onFailure(ErrorType error) { main.post(() -> { failed = true; fail("음성 안내를 사용할 수 없습니다."); }); }
        });
    }
    private void beginListening() { if (!closed && step != Step.IDLE) input.listen(); }
    void start() {
        cancel();
        step = Step.MESSAGE;
        speak("답장할 내용을 말씀해 주세요.", true);
    }
    private void recognized(String text) {
        if (step == Step.MESSAGE) {
            draft = text;
            step = Step.CONFIRM;
            speak(text + "라고 보낼까요? 보내 줘 또는 취소라고 말씀해 주세요.", true);
        } else if (step == Step.CONFIRM) {
            String normalized = text.replaceAll("[\\s\\p{Punct}]", "");
            if (normalized.equals("보내") || normalized.equals("보내줘") || normalized.equals("보내주세요")
                    || normalized.equals("전송") || normalized.equals("전송해줘") || normalized.equals("네") || normalized.equals("예")) {
                String confirmed = draft;
                step = Step.IDLE;
                draft = null;
                host.send(confirmed);
            } else if (normalized.equals("취소") || normalized.equals("취소해줘") || normalized.equals("아니") || normalized.equals("아니요")) {
                step = Step.IDLE; draft = null;
                speak("답장을 취소했습니다.", false);
            } else {
                speak("알아듣지 못했습니다. 보내 줘 또는 취소라고 말씀해 주세요.", true);
            }
        }
    }
    void announce(String text) { speak(text, false); }
    private void speak(String text, boolean listen) {
        host.status(text);
        prompt = text; listenAfterSpeech = listen;
        if (failed) { fail("음성 안내를 사용할 수 없습니다."); return; }
        playPending();
    }
    private void playPending() {
        if (!ready || closed || prompt == null) return;
        String text = prompt; prompt = null;
        try { tts.speak(text, true); } catch (RuntimeException error) { fail("음성 안내를 재생하지 못했습니다."); }
    }
    private void fail(String message) {
        cancel();
        host.status(message);
    }
    void cancel() {
        step = Step.IDLE; draft = null; prompt = null; listenAfterSpeech = false;
        main.removeCallbacks(beginListening);
        input.cancel();
        if (ready) try { tts.stop(); } catch (RuntimeException ignored) { }
    }
    void close() {
        cancel(); closed = true; input.close();
        try { tts.removeEventListener(events); tts.release(); } catch (RuntimeException ignored) { }
    }
}
