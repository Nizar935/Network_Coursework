class TimeoutTest {
    public static void main(String[] args) {
        try {
            TestSupport.printHeader("Timeout / Retry Test");
            Node a = TestSupport.startNode("N:timeout-a", 21850, false);
            TestSupport.bootstrapOneWay("N:timeout-a", 21850, "N:missing", 21999);
            Thread.sleep(300);

            long start = System.currentTimeMillis();
            boolean active = a.isActive("N:missing");
            long elapsed = System.currentTimeMillis() - start;

            System.out.println("isActive returned => " + active);
            System.out.println("Elapsed ms => " + elapsed);
            System.out.println("Expected roughly >= 20000ms if retries fire 4 sends total");
        } catch (Exception e) {
            System.err.println("Timeout test failed");
            e.printStackTrace(System.err);
        }
    }
}
