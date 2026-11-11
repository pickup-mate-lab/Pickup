package com.example.pickuppleos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;

import java.util.concurrent.TimeUnit;

/** Firebase phone verification is initiated explicitly by the driver while parked. */
final class DriverFirebaseAuth {
    interface Result { void onStatus(String message); }
    static final String ACCOUNT_TOKEN = "@firebase-account";

    static FirebaseAuth configured(Context context) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Resources r=context.getResources(); String pkg=context.getPackageName();
                String api=value(r,pkg,"pickup_firebase_api_key");
                String app=value(r,pkg,"pickup_firebase_app_id");
                String project=value(r,pkg,"pickup_firebase_project_id");
                String sender=value(r,pkg,"pickup_firebase_sender_id");
                if (api.isEmpty() || app.isEmpty() || project.isEmpty() || sender.isEmpty()) return null;
                FirebaseApp.initializeApp(context,new FirebaseOptions.Builder().setApiKey(api)
                        .setApplicationId(app).setProjectId(project).setGcmSenderId(sender).build());
            }
            return FirebaseAuth.getInstance();
        } catch (IllegalArgumentException | IllegalStateException error) { return null; }
    }
    private static String value(Resources r,String pkg,String name) {
        int id=r.getIdentifier(name,"string",pkg);return id==0?"":r.getString(id).trim();
    }
    static boolean signedIn(Context context) { FirebaseAuth auth=configured(context);return auth!=null && auth.getCurrentUser()!=null; }
    static String idToken(Context context) throws Exception {
        FirebaseAuth auth=configured(context);
        if(auth==null || auth.getCurrentUser()==null) throw new IllegalStateException("휴대전화 인증이 필요합니다.");
        String token=Tasks.await(auth.getCurrentUser().getIdToken(false),15,TimeUnit.SECONDS).getToken();
        if(token==null || token.isEmpty()) throw new IllegalStateException("인증 토큰을 받지 못했습니다.");
        return token;
    }
    static String normalizePhone(String value) {
        String p=value.replaceAll("[\\s()\\-]","");
        if(p.matches("010[0-9]{8}")) return "+82"+p.substring(1);
        if(p.matches("\\+[1-9][0-9]{7,14}")) return p;
        return null;
    }
    static void showLogin(Activity activity, Result result) {
        FirebaseAuth auth=configured(activity);
        if(auth==null) {result.onStatus("Firebase 설정이 필요합니다. 설정 안내를 확인해 주세요.");return;}
        if(auth.getCurrentUser()!=null) {result.onStatus("이미 휴대전화 인증이 완료되어 있습니다.");return;}
        EditText phone=new EditText(activity);phone.setInputType(InputType.TYPE_CLASS_PHONE);phone.setHint("01012345678");
        LinearLayout content=new LinearLayout(activity);content.setPadding(32,12,32,0);content.addView(phone);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("운전자 휴대전화 인증")
                .setMessage("주차 중 인증해 주세요. 전화번호는 본인 확인과 픽업 요청 연결에 사용되며 Firebase에 전달됩니다.")
                .setView(content).setNegativeButton("취소",null).setPositiveButton("인증번호 받기",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String number=normalizePhone(phone.getText().toString());
            if(number==null) {phone.setError("010 번호 또는 국제번호를 입력해 주세요.");return;}
            dialog.dismiss();result.onStatus("인증번호를 요청하고 있습니다.");
            PhoneAuthProvider.OnVerificationStateChangedCallbacks callbacks=new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) { signIn(auth,credential,result); }
                @Override public void onVerificationFailed(@NonNull FirebaseException error) { result.onStatus("휴대전화 인증을 시작하지 못했습니다."); }
                @Override public void onCodeSent(@NonNull String id,@NonNull PhoneAuthProvider.ForceResendingToken resendingToken) {
                    EditText code=new EditText(activity);code.setInputType(InputType.TYPE_CLASS_NUMBER);code.setHint("인증번호");
                    new AlertDialog.Builder(activity).setTitle("인증번호 입력").setView(code).setNegativeButton("취소",null)
                            .setPositiveButton("확인",(d,w)->signIn(auth,PhoneAuthProvider.getCredential(id,code.getText().toString().trim()),result)).show();
                }
            };
            PhoneAuthProvider.verifyPhoneNumber(PhoneAuthOptions.newBuilder(auth).setPhoneNumber(number)
                    .setTimeout(60L,TimeUnit.SECONDS).setActivity(activity).setCallbacks(callbacks).build());
        }));
        dialog.show();
    }
    private static void signIn(FirebaseAuth auth, PhoneAuthCredential credential, Result result) {
        auth.signInWithCredential(credential).addOnCompleteListener(task -> result.onStatus(task.isSuccessful()
                ? "휴대전화 인증이 완료됐습니다." : "인증번호를 확인해 주세요."));
    }
}
