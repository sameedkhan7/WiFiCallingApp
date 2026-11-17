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
import com.secure.wificalling.service.CallBackgroundService;

/**
 * Google Dialer Style Incoming Call Screen.
 * Wakes up phone from lockscreen, shows caller identity,
 * and handles Answer/Decline with 100% reliable ringtone shutdown.
 */
public class IncomingCallActivity extends Activity implements CallSignaling.CallListener {

    private String callerName;
    private String callerIp;
    private String callerExt;
    private String callerPublicEp;
    private CallSignaling signaling;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        unlockAndTurnOnScreen();

        setContentView(R.layout.activity_incoming_call);

        callerName = getIntent().getStringExtra("CALLER_NAME");
        callerIp = getIntent().getStringExtra("CALLER_IP");
        callerExt = getIntent().getStringExtra("CALLER_EXT");
        callerPublicEp = getIntent().getStringExtra("CALLER_PUBLIC_EP");

        TextView tvCaller = findViewById(R.id.tvCallerName);
        tvCaller.setText(callerName != null ? callerName : "Incoming Wi-Fi Call");

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
        // Stop incoming ringtone immediately
        CallBackgroundService.stopRingtone(this);

        if (signaling != null) {
            signaling.sendCommand(callerExt, callerIp, "ACCEPT");
        }

        Intent inCall = new Intent(this, InCallActivity.class);
        inCall.putExtra("REMOTE_NAME", callerName);
        inCall.putExtra("REMOTE_IP", callerIp);
        inCall.putExtra("REMOTE_EXT", callerExt);
        inCall.putExtra("REMOTE_PUBLIC_EP", callerPublicEp != null ? callerPublicEp : CallSignaling.getPeerPublicEndpoint(callerExt));
        inCall.putExtra("IS_OUTGOING", false);
        startActivity(inCall);

        finish();
    }

    private void rejectCall() {
        // Stop incoming ringtone immediately
        CallBackgroundService.stopRingtone(this);

        if (signaling != null) {
            signaling.sendCommand(callerExt, callerIp, "REJECT");
        }
        finish();
    }

    @Override
    public void onCallEnded(String senderIp) {
        CallBackgroundService.stopRingtone(this);
        mainHandler.post(this::finish);
    }

    @Override
    public void onIncomingCall(String callerName, String callerIp, String callerExt) {}

    @Override
    public void onCallRinging(String remoteIp) {}

    @Override
    public void onCallAccepted(String remoteIp) {}

    @Override
    public void onCallRejected(String remoteIp) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CallBackgroundService.stopRingtone(this);
        if (signaling != null) {
            signaling.removeListener(this);
        }
    }
}
