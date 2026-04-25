class NearestReadWriteTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Nearest / Read / Write Test");
            Node a = TestSupport.startNode("N:nrw-a", 21860, false);
            Node b = TestSupport.startNode("N:nrw-b", 21861, true);
            Node c = TestSupport.startNode("N:nrw-c", 21862, true);

            TestSupport.bootstrapOneWay("N:nrw-a", 21860, "N:nrw-b", 21861);
            TestSupport.bootstrapOneWay("N:nrw-a", 21860, "N:nrw-c", 21862);
            Thread.sleep(300);

            System.out.println("Unknown read => " + a.read("D:missing"));
            System.out.println("Unknown exists => " + a.exists("D:missing"));
            System.out.println("Write demo => " + a.write("D:demo", "value"));
            System.out.println("Read demo => " + a.read("D:demo"));
            System.out.println("Exists demo => " + a.exists("D:demo"));
        } catch (Exception e) {
            System.err.println("Nearest/read/write test failed");
            e.printStackTrace(System.err);
        }
    }
}
