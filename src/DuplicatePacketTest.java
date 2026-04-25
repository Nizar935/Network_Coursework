import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

class DuplicatePacketTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Duplicate Packet Test");
            TestSupport.startNode("N:dup", 21830, true);
            Thread.sleep(300);

            String request = "AB G";
            DatagramSocket ds = new DatagramSocket();
            ds.setSoTimeout(2000);
            DatagramPacket packet = new DatagramPacket(request.getBytes(StandardCharsets.UTF_8),
                request.length(), InetAddress.getByName("127.0.0.1"), 21830);
            ds.send(packet);
            ds.send(packet);

            byte[] buffer = new byte[1024];
            for (int i = 0; i < 2; i++) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                ds.receive(response);
                String text = new String(response.getData(), 0, response.getLength(),
                    StandardCharsets.UTF_8);
                System.out.println("Response " + (i + 1) + " => " + text);
            }
            ds.close();
        } catch (Exception e) {
            System.err.println("Duplicate packet test failed");
            e.printStackTrace(System.err);
        }
    }
}
