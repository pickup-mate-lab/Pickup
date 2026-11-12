package com.example.pickuppleos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
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
    private static final int NAVY = Color.rgb(36, 59, 52);
    private static final int INK = Color.rgb(37, 59, 52);
    private static final int MINT = Color.rgb(200, 77, 54);
    private static final int MUTED = Color.rgb(112, 119, 105);
    private static final int BORDER = Color.rgb(228, 223, 211);
    private static final int SURFACE = Color.rgb(247, 245, 239);
    private static final int CONTACTS = 10, LOCATION = 11, PICK_CONTACT = 12;
    private static final String START = "START_PICKUP";
    private static final String CHOOSE = "CHOOSE_PICKUP_CONTACT";
    private static final String STATUS = "GET_PICKUP_STATUS";
    private static final String REPLY = "START_VOICE_REPLY";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<ContactResolver.Choice> choices = new ArrayList<>();
    private String id, driverToken, passengerToken, base;
    private String pendingName, pendingTime;
    private boolean pendingVoice;
    private ContactResolver.Choice selectedContact;
    private JSONObject pickup;
    private boolean polling, fetching, starting;
    private AlertDialog choiceDialog;
    private EditText serverField, nameField, timeField;
    private TextView message, session, pickupLabel, passengerLabel, etaLabel, inviteLabel;
    private TextView messageHistory;
    private LinearLayout messagePanel;
    private Button startButton, finishButton, refreshButton;
    private LinearLayout setupCard, actionCard, settingsCard;
    private VoiceReply voiceReply;
    private String voiceReplySessionId;
    private boolean pendingVoiceReply;

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
        id = saved.getString("id", null);
        driverToken = saved.getString("driverToken", null);
        passengerToken = saved.getString("passengerToken", null);
        createScreen();
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
        if (pendingVoiceReply && pickup != null) { pendingVoiceReply = false; main.postDelayed(this::startVoiceReply, 600); }
    }

    @Override protected void onPause() {
        polling = false;
        main.removeCallbacks(poll);
        if (voiceReply != null) voiceReply.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (choiceDialog != null) choiceDialog.dismiss();
        if (voiceReply != null) voiceReply.close();
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
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22C84D36),
                shape(Color.WHITE, 14, BORDER), null));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void primary(Button view) {
        view.setTextColor(Color.WHITE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                shape(MINT, 14, Color.TRANSPARENT), null));
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
        header.setBackgroundColor(SURFACE);
        header.setPadding(dp(38), dp(18), dp(38), dp(22));
        TextView title = text("픽업메이트", 32);
        title.setTextColor(NAVY);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(header, title, 0);
        TextView subtitle = text("DRIVER  ·  안전하게 만나는 가장 쉬운 방법", 18);
        subtitle.setTextColor(MUTED);
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
        statusCard.setBackground(shape(NAVY, 22, Color.TRANSPARENT));
        TextView statusHeading = eyebrow("현재 픽업  /  PICKUP STATUS");
        statusHeading.setTextColor(0xFFC9D8C9);
        add(statusCard, statusHeading, 0);
        session = text("진행 중인 픽업 없음", 34);
        session.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        session.setTextColor(Color.WHITE);
        add(statusCard, session, 14);
        message = text("탑승자를 선택하거나 이름을 입력해 픽업을 시작하세요.", 20);
        message.setTextColor(NAVY);
        message.setPadding(dp(18), dp(14), dp(18), dp(14));
        message.setBackground(shape(0xFFF6E8DC, 12, Color.TRANSPARENT));
        add(statusCard, message, 18);
        View divider = new View(this);
        divider.setBackgroundColor(0xFF61756A);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(24);
        statusCard.addView(divider, dividerParams);
        pickupLabel = text("승차 지점: 탑승자 지정 대기", 27);
        pickupLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pickupLabel.setTextColor(Color.WHITE);
        add(statusCard, pickupLabel, 23);
        passengerLabel = text("탑승자 현재 위치: 공유 대기", 21);
        passengerLabel.setTextColor(0xFFCBDACE);
        add(statusCard, passengerLabel, 18);
        etaLabel = text("예상 도착시간: 아직 제공되지 않음", 25);
        etaLabel.setTextColor(0xFFF3D1BE);
        etaLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(statusCard, etaLabel, 12);
        refreshButton = button("상태 새로고침", this::refresh);
        add(statusCard, refreshButton, 25);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        setupCard = card();
        add(setupCard, eyebrow("새 픽업 시작"), 0);
        TextView setupHint = text("탑승자를 선택해 픽업을 준비하세요.", 20);
        setupHint.setTextColor(MUTED);
        add(setupCard, setupHint, 12);
        add(setupCard, button("운전자 휴대전화 인증", () -> DriverFirebaseAuth.showLogin(this, this::show)), 12);
        serverField = field("차량 앱 서버 주소", base);
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
        finishButton = button("픽업 완료 · 위치 공유 종료", this::finishPickup);
        finishButton.setTextColor(0xFF9F493E);
        add(actionCard, finishButton, 18);
        messagePanel = new LinearLayout(this);
        messagePanel.setOrientation(LinearLayout.VERTICAL);
        add(messagePanel, eyebrow("간단한 메시지"), 0);
        messageHistory = text("아직 주고받은 메시지가 없습니다.", 19);
        messageHistory.setTextColor(MUTED);
        add(messagePanel, messageHistory, 10);
        add(messagePanel, button("지금 가고 있어요", () -> sendMessage("지금 가고 있어요.")), 12);
        add(messagePanel, button("잠시 기다려 주세요", () -> sendMessage("잠시 기다려 주세요.")), 10);
        add(messagePanel, button("음성으로 답장", this::startVoiceReply), 10);
        add(actionCard, messagePanel, 24);

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

        Button settingsToggle = button("서버 설정  ▾", () -> {
            settingsCard.setVisibility(settingsCard.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });
        settingsToggle.setTextSize(18);
        add(body, settingsToggle, 22);
        settingsCard = card();
        add(settingsCard, eyebrow("서버 주소"), 0);
        TextView serverHint = text("차량 앱이 연결할 서버 주소입니다. 실제 계정은 HTTPS 주소를 사용합니다.", 18);
        serverHint.setTextColor(MUTED);
        add(settingsCard, serverHint, 8);
        add(settingsCard, serverField, 18);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            TextView demoHint = text("아래 버튼은 실제 탑승자에게 전송하지 않는 시연용 기능입니다.", 18);
            demoHint.setTextColor(MUTED);
            add(settingsCard, demoHint, 18);
            add(settingsCard, button("시연: 탑승자 승인", () -> demoPickup(37.4979, 127.0276, "강남역 인근")), 12);
            add(settingsCard, button("시연: 승차 지점 변경", () -> demoPickup(37.5000, 127.0300, "변경된 승차 지점")), 10);
            add(settingsCard, button("시연: 탑승자 메시지", this::demoMessage), 10);
        }
        add(body, settingsCard, 10);
        settingsCard.setVisibility(View.GONE);
    }

    private void show(String value) { message.setText(value); }

    private void renderButtons() {
        setupCard.setVisibility(id == null ? View.VISIBLE : View.GONE);
        actionCard.setVisibility(id == null ? View.GONE : View.VISIBLE);
        startButton.setEnabled(id == null && !starting);
        finishButton.setEnabled(id != null);
        refreshButton.setVisibility(id == null ? View.GONE : View.VISIBLE);
        messagePanel.setVisibility(id != null && pickup != null ? View.VISIBLE : View.GONE);
        inviteLabel.setText(id == null ? "" : DriverFirebaseAuth.ACCOUNT_TOKEN.equals(driverToken)
                ? "탑승자의 요청 확인을 기다리고 있습니다." : "로컬 데모 픽업입니다.");
    }

    private void handleGleo(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!START.equals(action) && !CHOOSE.equals(action) && !STATUS.equals(action) && !REPLY.equals(action)) return;
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
            } else if (REPLY.equals(action)) {
                if (id == null) {
                    GleoResponder.send(this, action, "fail", "There is no approved pickup available for a reply.", null);
                } else {
                    // Gleo should not speak over our own prompt.
                    GleoResponder.sendSilent(this, action, "success", "Starting voice reply.");
                    pendingVoiceReply = true;
                    if (hasWindowFocus() && pickup != null) { pendingVoiceReply = false; main.postDelayed(this::startVoiceReply, 600); }
                    else refresh();
                }
            } else {
                voiceStatus();
            }
        } catch (Exception error) {
            GleoResponder.send(this, action, "fail", "I could not read the pickup request.", null);
        }
    }

    private void voiceStatus() {
        if (id == null) {
            GleoResponder.send(this, STATUS, "success", "There is no active pickup.", null);
            return;
        }
        String currentId = id, currentToken = driverToken, currentBase = base;
        worker.execute(() -> {
            try {
                JSONObject state = new PickupApi(PickupActivity.this, currentBase).state(currentId, currentToken);
                JSONObject point = state.optJSONObject("pickup");
                JSONArray data = new JSONArray();
                data.put(new JSONObject().put("contactName", state.optString("contactName"))
                        .put("status", state.optString("status"))
                        .put("pickupLocation", point == null ? "" : point.optString("label")));
                runOnUiThread(() -> GleoResponder.send(this, STATUS, "success",
                        "Here is the current pickup status.", data));
            } catch (Exception error) {
                runOnUiThread(() -> GleoResponder.send(this, STATUS, "fail",
                        "I could not retrieve the pickup status.", null));
            }
        });
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
        selectedContact = null;
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
                            "The pickup request was prepared. Check the passenger response.", null);
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
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            show("서버 주소는 http:// 또는 https://로 시작해야 합니다.");
            if (voiceAction != null) GleoResponder.send(this, voiceAction, "fail",
                    "The server address is invalid.", null);
            return;
        }
        boolean account = DriverFirebaseAuth.signedIn(this);
        boolean configured = DriverFirebaseAuth.configured(this) != null;
        boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!account && (configured || !debug)) {
            show(configured ? "먼저 운전자 휴대전화 인증을 완료해 주세요." : "Firebase 설정과 운전자 인증이 필요합니다.");
            if (voiceAction != null) GleoResponder.send(this, voiceAction, "fail", "Please authenticate the driver in the app first.", null);
            return;
        }
        final String passengerPhone = account && selectedContact != null
                ? DriverFirebaseAuth.normalizePhone(selectedContact.phone) : null;
        if (account && passengerPhone == null) {
            show("연락처에서 올바른 탑승자 전화번호를 선택해 주세요.");
            if (voiceAction != null) GleoResponder.send(this, voiceAction, "fail", "Please choose a contact with a valid phone number.", null);
            return;
        }
        starting = true;
        renderButtons();
        api("픽업 요청을 만드는 중입니다.", () -> account
                ? new PickupApi(PickupActivity.this, base).createAccount(name, time, passengerPhone)
                : new PickupApi(PickupActivity.this, base).create(name, time), result -> {
            starting = false;
            id = result.optString("id", null);
            driverToken = account ? DriverFirebaseAuth.ACCOUNT_TOKEN : result.optString("driverToken", null);
            passengerToken = result.optString("passengerToken", null);
            getPreferences(MODE_PRIVATE).edit().putString("base", base)
                    .putString("id", id)
                    .putString("driverToken", driverToken).putString("passengerToken", passengerToken).apply();
            session.setText(name + "님 픽업 · 탑승자 응답 대기");
            show(account ? "탑승자의 요청 확인을 기다리고 있습니다." : "데모 픽업을 준비했습니다.");
            renderButtons();
            shareDriverLocation();
            refresh();
        }, voiceAction);
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
                JSONObject result = new PickupApi(PickupActivity.this, currentBase).state(currentId, currentToken);
                runOnUiThread(() -> {
                    fetching = false;
                    if (currentId.equals(id)) renderState(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    fetching = false;
                    if (!currentId.equals(id)) return;
                    if (PickupApi.isTerminalSessionError(error, currentToken)) {
                        clearSession("픽업이 종료되었거나 요청이 취소됐습니다.");
                    } else show("서버 연결 확인: " + error.getMessage());
                });
            }
        });
    }

    private void renderState(JSONObject state) {
        String status = state.optString("status");
        if ("finished".equals(status) || "expired".equals(status)) {
            String reason = state.optString("endReason", "");
            clearSession("declined".equals(reason) ? "탑승자가 픽업 요청을 거절했습니다."
                    : "finished".equals(status) ? "픽업이 완료됐습니다." : "초대가 만료됐습니다.");
            return;
        }
        session.setText(state.optString("contactName") + "님 픽업 · "
                + ("invited".equals(status) ? "탑승자 응답 대기"
                : "pickup_pending".equals(status) ? "승차 지점 경로 연결 중" : "픽업 진행 중"));
        pickup = state.optJSONObject("pickup");
        if (pendingVoiceReply && hasWindowFocus() && pickup != null) { pendingVoiceReply = false; main.postDelayed(this::startVoiceReply, 600); }
        if (pickup == null) pickupLabel.setText("승차 지점: 탑승자 지정 대기");
        else pickupLabel.setText("승차 지점: " + pickup.optString("label") + "\n"
                + String.format(Locale.KOREA, "%.5f, %.5f", pickup.optDouble("lat"), pickup.optDouble("lon"))
                + (pickup.optBoolean("confirmed") ? " · 수신됨" : " · 경로 요청 준비 중"));
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
        JSONArray messages = state.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            messageHistory.setText("아직 주고받은 메시지가 없습니다.");
        } else {
            StringBuilder history = new StringBuilder();
            for (int i = Math.max(0, messages.length() - 3); i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if (item == null) continue;
                if (history.length() > 0) history.append('\n');
                history.append("passenger".equals(item.optString("sender")) ? "탑승자: " : "나: ")
                        .append(item.optString("text"));
            }
            messageHistory.setText(history.toString());
        }
        if (eta >= 0) etaLabel.setText("예상 도착시간: 약 " + Math.max(1, (eta + 59) / 60) + "분");
        else etaLabel.setText("예상 도착시간: 아직 제공되지 않음");
        renderButtons();
    }

    private void sendMessage(String text) {
        if (id == null || pickup == null) return;
        String currentId = id, currentToken = driverToken, currentBase = base;
        api("메시지를 보내는 중입니다.",
                () -> new PickupApi(PickupActivity.this, currentBase).message(currentId, currentToken, text),
                result -> { renderState(result); show("메시지를 보냈습니다."); }, null);
    }

    private void startVoiceReply() {
        if (id == null || pickup == null) { show("먼저 탑승자가 요청을 승인해야 합니다."); return; }
        voiceReplySessionId = id;
        try {
            if (voiceReply == null) voiceReply = new VoiceReply(this, new VoiceReply.Host() {
                @Override public void status(String text) { show(text); }
                @Override public void send(String text) {
                    if (id == null || !id.equals(voiceReplySessionId) || pickup == null) {
                        voiceReply.announce("픽업이 변경되어 메시지를 보내지 않았습니다."); return;
                    }
                    String currentId = id, currentToken = driverToken, currentBase = base;
                    worker.execute(() -> {
                        try {
                            JSONObject result = new PickupApi(PickupActivity.this, currentBase).message(currentId, currentToken, text);
                            runOnUiThread(() -> {
                                if (currentId.equals(id)) { renderState(result); voiceReply.announce("메시지를 보냈습니다."); }
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> voiceReply.announce("메시지를 보내지 못했습니다. 다시 시도해 주세요."));
                        }
                    });
                }
            });
            voiceReply.start();
        } catch (RuntimeException | LinkageError error) { show("음성 인식을 사용할 수 없습니다."); }
    }

    private void demoPickup(double lat, double lon, String label) {
        if (id == null || passengerToken == null) { show("먼저 픽업을 시작해 주세요."); return; }
        String currentId = id, currentToken = passengerToken, currentBase = base;
        api("시연 위치를 전달하는 중입니다.",
                () -> new PickupApi(PickupActivity.this, currentBase).passengerPickup(currentId, currentToken, lat, lon, label),
                result -> { show("시연 위치를 전달했습니다. 자동 경로 요청을 확인하세요."); refresh(); }, null);
    }

    private void demoMessage() {
        if (id == null || passengerToken == null || pickup == null) {
            show("먼저 시연 승차 지점을 설정해 주세요."); return;
        }
        String currentId = id, currentToken = passengerToken, currentBase = base;
        api("시연 메시지를 보내는 중입니다.",
                () -> new PickupApi(PickupActivity.this, currentBase).message(currentId, currentToken, "입구에서 기다리고 있어요."),
                result -> { show("시연 메시지를 전달했습니다."); refresh(); }, null);
    }

    private void finishPickup() {
        if (id == null) return;
        new AlertDialog.Builder(this).setTitle("픽업 완료")
                .setMessage("탑승을 확인하고 위치 공유를 종료할까요?")
                .setNegativeButton("계속", null)
                .setPositiveButton("종료", (dialog, which) -> {
                    String currentId = id, currentToken = driverToken, currentBase = base;
                    api("픽업을 종료하는 중입니다.",
                            () -> new PickupApi(PickupActivity.this, currentBase).finish(currentId, currentToken),
                            result -> clearSession("픽업이 완료되어 위치 공유가 종료됐습니다."), null);
                }).show();
    }

    private void clearSession(String reason) {
        if (voiceReply != null) voiceReply.cancel();
        stopService(new Intent(this, DriverLocationService.class));
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
