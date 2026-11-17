import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

public class TestPahoSSL {
    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient("ssl://broker.hivemq.com:8883", "test_sk_ssl_" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setServerURIs(new String[]{"ssl://broker.hivemq.com:8883", "ssl://broker.emqx.io:8883"});
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(6);

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());
            options.setSocketFactory(sc.getSocketFactory());

            client.connect(options);
            System.out.println("TEST SSL CONNECTED SUCCESSFUL! IsConnected: " + client.isConnected());
            client.disconnect();
            client.close();
        } catch (Throwable t) {
            System.out.println("TEST SSL FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }
}
