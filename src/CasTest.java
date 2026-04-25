class CasTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("CAS Test");
            Node a = TestSupport.startNode("N:cas-a", 21810, false);
            Node b = TestSupport.startNode("N:cas-b", 21811, true);
            TestSupport.bootstrapOneWay("N:cas-a", 21810, "N:cas-b", 21811);
            Thread.sleep(300);

            String key = "D:cas-demo";
            System.out.println("Initial read => " + a.read(key));
            System.out.println("CAS absent => " + a.CAS(key, "old", "first"));
            System.out.println("After absent CAS => " + a.read(key));
            System.out.println("CAS wrong expected => " + a.CAS(key, "wrong", "second"));
            System.out.println("After failed CAS => " + a.read(key));
            System.out.println("CAS correct expected => " + a.CAS(key, "first", "second"));
            System.out.println("After successful CAS => " + a.read(key));
        } catch (Exception e) {
            System.err.println("CAS test failed");
            e.printStackTrace(System.err);
        }
    }
}
