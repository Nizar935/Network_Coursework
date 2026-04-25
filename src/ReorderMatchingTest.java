import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class ReorderMatchingTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Reorder / TxID Matching Test");
            TestSupport.startNode("N:reorder", 21880, true);
            Thread.sleep(300);

            DatagramSocket ds = new DatagramSocket();
            ds.setSoTimeout(2000);
            byte[] first = "AA G".getBytes(StandardCharsets.UTF_8);
            byte[] second = "BB G".getBytes(StandardCharsets.UTF_8);
            ds.send(new DatagramPacket(second, second.length, InetAddress.getByName("127.0.0.1"), 21880));
            ds.send(new DatagramPacket(first, first.length, InetAddress.getByName("127.0.0.1"), 21880));

            byte[] buffer = new byte[1024];
            for (int i = 0; i < 2; i++) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                ds.receive(response);
                byte[] copy = Arrays.copyOf(response.getData(), response.getLength());
                System.out.println("Response bytes => " + Arrays.toString(copy));
                System.out.println("Response text  => " +
                    new String(copy, StandardCharsets.UTF_8));
            }
            ds.close();
            System.out.println("Check that txids AA and BB are preserved regardless of arrival order.");
        } catch (Exception e) {
            System.err.println("Reorder test failed");
            e.printStackTrace(System.err);
        }
    }
}
