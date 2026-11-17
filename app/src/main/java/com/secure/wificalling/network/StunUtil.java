package com.secure.wificalling.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;

/**
 * Lightweight RFC 5389 STUN NAT Traversal Client.
 * Queries Google STUN servers to discover public WAN IP and port
 * for cross-network (Wi-Fi <-> 4G/5G) P2P voice calling.
 */
public class StunUtil {

    public static class StunResult {
        public String publicIp;
        public int publicPort;

        public StunResult(String ip, int port) {
            this.publicIp = ip;
            this.publicPort = port;
        }
    }

    public static StunResult getPublicAddress(DatagramSocket socket) {
        try {
            byte[] req = new byte[20];
            // STUN Header: Binding Request (0x0001), Length (0x0000), Magic Cookie (0x2112A442)
            req[0] = 0x00; req[1] = 0x01;
            req[2] = 0x00; req[3] = 0x00;
            req[4] = 0x21; req[5] = 0x12; req[6] = (byte) 0xA4; req[7] = 0x42;
            new SecureRandom().nextBytes(req);
            // Re-apply header after random bytes fill transaction ID (bytes 8-19)
            req[0] = 0x00; req[1] = 0x01;
            req[2] = 0x00; req[3] = 0x00;
            req[4] = 0x21; req[5] = 0x12; req[6] = (byte) 0xA4; req[7] = 0x42;

            InetAddress stunServer = InetAddress.getByName("stun.l.google.com");
            DatagramPacket outPacket = new DatagramPacket(req, req.length, stunServer, 19302);
            socket.send(outPacket);

            byte[] buf = new byte[512];
            DatagramPacket inPacket = new DatagramPacket(buf, buf.length);
            int prevTimeout = socket.getSoTimeout();
            socket.setSoTimeout(2500);
            try {
                socket.receive(inPacket);
            } finally {
                socket.setSoTimeout(prevTimeout);
            }

            int idx = 20;
            int len = inPacket.getLength();
            while (idx + 4 <= len) {
                int attrType = ((buf[idx] & 0xFF) << 8) | (buf[idx + 1] & 0xFF);
                int attrLen = ((buf[idx + 2] & 0xFF) << 8) | (buf[idx + 3] & 0xFF);
                if (attrType == 0x0020 && idx + 4 + attrLen <= len) { // XOR-MAPPED-ADDRESS
                    int port = (((buf[idx + 6] & 0xFF) << 8) | (buf[idx + 7] & 0xFF)) ^ 0x2112;
                    int b0 = (buf[idx + 8] & 0xFF) ^ 0x21;
                    int b1 = (buf[idx + 9] & 0xFF) ^ 0x12;
                    int b2 = (buf[idx + 10] & 0xFF) ^ 0xA4;
                    int b3 = (buf[idx + 11] & 0xFF) ^ 0x42;
                    String ip = b0 + "." + b1 + "." + b2 + "." + b3;
                    return new StunResult(ip, port);
                }
                idx += 4 + attrLen;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
