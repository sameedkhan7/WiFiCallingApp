import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class LongMonitor {
    public static void main(String[] args) {
        try {
            MqttClient client = new MqttClient("tcp://broker.hivemq.com:1883", "monitor_long_" + System.currentTimeMillis(), new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) {}
                public void messageArrived(String topic, MqttMessage message) {
                    System.out.println("RECV on [" + topic + "] retained=" + message.isRetained() + ": " + new String(message.getPayload()));
                }
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            client.connect(options);
            System.out.println("CONNECTED! Subscribing to #...");
            client.subscribe("wificall_sk_v6/#", 1);
            client.subscribe("wificall_sk_v5/#", 1);

            Thread.sleep(25000);
            client.disconnect();
            client.close();
            System.out.println("DONE.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
