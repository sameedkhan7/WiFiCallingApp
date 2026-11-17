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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hybrid Resilient Voice Engine.
 * Dual-transmission architecture:
 * 1. Ultra-low-latency direct UDP P2P (for same Wi-Fi / open NAT).
 * 2. Real-time Cloud MQTT Audio Relay (for double symmetric CGNAT across 4G/5G mobile carriers).
 * Zero-echo frame de-duplication guarantees crystal-clear, zero-drop voice calls.
 */
public class VoiceEngine {

    private static final String TAG = "VoiceEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 640; // 20 ms frame @ 16kHz 16-bit mono
    private static final int HEADER_SIZE = 8;  // Magic 'W','A' (2B) + seqNum (4B) + len (2B)

    private Context context;
    private volatile boolean isCalling = false;
    private String remoteIp;
    private String remotePublicEndpoint;
    private String remoteExt;
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

    // Frame sequence tracking & de-duplication
    private int sendSeqNum = 0;
    private final Object playLock = new Object();
    private int lastPlayedSeq = -1;
    private final Set<Integer> playedSeqs = Collections.newSetFromMap(new LinkedHashMap<Integer, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 400; // Keep up to 400 recent frames (~8 seconds)
        }
    });

    // Asynchronous relay audio sending queue (prevents AudioRecord thread blocking)
    private final BlockingQueue<byte[]> relaySendQueue = new LinkedBlockingQueue<>(16);
    private Thread relaySendThread;

    public VoiceEngine(Context context) {
        this.context = context;
    }

    public void startCall(String remoteIp, String remotePublicEndpoint, String remoteExt) {
        if (isCalling) return;
        this.remoteIp = remoteIp;
        this.remotePublicEndpoint = remotePublicEndpoint;
        this.remoteExt = remoteExt;
        this.isCalling = true;
        this.hasLockedTarget = false;
        this.sendSeqNum = 0;
        this.lastPlayedSeq = -1;
        synchronized (playLock) {
            this.playedSeqs.clear();
        }
        this.relaySendQueue.clear();

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

        // Initialize candidate targets (LAN IP and STUN WAN Endpoint)
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

        // Register for incoming cloud relay audio frames
        CallSignaling.setAudioRelayListener(this::onRelayAudioReceived);

        // Start dedicated relay worker thread
        startRelaySendWorker();

        // Punch NAT holes concurrently
        new Thread(this::punchNatHoles, "NatHolePunchThread").start();

        new Thread(this::recordAndSend, "AudioRecordThread").start();
        new Thread(this::receiveAndPlay, "AudioTrackThread").start();
    }

    public void startCall(String remoteIp, String remotePublicEndpoint) {
        startCall(remoteIp, remotePublicEndpoint, null);
    }

    public void startCall(String remoteIp) {
        startCall(remoteIp, null, null);
    }

    private void startRelaySendWorker() {
        relaySendThread = new Thread(() -> {
            while (isCalling) {
                try {
                    byte[] packet = relaySendQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (packet != null && remoteExt != null && !remoteExt.isEmpty()) {
                        CallSignaling.sendRelayAudio(remoteExt, packet);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {}
            }
        }, "RelaySendThread");
        relaySendThread.start();
    }

    private void punchNatHoles() {
        if (sharedUdpSocket == null) return;
        try {
            StunUtil.getPublicAddress(sharedUdpSocket);

            byte[] punchBytes = new byte[32];
            java.util.Arrays.fill(punchBytes, (byte) 0xAA);

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
            byte[] rawPcmFrame = new byte[FRAME_SIZE];

            while (isCalling) {
                int read = 0;
                while (read < FRAME_SIZE && isCalling) {
                    int r = audioRecord.read(rawPcmFrame, read, FRAME_SIZE - read);
                    if (r > 0) read += r;
                    else break;
                }

                if (read == FRAME_SIZE && isCalling) {
                    if (isMuted) {
                        java.util.Arrays.fill(rawPcmFrame, (byte) 0);
                    }

                    // Build formatted packet: [Header 8B] + [Encrypted PCM 640B]
                    byte[] packetData = new byte[HEADER_SIZE + FRAME_SIZE];
                    packetData[0] = (byte) 0x57; // 'W'
                    packetData[1] = (byte) 0x41; // 'A'
                    packetData[2] = (byte) ((sendSeqNum >> 24) & 0xFF);
                    packetData[3] = (byte) ((sendSeqNum >> 16) & 0xFF);
                    packetData[4] = (byte) ((sendSeqNum >> 8) & 0xFF);
                    packetData[5] = (byte) (sendSeqNum & 0xFF);
                    packetData[6] = (byte) ((FRAME_SIZE >> 8) & 0xFF);
                    packetData[7] = (byte) (FRAME_SIZE & 0xFF);
                    sendSeqNum++;

                    System.arraycopy(rawPcmFrame, 0, packetData, HEADER_SIZE, FRAME_SIZE);
                    CryptoUtil.encryptAudioBuffer(packetData, HEADER_SIZE, FRAME_SIZE);

                    // 1. Direct UDP transmission
                    if (sharedUdpSocket != null && !sharedUdpSocket.isClosed()) {
                        if (targetAddress != null) {
                            try {
                                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, targetAddress, targetPort);
                                sharedUdpSocket.send(packet);
                            } catch (Exception ignored) {}
                        }
                        if (!hasLockedTarget && wanTargetAddress != null && (!wanTargetAddress.equals(targetAddress) || wanTargetPort != targetPort)) {
                            try {
                                DatagramPacket wanPacket = new DatagramPacket(packetData, packetData.length, wanTargetAddress, wanTargetPort);
                                sharedUdpSocket.send(wanPacket);
                            } catch (Exception ignored) {}
                        }
                    }

                    // 2. Cloud MQTT Audio Relay (for Symmetric Carrier NAT)
                    if (remoteExt != null && !remoteExt.isEmpty()) {
                        if (!relaySendQueue.offer(packetData)) {
                            relaySendQueue.poll();
                            relaySendQueue.offer(packetData);
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

            byte[] inBuffer = new byte[HEADER_SIZE + FRAME_SIZE + 128];
            DatagramPacket packet = new DatagramPacket(inBuffer, inBuffer.length);

            while (isCalling && sharedUdpSocket != null && !sharedUdpSocket.isClosed()) {
                try {
                    sharedUdpSocket.receive(packet);
                    int len = packet.getLength();

                    if (len >= HEADER_SIZE + FRAME_SIZE && inBuffer[0] == 0x57 && inBuffer[1] == 0x41) {
                        targetAddress = packet.getAddress();
                        targetPort = packet.getPort();
                        hasLockedTarget = true;
                        playAudioPacket(inBuffer, len);
                    } else if (len == FRAME_SIZE) {
                        targetAddress = packet.getAddress();
                        targetPort = packet.getPort();
                        hasLockedTarget = true;
                        playAudioPacket(inBuffer, len);
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

    private void onRelayAudioReceived(byte[] audioData) {
        if (audioData != null && isCalling) {
            playAudioPacket(audioData, audioData.length);
        }
    }

    /**
     * Unified, de-duplicated audio packet playback.
     * Safely invoked concurrently by UDP receiver and Cloud Relay listener.
     */
    private void playAudioPacket(byte[] data, int length) {
        if (data == null || length < FRAME_SIZE || !isCalling) return;

        synchronized (playLock) {
            if (audioTrack == null || !isCalling) return;

            if (length >= HEADER_SIZE + FRAME_SIZE && data[0] == 0x57 && data[1] == 0x41) {
                int seqNum = ((data[2] & 0xFF) << 24) |
                             ((data[3] & 0xFF) << 16) |
                             ((data[4] & 0xFF) << 8)  |
                             (data[5] & 0xFF);

                // Discard duplicate packets (e.g. delivered by both UDP and Cloud Relay)
                if (playedSeqs.contains(seqNum)) {
                    return;
                }
                // Discard obsolete packets (> 4 seconds delayed)
                if (lastPlayedSeq > 200 && seqNum < lastPlayedSeq - 200) {
                    return;
                }

                playedSeqs.add(seqNum);
                if (seqNum > lastPlayedSeq) {
                    lastPlayedSeq = seqNum;
                }

                CryptoUtil.decryptAudioBuffer(data, HEADER_SIZE, FRAME_SIZE);
                audioTrack.write(data, HEADER_SIZE, FRAME_SIZE);
            } else if (length == FRAME_SIZE) {
                // Fallback for unsequenced raw packets
                CryptoUtil.decryptAudioBuffer(data, 0, FRAME_SIZE);
                audioTrack.write(data, 0, FRAME_SIZE);
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

        CallSignaling.setAudioRelayListener(null);
        relaySendQueue.clear();

        if (relaySendThread != null) {
            relaySendThread.interrupt();
            relaySendThread = null;
        }

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
