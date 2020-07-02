# BitcoinCore for Android

This app includes bitcoind and bitcoin-cli compiled for Android. All command line and config options are available to the user. There is an option to auto-start the daemon when device is charging and on WiFi.

Run it for fun, or to help me experiment with NAT traversal and other new capabilities. Please do not rely on this app to serve your wallet or you might lose all your funds.

Download on PlayStore, or build yourself. 

To download the debug.log file from the app, use:
`adb backup -f backup.ab org.lndroid.bitcoincore; dd if=backup.ab bs=4K iflag=skip_bytes skip=24 | python -c "import zlib,sys;sys.stdout.write(zlib.decompress(sys.stdin.read()))" > backup.tar`
You will have to confirm a backup operation on your phone. After that, the contents of .bitcoin directory will be in the backup.tar.

## Building

First you need to build the bitcoin-core binaries for Android. Among common build dependencies you'll need Android Toolchain. Get an Android Studio and download the toolchain using SDK Manager.

First you need to build bitcoin dependencies:
* Apply [this commit](https://github.com/autoconf-archive/autoconf-archive/pull/211/commits/0087595e99c8bb9a41f5c05a426b453c8d4d931c) to ./build-aux/m4/ax_boost_thread.m4.
* Go to 'depends', read README. Choose you Android architecture (aarch64-linux-android and x86_64-linux-android are most common) and run something like:
    ANDROID_SDK=/home/user/Android/Sdk ANDROID_NDK=/home/user/Android/Sdk/ndk-bundle make HOST=aarch64-linux-android ANDROID_API_LEVEL=28 ANDROID_TOOLCHAIN_BIN=/home/user/Android/Sdk/ndk-bundle/toolchains/llvm/prebuilt/linux-x86_64/bin
- When depends are built, configure bitcoin core to use them:
    ./configure --host=aarch64-linux-android --disable-wallet --with-gui=no --prefix=/home/user/bitcoin/depends/aarch64-linux-android
- Then make. What you get is bitcoind and bitcoin-cli compiled for your chosen arch.

Now you can build this app with your compiled binaries:
- Clone this repo and cd.
- Create a lib directory for your chosen architecture. ARCH - one of names from [here](https://developer.android.com/ndk/guides/abis), most commonly arm64-v8a or x86_64.
    mkdir app/src/main/jniLibs/$(ARCH)
- Copy your bitcoind and bitcoin-cli into the dir created above *under names libbitcoind.so and libbitcoin-cli.so*. This is the only way to force Android Studio to bundle the binaries with the APK.
- Edit build.gradle's 'abiFilters' to only include the archs you compiled.
- Build the project. The built apk should will now include your bitcoin core binaries.

## NAT traversal

This app is built as a testing environment for NAT traversal capability that I'm working on. [Igor Cota](https://icota.github.com/) proposed the idea of 'sleeper nodes' on the LN [mailing list](https://lists.linuxfoundation.org/pipermail/lightning-dev/2020-May/002694.html). Essentially, we want the pruned nodes on mobile devices to be able to serve light clients (at night, when device is charging and has access to WiFi). For that we need NAT traversal built into the bitcoind.

Here is brief overview of the problem:

The main problem with NAT is that it only allows outgoing connections: external endpoint (IP+port) is assigned to an internal endpoint when it connects to public internet. Incoming connections are blocked bcs NAT doesn't know which internal endpoint is targeted by this incoming packet. Our case might be worse bcs if both peers that try to communicate are behind NAT, so no matter which party tries to connect - it won't work.

The main solution for 'both peers behind NAT' is 'hole-punching', specifically [TCP hole punching](https://en.wikipedia.org/wiki/TCP_hole_punching) in our case. It is basically this:
- Get a predictable external endpoint by taking advantage of [default NAT behaviours](https://tools.ietf.org/html/rfc5382)
- Exchange external endpoints with a peer that you want to talk to
- Do [simultaneous TCP open](http://ttcplinux.sourceforge.net/documents/one/tcpstate/tcpstate.html): simultaneously call 'connect' to each other, which results in 2 SYN packets sent, at least one of which will generally pass through and result in a connection.

The solution to 'only acceptor is NATed' is similar:
- Acceptor gets a predictable external endpoint by taking advantage of default NAT behaviours
- Connector exchanges it's external endpoint with acceptor
- Acceptor connect to connector.

In more details, and for bitcoin core in particular:
- NAT-ed peer must have at least 1 outgoing connection kept-alive to some known public endpoint. When such connection is established, NAT will assign an external IP+port to our internal ip+port, and will assign the same external IP+port to all connections established from the same internal IP+port to any other external endpoint. In our case, our node just needs to connect to at least 1 full node with public IP, which it already does. Also, the first version+verack exchange results in our node discovering it's external endpoint assigned by NAT which it will use later.
- All further connections will have to be made using THE SAME local port, bcs any other port would get a different external IP+port and that's not what we need. This means that bitcoin-core must do 'bind(fd, 8333)' on every outgoing connection before calling 'connect'. This way all connections our node has will have the same internal IP+port and same external IP+port.
- Now, the external IP+port assigned to our node A starts propagating over the network, and let's say node B discovers it and wants to connect. Somehow, node A must be informed about B's intention so that both of them could do 'simultaneous TCP open'. This is THE PROBLEM, which others solve using [STUN](https://tools.ietf.org/html/rfc5389), and I will describe my ideas about it below. This is where B needs to know it's own external endpoint - to advertise it to A.
- So, B want's to connect to A and A knows that (and both now know each other's external endpoints). Both call 'connect' approximately simultaneously, sending SYN packets to each other. For both peers, when such SYN reaches local NAT, it results in NAT registering that remote peer endpoint is now allowed to send packets to this external IP+port and his packets must be delivered to such and such private IP+port. And so both SYNs create an address mapping in their local NATs such that when remote SYN arrives, NAT lets it through and the connection is established. Both peers perform several connect attempts until some timeout, bcs the whole thing is rather fragile and non-deterministic. If only one party is NATed, then only NATed connects, and non-NATed accepts as usual.

So, what needs to be done in bitcoin core is:
- a -nat-traversal flag to enable this special mode (probably can be auto-enabled if node discovers he is behind NAT)
- *bindconnect*: all 'connect' calls are now preceeded with 'bind' using the port we'd listen to (8333 by default)
- *relay*: a new subsystem that allows a node to receive requests-for-connection from external peers (let's call it "RELAY")
- *inboundconnect*: a new logic reacting to requests-for-connection from relay: create a socket, bind it and connect to requesting peer and then mark the peer as 'inbound' 
- *outboundaccept*: a new logic where our node is not NATed and wants to connect to a NATed one: we send NATed node our request-for-connection using relay and then accept his connection and mark it as 'outbound' 

Relay seems like the hardest problem, and essentially [STUN](https://tools.ietf.org/html/rfc5389) is the known solution. It's a known third-party server, to which all peers are connected (or rather 'send UDP packets once per minute'). When B wants to connect to A it sends a request to Relay, and Relay forwards the request to A, which passes through A's NAT bcs A is pinging the Relay and it's NAT keeps an address mapping.

Initially, we could just build a simple open-source Relay server. Problem is: every peer needs to connect to it, which makes it a single-point of failure and centralization. If volunteers ran many copies of it then all nodes would either have to connect to all Relays or use some sharding. And if we take the sharding logic far enough we end up with a DHT (which could be built into the bitcoin core): each peer could store a 'mailbox' for a subset of network peers, those peers would keep connections to it and others could send requests-for-connection through the mailboxes. We could also try to come up with a way for public nodes both peers are connected to to act as matchmakers, although I'm skeptical due to privacy and scalability issues.

I have implemented [bindconnect](https://github.com/brugeman/bitcoin/tree/bindconnect) and [inboundconnect](https://github.com/brugeman/bitcoin/tree/inboundconnect), and working on outboundaccept now. Then I will experiment with relay options. All these will be tested using this app and it's userbase.

Join my coding efforts, or at least run the app :)

## License

MIT