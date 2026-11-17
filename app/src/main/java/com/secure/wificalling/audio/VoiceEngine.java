package com.secure.wificalling.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import com.secure.wificalling.network.CallSignaling;
import com.secure.wificalling.security.CryptoUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Full-Duplex Low-Latency Encrypted Audio Streaming Engine.
 * Supports hardware Acoustic Echo Cancellation (AEC) and Noise Suppression.
 * Compatible from Android 5.0 (API 21) up to Android 14+ (API 34).
 */
public class VoiceEngine {

    private static final int SAMPLE_RATE = 16000; // 16kHz HD Voice
    private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private Context context;
    private boolean isCalling = false;
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
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        new Thread(this::recordAndSend).start();
        new Thread(this::receiveAndPlay).start();
    }

    private void recordAndSend() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize);

        // Hardware Echo Cancellation & Noise Suppression
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            if (echoCanceler != null) echoCanceler.setEnabled(true);
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioRecord.getAudioSessionId());
            if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
        }

        try {
            audioRecord.startRecording();
            InetAddress targetAddr = InetAddress.getByName(remoteIp);
            DatagramSocket sendSocket = new DatagramSocket();

            byte[] buffer = new byte[bufferSize];
            while (isCalling) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    if (isMuted) {
                        java.util.Arrays.fill(buffer, 0, read, (byte) 0);
                    }
                    // Real-time AES Audio Encryption
                    CryptoUtil.encryptAudioBuffer(buffer, 0, read);

                    DatagramPacket packet = new DatagramPacket(buffer, read, targetAddr, CallSignaling.AUDIO_PORT);
                    sendSocket.send(packet);
                }
            }
            sendSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
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
        int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, bufferSize, AudioTrack.MODE_STREAM);

        audioTrack.play();

        try {
            udpSocket = new DatagramSocket(CallSignaling.AUDIO_PORT);
            byte[] buffer = new byte[bufferSize];

            while (isCalling) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                // Decrypt audio packet
                CryptoUtil.decryptAudioBuffer(packet.getData(), 0, packet.getLength());

                audioTrack.write(packet.getData(), 0, packet.getLength());
            }
        } catch (Exception e) {
            e.printStackTrace();
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
        am.setSpeakerphoneOn(speakerphone);
    }

    public void stopCall() {
        isCalling = false;
        if (echoCanceler != null) echoCanceler.release();
        if (noiseSuppressor != null) noiseSuppressor.release();

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);
    }
}
