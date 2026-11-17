package com.secure.wificalling.network;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import com.secure.wificalling.security.CryptoUtil;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hybrid P2P Signaling Engine:
 * 1. Local Wi-Fi Direct TCP Sockets (Offline LAN, 0 ms lag).
 * 2. Internet MQTT Real-Time Signaling (HiveMQ broker).
 * Automatic STUN WAN public endpoint discovery & seamless reconnect.
 */
public class CallSignaling {

    private static final String TAG = "CallSignaling";
    public static final int SIGNALING_PORT = 50620;
    public static final int DISCOVERY_PORT = 50621;
    public static final int AUDIO_PORT = 50622;

    private static final String MQTT_BROKER = "tcp://broker.hivemq.com:1883";
    private static final String TOPIC_PREFIX = "wificall_sk_v5/call/";
    private static final String PRESENCE_PREFIX = "wificall_sk_v5/presence/";

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
        public String publicEndpoint;
        public String networkType; // "Wi-Fi" or "Mobile Data"
        public long lastSeen;

        public PeerStatus(String name, String ext, String localIp, String publicEndpoint, String networkType) {
            this.name = name;
            this.ext = ext;
            this.localIp = localIp;
            this.publicEndpoint = publicEndpoint;
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
    public static final ConcurrentHashMap<String, String> peerPublicEndpoints = new ConcurrentHashMap<>();
    public static volatile String myPublicEndpoint = "";

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
        String info = (Build.MODEL + " " + Build.BRAND + " " + Build.MANUFACTURER + " " + Build.DEVICE + " " + Build.PRODUCT).toLowerCase();
        if (info.contains("vivo") || info.contains("y51l") || info.contains("pd1510")) {
            return "101"; // Vivo Y51L (Home Phone)
        } else if (info.contains("cph2381") || info.contains("oneplus") || info.contains("nord") || info.contains("oppo")) {
            return "102"; // OnePlus Nord CE 2 Lite (CPH2381)
        } else if (info.contains("s24") || info.contains("sm-s928") || info.contains("ultra") || info.contains("e3q")) {
            return "103"; // Samsung Galaxy S24 Ultra
        } else if (info.contains("m35") || info.contains("sm-m35") || info.contains("m356")) {
            return "104"; // Samsung Galaxy M35 5G
        }
        return "102"; // Default
    }

    public static String getPeerPublicEndpoint(String ext) {
        if (ext == null) return "";
        String ep = peerPublicEndpoints.get(ext);
        return ep != null ? ep : "";
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        threadPool.execute(this::runSignalingServer);
        threadPool.execute(this::runDiscoveryListener);
        threadPool.execute(this::runMqttLoop);
        threadPool.execute(this::runHeartbeatLoop);
        threadPool.execute(this::refreshPublicEndpoint);
        broadcastPresence();
    }

