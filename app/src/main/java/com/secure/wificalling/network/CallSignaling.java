package com.secure.wificalling.network;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import com.secure.wificalling.security.CryptoUtil;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
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
 * Hybrid P2P Signaling Engine:
 * 1. Local Wi-Fi Direct TCP Sockets (Offline LAN, 0 ms lag).
 * 2. Internet MQTT Real-Time Signaling (Free public broker: broker.emqx.io).
 * Works across local Wi-Fi, cross-Wi-Fi, and 4G/5G mobile data seamlessly!
 */
public class CallSignaling {

    private static final String TAG = "CallSignaling";
    public static final int SIGNALING_PORT = 50620;
    public static final int DISCOVERY_PORT = 50621;
    public static final int AUDIO_PORT = 50622;

    private static final String MQTT_BROKER = "tcp://broker.hivemq.com:1883";
    private static final String MQTT_BROKER_BACKUP = "tcp://broker.emqx.io:1883";
    private static final String TOPIC_PREFIX = "wificalling/call/";
    private static final String PRESENCE_PREFIX = "wificalling/presence/";

    public interface CallListener {
        void onIncomingCall(String callerName, String callerIp, String callerExt);
        void onCallRinging(String remoteIp);
        void onCallAccepted(String remoteIp);
        void onCallRejected(String remoteIp);
        void onCallEnded(String remoteIp);
    }

    public static class PeerStatus {
        public String name;
        public String ext;
        public String localIp;
        public String networkType; // "Wi-Fi" or "Mobile Data"
        public long lastSeen;

