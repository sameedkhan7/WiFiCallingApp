package com.secure.wificalling.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.util.Log;
import com.secure.wificalling.network.CallSignaling;
import com.secure.wificalling.network.StunUtil;
import com.secure.wificalling.security.CryptoUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * High-Definition Low-Latency Full-Duplex Voice Engine.
 * Single UDP Socket with STUN NAT Hole Punching & Symmetric Reflection.
 * Works seamlessly across local Wi-Fi, cross-Wi-Fi, and 4G/5G mobile networks.
 */
public class VoiceEngine {

    private static final String TAG = "VoiceEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 640; // 20 ms frame @ 16kHz 16-bit mono

    private Context context;
    private volatile boolean isCalling = false;
    private String remoteIp;
    private String remotePublicEndpoint;
    private boolean isMuted = false;
    private boolean isSpeakerphone = false;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private DatagramSocket sharedUdpSocket;

    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    private volatile InetAddress targetAddress;
    private volatile int targetPort = CallSignaling.AUDIO_PORT;
    private volatile InetAddress lanTargetAddress;
    private volatile int lanTargetPort = CallSignaling.AUDIO_PORT;
    private volatile InetAddress wanTargetAddress;
    private volatile int wanTargetPort = CallSignaling.AUDIO_PORT;
    private volatile boolean hasLockedTarget = false;

    public VoiceEngine(Context context) {
        this.context = context;
    }

    public void startCall(String remoteIp, String remotePublicEndpoint) {
        if (isCalling) return;
        this.remoteIp = remoteIp;
        this.remotePublicEndpoint = remotePublicEndpoint;
        this.isCalling = true;
        this.hasLockedTarget = false;

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }

        try {
            sharedUdpSocket = new DatagramSocket(null);
            sharedUdpSocket.setReuseAddress(true);
            sharedUdpSocket.setReceiveBufferSize(256 * 1024);
            sharedUdpSocket.setSendBufferSize(256 * 1024);
            sharedUdpSocket.bind(new InetSocketAddress(CallSignaling.AUDIO_PORT));
        } catch (Exception e) {
            Log.w(TAG, "Binding to AUDIO_PORT 50622 failed, using ephemeral port", e);
            try {
                sharedUdpSocket = new DatagramSocket();
                sharedUdpSocket.setReceiveBufferSize(256 * 1024);
                sharedUdpSocket.setSendBufferSize(256 * 1024);
            } catch (Exception ignored) {}
        }

        // Initialize target address candidates (both LAN and WAN)
        try {
            if (remoteIp != null && !remoteIp.equals("remote") && !remoteIp.equals("127.0.0.1") && !remoteIp.isEmpty()) {
                lanTargetAddress = InetAddress.getByName(remoteIp);
                lanTargetPort = CallSignaling.AUDIO_PORT;
                targetAddress = lanTargetAddress;
                targetPort = lanTargetPort;
            }
        } catch (Exception ignored) {}

        try {
            if (remotePublicEndpoint != null && remotePublicEndpoint.contains(":")) {
                String[] parts = remotePublicEndpoint.split(":");
                wanTargetAddress = InetAddress.getByName(parts[0]);
                wanTargetPort = Integer.parseInt(parts[1]);
                if (targetAddress == null) {
                    targetAddress = wanTargetAddress;
                    targetPort = wanTargetPort;
                }
            }
        } catch (Exception ignored) {}

        // Punch NAT holes concurrently
        new Thread(this::punchNatHoles, "NatHolePunchThread").start();

