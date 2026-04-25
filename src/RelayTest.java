class RelayTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Relay Test");
            Node a = TestSupport.startNode("N:relay-a", 21840, false);
            Node b = TestSupport.startNode("N:relay-b", 21841, true);
            Node c = TestSupport.startNode("N:relay-c", 21842, true);

            TestSupport.bootstrapOneWay("N:relay-a", 21840, "N:relay-b", 21841);
            TestSupport.bootstrapOneWay("N:relay-a", 21840, "N:relay-c", 21842);
            TestSupport.bootstrapOneWay("N:relay-b", 21841, "N:relay-c", 21842);
            Thread.sleep(300);

            c.write("D:relay-demo", "through-relay");
            a.pushRelay("N:relay-b");
            System.out.println("Read through relay => " + a.read("D:relay-demo"));
            a.popRelay();
            System.out.println("Read without relay => " + a.read("D:relay-demo"));
        } catch (Exception e) {
            System.err.println("Relay test failed");
            e.printStackTrace(System.err);
        }
    }
}