        public PeerStatus(String name, String ext, String localIp, String networkType) {
            this.name = name;
            this.ext = ext;
            this.localIp = localIp;
            this.networkType = networkType;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private static final CopyOnWriteArrayList<CallListener> listeners = new CopyOnWriteArrayList<>();
    private static ServerSocket serverSocket;
    private static DatagramSocket discoverySocket;
    private static MqttClient mqttClient;
    private static volatile boolean isRunning = false;
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static final ConcurrentHashMap<String, String> onlineDevices = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, PeerStatus> peerStatuses = new ConcurrentHashMap<>();

    private Context context;
    private String myExtId;

    public CallSignaling(Context context, CallListener listener) {
        this.context = context;
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
        this.myExtId = detectMyExtensionId();
    }

    public void removeListener(CallListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public static String detectMyExtensionId() {
        String model = Build.MODEL.toLowerCase();
        if (model.contains("vivo") || model.contains("y51l") || model.contains("pd1510")) {
            return "101"; // Vivo Y51L
        } else if (model.contains("oneplus") || model.contains("cph2381") || model.contains("nord")) {
            return "102"; // OnePlus Nord CE 2 Lite
        } else if (model.contains("s24") || model.contains("sm-s928") || model.contains("ultra")) {
            return "103"; // Samsung S24 Ultra
        } else if (model.contains("m35") || model.contains("sm-m35")) {
            return "104"; // Samsung M35 5G
        }
        return "102"; // Default
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        threadPool.execute(this::runSignalingServer);
        threadPool.execute(this::runDiscoveryListener);
        threadPool.execute(this::initMqtt);
        threadPool.execute(this::runHeartbeatLoop);
        broadcastPresence();
    }

    private void initMqtt() {
        String[] brokers = new String[]{MQTT_BROKER, MQTT_BROKER_BACKUP};
        int brokerIdx = 0;

        while (isRunning) {
            try {
                if (mqttClient == null || !mqttClient.isConnected()) {
                    String currentBroker = brokers[brokerIdx % brokers.length];
                    String clientId = "WiFiPhone_" + myExtId + "_" + (System.currentTimeMillis() % 100000);

                    if (mqttClient != null) {
                        try { mqttClient.disconnectForcibly(); } catch (Throwable ignored) {}
                        try { mqttClient.close(); } catch (Throwable ignored) {}
                    }

                    mqttClient = new MqttClient(currentBroker, clientId, new MemoryPersistence());
                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setAutomaticReconnect(true);
                    options.setCleanSession(true);
                    options.setConnectionTimeout(8);
                    options.setKeepAliveInterval(20);

                    mqttClient.setCallback(new MqttCallback() {
                        @Override
                        public void connectionLost(Throwable cause) {
                            Log.w(TAG, "MQTT connection lost, will reconnect");
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) {
                            handleMqttMessage(topic, new String(message.getPayload()));
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                    });

                    Log.i(TAG, "Connecting to MQTT broker: " + currentBroker);
                    mqttClient.connect(options);
                    Log.i(TAG, "Connected to MQTT broker successfully!");

                    // Subscribe to personal incoming calls
                    mqttClient.subscribe(TOPIC_PREFIX + myExtId, 1);

                    // Subscribe to all presence updates
                    mqttClient.subscribe(PRESENCE_PREFIX + "#", 0);

                    publishPresence();
                    break;
                }
            } catch (Throwable e) {
                Log.w(TAG, "MQTT connect attempt failed, retrying in 4s: " + e.getMessage());
                brokerIdx++;
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void handleMqttMessage(String topic, String rawPayload) {
        try {
            if (topic.startsWith(PRESENCE_PREFIX)) {
                // Presence heartbeat
                JSONObject json = new JSONObject(rawPayload);
                String ext = json.optString("ext");
                if (ext.equals(myExtId)) return; // Ignore self

                String name = json.optString("name", "Device " + ext);
                String localIp = json.optString("localIp", "");
                String netType = json.optString("netType", "Online");

                peerStatuses.put(ext, new PeerStatus(name, ext, localIp, netType));
                if (!localIp.isEmpty()) {
                    onlineDevices.put(name, localIp);
                }
            } else if (topic.equals(TOPIC_PREFIX + myExtId)) {
                // Incoming call signaling
                String decrypted = CryptoUtil.decrypt(rawPayload);
                if (decrypted == null) return;

                String[] parts = decrypted.split("\\|");
                String command = parts[0];
                String senderName = parts.length > 1 ? parts[1] : "Unknown";
                String senderExt = parts.length > 2 ? parts[2] : "";
                String senderIp = parts.length > 3 ? parts[3] : "remote";

                Log.i(TAG, "MQTT Signaling received: [" + command + "] from " + senderName + " (" + senderExt + ")");
                dispatchSignalingCommand(command, senderName, senderIp, senderExt);
            }
        } catch (Exception e) {
            Log.e(TAG, "handleMqttMessage error", e);
        }
    }

    private void publishPresence() {
        if (mqttClient == null || !mqttClient.isConnected()) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("ext", myExtId);
            obj.put("name", Build.MODEL);
            obj.put("localIp", getLocalIpAddress());
            obj.put("netType", isWifiConnected() ? "Wi-Fi" : "Mobile Data");

            MqttMessage msg = new MqttMessage(obj.toString().getBytes());
            msg.setQos(0);
            mqttClient.publish(PRESENCE_PREFIX + myExtId, msg);
        } catch (Exception ignored) {}
    }

    private void runHeartbeatLoop() {
        while (isRunning) {
            try {
                publishPresence();
                broadcastPresence();
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            } catch (Exception ignored) {}
        }
    }

    private void runSignalingServer() {
        try {
            if (serverSocket == null || serverSocket.isClosed()) {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(SIGNALING_PORT));
            }
            Log.i(TAG, "Local TCP Signaling server bound to port " + SIGNALING_PORT);
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
            String senderExt = parts.length > 2 ? parts[2] : "";
            String senderIp = socket.getInetAddress().getHostAddress();

            Log.i(TAG, "TCP Signaling received: [" + command + "] from " + senderName + " (" + senderIp + ")");
            dispatchSignalingCommand(command, senderName, senderIp, senderExt);

            socket.close();
        } catch (Exception e) {
            Log.e(TAG, "handleClient error", e);
        }
    }

    private void dispatchSignalingCommand(String command, String senderName, String senderIp, String senderExt) {
        for (CallListener l : listeners) {
            try {
                switch (command) {
                    case "INVITE":
                        l.onIncomingCall(senderName, senderIp, senderExt);
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
    }

    public void sendCommand(String targetExt, String targetIp, String command) {
        threadPool.execute(() -> {
            String myIp = getLocalIpAddress();
            String payload = command + "|" + Build.MODEL + "|" + myExtId + "|" + myIp;
            String encrypted = CryptoUtil.encrypt(payload);

            // 1. Send via Internet MQTT (Works 100% across Wi-Fi & 4G/5G mobile data)
            if (mqttClient != null && mqttClient.isConnected() && targetExt != null && !targetExt.isEmpty()) {
                try {
                    MqttMessage msg = new MqttMessage(encrypted.getBytes());
                    msg.setQos(1);
                    mqttClient.publish(TOPIC_PREFIX + targetExt, msg);
                    Log.i(TAG, "Sent [" + command + "] via MQTT to ext " + targetExt);
                } catch (Exception e) {
                    Log.w(TAG, "MQTT send failed: " + e.getMessage());
                }
            }

            // 2. Also send via Local TCP if IP is available (Instant LAN delivery)
            if (targetIp != null && !targetIp.isEmpty() && !targetIp.equals("remote")) {
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(targetIp, SIGNALING_PORT), 1500);
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    out.println(encrypted);
                    out.flush();
                    socket.close();
                    Log.i(TAG, "Sent [" + command + "] via local TCP to " + targetIp);
                } catch (Exception ignored) {}
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
                    String ext = parts.length > 2 ? parts[2] : "";
                    String deviceIp = packet.getAddress().getHostAddress();
                    onlineDevices.put(deviceName, deviceIp);
                    if (!ext.isEmpty()) {
                        peerStatuses.put(ext, new PeerStatus(deviceName, ext, deviceIp, "Wi-Fi"));
                    }
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

                String payload = "DISCOVERY|" + Build.MODEL + "|" + myExtId;
                String encrypted = CryptoUtil.encrypt(payload);
                byte[] data = encrypted.getBytes();

                DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddress, DISCOVERY_PORT);
                socket.send(packet);
                socket.close();
            } catch (Exception ignored) {}
        });
    }

    private String getLocalIpAddress() {
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private boolean isWifiConnected() {
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                return wm != null && wm.isWifiEnabled() && wm.getConnectionInfo().getIpAddress() != 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void stop() {}
}
