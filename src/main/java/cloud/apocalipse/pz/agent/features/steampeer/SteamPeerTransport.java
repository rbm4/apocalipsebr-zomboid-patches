package cloud.apocalipse.pz.agent.features.steampeer;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.*;

final class SteamPeerTransport {
    // Dedicated probe channel in the game's existing Steam app context.
    private static final int CHANNEL = 0x41505031;
    private static final int RELIABLE = 8;
    private static final int OK = 1;
    // SteamNetworkingIdentity is two int32 fields plus a 128-byte union.
    private static final int IDENTITY_SIZE = 136;
    // Windows x64 SteamNetworkingMessage_t prefix: data pointer, size, connection, peer identity.
    private static final int PEER_OFFSET = 16;

    record Message(long sender, byte[] payload) {}

    private final MemorySegment api;
    private final MethodHandle setId, getId, send, receive, release, closeChannel;

    SteamPeerTransport() throws Throwable {
        var symbols = SymbolLookup.loaderLookup();
        api = (MemorySegment) bind(symbols, "SteamAPI_SteamNetworkingMessages_SteamAPI_v002",
                FunctionDescriptor.of(ADDRESS)).invokeExact();
        if (api.address() == 0) throw new IllegalStateException("SteamNetworkingMessages unavailable");
        setId = bind(symbols, "SteamAPI_SteamNetworkingIdentity_SetSteamID64",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        getId = bind(symbols, "SteamAPI_SteamNetworkingIdentity_GetSteamID64",
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        send = bind(symbols, "SteamAPI_ISteamNetworkingMessages_SendMessageToUser",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        receive = bind(symbols, "SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        release = bind(symbols, "SteamAPI_SteamNetworkingMessage_t_Release", FunctionDescriptor.ofVoid(ADDRESS));
        closeChannel = bind(symbols, "SteamAPI_ISteamNetworkingMessages_CloseChannelWithUser",
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, JAVA_INT));
    }

    void send(long peer, byte[] payload) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(JAVA_BYTE, payload);
            // An outbound send implicitly accepts the session for this configured peer.
            int result = (int) send.invokeExact(api, identity(arena, peer), data, payload.length, RELIABLE, CHANNEL);
            if (result != OK) throw new IllegalStateException("Steam send result " + result);
        }
    }

    Message receive(long peer) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(ADDRESS);
            int count = (int) receive.invokeExact(api, CHANNEL, slot, 1);
            if (count == 0) return null;
            if (count != 1) throw new IllegalStateException("Steam receive count " + count);
            MemorySegment message = slot.get(ADDRESS, 0);
            if (message.address() == 0) throw new IllegalStateException("Steam returned a null message");
            try {
                MemorySegment header = message.reinterpret(PEER_OFFSET + IDENTITY_SIZE);
                long sender = (long) getId.invokeExact(header.asSlice(PEER_OFFSET, IDENTITY_SIZE));
                int length = header.get(JAVA_INT, 8);
                if (sender != peer || length != SteamPeerExchange.MESSAGE_SIZE) {
                    return new Message(sender, new byte[0]);
                }
                MemorySegment data = header.get(ADDRESS, 0);
                if (data.address() == 0) throw new IllegalStateException("Steam returned a null payload");
                return new Message(sender, data.reinterpret(length).toArray(JAVA_BYTE));
            } finally {
                release.invokeExact(message);
            }
        }
    }

    void close(long peer) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            boolean closed = (boolean) closeChannel.invokeExact(api, identity(arena, peer), CHANNEL);
        }
    }

    private MemorySegment identity(Arena arena, long peer) throws Throwable {
        MemorySegment identity = arena.allocate(IDENTITY_SIZE, 8);
        setId.invokeExact(identity, peer);
        return identity;
    }

    private static MethodHandle bind(SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(
                symbols.find(name).orElseThrow(() -> new IllegalStateException("Missing Steam export " + name)),
                descriptor);
    }
}

