import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class MalformedPacketTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Malformed Packet Test");
            Node node = TestSupport.startNode("N:malformed", 21820, true);
            Thread.sleep(300);

            byte[][] badPackets = new byte[][] {
                "X".getBytes(StandardCharsets.UTF_8),
                "A G ".getBytes(StandardCharsets.UTF_8),
                "AB Z ".getBytes(StandardCharsets.UTF_8),
                "AB N short".getBytes(StandardCharsets.UTF_8),
                "AB W 0 D:key ".getBytes(StandardCharsets.UTF_8),
                "AB R broken".getBytes(StandardCharsets.UTF_8)
            };

            for (int i = 0; i < badPackets.length; i++) {
                sendAndPrint(21820, badPackets[i], "packet-" + i);
            }

            System.out.println("Node still responsive => " + node.isActive("N:malformed"));
        } catch (Exception e) {
            System.err.println("Malformed packet test failed");
            e.printStackTrace(System.err);
        }
    }

    private static void sendAndPrint(int port, byte[] payload, String label) throws Exception {
        DatagramSocket ds = new DatagramSocket();
        ds.setSoTimeout(1000);
        DatagramPacket packet = new DatagramPacket(payload, payload.length,
            InetAddress.getByName("127.0.0.1"), port);
        ds.send(packet);
        byte[] buffer = new byte[1024];
        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
        try {
            ds.receive(response);
            System.out.println(label + " => response " +
                Arrays.toString(Arrays.copyOf(response.getData(), response.getLength())));
        } catch (SocketTimeoutException e) {
            System.out.println(label + " => no response");
        }
        ds.close();
    }
}
