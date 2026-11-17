package com.secure.wificalling.ui;

import android.app.Activity;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.TextView;
import com.secure.wificalling.R;
import com.secure.wificalling.audio.VoiceEngine;
import com.secure.wificalling.network.CallSignaling;

/**
 * Google Dialer Style Outgoing & Active Call Screen.
 * Handshakes call states: CALLING -> RINGING -> CONNECTED -> ENDED.
 * Timer ONLY starts when remote peer accepts the call!
 */
public class InCallActivity extends Activity implements CallSignaling.CallListener {

    private static final String TAG = "InCallActivity";

    private VoiceEngine voiceEngine;
    private CallSignaling signaling;
    private String remoteIp;
    private String remoteName;
    private boolean isOutgoing;

    private TextView tvStatus;
    private Chronometer chronometer;
    private ToneGenerator ringbackTone;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isMuted = false;
    private boolean isSpeakerOn = false;
    private boolean isCallActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_in_call);

        remoteName = getIntent().getStringExtra("REMOTE_NAME");
        remoteIp = getIntent().getStringExtra("REMOTE_IP");
        isOutgoing = getIntent().getBooleanExtra("IS_OUTGOING", true);

        TextView tvName = findViewById(R.id.tvInCallName);
        tvName.setText(remoteName != null ? remoteName : "Wi-Fi Call");

        tvStatus = findViewById(R.id.tvInCallStatus);
        chronometer = findViewById(R.id.callChronometer);

        Button btnMute = findViewById(R.id.btnMute);
        Button btnSpeaker = findViewById(R.id.btnSpeaker);
        Button btnHangup = findViewById(R.id.btnHangup);

        signaling = new CallSignaling(this, this);

        if (isOutgoing) {
            // Outgoing call: Wait for peer to answer!
            tvStatus.setText("Calling...");
            chronometer.setVisibility(View.GONE);
            startRingbackTone();
        } else {
            // Incoming answered call: Immediately active
            startActiveCall();
        }

        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (voiceEngine != null) voiceEngine.setMute(isMuted);
            btnMute.setText(isMuted ? "Unmute" : "Mute");
        });

        btnSpeaker.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            if (voiceEngine != null) voiceEngine.setSpeakerphone(isSpeakerOn);
            btnSpeaker.setText(isSpeakerOn ? "Earpiece" : "Speaker");
        });

        btnHangup.setOnClickListener(v -> endCall(true));
    }

    private void startRingbackTone() {
        try {
            ringbackTone = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80);
            ringbackTone.startTone(ToneGenerator.TONE_SUP_RINGTONE);
        } catch (Throwable t) {
            Log.e(TAG, "Ringback error", t);
        }
    }

    private void stopRingbackTone() {
        try {
            if (ringbackTone != null) {
                ringbackTone.stopTone();
                ringbackTone.release();
                ringbackTone = null;
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void startActiveCall() {
        if (isCallActive) return;
        isCallActive = true;

        stopRingbackTone();

        mainHandler.post(() -> {
            tvStatus.setText("Connected • Encrypted");
            chronometer.setVisibility(View.VISIBLE);
            chronometer.setBase(SystemClock.elapsedRealtime());
            chronometer.start();
        });

        voiceEngine = new VoiceEngine(this);
        voiceEngine.startCall(remoteIp);
    }

    private synchronized void endCall(boolean notifyRemote) {
        stopRingbackTone();

        if (voiceEngine != null) {
            voiceEngine.stopCall();
            voiceEngine = null;
        }

        if (notifyRemote && signaling != null && remoteIp != null) {
            signaling.sendCommand(remoteIp, "HANGUP", Build.MODEL);
        }

        mainHandler.post(() -> {
            tvStatus.setText("Call Ended");
            chronometer.stop();
            mainHandler.postDelayed(this::finish, 1000);
        });
    }

    @Override
    public void onCallRinging(String senderIp) {
        mainHandler.post(() -> tvStatus.setText("Ringing..."));
    }

    @Override
    public void onCallAccepted(String senderIp) {
        startActiveCall();
    }

    @Override
    public void onCallRejected(String senderIp) {
        stopRingbackTone();
        mainHandler.post(() -> {
            tvStatus.setText("Call Declined");
            mainHandler.postDelayed(this::finish, 1500);
        });
    }

    @Override
    public void onCallEnded(String senderIp) {
        endCall(false);
    }

    @Override
    public void onIncomingCall(String callerName, String callerIp) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingbackTone();
        if (voiceEngine != null) {
            voiceEngine.stopCall();
        }
        if (signaling != null) {
            signaling.removeListener(this);
        }
    }
}
