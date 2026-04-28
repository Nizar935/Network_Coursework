Build Instructions
==================

Open a terminal in the directory that contains the submitted Java source files.

Compile with:
javac *.java

Run the local multi-node test with:
java LocalTest

Run the Azure smoke test with:
java AzureLabTest Nizar.Omar@city.ac.uk 10.216.35.149 20110

Example:
java AzureLabTest Nizar.Omar@city.ac.uk 10.216.35.149 20110


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

The following functionality is implemented:

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
- Azure smoke test successfully read all 7 poem entries from the lab network
- Local tests for CAS, malformed packets, duplicate packets and basic txid matching were used during debugging


Known Limitations
=================

- Relay, storage-rule, and robustness behaviour were tested manually and with
  helper test files, but hidden marking tests may still expose edge cases.
- The address-book stability policy is conservative when more than three nodes
  are known at the same distance.
- The submission ZIP should include only the solution Java files, README.txt,
  and the Wireshark capture file. Debug-only test files used during
  development should not be included.
