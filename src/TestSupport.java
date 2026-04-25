import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

class TestSupport {
    public static Node startNode(String nodeName, int port, boolean background) throws Exception {
        Node node = new Node();
        node.setNodeName(nodeName);
        node.openPort(port);
        if (background) {
            Thread t = new Thread(() -> {
                try {
                    node.handleIncomingMessages(0);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            });
            t.setDaemon(true);
            t.start();
        }
        return node;
    }

    public static void bootstrapOneWay(String sourceNodeName, int sourcePort, String targetNodeName,
        int targetPort) throws Exception {
        String message = "00 W " + encodeString(targetNodeName) + encodeString("127.0.0.1:" + targetPort);
        sendRaw(message.getBytes(StandardCharsets.UTF_8), sourcePort);
    }

    public static void sendRaw(byte[] bytes, int destinationPort) throws Exception {
        DatagramSocket ds = new DatagramSocket();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length,
            InetAddress.getByName("127.0.0.1"), destinationPort);
        ds.send(packet);
        ds.close();
    }

    public static String encodeString(String value) {
        int spaces = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ' ') {
                spaces++;
            }
        }
        return Integer.toString(spaces) + " " + value + " ";
    }

    public static void printHeader(String title) {
        System.out.println("==============================================");
        System.out.println(title);
        System.out.println("==============================================");
    }
}
