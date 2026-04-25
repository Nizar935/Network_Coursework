Build Instructions
==================

Open a terminal in the directory that contains the Java source files.

Compile with:
javac *.java

Run the local multi-node test with:
java LocalTest

Run the Azure smoke test with:
java AzureLabTest nizar.omar@city.ac.uk  127.0.0.1 [port]


Run Instructions
================

Create a Node object, call setNodeName with a value starting with N:, then call
openPort with the UDP port to bind. After that the node can be used through the
NodeInterface methods.

To keep a node alive for background processing, call:
handleIncomingMessages(0)

To process traffic for a limited amount of time, call:
handleIncomingMessages(delayInMilliseconds)


Working Functionality
=====================

The following functionality is implemented in Node.java:

- SHA-256 based hashID handling and distance comparison
- CRN string encoding and parsing
- UDP send/receive handling with request retransmission after timeout
- Duplicate request response caching
- Name, Nearest, Exists, Read, Write and Compare-and-Swap messages
- Address storage and local data storage
- Relay stack support for outgoing requests
- Incoming relay forwarding with transaction ID rewriting
- Passive node discovery from protocol traffic
- Active nearest-based node discovery
- Local request handling while the node is running in the background


Known Limitations
=================

- This implementation has been smoke-tested locally, but it still needs final
  validation against the Azure lab nodes and the provided shared network.
- The Wireshark capture required for submission has not been generated in this
  repository.
- The address-book stability policy is conservative when more than three nodes
  are known at the same distance.
