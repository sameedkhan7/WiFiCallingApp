import org.eclipse.paho.client.mqttv3.*;
public class TestHiveMqKeepalive {
    public static void main(String[] args) throws Exception {
        MqttClient client = new MqttClient("tcp://broker.hivemq.com:1883", "TestKeepalive_" + System.currentTimeMillis());
        client.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {
                System.out.println("CONNECTION LOST: " + cause);
            }
            public void messageArrived(String topic, MqttMessage message) {}
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });
        MqttConnectOptions opt = new MqttConnectOptions();
        opt.setKeepAliveInterval(30);
        opt.setCleanSession(true);
        client.connect(opt);
        System.out.println("Connected to HiveMQ! Waiting 15s...");
        Thread.sleep(15000);
        System.out.println("Still connected? " + client.isConnected());
        client.disconnect();
        System.out.println("Done.");
    }
}
