// IN2011 Computer Networks
// Coursework 2024/2025
//
// Submission by
//  Nizar Omar
//  240025466
//  Nizar.Omar@city.ac.uk




import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class Node implements NodeInterface {
    private static final int MAX_PACKET_SIZE = 65507;
    private static final int RECEIVE_TIMEOUT_MS = 250;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int MAX_RETRIES = 3;
    private static final int CACHE_RETENTION_MS = 60000;
    private static final int RELAY_WORKER_WAIT_MS = 50;

    private final Object stateLock = new Object();
    private final Object relayLock = new Object();
    private final Random random = new Random();

    private DatagramSocket socket;
    private Thread receiverThread;
    private volatile boolean running;

    private String nodeName;
    private byte[] nodeHash;
    private String localAddressValue;

    private final Map<String, String> dataStore = new HashMap<String, String>();
    private final Map<String, AddressEntry> addressBook = new HashMap<String, AddressEntry>();
    private final Map<String, BlockingQueue<Message>> pendingResponses =
        new ConcurrentHashMap<String, BlockingQueue<Message>>();
    private final Map<String, CachedResponse> requestCache =
        new ConcurrentHashMap<String, CachedResponse>();
    private final Map<String, RelayContext> relayContexts =
        new ConcurrentHashMap<String, RelayContext>();
    private final Deque<String> relayStack = new ArrayDeque<String>();

    public void setNodeName(String nodeName) throws Exception {
        if (nodeName == null || !nodeName.startsWith("N:")) {
            throw new IllegalArgumentException("Node name must start with N:");
        }
        synchronized (stateLock) {
            this.nodeName = nodeName;
            this.nodeHash = HashID.computeHashID(nodeName);
            if (localAddressValue != null) {
                storeAddressEntry(nodeName, localAddressValue, true);
            }
        }
    }

    public void openPort(int portNumber) throws Exception {
        if (portNumber < 1 || portNumber > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }
        synchronized (stateLock) {
            if (socket != null) {
                throw new IllegalStateException("Port already open");
            }
            socket = new DatagramSocket(portNumber);
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            running = true;
            localAddressValue = determineLocalAddress(socket, portNumber);
            if (nodeName != null) {
                storeAddressEntry(nodeName, localAddressValue, true);
            }
            receiverThread = new Thread(new Runnable() {
                public void run() {
                    receiveLoop();
                }
            });
            receiverThread.setDaemon(true);
            receiverThread.start();
        }
    }

    public void handleIncomingMessages(int delay) throws Exception {
        ensureReady();
        if (delay == 0) {
            while (running) {
                Thread.sleep(200L);
            }
            return;
        }
        long deadline = System.currentTimeMillis() + delay;
        while (running && System.currentTimeMillis() < deadline) {
            Thread.sleep(Math.min(100L, deadline - System.currentTimeMillis()));
        }
    }

    public boolean isActive(String targetNodeName) throws Exception {
        AddressEntry entry = resolveAddress(targetNodeName, true);
        if (entry == null) {
            return false;
        }
        Message response = sendRequest(entry, buildSimpleRequest('G'));
        if (response == null || response.type != 'H') {
            markAddressInactive(targetNodeName);
            return false;
        }
        ParseCursor cursor = new ParseCursor(response.payload, 0);
        String responseName = parseString(cursor);
        if (responseName == null || !responseName.equals(targetNodeName)) {
            markAddressInactive(targetNodeName);
            return false;
        }
        storeAddressEntry(targetNodeName, entry.addressValue, true);
        return true;
    }

    public void pushRelay(String targetNodeName) throws Exception {
        if (targetNodeName == null || !targetNodeName.startsWith("N:")) {
            throw new IllegalArgumentException("Relay target must be a node name");
        }
        synchronized (relayLock) {
            relayStack.addLast(targetNodeName);
        }
    }

    public void popRelay() throws Exception {
        synchronized (relayLock) {
            if (!relayStack.isEmpty()) {
                relayStack.removeLast();
            }
        }
    }

    public boolean exists(String key) throws Exception {
        ensureReady();
        if (key == null) {
            return false;
        }
        if (isStoredLocally(key)) {
            return true;
        }
        boolean sawNegative = false;
        for (AddressEntry target : findClosestNodesToKey(key, true)) {
            Message response = sendRequest(target, buildKeyRequest('E', key));
            if (response == null || response.type != 'F' || response.payload.length < 1) {
                continue;
            }
            ParseCursor cursor = new ParseCursor(response.payload, 0);
            Character code = parseCode(cursor);
            if (code == null) {
                continue;
            }
            if (code == 'Y') {
                return true;
            }
            if (code == 'N') {
                sawNegative = true;
            }
        }
        return false;
    }

    public String read(String key) throws Exception {
        ensureReady();
        if (key == null) {
            return null;
        }
        String localValue = getLocalValue(key);
        if (localValue != null) {
            return localValue;
        }
        boolean sawNegative = false;
        for (AddressEntry target : findClosestNodesToKey(key, true)) {
            Message response = sendRequest(target, buildKeyRequest('R', key));
            if (response == null || response.type != 'S' || response.payload.length < 1) {
                continue;
            }
            ParseCursor cursor = new ParseCursor(response.payload, 0);
            Character code = parseCode(cursor);
            if (code == null) {
                continue;
            }
            if (code == 'Y') {
                return parseString(cursor);
            }
            if (code == 'N') {
                sawNegative = true;
            }
        }
        return null;
    }

    public boolean write(String key, String value) throws Exception {
        ensureReady();
        if (!isValidKey(key) || value == null) {
            return false;
        }
        if (isAddressKey(key)) {
            return storeAddressEntry(key, value, false);
        }
        boolean success = false;
        for (AddressEntry target : findClosestNodesToKey(key, true)) {
            Message response = sendRequest(target, buildWriteRequest('W', key, value));
            if (response == null || response.type != 'X' || response.payload.length < 1) {
                continue;
            }
            ParseCursor cursor = new ParseCursor(response.payload, 0);
            Character code = parseCode(cursor);
            if (code == null) {
                continue;
            }
            if (code == 'A' || code == 'R') {
                success = true;
            }
        }
        return success;
    }

    public boolean CAS(String key, String currentValue, String newValue) throws Exception {
        ensureReady();
        if (!isDataKey(key) || currentValue == null || newValue == null) {
            return false;
        }
        boolean success = false;
        for (AddressEntry target : findClosestNodesToKey(key, true)) {
            Message response = sendRequest(target, buildCasRequest(key, currentValue, newValue));
            if (response == null || response.type != 'D' || response.payload.length < 1) {
                continue;
            }
            ParseCursor cursor = new ParseCursor(response.payload, 0);
            Character code = parseCode(cursor);
            if (code == null) {
                continue;
            }
            if (code == 'A' || code == 'R') {
                success = true;
            }
        }
        return success;
    }

    private void ensureReady() {
        if (nodeName == null) {
            throw new IllegalStateException("Node name not set");
        }
        if (socket == null) {
            throw new IllegalStateException("Port not open");
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] copy = Arrays.copyOf(packet.getData(), packet.getLength());
                handleDatagram(copy, packet.getAddress(), packet.getPort());
            } catch (SocketTimeoutException e) {
                cleanupCaches();
            } catch (Exception e) {
                cleanupCaches();
            }
        }
    }

    private void cleanupCaches() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CachedResponse> entry : requestCache.entrySet()) {
            if (now - entry.getValue().timestamp > CACHE_RETENTION_MS) {
                requestCache.remove(entry.getKey());
            }
        }
        for (Map.Entry<String, RelayContext> entry : relayContexts.entrySet()) {
            if (now - entry.getValue().createdAt > CACHE_RETENTION_MS) {
                relayContexts.remove(entry.getKey());
            }
        }
    }

    private void handleDatagram(byte[] packetBytes, InetAddress sourceAddress, int sourcePort)
        throws Exception {
        Message message = parseMessage(packetBytes);
        if (message == null) {
            return;
        }
        String senderAddress = formatAddressValue(sourceAddress, sourcePort);
        if (sourcePort > 0) {
            storeAddressIfPossible(message, senderAddress);
        }

        if (isResponseType(message.type)) {
            RelayContext relayContext = relayContexts.remove(message.transactionId);
            if (relayContext != null) {
                byte[] responsePacket =
                    buildMessage(relayContext.originalTxId, message.type, message.payload);
                sendRaw(responsePacket, relayContext.clientAddress, relayContext.clientPort);
                return;
            }
            BlockingQueue<Message> queue = pendingResponses.get(message.transactionId);
            if (queue != null) {
                queue.offer(message);
            }
            return;
        }

        if (message.type == 'I') {
            return;
        }

        String cacheKey = sourceAddress.getHostAddress() + ":" + sourcePort + ":" + message.transactionId;
        CachedResponse cached = requestCache.get(cacheKey);
        if (cached != null && Arrays.equals(cached.requestPayload, message.rawBytes)) {
            sendRaw(cached.responseBytes, sourceAddress, sourcePort);
            return;
        }

        byte[] response = processRequest(message, sourceAddress, sourcePort);
        if (response != null) {
            requestCache.put(cacheKey, new CachedResponse(message.rawBytes, response));
            sendRaw(response, sourceAddress, sourcePort);
        }
    }

    private void storeAddressIfPossible(Message message, String senderAddress) {
        if (message.type == 'G' || message.type == 'V') {
            return;
        }
        if (message.type == 'H') {
            ParseCursor cursor = new ParseCursor(message.payload, 0);
            String discoveredName = parseString(cursor);
            if (discoveredName != null && isAddressKey(discoveredName)) {
                storeAddressEntry(discoveredName, senderAddress, true);
            }
        }
    }

    private byte[] processRequest(Message message, InetAddress sourceAddress, int sourcePort)
        throws Exception {
        switch (message.type) {
            case 'G':
                return buildMessage(message.transactionId, 'H', encodeStringPayload(nodeName));
            case 'N':
                return handleNearestRequest(message);
            case 'E':
                return handleExistsRequest(message);
            case 'R':
                return handleReadRequest(message);
            case 'W':
                return handleWriteRequest(message);
            case 'C':
                return handleCasRequest(message);
            case 'V':
                handleRelayRequest(message, sourceAddress, sourcePort);
                return null;
            default:
                return null;
        }
    }

    private byte[] handleNearestRequest(Message message) throws Exception {
        if (message.payload.length != 65 || message.payload[0] != (byte) ' ') {
            return null;
        }
        String hexHash = new String(message.payload, 1, 64, StandardCharsets.UTF_8);
        byte[] targetHash = parseHexHash(hexHash);
        if (targetHash == null) {
            return null;
        }
        List<AddressEntry> nearest = getClosestKnownNodes(targetHash, 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((byte) ' ');
        for (AddressEntry entry : nearest) {
            out.write(encodeString(entry.nodeName));
            out.write(encodeString(entry.addressValue));
        }
        return buildMessage(message.transactionId, 'O', out.toByteArray());
    }

    private byte[] handleExistsRequest(Message message) throws Exception {
        ParseCursor cursor = new ParseCursor(message.payload, 1);
        String key = parseString(cursor);
        if (!isValidKey(key)) {
            return buildCodeOnlyResponse(message.transactionId, 'F', '?');
        }
        char code = computeLookupResponseCode(key);
        return buildCodeOnlyResponse(message.transactionId, 'F', code);
    }

    private byte[] handleReadRequest(Message message) throws Exception {
        ParseCursor cursor = new ParseCursor(message.payload, 1);
        String key = parseString(cursor);
        if (!isValidKey(key)) {
            return buildCodeOnlyResponse(message.transactionId, 'S', '?');
        }
        String value = getLocalValue(key);
        if (value != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write((byte) ' ');
            out.write((byte) 'Y');
            out.write((byte) ' ');
            out.write(encodeString(value));
            return buildMessage(message.transactionId, 'S', out.toByteArray());
        }
        char code = isAmongClosestThree(key) ? 'N' : '?';
        return buildCodeOnlyResponse(message.transactionId, 'S', code);
    }

    private byte[] handleWriteRequest(Message message) throws Exception {
        ParseCursor cursor = new ParseCursor(message.payload, 1);
        String key = parseString(cursor);
        String value = parseString(cursor);
        if (!isValidKey(key) || value == null) {
            return buildCodeOnlyResponse(message.transactionId, 'X', 'X');
        }
        if (isAddressKey(key)) {
            boolean replaced = hasLocalValue(key);
            boolean stored = storeAddressEntry(key, value, false);
            char code = stored ? (replaced ? 'R' : 'A') : 'X';
            return buildCodeOnlyResponse(message.transactionId, 'X', code);
        }
        synchronized (stateLock) {
            boolean alreadyPresent = dataStore.containsKey(key);
            if (!alreadyPresent && !isAmongClosestThree(key)) {
                return buildCodeOnlyResponse(message.transactionId, 'X', 'X');
            }
            dataStore.put(key, value);
            return buildCodeOnlyResponse(message.transactionId, 'X',
                alreadyPresent ? 'R' : 'A');
        }
    }

    private byte[] handleCasRequest(Message message) throws Exception {
        ParseCursor cursor = new ParseCursor(message.payload, 1);
        String key = parseString(cursor);
        String requestedValue = parseString(cursor);
        String newValue = parseString(cursor);
        if (!isDataKey(key) || requestedValue == null || newValue == null) {
            return buildCodeOnlyResponse(message.transactionId, 'D', 'X');
        }
        synchronized (stateLock) {
            String currentValue = dataStore.get(key);
            if (currentValue == null) {
                if (!isAmongClosestThree(key)) {
                    return buildCodeOnlyResponse(message.transactionId, 'D', 'X');
                }
                dataStore.put(key, newValue);
                return buildCodeOnlyResponse(message.transactionId, 'D', 'A');
            }
            if (!currentValue.equals(requestedValue)) {
                return buildCodeOnlyResponse(message.transactionId, 'D', 'N');
            }
            dataStore.put(key, newValue);
            return buildCodeOnlyResponse(message.transactionId, 'D', 'R');
        }
    }

    private void handleRelayRequest(
        Message message,
        InetAddress sourceAddress,
        int sourcePort
    ) throws Exception {
        ParseCursor cursor = new ParseCursor(message.payload, 1);
        String targetNodeName = parseString(cursor);
        if (!isAddressKey(targetNodeName)) {
            return;
        }
        byte[] embedded = Arrays.copyOfRange(message.payload, cursor.index, message.payload.length);
        if (embedded.length < 4) {
            return;
        }
        Message embeddedMessage = parseMessage(embedded);
        if (embeddedMessage == null) {
            return;
        }
        final AddressEntry target = resolveAddress(targetNodeName, true);
        if (target == null) {
            return;
        }
        if (!isRequestType(embeddedMessage.type) || embeddedMessage.type == 'V') {
            sendRaw(embedded, target.inetAddress, target.port);
            return;
        }
        final String relayTxId = generateTransactionId();
        final RelayContext relayContext =
            new RelayContext(message.transactionId, sourceAddress, sourcePort);
        relayContexts.put(relayTxId, relayContext);

        final byte[] forwarded =
            buildMessage(relayTxId, embeddedMessage.type, embeddedMessage.payload);

        Thread relayThread = new Thread(new Runnable() {
            public void run() {
                try {
                    sendRequestWithPrebuiltTx(target, forwarded, relayTxId);
                } catch (Exception e) {
                    relayContexts.remove(relayTxId);
                }
            }
        });
        relayThread.setDaemon(true);
        relayThread.start();
        Thread.sleep(RELAY_WORKER_WAIT_MS);
    }

    private Message sendRequest(AddressEntry target, byte[] packet) throws Exception {
        String txId = extractTransactionId(packet);
        return sendRequestWithPrebuiltTx(target, packet, txId);
    }

    private Message sendRequestWithPrebuiltTx(AddressEntry target, byte[] packet, String txId)
        throws Exception {
        RelayDispatch dispatch = applyRelayStack(target, packet, txId);
        BlockingQueue<Message> queue = new LinkedBlockingQueue<Message>();
        pendingResponses.put(txId, queue);
        try {
            for (int attempt = 0; attempt <= MAX_RETRIES; ++attempt) {
                sendRaw(dispatch.packet, dispatch.firstHop.inetAddress, dispatch.firstHop.port);
                Message response = queue.poll(REQUEST_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
                if (response != null) {
                    return response;
                }
            }
        } finally {
            pendingResponses.remove(txId);
        }
        markAddressInactive(target.nodeName);
        return null;
    }

    private RelayDispatch applyRelayStack(AddressEntry target, byte[] packet, String txId)
        throws Exception {
        List<String> relays = new ArrayList<String>();
        synchronized (relayLock) {
            relays.addAll(relayStack);
        }
        if (relays.isEmpty()) {
            return new RelayDispatch(target, packet);
        }
        byte[] wrapped = packet;
        String nextTarget = target.nodeName;
        for (int i = relays.size() - 1; i >= 0; --i) {
            wrapped = buildRelayPacket(txId, nextTarget, wrapped);
            nextTarget = relays.get(i);
        }
        AddressEntry firstHop = resolveAddress(relays.get(0), true);
        if (firstHop == null) {
            throw new IllegalStateException("Unknown relay node: " + relays.get(0));
        }
        return new RelayDispatch(firstHop, wrapped);
    }

    private byte[] buildRelayPacket(String txId, String relayTarget, byte[] embedded)
        throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((byte) ' ');
        out.write(encodeString(relayTarget));
        out.write(embedded);
        return buildMessage(txId, 'V', out.toByteArray());
    }

    private void sendRaw(byte[] packet, InetAddress address, int port) throws Exception {
        DatagramPacket datagram = new DatagramPacket(packet, packet.length, address, port);
        socket.send(datagram);
    }

    private AddressEntry resolveAddress(String targetNodeName, boolean discover) throws Exception {
        synchronized (stateLock) {
            AddressEntry existing = addressBook.get(targetNodeName);
            if (existing != null) {
                return existing;
            }
        }
        if (!discover) {
            return null;
        }
        discoverNetwork(targetNodeName);
        synchronized (stateLock) {
            return addressBook.get(targetNodeName);
        }
    }

    private void discoverNetwork(String targetKey) throws Exception {
        byte[] targetHash = HashID.computeHashID(targetKey);
        Set<String> queried = new HashSet<String>();
        boolean changed = true;
        while (changed) {
            changed = false;
            List<AddressEntry> candidates = getClosestKnownNodes(targetHash, 6);
            for (AddressEntry candidate : candidates) {
                if (candidate.nodeName.equals(nodeName) || queried.contains(candidate.nodeName)) {
                    continue;
                }
                queried.add(candidate.nodeName);
                Message response = sendRequest(candidate, buildNearestRequest(targetHash));
                if (response == null || response.type != 'O') {
                    continue;
                }
                if (ingestNearestResponse(response)) {
                    changed = true;
                }
                synchronized (stateLock) {
                    if (addressBook.containsKey(targetKey)) {
                        return;
                    }
                }
            }
        }
    }

    private boolean ingestNearestResponse(Message response) {
        ParseCursor cursor = new ParseCursor(response.payload, 0);
        boolean changed = false;
        while (cursor.index < response.payload.length) {
            String foundName = parseString(cursor);
            String foundAddress = parseString(cursor);
            if (!isAddressKey(foundName) || foundAddress == null) {
                break;
            }
            if (storeAddressEntry(foundName, foundAddress, true)) {
                changed = true;
            }
        }
        return changed;
    }

    private List<AddressEntry> findClosestNodesToKey(String key, boolean discover) throws Exception {
        if (discover) {
            discoverNetwork(key);
        }
        byte[] keyHash = HashID.computeHashID(key);
        List<AddressEntry> closest = getClosestKnownNodes(keyHash, 3);
        boolean hasSelf = false;
        for (AddressEntry entry : closest) {
            if (entry.nodeName.equals(nodeName)) {
                hasSelf = true;
                break;
            }
        }
        if (!hasSelf) {
            synchronized (stateLock) {
                AddressEntry self = addressBook.get(nodeName);
                if (self != null) {
                    closest.add(self);
                }
            }
            Collections.sort(closest, new DistanceComparator(keyHash));
            while (closest.size() > 3) {
                closest.remove(closest.size() - 1);
            }
        }
        return closest;
    }

    private List<AddressEntry> getClosestKnownNodes(byte[] targetHash, int limit) {
        List<AddressEntry> allEntries = new ArrayList<AddressEntry>();
        synchronized (stateLock) {
            allEntries.addAll(addressBook.values());
        }
        Collections.sort(allEntries, new DistanceComparator(targetHash));
        List<AddressEntry> result = new ArrayList<AddressEntry>();
        for (AddressEntry entry : allEntries) {
            if (!entry.active) {
                continue;
            }
            result.add(entry);
            if (result.size() == limit) {
                break;
            }
        }
        return result;
    }

    private boolean isAmongClosestThree(String key) throws Exception {
        byte[] keyHash = HashID.computeHashID(key);
        List<AddressEntry> closest = getClosestKnownNodes(keyHash, 3);
        for (AddressEntry entry : closest) {
            if (entry.nodeName.equals(nodeName)) {
                return true;
            }
        }
        return closest.size() < 3;
    }

    private char computeLookupResponseCode(String key) throws Exception {
        if (hasLocalValue(key)) {
            return 'Y';
        }
        return isAmongClosestThree(key) ? 'N' : '?';
    }

    private boolean hasLocalValue(String key) {
        synchronized (stateLock) {
            if (isAddressKey(key)) {
                return addressBook.containsKey(key);
            }
            return dataStore.containsKey(key);
        }
    }

    private boolean isStoredLocally(String key) {
        return getLocalValue(key) != null;
    }

    private String getLocalValue(String key) {
        synchronized (stateLock) {
            if (isAddressKey(key)) {
                AddressEntry entry = addressBook.get(key);
                return entry == null ? null : entry.addressValue;
            }
            return dataStore.get(key);
        }
    }

    private boolean storeAddressEntry(String key, String value, boolean preferExisting) {
        ParsedAddress parsed = parseAddress(value);
        if (parsed == null || !isAddressKey(key)) {
            return false;
        }
        try {
            byte[] keyHash = HashID.computeHashID(key);
            synchronized (stateLock) {
                AddressEntry existing = addressBook.get(key);
                if (existing != null) {
                    if (preferExisting && existing.active) {
                        return false;
                    }
                    addressBook.put(key, new AddressEntry(key, value, parsed.address, parsed.port,
                        keyHash, true));
                    return true;
                }

                int targetDistance = distance(nodeHash, keyHash);
                List<AddressEntry> sameDistance = new ArrayList<AddressEntry>();
                for (AddressEntry entry : addressBook.values()) {
                    if (distance(entry.hash, nodeHash) == targetDistance) {
                        sameDistance.add(entry);
                    }
                }
                if (sameDistance.size() >= 3 && !key.equals(nodeName)) {
                    Collections.sort(sameDistance, new StabilityComparator());
                    return false;
                }
                addressBook.put(key, new AddressEntry(key, value, parsed.address, parsed.port,
                    keyHash, true));
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void markAddressInactive(String failedNodeName) {
        if (failedNodeName == null) {
            return;
        }
        synchronized (stateLock) {
            AddressEntry existing = addressBook.get(failedNodeName);
            if (existing != null && !failedNodeName.equals(nodeName)) {
                existing.active = false;
                existing.stabilityScore -= 1;
            }
        }
    }

    private byte[] buildSimpleRequest(char type) throws Exception {
        return buildMessage(generateTransactionId(), type, null);
    }

    private byte[] buildKeyRequest(char type, String key) throws Exception {
        return buildMessage(generateTransactionId(), type, encodeStringPayload(key));
    }

    private byte[] buildWriteRequest(char type, String key, String value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((byte) ' ');
        out.write(encodeString(key));
        out.write(encodeString(value));
        return buildMessage(generateTransactionId(), type, out.toByteArray());
    }

    private byte[] buildCasRequest(String key, String currentValue, String newValue) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((byte) ' ');
        out.write(encodeString(key));
        out.write(encodeString(currentValue));
        out.write(encodeString(newValue));
        return buildMessage(generateTransactionId(), 'C', out.toByteArray());
    }

    private byte[] buildNearestRequest(byte[] targetHash) throws Exception {
        String hexHash = toHex(targetHash);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(generateTransactionId().getBytes(StandardCharsets.ISO_8859_1));
        out.write((byte) ' ');
        out.write((byte) 'N');
        out.write((byte) ' ');
        out.write(hexHash.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private byte[] buildCodeOnlyResponse(String txId, char type, char code) throws Exception {
        return buildMessage(txId, type, new byte[] { (byte) ' ', (byte) code });
    }

    private byte[] buildMessage(String txId, char type, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(txId.getBytes(StandardCharsets.ISO_8859_1));
        out.write((byte) ' ');
        out.write((byte) type);
        if (payload != null) {
            out.write(payload);
        }
        return out.toByteArray();
    }

    private byte[] encodeStringPayload(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((byte) ' ');
        out.write(encodeString(value), 0, encodeString(value).length);
        return out.toByteArray();
    }

    private byte[] encodeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int spaces = 0;
        for (int i = 0; i < bytes.length; ++i) {
            if (bytes[i] == (byte) ' ') {
                spaces += 1;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String counter = Integer.toString(spaces);
        out.write(counter.getBytes(StandardCharsets.UTF_8), 0, counter.length());
        out.write((byte) ' ');
        out.write(bytes, 0, bytes.length);
        out.write((byte) ' ');
        return out.toByteArray();
    }

    private Message parseMessage(byte[] bytes) {
        try {
            if (bytes == null || bytes.length < 4 || bytes[2] != (byte) ' ') {
                return null;
            }
            if (bytes[0] == (byte) ' ' || bytes[1] == (byte) ' ') {
                return null;
            }
            String txId = new String(bytes, 0, 2, StandardCharsets.ISO_8859_1);
            char type = (char) bytes[3];
            byte[] payload = bytes.length > 4
                ? Arrays.copyOfRange(bytes, 4, bytes.length)
                : new byte[0];
            return new Message(txId, type, payload, bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseString(ParseCursor cursor) {
        try {
            if (cursor.index >= cursor.bytes.length) {
                return null;
            }
            while (cursor.index < cursor.bytes.length && cursor.bytes[cursor.index] == (byte) ' ') {
                cursor.index += 1;
            }
            int start = cursor.index;
            while (cursor.index < cursor.bytes.length && cursor.bytes[cursor.index] != (byte) ' ') {
                cursor.index += 1;
            }
            if (cursor.index >= cursor.bytes.length) {
                return null;
            }
            int spacesToRead = Integer.parseInt(new String(cursor.bytes, start,
                cursor.index - start, StandardCharsets.UTF_8));
            cursor.index += 1;
            int stringStart = cursor.index;
            int spacesSeen = 0;
            while (cursor.index < cursor.bytes.length) {
                if (cursor.bytes[cursor.index] == (byte) ' ') {
                    if (spacesSeen == spacesToRead) {
                        String result = new String(cursor.bytes, stringStart,
                            cursor.index - stringStart, StandardCharsets.UTF_8);
                        cursor.index += 1;
                        return result;
                    }
                    spacesSeen += 1;
                }
                cursor.index += 1;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private Character parseCode(ParseCursor cursor) {
        while (cursor.index < cursor.bytes.length && cursor.bytes[cursor.index] == (byte) ' ') {
            cursor.index += 1;
        }
        if (cursor.index >= cursor.bytes.length) {
            return null;
        }
        char code = (char) cursor.bytes[cursor.index];
        cursor.index += 1;
        while (cursor.index < cursor.bytes.length && cursor.bytes[cursor.index] == (byte) ' ') {
            cursor.index += 1;
        }
        return code;
    }

    private boolean isRequestType(char type) {
        return type == 'G' || type == 'N' || type == 'E' || type == 'R'
            || type == 'W' || type == 'C' || type == 'V';
    }

    private boolean isResponseType(char type) {
        return type == 'H' || type == 'O' || type == 'F' || type == 'S'
            || type == 'X' || type == 'D';
    }

    private boolean isValidKey(String key) {
        return isAddressKey(key) || isDataKey(key);
    }

    private boolean isAddressKey(String key) {
        return key != null && key.startsWith("N:");
    }

    private boolean isDataKey(String key) {
        return key != null && key.startsWith("D:");
    }

    private String generateTransactionId() {
        while (true) {
            byte first = (byte) (33 + random.nextInt(93));
            byte second = (byte) (33 + random.nextInt(93));
            if (first != (byte) ' ' && second != (byte) ' ') {
                return new String(new byte[] { first, second }, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private String extractTransactionId(byte[] packet) {
        return new String(packet, 0, 2, StandardCharsets.ISO_8859_1);
    }

    private byte[] parseHexHash(String hexHash) {
        if (hexHash == null || hexHash.length() != 64) {
            return null;
        }
        byte[] result = new byte[32];
        for (int i = 0; i < result.length; ++i) {
            int high = Character.digit(hexHash.charAt(2 * i), 16);
            int low = Character.digit(hexHash.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; ++i) {
            int value = bytes[i] & 0xff;
            String hex = Integer.toHexString(value);
            if (hex.length() == 1) {
                out.append('0');
            }
            out.append(hex);
        }
        return out.toString();
    }

    private int distance(byte[] first, byte[] second) {
        int leadingBits = 0;
        for (int i = 0; i < first.length && i < second.length; ++i) {
            int xor = (first[i] ^ second[i]) & 0xff;
            if (xor == 0) {
                leadingBits += 8;
            } else {
                leadingBits += Integer.numberOfLeadingZeros(xor) - 24;
                break;
            }
        }
        return 256 - leadingBits;
    }

    private ParsedAddress parseAddress(String addressValue) {
        if (addressValue == null) {
            return null;
        }
        int colon = addressValue.lastIndexOf(':');
        if (colon <= 0 || colon >= addressValue.length() - 1) {
            return null;
        }
        try {
            String host = addressValue.substring(0, colon);
            int port = Integer.parseInt(addressValue.substring(colon + 1));
            return new ParsedAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return null;
        }
    }

    private String determineLocalAddress(DatagramSocket datagramSocket, int portNumber) {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            return host + ":" + portNumber;
        } catch (Exception e) {
            InetSocketAddress socketAddress =
                (InetSocketAddress) datagramSocket.getLocalSocketAddress();
            return socketAddress.getAddress().getHostAddress() + ":" + portNumber;
        }
    }

    private String formatAddressValue(InetAddress address, int port) {
        return address.getHostAddress() + ":" + port;
    }

    private static class ParseCursor {
        private final byte[] bytes;
        private int index;

        private ParseCursor(byte[] bytes, int index) {
            this.bytes = bytes;
            this.index = index;
        }
    }

    private static class ParsedAddress {
        private final InetAddress address;
        private final int port;

        private ParsedAddress(InetAddress address, int port) {
            this.address = address;
            this.port = port;
        }
    }

    private static class Message {
        private final String transactionId;
        private final char type;
        private final byte[] payload;
        private final byte[] rawBytes;

        private Message(String transactionId, char type, byte[] payload, byte[] rawBytes) {
            this.transactionId = transactionId;
            this.type = type;
            this.payload = payload;
            this.rawBytes = rawBytes;
        }
    }

    private static class AddressEntry {
        private final String nodeName;
        private final String addressValue;
        private final InetAddress inetAddress;
        private final int port;
        private final byte[] hash;
        private boolean active;
        private int stabilityScore;

        private AddressEntry(
            String nodeName,
            String addressValue,
            InetAddress inetAddress,
            int port,
            byte[] hash,
            boolean active
        ) {
            this.nodeName = nodeName;
            this.addressValue = addressValue;
            this.inetAddress = inetAddress;
            this.port = port;
            this.hash = hash;
            this.active = active;
            this.stabilityScore = active ? 1 : 0;
        }
    }

    private static class CachedResponse {
        private final byte[] requestPayload;
        private final byte[] responseBytes;
        private final long timestamp;

        private CachedResponse(byte[] requestPayload, byte[] responseBytes) {
            this.requestPayload = requestPayload;
            this.responseBytes = responseBytes;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static class RelayContext {
        private final String originalTxId;
        private final InetAddress clientAddress;
        private final int clientPort;
        private final long createdAt;

        private RelayContext(String originalTxId, InetAddress clientAddress, int clientPort) {
            this.originalTxId = originalTxId;
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static class RelayDispatch {
        private final AddressEntry firstHop;
        private final byte[] packet;

        private RelayDispatch(AddressEntry firstHop, byte[] packet) {
            this.firstHop = firstHop;
            this.packet = packet;
        }
    }

    private class DistanceComparator implements Comparator<AddressEntry> {
        private final byte[] targetHash;

        private DistanceComparator(byte[] targetHash) {
            this.targetHash = targetHash;
        }

        public int compare(AddressEntry left, AddressEntry right) {
            int leftDistance = distance(left.hash, targetHash);
            int rightDistance = distance(right.hash, targetHash);
            if (leftDistance != rightDistance) {
                return leftDistance - rightDistance;
            }
            return left.nodeName.compareTo(right.nodeName);
        }
    }

    private static class StabilityComparator implements Comparator<AddressEntry> {
        public int compare(AddressEntry left, AddressEntry right) {
            if (left.stabilityScore != right.stabilityScore) {
                return right.stabilityScore - left.stabilityScore;
            }
            return left.nodeName.compareTo(right.nodeName);
        }
    }
}
