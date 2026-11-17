package com.secure.wificalling.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import com.secure.wificalling.network.CallSignaling;
import com.secure.wificalling.security.CryptoUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * High-Definition Low-Latency Full-Duplex Voice Engine.
 * Fixed 20ms Frame Processing (640 bytes @ 16kHz mono 16-bit PCM).
 * Large jitter socket buffers (128 KB) to maintain audio quality over distance.
 */
public class VoiceEngine {

    private static final String TAG = "VoiceEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAME_SIZE = 640; // Exact 20 ms frame

    private Context context;
    private volatile boolean isCalling = false;
    private String remoteIp;
    private boolean isMuted = false;
    private boolean isSpeakerphone = false;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private DatagramSocket udpSocket;

    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;

    public VoiceEngine(Context context) {
        this.context = context;
    }

    public void startCall(String remoteIp) {
        if (isCalling) return;
        this.remoteIp = remoteIp;
        this.isCalling = true;

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }

        new Thread(this::recordAndSend, "AudioRecordThread").start();
        new Thread(this::receiveAndPlay, "AudioTrackThread").start();
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

            DatagramSocket sendSocket = new DatagramSocket();
            sendSocket.setSendBufferSize(128 * 1024);

            InetAddress targetAddr = (remoteIp != null && !remoteIp.equals("remote") && !remoteIp.isEmpty())
                    ? InetAddress.getByName(remoteIp) : null;

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

                    if (targetAddr != null) {
                        DatagramPacket packet = new DatagramPacket(frame, FRAME_SIZE, targetAddr, CallSignaling.AUDIO_PORT);
                        sendSocket.send(packet);
                    }
                }
            }
            sendSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "RecordAndSend error", e);
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

            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            udpSocket.setReceiveBufferSize(128 * 1024);
            udpSocket.bind(new InetSocketAddress(CallSignaling.AUDIO_PORT));

            byte[] buffer = new byte[FRAME_SIZE * 2];

            while (isCalling) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                int len = packet.getLength();
                if (len > 0) {
                    // Update remote IP from incoming audio packets if needed
                    if (remoteIp == null || remoteIp.equals("remote")) {
                        remoteIp = packet.getAddress().getHostAddress();
                    }

                    CryptoUtil.decryptAudioBuffer(packet.getData(), 0, len);
                    audioTrack.write(packet.getData(), 0, len);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "ReceiveAndPlay error", e);
        } finally {
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                    audioTrack.release();
                } catch (Exception ignored) {}
            }
            if (udpSocket != null) {
                udpSocket.close();
            }
        }
    }

    public void setMute(boolean mute) {
        this.isMuted = mute;
    }

    public void setSpeakerphone(boolean speakerphone) {
        this.isSpeakerphone = speakerphone;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (speakerphone) {
                    List<AudioDeviceInfo> devices = am.getAvailableCommunicationDevices();
                    for (AudioDeviceInfo dev : devices) {
                        if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            am.setCommunicationDevice(dev);
                            break;
                        }
                    }
                } else {
                    am.clearCommunicationDevice();
                }
            } catch (Throwable t) {
                am.setSpeakerphoneOn(speakerphone);
            }
        } else {
            am.setSpeakerphoneOn(speakerphone);
        }
    }

    public void stopCall() {
        isCalling = false;
        if (echoCanceler != null) echoCanceler.release();
        if (noiseSuppressor != null) noiseSuppressor.release();

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    am.clearCommunicationDevice();
                } catch (Throwable ignored) {}
            }
            am.setSpeakerphoneOn(false);
            am.setMode(AudioManager.MODE_NORMAL);
        }
    }
}
