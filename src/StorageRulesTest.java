import java.util.ArrayList;
import java.util.List;

class StorageRulesTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Storage Rules Exploration");
            Node origin = TestSupport.startNode("N:store-origin", 21870, false);
            List<Node> nodes = new ArrayList<Node>();
            for (int i = 0; i < 5; i++) {
                nodes.add(TestSupport.startNode("N:store-" + i, 21871 + i, true));
                TestSupport.bootstrapOneWay("N:store-origin", 21870, "N:store-" + i, 21871 + i);
            }
            Thread.sleep(500);

            String[] keys = { "D:alpha", "D:beta", "D:gamma" };
            for (int i = 0; i < keys.length; i++) {
                System.out.println("write(" + keys[i] + ") => " + origin.write(keys[i], "value-" + i));
                System.out.println("read(" + keys[i] + ") => " + origin.read(keys[i]));
            }

            System.out.println("Address writes:");
            for (int i = 0; i < 5; i++) {
                System.out.println("N:extra-" + i + " => " +
                    origin.write("N:extra-" + i, "127.0.0.1:" + (21910 + i)));
            }
            System.out.println("Use this test with Wireshark/logs to inspect which nodes accept or reject writes.");
        } catch (Exception e) {
            System.err.println("Storage rules test failed");
            e.printStackTrace(System.err);
        }
    }
}
