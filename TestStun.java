import java.net.*;
public class TestStun {
    public static void main(String[] args) throws Exception {
        DatagramSocket sock = new DatagramSocket();
        com.secure.wificalling.network.StunUtil.StunResult res = com.secure.wificalling.network.StunUtil.getPublicAddress(sock);
        sock.close();
        if (res != null) {
            System.out.println("STUN DISCOVERED: " + res.publicIp + ":" + res.publicPort);
        } else {
            System.out.println("STUN FAILED!");
        }
    }
}
