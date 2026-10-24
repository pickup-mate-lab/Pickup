package com.example.pickuppleos;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setText("지금 태우러 갑니다\nPleos 환경 설정 확인용 빈 앱");
        label.setTextSize(24);
        label.setGravity(Gravity.CENTER);
        setContentView(label);
    }
}
