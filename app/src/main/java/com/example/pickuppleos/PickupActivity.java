package com.example.pickuppleos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PickupActivity extends Activity {
    private static final int NAVY = Color.rgb(11, 45, 107);
    private static final int INK = Color.rgb(18, 39, 72);
    private static final int MINT = Color.rgb(0, 199, 178);
    private static final int MUTED = Color.rgb(91, 108, 133);
    private static final int BORDER = Color.rgb(215, 225, 237);
    private static final int SURFACE = Color.rgb(244, 247, 251);
    private static final int CONTACTS = 10, LOCATION = 11, PICK_CONTACT = 12;
    private static final String START = "START_PICKUP";
    private static final String CHOOSE = "CHOOSE_PICKUP_CONTACT";
    private static final String ROUTE = "CONFIRM_PICKUP_ROUTE";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<ContactResolver.Choice> choices = new ArrayList<>();
    private String id, driverToken, passengerToken, base, inviteBase;
    private String pendingName, pendingTime;
    private boolean pendingVoice;
    private ContactResolver.Choice selectedContact;
    private JSONObject pickup;
    private boolean polling, fetching, starting, routeRequested;
    private AlertDialog choiceDialog;
    private PleosNavigation navigation;
    private EditText serverField, inviteField, nameField, timeField;
    private TextView message, session, pickupLabel, passengerLabel, etaLabel, inviteLabel;
    private Button startButton, shareButton, confirmButton, finishButton, refreshButton;
    private LinearLayout setupCard, actionCard, settingsCard;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            if (id != null) refresh();
            main.postDelayed(this, 3000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences saved = getPreferences(MODE_PRIVATE);
        base = saved.getString("base", "http://10.0.2.2:8787");
        inviteBase = saved.getString("inviteBase", "http://127.0.0.1:8787");
        id = saved.getString("id", null);
        driverToken = saved.getString("driverToken", null);
        passengerToken = saved.getString("passengerToken", null);
        createScreen();
        navigation = new PleosNavigation(this, new PleosNavigation.Listener() {
            @Override public void onStatus(String text) { show(text); }
            @Override public void onEtaSeconds(Integer seconds) {
                if (seconds == null) return;
                etaLabel.setText("예상 도착시간: 약 " + Math.max(1, (seconds + 59) / 60) + "분");
                if (id != null) {
                    Intent update = new Intent(PickupActivity.this, DriverLocationService.class);
                    update.setAction(DriverLocationService.ACTION_ETA);
                    update.putExtra(DriverLocationService.EXTRA_ETA, seconds);
                    try { startService(update); }
                    catch (IllegalStateException | SecurityException ignored) {
                        // Location updates continue even if Android blocks a background ETA update.
                    }
                }
            }
        });
        renderButtons();
        handleGleo(getIntent());
        if (id != null) shareDriverLocation();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleGleo(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!polling) { polling = true; main.post(poll); }
    }

    @Override protected void onPause() {
        polling = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (choiceDialog != null) choiceDialog.dismiss();
        if (navigation != null) navigation.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(INK);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) drawable.setStroke(dp(1), border);
        return drawable;
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(28), dp(26), dp(28), dp(28));
        view.setBackground(shape(Color.WHITE, 22, BORDER));
        view.setElevation(dp(3));
        return view;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private TextView eyebrow(String label) {
        TextView view = text(label, 17);
        view.setTextColor(NAVY);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText field(String hint, String value) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setTextSize(23);
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setSingleLine(true);
        view.setMinHeight(dp(70));
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setBackground(shape(SURFACE, 13, BORDER));
        return view;
    }

    private Button button(String title, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(22);
        view.setAllCaps(false);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(NAVY);
        view.setMinHeight(dp(72));
        view.setPadding(dp(18), dp(12), dp(18), dp(12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2200C7B2),
                shape(Color.WHITE, 14, BORDER), null));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void primary(Button view) {
        view.setTextColor(Color.WHITE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                shape(NAVY, 14, Color.TRANSPARENT), null));
    }

    private void createScreen() {
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        setContentView(root);
        View accent = new View(this);
        accent.setBackgroundColor(MINT);
        root.addView(accent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5)));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(NAVY);
        header.setPadding(dp(38), dp(18), dp(38), dp(22));
        TextView title = text("●  픽업메이트", 35);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(header, title, 0);
        TextView subtitle = text("연락처로 연결하고, 탑승자가 정한 승차 지점으로 이동합니다.", 20);
        subtitle.setTextColor(0xFFE2EAF5);
        add(header, subtitle, 3);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(36), dp(30), dp(36), dp(40));
        scroll.addView(body);

        LinearLayout columns = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 1000;
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        add(body, columns, 0);

        LinearLayout statusCard = card();
        add(statusCard, eyebrow("현재 픽업"), 0);
        session = text("진행 중인 픽업 없음", 32);
        session.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(statusCard, session, 14);
        message = text("탑승자를 선택하거나 이름을 입력해 픽업을 시작하세요.", 20);
        message.setTextColor(NAVY);
        message.setPadding(dp(18), dp(14), dp(18), dp(14));
        message.setBackground(shape(0xFFE4F8F5, 12, Color.TRANSPARENT));
        add(statusCard, message, 18);
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(24);
        statusCard.addView(divider, dividerParams);
        pickupLabel = text("승차 지점: 탑승자 지정 대기", 27);
        pickupLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(statusCard, pickupLabel, 23);
        passengerLabel = text("탑승자 현재 위치: 공유 대기", 21);
        passengerLabel.setTextColor(MUTED);
        add(statusCard, passengerLabel, 18);
        etaLabel = text("예상 도착시간: 아직 제공되지 않음", 21);
        etaLabel.setTextColor(MUTED);
        add(statusCard, etaLabel, 12);
        refreshButton = button("상태 새로고침", this::refresh);
        add(statusCard, refreshButton, 25);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        setupCard = card();
        add(setupCard, eyebrow("새 픽업 시작"), 0);
        TextView setupHint = text("탑승자를 선택하면 승차 지점 요청 링크가 만들어집니다.", 20);
        setupHint.setTextColor(MUTED);
        add(setupCard, setupHint, 12);
        serverField = field("차량 앱 서버 주소", base);
        inviteField = field("탑승자 링크 주소", inviteBase);
        nameField = field("탑승자 이름", "");
        timeField = field("픽업 시각 (선택)", "");
        add(setupCard, nameField, 22);
        add(setupCard, timeField, 12);
        add(setupCard, button("연락처에서 선택", () -> {
            try { startActivityForResult(new Intent(Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI), PICK_CONTACT); }
            catch (Exception error) { show("연락처 앱을 열 수 없습니다."); }
        }), 14);
        startButton = button("픽업 시작", () -> resolveName(nameField.getText().toString().trim(),
                timeField.getText().toString().trim(), false));
        primary(startButton);
        add(setupCard, startButton, 14);

        actionCard = card();
        add(actionCard, eyebrow("다음 행동"), 0);
        inviteLabel = text("", 18);
        inviteLabel.setTextColor(MUTED);
        inviteLabel.setMaxLines(2);
        inviteLabel.setEllipsize(TextUtils.TruncateAt.END);
        inviteLabel.setTextIsSelectable(true);
        add(actionCard, inviteLabel, 14);
        shareButton = button("초대 링크 공유", this::shareInvite);
        confirmButton = button("승차 지점 확인 · 경로 안내", () -> confirm(null));
        finishButton = button("픽업 완료 · 위치 공유 종료", this::finishPickup);
        primary(confirmButton);
        finishButton.setTextColor(0xFFB84B3E);
        add(actionCard, confirmButton, 18);
        add(actionCard, shareButton, 12);
        add(actionCard, finishButton, 12);

        if (wide) {
            columns.addView(statusCard, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f));
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f);
            rightParams.leftMargin = dp(22);
            columns.addView(right, rightParams);
        } else {
            add(columns, statusCard, 0);
            add(columns, right, 18);
        }
        add(right, setupCard, 0);
        add(right, actionCard, 0);

        Button settingsToggle = button("데모 서버 설정  ▾", () -> {
            settingsCard.setVisibility(settingsCard.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });
        settingsToggle.setTextSize(18);
        add(body, settingsToggle, 22);
        settingsCard = card();
        add(settingsCard, eyebrow("데모 서버 주소"), 0);
        TextView serverHint = text("실제 휴대전화에서는 접근 가능한 HTTPS 주소를 입력하세요.", 18);
        serverHint.setTextColor(MUTED);
        add(settingsCard, serverHint, 8);
        add(settingsCard, serverField, 18);
        add(settingsCard, inviteField, 12);
        add(body, settingsCard, 10);
        settingsCard.setVisibility(View.GONE);
    }

    private void show(String value) { message.setText(value); }

    private String inviteUrl() {
        return id == null ? "" : inviteBase + "/p/" + id + "/" + passengerToken;
    }

    private void renderButtons() {
        setupCard.setVisibility(id == null ? View.VISIBLE : View.GONE);
        actionCard.setVisibility(id == null ? View.GONE : View.VISIBLE);
        startButton.setEnabled(id == null && !starting);
        shareButton.setEnabled(id != null);
        finishButton.setEnabled(id != null);
        refreshButton.setVisibility(id == null ? View.GONE : View.VISIBLE);
        confirmButton.setEnabled(id != null && pickup != null && !pickup.optBoolean("confirmed"));
        confirmButton.setVisibility(confirmButton.isEnabled() ? View.VISIBLE : View.GONE);
        inviteLabel.setText(inviteUrl());
    }

    private void handleGleo(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!START.equals(action) && !CHOOSE.equals(action) && !ROUTE.equals(action)) return;
        try {
            JSONObject args = GleoResponder.parameters(intent);
            if (START.equals(action)) {
                String name = args.optString("contactName", "").trim();
                String time = args.optString("pickupTime", "").trim();
                nameField.setText(name);
                timeField.setText(time);
                resolveName(name, time, true);
            } else if (CHOOSE.equals(action)) {
                int index = args.optInt("choiceIndex", -1) - 1;
                if (index < 0 || index >= choices.size()) {
                    GleoResponder.send(this, action, "fail", "Please choose a listed contact number.", null);
                } else {
                    chooseContact(index, pendingTime, action);
                }
            } else if (pickup == null || pickup.optBoolean("confirmed")) {
                GleoResponder.send(this, action, "fail", "There is no new pickup point to confirm.", null);
            } else {
                confirm(action);
            }
        } catch (Exception error) {
            GleoResponder.send(this, action, "fail", "I could not read the pickup request.", null);
        }
    }

    private void resolveName(String name, String time, boolean voice) {
        if (choiceDialog != null) {
            choiceDialog.dismiss();
            choiceDialog = null;
        }
        choices.clear();
        if (name.isEmpty()) {
            show("탑승자 이름을 입력해 주세요.");
            if (voice) GleoResponder.send(this, START, "asking", "Who would you like to pick up?", null);
            return;
        }
        if (id != null || starting) {
            show("진행 중인 픽업을 먼저 완료해 주세요.");
            if (voice) GleoResponder.send(this, START, "fail", "A pickup is already active.", null);
            return;
        }
        pendingName = name;
        pendingTime = time;
        pendingVoice = voice;
        if (selectedContact != null && name.equals(selectedContact.name)) {
            startSession(name, time, voice ? START : null);
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACTS);
            if (voice) GleoResponder.send(this, START, "asking",
                    "Please allow contact access in the app and try again.", null);
            return;
        }
        List<ContactResolver.Choice> found = ContactResolver.find(this, name);
        if (found.size() > 1) {
            choices.clear();
            choices.addAll(found);
            JSONArray candidates = new JSONArray();
            String[] labels = new String[found.size()];
            for (int i = 0; i < found.size(); i++) {
                labels[i] = found.get(i).label();
                try {
                    candidates.put(new JSONObject().put("index", i + 1).put("label", labels[i]));
                } catch (Exception ignored) { }
            }
            if (choiceDialog != null) choiceDialog.dismiss();
            choiceDialog = new AlertDialog.Builder(this).setTitle("동명이인 확인")
                    .setItems(labels, (dialog, selected) -> {
                        if (selected < choices.size()) chooseContact(selected, time, null);
                    }).create();
            choiceDialog.show();
            if (voice) GleoResponder.send(this, START, "asking",
                    "I found multiple contacts. Please say the number of the person to pick up.", candidates);
            return;
        }
        if (found.size() == 1) selectedContact = found.get(0);
        else if (voice) {
            show("연락처에 같은 이름이 없습니다. 연락처를 확인해 주세요.");
            GleoResponder.send(this, START, "asking",
                    "I could not find that contact. Please check the name or choose a contact in the app.", null);
            return;
        } else show("연락처에 같은 이름이 없어 입력한 이름으로 진행합니다.");
        startSession(found.size() == 1 ? found.get(0).name : name, time, voice ? START : null);
    }

    private void chooseContact(int index, String time, String voiceAction) {
        if (index < 0 || index >= choices.size() || id != null || starting) return;
        selectedContact = choices.get(index);
        choices.clear();
        if (choiceDialog != null) {
            choiceDialog.dismiss();
            choiceDialog = null;
        }
        startSession(selectedContact.name, time, voiceAction);
    }

    private interface ApiCall { JSONObject run() throws Exception; }
    private interface ApiSuccess { void accept(JSONObject result); }

    private void api(String working, ApiCall call, ApiSuccess success, String voiceAction) {
        show(working);
        worker.execute(() -> {
            try {
                JSONObject result = call.run();
                runOnUiThread(() -> {
                    success.accept(result);
                    if (voiceAction != null) GleoResponder.send(this, voiceAction, "success",
                            "The pickup request is ready.", null);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    starting = false;
                    renderButtons();
                    show("요청 실패: " + error.getMessage());
                    if (voiceAction != null) GleoResponder.send(this, voiceAction, "fail",
                            "I could not complete the pickup request. Please check the app connection.", null);
                });
            }
        });
    }

    private void startSession(String name, String time, String voiceAction) {
        base = serverField.getText().toString().trim().replaceAll("/+$", "");
        inviteBase = inviteField.getText().toString().trim().replaceAll("/+$", "");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            show("서버 주소는 http:// 또는 https://로 시작해야 합니다.");
            if (voiceAction != null) GleoResponder.send(this, voiceAction, "fail",
                    "The server address is invalid.", null);
            return;
        }
        if (!inviteBase.startsWith("http://") && !inviteBase.startsWith("https://")) {
            show("탑승자 링크 주소는 http:// 또는 https://로 시작해야 합니다.");
            if (voiceAction != null) GleoResponder.send(this, voiceAction, "fail",
                    "The passenger link address is invalid.", null);
            return;
        }
        starting = true;
        renderButtons();
        api("픽업 요청을 만드는 중입니다.", () -> new PickupApi(base).create(name, time), result -> {
            starting = false;
            id = result.optString("id", null);
            driverToken = result.optString("driverToken", null);
            passengerToken = result.optString("passengerToken", null);
            getPreferences(MODE_PRIVATE).edit().putString("base", base)
                    .putString("inviteBase", inviteBase).putString("id", id)
                    .putString("driverToken", driverToken).putString("passengerToken", passengerToken).apply();
            session.setText(name + "님 픽업 · 탑승자 응답 대기");
            show("초대 링크를 탑승자에게 보내 주세요.");
            renderButtons();
            shareDriverLocation();
            refresh();
        }, voiceAction);
    }

    private void shareInvite() {
        if (id == null) return;
        String message = "픽업 위치를 지정해 주세요: " + inviteUrl();
        if (selectedContact != null && selectedContact.phone != null
                && !selectedContact.phone.isEmpty()) {
            Intent sms = new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("smsto", selectedContact.phone, null));
            sms.putExtra("sms_body", message);
            if (sms.resolveActivity(getPackageManager()) != null) {
                startActivity(sms);
                return;
            }
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, message);
        startActivity(Intent.createChooser(share, "픽업 초대 보내기"));
    }

    private void shareDriverLocation() {
        if (id == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION);
            return;
        }
        Intent service = new Intent(this, DriverLocationService.class);
        service.setAction(DriverLocationService.ACTION_START);
        service.putExtra(DriverLocationService.EXTRA_BASE, base);
        service.putExtra(DriverLocationService.EXTRA_ID, id);
        service.putExtra(DriverLocationService.EXTRA_TOKEN, driverToken);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
    }

    private void refresh() {
        if (id == null || fetching) return;
        fetching = true;
        String currentId = id, currentToken = driverToken, currentBase = base;
        worker.execute(() -> {
            try {
                JSONObject result = new PickupApi(currentBase).state(currentId, currentToken);
                runOnUiThread(() -> {
                    fetching = false;
                    if (currentId.equals(id)) renderState(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    fetching = false;
                    if (!currentId.equals(id)) return;
                    if (error instanceof PickupApi.ApiException
                            && ((PickupApi.ApiException) error).status == 401) {
                        clearSession("이전 픽업이 서버에 없어 초기화했습니다.");
                    } else show("서버 연결 확인: " + error.getMessage());
                });
            }
        });
    }

    private void renderState(JSONObject state) {
        String status = state.optString("status");
        if ("finished".equals(status) || "expired".equals(status)) {
            clearSession("finished".equals(status) ? "픽업이 완료됐습니다." : "초대가 만료됐습니다.");
            return;
        }
        session.setText(state.optString("contactName") + "님 픽업 · "
                + ("invited".equals(status) ? "탑승자 응답 대기"
                : "pickup_pending".equals(status) ? "승차 지점 확인 필요" : "픽업 진행 중"));
        pickup = state.optJSONObject("pickup");
        if (pickup != null && !pickup.optBoolean("confirmed") && routeRequested) {
            navigation.stopRoute();
            routeRequested = false;
            show("승차 지점이 변경돼 이전 경로를 중단했습니다.");
        }
        if (pickup == null) pickupLabel.setText("승차 지점: 탑승자 지정 대기");
        else pickupLabel.setText("승차 지점: " + pickup.optString("label") + "\n"
                + String.format(Locale.KOREA, "%.5f, %.5f", pickup.optDouble("lat"), pickup.optDouble("lon"))
                + (pickup.optBoolean("confirmed") ? " · 확인됨" : " · 운전자 확인 대기"));
        JSONObject passenger = state.optJSONObject("passengerLocation");
        if (passenger == null) passengerLabel.setText("탑승자 현재 위치: 공유되지 않음");
        else if (pickup != null) {
            float[] meters = new float[1];
            android.location.Location.distanceBetween(passenger.optDouble("lat"), passenger.optDouble("lon"),
                    pickup.optDouble("lat"), pickup.optDouble("lon"), meters);
            passengerLabel.setText("탑승자 현재 위치: 승차 지점에서 직선거리 약 "
                    + Math.round(meters[0]) + "m");
        }
        int eta = state.optInt("etaSeconds", -1);
        if (eta >= 0) etaLabel.setText("예상 도착시간: 약 " + Math.max(1, (eta + 59) / 60) + "분");
        else etaLabel.setText("예상 도착시간: 아직 제공되지 않음");
        if (pickup != null && pickup.optBoolean("confirmed")) navigation.refreshEta();
        renderButtons();
    }

    private void confirm(String voiceAction) {
        if (id == null || pickup == null || pickup.optBoolean("confirmed")) return;
        int revision = pickup.optInt("revision", -1);
        double lat = pickup.optDouble("lat"), lon = pickup.optDouble("lon");
        String label = pickup.optString("label", "승차 지점");
        String currentBase = base, currentId = id, currentToken = driverToken;
        api("승차 지점을 확인하는 중입니다.",
                () -> new PickupApi(currentBase).confirm(currentId, currentToken, revision),
                result -> {
                    renderState(result);
                    routeRequested = true;
                    navigation.routeTo(lat, lon, label);
                }, voiceAction);
    }

    private void finishPickup() {
        if (id == null) return;
        new AlertDialog.Builder(this).setTitle("픽업 완료")
                .setMessage("탑승을 확인하고 위치 공유를 종료할까요?")
                .setNegativeButton("계속", null)
                .setPositiveButton("종료", (dialog, which) -> {
                    String currentId = id, currentToken = driverToken, currentBase = base;
                    api("픽업을 종료하는 중입니다.",
                            () -> new PickupApi(currentBase).finish(currentId, currentToken),
                            result -> clearSession("픽업이 완료되어 위치 공유가 종료됐습니다."), null);
                }).show();
    }

    private void clearSession(String reason) {
        stopService(new Intent(this, DriverLocationService.class));
        if (navigation != null && routeRequested) navigation.stopRoute();
        routeRequested = false;
        getPreferences(MODE_PRIVATE).edit().remove("id").remove("driverToken")
                .remove("passengerToken").apply();
        id = null; driverToken = null; passengerToken = null; pickup = null;
        session.setText("진행 중인 픽업 없음");
        pickupLabel.setText("승차 지점: 탑승자 지정 대기");
        passengerLabel.setText("탑승자 현재 위치: 공유 대기");
        etaLabel.setText("예상 도착시간: 아직 제공되지 않음");
        show(reason);
        renderButtons();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (code == CONTACTS) {
            if (granted && !pendingVoice) resolveName(pendingName, pendingTime, false);
            else if (granted) show("연락처 권한을 허용했습니다. 음성 명령을 다시 말씀해 주세요.");
            else show("연락처 권한 없이 이름을 직접 입력해 픽업할 수 있습니다.");
        } else if (code == LOCATION) {
            if (granted) shareDriverLocation();
            else show("위치 권한이 없어 차량 위치를 공유할 수 없습니다.");
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICK_CONTACT || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                selectedContact = new ContactResolver.Choice(cursor.getString(0), cursor.getString(1));
                nameField.setText(selectedContact.name);
            }
        } catch (SecurityException error) { show("연락처를 읽을 수 없습니다."); }
    }
}