    private void refreshPublicEndpoint() {
        while (isRunning) {
            try {
                DatagramSocket sock = new DatagramSocket();
                StunUtil.StunResult res = StunUtil.getPublicAddress(sock);
                sock.close();
                if (res != null) {
                    myPublicEndpoint = res.publicIp + ":" + res.publicPort;
                    Log.i(TAG, "Discovered WAN Public Endpoint: " + myPublicEndpoint);
                }
            } catch (Exception ignored) {}

            try {
                Thread.sleep(120000);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private void runMqttLoop() {
        while (isRunning) {
            try {
                if (mqttClient == null || !mqttClient.isConnected()) {
                    String clientId = "WiFiPhone_" + myExtId + "_" + (System.currentTimeMillis() % 100000);

                    if (mqttClient != null) {
                        try { mqttClient.disconnectForcibly(); } catch (Throwable ignored) {}
                        try { mqttClient.close(); } catch (Throwable ignored) {}
                    }

                    mqttClient = new MqttClient(MQTT_BROKER, clientId, new MemoryPersistence());
                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setAutomaticReconnect(true);
                    options.setCleanSession(true);
                    options.setConnectionTimeout(15);
                    options.setKeepAliveInterval(60);

                    mqttClient.setCallback(new MqttCallbackExtended() {
                        @Override
                        public void connectComplete(boolean reconnect, String serverURI) {
                            Log.i(TAG, "MQTT connectComplete (reconnect=" + reconnect + ") to " + serverURI);
                            try {
                                mqttClient.subscribe(TOPIC_PREFIX + myExtId, 1);
                                mqttClient.subscribe(PRESENCE_PREFIX + "#", 0);
                                publishPresence();
                            } catch (Exception e) {
                                Log.e(TAG, "Subscription on connect error", e);
                            }
                        }

                        @Override
                        public void connectionLost(Throwable cause) {
                            Log.w(TAG, "MQTT connection lost: " + (cause != null ? cause.getMessage() : "unknown"));
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) {
                            handleMqttMessage(topic, new String(message.getPayload()));
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                    });

                    Log.i(TAG, "Connecting to MQTT broker: " + MQTT_BROKER);
                    mqttClient.connect(options);
                    Log.i(TAG, "Connected to MQTT broker successfully!");
                }
            } catch (Throwable e) {
                Log.w(TAG, "MQTT connect failed: " + e.getMessage() + ", will retry in 3s");
            }

            try {
                Thread.sleep(4000);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private synchronized void ensureMqttConnected() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            try {
                String clientId = "WiFiPhone_" + myExtId + "_" + (System.currentTimeMillis() % 100000);
                mqttClient = new MqttClient(MQTT_BROKER, clientId, new MemoryPersistence());
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setConnectionTimeout(10);
                options.setKeepAliveInterval(60);
                mqttClient.connect(options);
                mqttClient.subscribe(TOPIC_PREFIX + myExtId, 1);
                mqttClient.subscribe(PRESENCE_PREFIX + "#", 0);
            } catch (Throwable ignored) {}
        }
    }

    private void handleMqttMessage(String topic, String rawPayload) {
        try {
            if (topic.startsWith(PRESENCE_PREFIX)) {
                JSONObject json = new JSONObject(rawPayload);
                String ext = json.optString("ext");
                if (ext.equals(myExtId)) return;

                String name = json.optString("name", "Device " + ext);
                String localIp = json.optString("localIp", "");
                String publicEp = json.optString("publicEp", "");
                String netType = json.optString("netType", "Online");

                peerStatuses.put(ext, new PeerStatus(name, ext, localIp, publicEp, netType));
                if (!publicEp.isEmpty()) {
                    peerPublicEndpoints.put(ext, publicEp);
                }
                if (!localIp.isEmpty()) {
                    onlineDevices.put(name, localIp);
                }
            } else if (topic.equals(TOPIC_PREFIX + myExtId)) {
                String decrypted = CryptoUtil.decrypt(rawPayload);
                if (decrypted == null) return;

                String[] parts = decrypted.split("\\|");
                String command = parts[0];
                String senderName = parts.length > 1 ? parts[1] : "Unknown";
                String senderExt = parts.length > 2 ? parts[2] : "";
                String senderIp = parts.length > 3 ? parts[3] : "remote";
                String senderPublicEp = parts.length > 4 ? parts[4] : "";

                if (!senderExt.isEmpty() && !senderPublicEp.isEmpty()) {
                    peerPublicEndpoints.put(senderExt, senderPublicEp);
                }

                Log.i(TAG, "MQTT Signaling received: [" + command + "] from " + senderName + " (Ext: " + senderExt + ")");
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
            obj.put("publicEp", myPublicEndpoint);
            obj.put("netType", isWifiConnected() ? "Wi-Fi" : "Mobile Data");

            MqttMessage msg = new MqttMessage(obj.toString().getBytes(StandardCharsets.UTF_8));
            msg.setQos(0);
            mqttClient.publish(PRESENCE_PREFIX + myExtId, msg);
        } catch (Exception ignored) {}
    }

    private void runHeartbeatLoop() {
        while (isRunning) {
            try {
                publishPresence();
                broadcastPresence();
                Thread.sleep(25000); // 25s interval to save battery and reduce radio wakeups
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
            String senderPublicEp = parts.length > 4 ? parts[4] : "";

            if (!senderExt.isEmpty() && !senderPublicEp.isEmpty()) {
                peerPublicEndpoints.put(senderExt, senderPublicEp);
            }

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
            String payload = command + "|" + Build.MODEL + "|" + myExtId + "|" + myIp + "|" + myPublicEndpoint;
            String encrypted = CryptoUtil.encrypt(payload);

            // 1. Send via Internet MQTT (Guaranteed delivery across Wi-Fi & 4G/5G mobile data)
            if (targetExt != null && !targetExt.isEmpty()) {
                ensureMqttConnected();
                if (mqttClient != null && mqttClient.isConnected()) {
                    try {
                        MqttMessage msg = new MqttMessage(encrypted.getBytes(StandardCharsets.UTF_8));
                        msg.setQos(1);
                        mqttClient.publish(TOPIC_PREFIX + targetExt, msg);
                        Log.i(TAG, "Sent [" + command + "] via MQTT to ext " + targetExt);
                    } catch (Exception e) {
                        Log.w(TAG, "MQTT send failed: " + e.getMessage());
                    }
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
                        peerStatuses.put(ext, new PeerStatus(deviceName, ext, deviceIp, "", "Wi-Fi"));
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
}
