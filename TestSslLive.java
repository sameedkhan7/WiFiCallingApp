import org.eclipse.paho.client.mqttv3.*;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

public class TestSslLive {
    public static void main(String[] args) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            SSLSocketFactory factory = sslContext.getSocketFactory();

            String[] brokers = new String[]{"ssl://broker.hivemq.com:8883", "ssl://test.mosquitto.org:8883"};
            MqttClient client = new MqttClient(brokers[0], "TestSSL_" + System.currentTimeMillis(), new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
            MqttConnectOptions opt = new MqttConnectOptions();
            opt.setServerURIs(brokers);
            opt.setSocketFactory(factory);
            opt.setConnectionTimeout(5);
            opt.setKeepAliveInterval(30);
            opt.setCleanSession(true);
            System.out.println("Connecting SSL...");
            client.connect(opt);
            System.out.println("SSL CONNECTED: " + client.isConnected());
            client.disconnect();
            System.out.println("SUCCESS!");
        } catch (Throwable t) {
            System.out.println("SSL ERROR: " + t);
            t.printStackTrace(System.out);
        }
    }
}
