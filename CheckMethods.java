import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import java.lang.reflect.Method;
public class CheckMethods {
    public static void main(String[] args) {
        for (Method m : MqttConnectOptions.class.getMethods()) {
            if (m.getName().toLowerCase().contains("host") || m.getName().toLowerCase().contains("ssl") || m.getName().toLowerCase().contains("socket")) {
                System.out.println(m.getName());
            }
        }
    }
}
