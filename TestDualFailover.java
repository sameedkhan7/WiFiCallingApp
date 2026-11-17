import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

public class TestDualFailover {
    public static void main(String[] args) {
        String[] TCP_BROKERS = {"tcp://broker.hivemq.com:1883", "tcp://broker.emqx.io:1883"};
        String[] SSL_BROKERS = {"ssl://broker.hivemq.com:8883", "ssl://broker.emqx.io:8883"};

        boolean connected = false;
        MqttClient client = null;

        // Try TCP first
        try {
            client = new MqttClient(TCP_BROKERS[0], "test_tcp_" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setServerURIs(TCP_BROKERS);
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(4);
            client.connect(options);
            System.out.println("TCP CONNECT SUCCESSFUL: " + client.isConnected());
            connected = true;
            client.disconnect();
            client.close();
        } catch (Throwable t) {
            System.out.println("TCP FAILED: " + t.getMessage());
        }

        // Try SSL if TCP failed or for SSL verification
        try {
            client = new MqttClient(SSL_BROKERS[0], "test_ssl_" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions sslOptions = new MqttConnectOptions();
            sslOptions.setServerURIs(SSL_BROKERS);
            sslOptions.setAutomaticReconnect(true);
            sslOptions.setCleanSession(true);
            sslOptions.setConnectionTimeout(4);

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());
            sslOptions.setSocketFactory(sc.getSocketFactory());

            client.connect(sslOptions);
            System.out.println("SSL CONNECT SUCCESSFUL: " + client.isConnected());
            client.disconnect();
            client.close();
        } catch (Throwable t) {
            System.out.println("SSL FAILED: " + t.getMessage());
        }
    }
}
