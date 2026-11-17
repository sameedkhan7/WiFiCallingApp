package com.secure.wificalling.network;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import com.secure.wificalling.security.CryptoUtil;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P2P Local Wi-Fi Signaling Engine.
 * Manages INVITE, RINGING, ACCEPT, REJECT, and HANGUP state synchronization.
 * 100% Offline, Peer-to-Peer, and AES-256 Encrypted.
 */
public class CallSignaling {

    private static final String TAG = "CallSignaling";
    public static final int SIGNALING_PORT = 50620;
    public static final int DISCOVERY_PORT = 50621;
    public static final int AUDIO_PORT = 50622;

    public interface CallListener {
        void onIncomingCall(String callerName, String callerIp);
        void onCallRinging(String remoteIp);
        void onCallAccepted(String remoteIp);
        void onCallRejected(String remoteIp);
        void onCallEnded(String remoteIp);
    }

    private static final CopyOnWriteArrayList<CallListener> listeners = new CopyOnWriteArrayList<>();
    private static ServerSocket serverSocket;
    private static DatagramSocket discoverySocket;
    private static volatile boolean isRunning = false;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static final ConcurrentHashMap<String, String> onlineDevices = new ConcurrentHashMap<>();

    private Context context;

    public CallSignaling(Context context, CallListener listener) {
        this.context = context;
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(CallListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        threadPool.execute(this::runSignalingServer);
        threadPool.execute(this::runDiscoveryListener);
        broadcastPresence();
    }

    private void runSignalingServer() {
        try {
            if (serverSocket == null || serverSocket.isClosed()) {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(SIGNALING_PORT));
            }
            Log.i(TAG, "Signaling server bound to port " + SIGNALING_PORT);
            while (isRunning) {
                Socket client = serverSocket.accept();
                threadPool.execute(() -> handleClient(client));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in signaling server: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String rawLine = in.readLine();
            if (rawLine == null) {
                socket.close();
                return;
            }

            String decrypted = CryptoUtil.decrypt(rawLine);
            if (decrypted == null) {
                socket.close();
                return;
            }

            String[] parts = decrypted.split("\\|");
            String command = parts[0];
            String senderName = parts.length > 1 ? parts[1] : "Unknown";
            String senderIp = socket.getInetAddress().getHostAddress();

            Log.i(TAG, "Received signaling command [" + command + "] from " + senderName + " (" + senderIp + ")");

            for (CallListener l : listeners) {
                try {
                    switch (command) {
                        case "INVITE":
                            l.onIncomingCall(senderName, senderIp);
                            break;
                        case "RINGING":
                            l.onCallRinging(senderIp);
                            break;
                        case "ACCEPT":
                            l.onCallAccepted(senderIp);
                            break;
                        case "REJECT":
                            l.onCallRejected(senderIp);
                            break;
                        case "HANGUP":
                            l.onCallEnded(senderIp);
                            break;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Listener error", t);
                }
            }

            socket.close();
        } catch (Exception e) {
            Log.e(TAG, "handleClient error", e);
        }
    }

    public void sendCommand(String targetIp, String command, String myDeviceName) {
        threadPool.execute(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(targetIp, SIGNALING_PORT), 3000);
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                String payload = command + "|" + myDeviceName;
                String encrypted = CryptoUtil.encrypt(payload);

                out.println(encrypted);
                out.flush();
                socket.close();
                Log.i(TAG, "Sent [" + command + "] to " + targetIp);
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command [" + command + "] to " + targetIp + ": " + e.getMessage());
            }
        });
    }

    private void runDiscoveryListener() {
        try {
            if (discoverySocket == null || discoverySocket.isClosed()) {
                discoverySocket = new DatagramSocket(null);
                discoverySocket.setReuseAddress(true);
                discoverySocket.bind(new InetSocketAddress(DISCOVERY_PORT));
            }
            byte[] buf = new byte[1024];
            while (isRunning) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                discoverySocket.receive(packet);

                String data = new String(packet.getData(), 0, packet.getLength());
                String decrypted = CryptoUtil.decrypt(data);
                if (decrypted != null && decrypted.startsWith("DISCOVERY|")) {
                    String[] parts = decrypted.split("\\|");
                    String deviceName = parts[1];
                    String deviceIp = packet.getAddress().getHostAddress();
                    onlineDevices.put(deviceName, deviceIp);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Discovery listener error: " + e.getMessage());
        }
    }

    public void broadcastPresence() {
        threadPool.execute(() -> {
            try {
                if (context == null) return;
                WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) return;
                DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp == null || dhcp.ipAddress == 0) return;

                int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                byte[] quads = new byte[4];
                for (int k = 0; k < 4; k++) quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
                InetAddress broadcastAddress = InetAddress.getByAddress(quads);

                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);

                String payload = "DISCOVERY|" + android.os.Build.MODEL;
                String encrypted = CryptoUtil.encrypt(payload);
                byte[] data = encrypted.getBytes();

                DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, DISCOVERY_PORT);
                socket.send(packet);
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Broadcast error: " + e.getMessage());
            }
        });
    }

    public void stop() {
        // Keeps socket active across activities
    }
}
