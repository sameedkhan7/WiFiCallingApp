import socket
import hashlib
import os
import base64
import time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SHARED_SECRET = "SecureWiFiCall@S24U_VivoY51L_OPLite_2026"
key = hashlib.sha256(SHARED_SECRET.encode('utf-8')).digest()
aesgcm = AESGCM(key)

def encrypt(msg: str) -> str:
    iv = os.urandom(12)
    ciphertext = aesgcm.encrypt(iv, msg.encode('utf-8'), None)
    combined = iv + ciphertext
    return base64.b64encode(combined).decode('utf-8')

def send_tcp(ip: str, port: int, payload: str):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5.0)
    s.connect((ip, port))
    enc = encrypt(payload) + "\n"
    s.sendall(enc.encode('utf-8'))
    s.close()
    print(f"Sent: {payload}")

if __name__ == "__main__":
    VIVO_IP = "192.168.2.118"
    PORT = 50620

    print("=== TEST 1: Sending INVITE to Vivo (Ext 101) ===")
    send_tcp(VIVO_IP, PORT, "INVITE|OnePlus Nord CE 2 Lite|102|192.168.2.156")

    print("Sleeping 4 seconds while phone rings...")
    time.sleep(4)

    print("=== TEST 2: Sending HANGUP to Vivo ===")
    send_tcp(VIVO_IP, PORT, "HANGUP|OnePlus Nord CE 2 Lite|102|192.168.2.156")

    print("Test finished successfully!")
