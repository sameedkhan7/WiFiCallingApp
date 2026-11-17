import paho.mqtt.client as mqtt
import time

def on_connect(client, userdata, flags, rc):
    print('CONNECTED to HiveMQ broker with code', rc)
    client.subscribe('wificall_sk_v6/#')
    client.subscribe('wificall_sk_v5/#')

def on_message(client, userdata, msg):
    print(f'MSG RECV on [{msg.topic}]: {msg.payload.decode("utf-8", errors="ignore")}')

client = mqtt.Client('sk_test_monitor_' + str(int(time.time())))
client.on_connect = on_connect
client.on_message = on_message
client.connect('broker.hivemq.com', 1883, 10)
client.loop_start()
print('Listening for 10 seconds on all topics...')
time.sleep(10)
client.loop_stop()