        new Thread(this::recordAndSend, "AudioRecordThread").start();
        new Thread(this::receiveAndPlay, "AudioTrackThread").start();
    }

    public void startCall(String remoteIp) {
        startCall(remoteIp, null);
    }

    private void punchNatHoles() {
        if (sharedUdpSocket == null) return;
        try {
            // 1. Discover and punch through NAT via STUN
            StunUtil.getPublicAddress(sharedUdpSocket);

            byte[] punchBytes = new byte[32];
            java.util.Arrays.fill(punchBytes, (byte) 0xAA);

            // 2. Blast hole punch packets to both local IP and public WAN endpoint
            for (int i = 0; i < 12 && isCalling; i++) {
                if (lanTargetAddress != null) {
                    try {
                        sharedUdpSocket.send(new DatagramPacket(punchBytes, punchBytes.length, lanTargetAddress, lanTargetPort));
                    } catch (Exception ignored) {}
                }
                if (wanTargetAddress != null) {
                    try {
                        sharedUdpSocket.send(new DatagramPacket(punchBytes, punchBytes.length, wanTargetAddress, wanTargetPort));
                    } catch (Exception ignored) {}
                }
                Thread.sleep(100);
            }
        } catch (Exception ignored) {}
    }

    private void recordAndSend() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        int recordBufferSize = Math.max(minBufferSize, FRAME_SIZE * 4);

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, recordBufferSize);

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                } catch (Throwable ignored) {}
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                } catch (Throwable ignored) {}
            }

            audioRecord.startRecording();
            byte[] frame = new byte[FRAME_SIZE];

            while (isCalling) {
                int read = 0;
                while (read < FRAME_SIZE && isCalling) {
                    int r = audioRecord.read(frame, read, FRAME_SIZE - read);
                    if (r > 0) read += r;
                    else break;
                }

                if (read == FRAME_SIZE && isCalling) {
                    if (isMuted) {
                        java.util.Arrays.fill(frame, (byte) 0);
                    }

                    CryptoUtil.encryptAudioBuffer(frame, 0, FRAME_SIZE);

                    if (sharedUdpSocket != null && !sharedUdpSocket.isClosed()) {
                        // 1. Send to primary / locked target
                        if (targetAddress != null) {
                            try {
                                DatagramPacket packet = new DatagramPacket(frame, FRAME_SIZE, targetAddress, targetPort);
                                sharedUdpSocket.send(packet);
                            } catch (Exception ignored) {}
                        }
                        // 2. Until symmetric lock is confirmed, concurrently stream to WAN target as well
                        if (!hasLockedTarget && wanTargetAddress != null && (!wanTargetAddress.equals(targetAddress) || wanTargetPort != targetPort)) {
                            try {
                                DatagramPacket wanPacket = new DatagramPacket(frame, FRAME_SIZE, wanTargetAddress, wanTargetPort);
                                sharedUdpSocket.send(wanPacket);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "recordAndSend error", e);
        } finally {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                    audioRecord.release();
                } catch (Exception ignored) {}
            }
        }
    }

    private void receiveAndPlay() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
        int trackBufferSize = Math.max(minBufferSize, FRAME_SIZE * 4);

        try {
            audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, trackBufferSize, AudioTrack.MODE_STREAM);

            audioTrack.play();

            byte[] inBuffer = new byte[FRAME_SIZE + 128];
            DatagramPacket packet = new DatagramPacket(inBuffer, inBuffer.length);

            while (isCalling && sharedUdpSocket != null && !sharedUdpSocket.isClosed()) {
                try {
                    sharedUdpSocket.receive(packet);
                    int len = packet.getLength();

                    // Discard small NAT keepalive / punch packets (32 bytes)
                    if (len == FRAME_SIZE) {
                        // Symmetric Reflection: Lock return traffic directly to sender
                        targetAddress = packet.getAddress();
                        targetPort = packet.getPort();
                        hasLockedTarget = true;

                        CryptoUtil.decryptAudioBuffer(inBuffer, 0, len);
                        audioTrack.write(inBuffer, 0, len);
                    }
                } catch (Exception e) {
                    if (!isCalling) break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "receiveAndPlay error", e);
        } finally {
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                    audioTrack.release();
                } catch (Exception ignored) {}
            }
        }
    }

    public void setMute(boolean mute) {
        this.isMuted = mute;
    }

    public void setSpeakerphone(boolean speaker) {
        this.isSpeakerphone = speaker;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setSpeakerphoneOn(speaker);
        }
    }

    public synchronized void stopCall() {
        if (!isCalling) return;
        isCalling = false;

        if (sharedUdpSocket != null) {
            try {
                sharedUdpSocket.close();
            } catch (Exception ignored) {}
            sharedUdpSocket = null;
        }

        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Throwable ignored) {}
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Throwable ignored) {}
        }

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        }
    }
}
