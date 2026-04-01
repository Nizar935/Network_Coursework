class AzureLabTest {
    private static final int DEFAULT_PORT = 20110;
    private static final int BOOTSTRAP_WAIT_MS = 12000;

    public static void main(String[] args) {
        try {
            Config config = parseArgs(args);
            printBanner(config);

            Node node = new Node();
            String nodeName = "N:" + config.emailAddress;
            node.setNodeName(nodeName);
            node.openPort(config.port);

            System.out.println("[OK] Node started");
            System.out.println("node name : " + nodeName);
            System.out.println("bind port : " + config.port);
            System.out.println();

            System.out.println("[STEP 1] Waiting for initial contact...");
            node.handleIncomingMessages(BOOTSTRAP_WAIT_MS);

            System.out.println("[STEP 2] Reading known poem entries...");
            int versesFound = 0;
            for (int i = 0; i < 7; i++) {
                String key = "D:jabberwocky" + i;
                String value = node.read(key);
                if (value == null) {
                    System.out.println("[WARN] Could not read key: " + key);
                } else {
                    versesFound++;
                    System.out.println("[OK] " + key + " -> " + value);
                }
            }
            System.out.println("[INFO] Poem entries found: " + versesFound + " / 7");
            System.out.println();

            System.out.println("[STEP 3] Writing a marker value...");
            String markerKey = "D:" + config.emailAddress;
            String markerValue = "It works!";
            boolean writeSuccess = node.write(markerKey, markerValue);
            System.out.println("[INFO] write(" + markerKey + ") returned: " + writeSuccess);
            System.out.println("[INFO] read-back: " + node.read(markerKey));
            System.out.println();

            System.out.println("[STEP 4] Advertising this node address...");
            String addressValue = config.ipAddress + ":" + config.port;
            boolean advertiseSuccess = node.write(nodeName, addressValue);
            System.out.println("[INFO] write(" + nodeName + ", " + addressValue + ") returned: "
                + advertiseSuccess);
            System.out.println();

            System.out.println("[STEP 5] Handling incoming messages...");
            node.handleIncomingMessages(0);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Exception during AzureLabTest");
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static Config parseArgs(String[] args) {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Expected 2 or 3 arguments.");
        }
        String emailAddress = args[0].trim();
        String ipAddress = args[1].trim();
        int port = DEFAULT_PORT;
        if (!emailAddress.contains("@")) {
            throw new IllegalArgumentException("First argument must be your email address.");
        }
        if (!looksLikeAzureIp(ipAddress)) {
            throw new IllegalArgumentException("Second argument must look like 10.x.x.x.");
        }
        if (args.length == 3) {
            port = Integer.parseInt(args[2].trim());
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1024 and 65535.");
            }
        }
        return new Config(emailAddress, ipAddress, port);
    }

    private static boolean looksLikeAzureIp(String ipAddress) {
        if (!ipAddress.startsWith("10.")) {
            return false;
        }
        String[] parts = ipAddress.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (int i = 0; i < parts.length; i++) {
            try {
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static void printBanner(Config config) {
        System.out.println("==============================================");
        System.out.println("AzureLabTest - CRN Azure smoke test");
        System.out.println("==============================================");
        System.out.println("Email    : " + config.emailAddress);
        System.out.println("Azure IP : " + config.ipAddress);
        System.out.println("Port     : " + config.port);
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("java AzureLabTest your.email@city.ac.uk 10.x.x.x [port]");
    }

    private static class Config {
        private final String emailAddress;
        private final String ipAddress;
        private final int port;

        private Config(String emailAddress, String ipAddress, int port) {
            this.emailAddress = emailAddress;
            this.ipAddress = ipAddress;
            this.port = port;
        }
    }
}
