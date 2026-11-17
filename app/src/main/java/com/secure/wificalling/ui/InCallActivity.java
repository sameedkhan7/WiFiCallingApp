package com.secure.wificalling.ui;

import android.app.Activity;
import android.media.AudioManager;
import android.media.ToneGenerator;
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
import com.secure.wificalling.service.CallBackgroundService;

/**
 * Active In-Call Screen.
 * Handshakes call states: CALLING -> RINGING -> CONNECTED -> ENDED.
 * Synchronized hangup across both devices.
 */
public class InCallActivity extends Activity implements CallSignaling.CallListener {

    private static final String TAG = "InCallActivity";

    private VoiceEngine voiceEngine;
    private CallSignaling signaling;
    private String remoteIp;
    private String remoteName;
    private String remoteExt;
    private String remotePublicEndpoint;
    private boolean isOutgoing;

    private TextView tvStatus;
    private Chronometer chronometer;
    private ToneGenerator toneGenerator;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isMuted = false;
    private boolean isSpeakerOn = false;
    private boolean isCallActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_in_call);

        // Ensure any incoming ringtone is stopped
        CallBackgroundService.stopRingtone(this);

        remoteName = getIntent().getStringExtra("REMOTE_NAME");
        remoteIp = getIntent().getStringExtra("REMOTE_IP");
        remoteExt = getIntent().getStringExtra("REMOTE_EXT");
        remotePublicEndpoint = getIntent().getStringExtra("REMOTE_PUBLIC_EP");
        if (remotePublicEndpoint == null || remotePublicEndpoint.isEmpty()) {
            remotePublicEndpoint = CallSignaling.getPeerPublicEndpoint(remoteExt);
        }
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
            tvStatus.setText("Calling...");
            chronometer.setVisibility(View.GONE);
            startRingbackTone();
        } else {
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
            toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80);
            toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE);
        } catch (Throwable t) {
            Log.e(TAG, "Ringback error", t);
        }
    }

    private void stopRingbackTone() {
        try {
            if (toneGenerator != null) {
                toneGenerator.stopTone();
                toneGenerator.release();
                toneGenerator = null;
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

        if (remotePublicEndpoint == null || remotePublicEndpoint.isEmpty()) {
            remotePublicEndpoint = CallSignaling.getPeerPublicEndpoint(remoteExt);
        }

        voiceEngine = new VoiceEngine(this);
        voiceEngine.startCall(remoteIp, remotePublicEndpoint);
    }

    private synchronized void endCall(boolean notifyRemote) {
        stopRingbackTone();

        if (voiceEngine != null) {
            voiceEngine.stopCall();
            voiceEngine = null;
        }

        if (notifyRemote && signaling != null) {
            signaling.sendCommand(remoteExt, remoteIp, "HANGUP");
        }

        try {
            ToneGenerator beep = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 90);
            beep.startTone(ToneGenerator.TONE_PROP_BEEP2, 250);
        } catch (Throwable ignored) {}

        mainHandler.post(() -> {
            tvStatus.setText("Call Ended");
            chronometer.stop();
            mainHandler.postDelayed(this::finish, 800);
        });
    }

    @Override
    public void onCallRinging(String senderIp) {
        mainHandler.post(() -> tvStatus.setText("Ringing..."));
    }

    @Override
    public void onCallAccepted(String senderIp) {
        if (senderIp != null && !senderIp.isEmpty() && !senderIp.equals("remote")) {
            remoteIp = senderIp;
        }
        startActiveCall();
    }

    @Override
    public void onCallRejected(String senderIp) {
        stopRingbackTone();
        mainHandler.post(() -> {
            tvStatus.setText("Call Declined");
            mainHandler.postDelayed(this::finish, 1200);
        });
    }

    @Override
    public void onCallEnded(String senderIp) {
        endCall(false);
    }

    @Override
    public void onIncomingCall(String callerName, String callerIp, String callerExt) {}

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
