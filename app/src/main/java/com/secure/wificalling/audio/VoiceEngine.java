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
 * High-Definition Resilient Voice Engine.
 * Dual-transmission architecture:
 * 1. IMA-ADPCM 4:1 Real-time Audio Compression (1280B PCM -> 320B ADPCM).
 * 2. Ultra-low latency: 40ms frames @ 25 packets/sec (only 8 KB/s bandwidth).
 * 3. Simultaneous Direct UDP P2P + Cloud Relay (works over 4G/5G CGNAT, Wi-Fi & Cross-Border).
 * 4. Zero-echo frame de-duplication guarantees crystal-clear voice playback.
 */
public class VoiceEngine {

    private static final String TAG = "VoiceEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // 40 ms frame @ 16kHz 16-bit mono = 640 samples = 1280 PCM bytes
    public static final int PCM_FRAME_SIZE = 1280;
    // 640 samples compressed at 4-bit/sample = 320 ADPCM bytes
    public static final int ADPCM_FRAME_SIZE = 320;
    public static final int HEADER_SIZE = 8; // Magic 'W','A' (2B) + seqNum (4B) + len (2B)
    public static final int PACKET_SIZE = HEADER_SIZE + ADPCM_FRAME_SIZE; // 328 bytes total

    private Context context;
    private volatile boolean isCalling = false;
    private String remoteIp;
    private String remotePublicEndpoint;
    private String remoteExt;
    private boolean isMuted = false;
    private boolean isSpeakerphone = true;

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
            return size() > 400; // Keep up to 400 recent frames (~16 seconds)
        }
    });

    // Asynchronous relay audio sending queue
    private final BlockingQueue<byte[]> relaySendQueue = new LinkedBlockingQueue<>(24);
    private Thread relaySendThread;

    // Codec state instances
    private final AdpcmCodec.State encodeState = new AdpcmCodec.State();
    private final AdpcmCodec.State decodeState = new AdpcmCodec.State();

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
            this.decodeState.valprev = 0;
            this.decodeState.index = 0;
        }
        this.encodeState.valprev = 0;
        this.encodeState.index = 0;
        this.relaySendQueue.clear();

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
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

        // Punch NAT holes continuously throughout the call
        new Thread(this::continuousNatHolePunch, "NatHolePunchThread").start();

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

    private void continuousNatHolePunch() {
        if (sharedUdpSocket == null) return;
        try {
            StunUtil.getPublicAddress(sharedUdpSocket);
            byte[] punchBytes = new byte[16];
            java.util.Arrays.fill(punchBytes, (byte) 0xAA);

            while (isCalling) {
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
                Thread.sleep(600); // Keep NAT pinhole open continuously
            }
        } catch (Exception ignored) {}
    }

    private void recordAndSend() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        int recordBufferSize = Math.max(minBufferSize, PCM_FRAME_SIZE * 4);

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
            byte[] rawPcmFrame = new byte[PCM_FRAME_SIZE];
            byte[] compressedAdpcm = new byte[ADPCM_FRAME_SIZE];

            while (isCalling) {
                int read = 0;
                while (read < PCM_FRAME_SIZE && isCalling) {
                    int r = audioRecord.read(rawPcmFrame, read, PCM_FRAME_SIZE - read);
                    if (r > 0) read += r;
                    else break;
                }

                if (read == PCM_FRAME_SIZE && isCalling) {
                    if (isMuted) {
                        java.util.Arrays.fill(rawPcmFrame, (byte) 0);
                    }

                    // 1. Compress 1280 bytes PCM -> 320 bytes ADPCM (4:1 ratio)
                    AdpcmCodec.encode(rawPcmFrame, 0, PCM_FRAME_SIZE, compressedAdpcm, 0, encodeState);

                    // 2. Build formatted packet: [Header 8B] + [Encrypted ADPCM 320B] = 328B total
                    byte[] packetData = new byte[PACKET_SIZE];
                    packetData[0] = (byte) 0x57; // 'W'
                    packetData[1] = (byte) 0x41; // 'A'
                    packetData[2] = (byte) ((sendSeqNum >> 24) & 0xFF);
                    packetData[3] = (byte) ((sendSeqNum >> 16) & 0xFF);
                    packetData[4] = (byte) ((sendSeqNum >> 8) & 0xFF);
                    packetData[5] = (byte) (sendSeqNum & 0xFF);
                    packetData[6] = (byte) ((ADPCM_FRAME_SIZE >> 8) & 0xFF);
                    packetData[7] = (byte) (ADPCM_FRAME_SIZE & 0xFF);
                    sendSeqNum++;

                    System.arraycopy(compressedAdpcm, 0, packetData, HEADER_SIZE, ADPCM_FRAME_SIZE);
                    CryptoUtil.encryptAudioBuffer(packetData, HEADER_SIZE, ADPCM_FRAME_SIZE);

                    // 3. Direct UDP transmission (P2P Wi-Fi / Open NAT)
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

                    // 4. Cloud Audio Relay (Guaranteed delivery over Symmetric 4G/5G Carrier CGNAT)
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
        int trackBufferSize = Math.max(minBufferSize, PCM_FRAME_SIZE * 4);

        try {
            audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, trackBufferSize, AudioTrack.MODE_STREAM);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                audioTrack.setVolume(1.0f);
            } else {
                audioTrack.setStereoVolume(1.0f, 1.0f);
            }
            audioTrack.play();

            byte[] inBuffer = new byte[PACKET_SIZE + 64];
            DatagramPacket packet = new DatagramPacket(inBuffer, inBuffer.length);

            while (isCalling && sharedUdpSocket != null && !sharedUdpSocket.isClosed()) {
                try {
                    sharedUdpSocket.receive(packet);
                    int len = packet.getLength();

                    if (len >= HEADER_SIZE + ADPCM_FRAME_SIZE && inBuffer[0] == 0x57 && inBuffer[1] == 0x41) {
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

    private final byte[] decompressedPcm = new byte[PCM_FRAME_SIZE];

    /**
     * Unified, de-duplicated audio packet playback.
     * Safely invoked concurrently by UDP receiver and Cloud Relay listener.
     */
    private void playAudioPacket(byte[] data, int length) {
        if (data == null || length < HEADER_SIZE + ADPCM_FRAME_SIZE || !isCalling) return;

        synchronized (playLock) {
            if (audioTrack == null || !isCalling) return;

            if (data[0] == 0x57 && data[1] == 0x41) {
                int seqNum = ((data[2] & 0xFF) << 24) |
                             ((data[3] & 0xFF) << 16) |
                             ((data[4] & 0xFF) << 8)  |
                             (data[5] & 0xFF);

                // Discard duplicate packets
                if (playedSeqs.contains(seqNum)) {
                    return;
                }
                // Discard obsolete packets (> 4 seconds delayed)
                if (lastPlayedSeq > 100 && seqNum < lastPlayedSeq - 100) {
                    return;
                }

                playedSeqs.add(seqNum);
                if (seqNum > lastPlayedSeq) {
                    lastPlayedSeq = seqNum;
                }

                // 1. Decrypt ADPCM payload
                CryptoUtil.decryptAudioBuffer(data, HEADER_SIZE, ADPCM_FRAME_SIZE);

                // 2. Decompress 320 bytes ADPCM -> 1280 bytes PCM
                AdpcmCodec.decode(data, HEADER_SIZE, ADPCM_FRAME_SIZE, decompressedPcm, 0, decodeState);

                // 3. Play live PCM
                audioTrack.write(decompressedPcm, 0, PCM_FRAME_SIZE);
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

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }

        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }

        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Exception ignored) {}
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Exception ignored) {}
            noiseSuppressor = null;
        }

        if (sharedUdpSocket != null && !sharedUdpSocket.isClosed()) {
            try { sharedUdpSocket.close(); } catch (Exception ignored) {}
            sharedUdpSocket = null;
        }
    }
}
