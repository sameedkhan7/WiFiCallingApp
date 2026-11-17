import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class TestPaho {
    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient("tcp://broker.hivemq.com:1883", "test_sk_client_" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setServerURIs(new String[]{"tcp://broker.hivemq.com:1883", "tcp://broker.emqx.io:1883"});
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(6);
            client.connect(options);
            System.out.println("TEST CONNECTED SUCCESSFUL! IsConnected: " + client.isConnected());
            client.disconnect();
            client.close();
        } catch (Throwable t) {
            System.out.println("TEST FAILED: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }
}
