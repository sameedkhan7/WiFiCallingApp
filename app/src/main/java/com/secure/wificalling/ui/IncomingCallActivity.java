package com.secure.wificalling.ui;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import com.secure.wificalling.R;
import com.secure.wificalling.network.CallSignaling;

/**
 * Google Dialer Style Incoming Call Screen.
 * Wakes up locked phone, dismisses keyguard, shows caller info,
 * and handles Answer/Decline actions.
 */
public class IncomingCallActivity extends Activity implements CallSignaling.CallListener {

    private String callerName;
    private String callerIp;
    private CallSignaling signaling;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        unlockAndTurnOnScreen();

        setContentView(R.layout.activity_incoming_call);

        callerName = getIntent().getStringExtra("CALLER_NAME");
        callerIp = getIntent().getStringExtra("CALLER_IP");

        TextView tvCaller = findViewById(R.id.tvCallerName);
        tvCaller.setText(callerName != null ? callerName : "Trusted Wi-Fi Device");

        signaling = new CallSignaling(this, this);

        Button btnAnswer = findViewById(R.id.btnAnswer);
        Button btnReject = findViewById(R.id.btnReject);

        btnAnswer.setOnClickListener(v -> answerCall());
        btnReject.setOnClickListener(v -> rejectCall());
    }

    private void unlockAndTurnOnScreen() {
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                km.requestDismissKeyguard(this, null);
            }
        }
    }

    private void answerCall() {
        if (signaling != null && callerIp != null) {
            signaling.sendCommand(callerIp, "ACCEPT", Build.MODEL);
        }

        Intent inCall = new Intent(this, InCallActivity.class);
        inCall.putExtra("REMOTE_NAME", callerName);
        inCall.putExtra("REMOTE_IP", callerIp);
        inCall.putExtra("IS_OUTGOING", false);
        startActivity(inCall);

        finish();
    }

    private void rejectCall() {
        if (signaling != null && callerIp != null) {
            signaling.sendCommand(callerIp, "REJECT", Build.MODEL);
        }
        finish();
    }

    @Override
    public void onCallEnded(String senderIp) {
        mainHandler.post(this::finish);
    }

    @Override
    public void onIncomingCall(String callerName, String callerIp) {}

    @Override
    public void onCallRinging(String remoteIp) {}

    @Override
    public void onCallAccepted(String remoteIp) {}

    @Override
    public void onCallRejected(String remoteIp) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (signaling != null) {
            signaling.removeListener(this);
        }
    }
}
