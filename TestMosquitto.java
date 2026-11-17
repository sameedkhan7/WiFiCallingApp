import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class TestMosquitto {
    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient("tcp://test.mosquitto.org:1883", "test_mosq_" + System.currentTimeMillis(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            client.connect(options);
            System.out.println("MOSQUITTO TCP CONNECTED: " + client.isConnected());
            client.disconnect();
            client.close();
        } catch (Throwable t) {
            System.out.println("MOSQUITTO FAILED: " + t.getMessage());
        }
    }
}
