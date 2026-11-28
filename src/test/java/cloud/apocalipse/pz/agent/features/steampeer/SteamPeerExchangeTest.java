package cloud.apocalipse.pz.agent.features.steampeer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteamPeerExchangeTest {
    @Test
    void measuresNearbyPlayersOnTheSameFloor() {
        assertEquals(900, SteamPeerRendezvous.distanceSquared(0, 0, 1, 18, 24, 1));
        assertTrue(SteamPeerRendezvous.distanceSquared(0, 0, 1, 31, 0, 1) > 900);
        assertEquals(Double.POSITIVE_INFINITY, SteamPeerRendezvous.distanceSquared(0, 0, 1, 0, 0, 2));
    }

    @Test
    void confirmsOnlyTheConfiguredPeerReplyingWithTheMatchingNonce() {
        var first = new SteamPeerExchange(20, 123);
        var second = new SteamPeerExchange(10, 456);
        byte[] reply = second.receive(10, first.ping());
        assertNotNull(reply);
        assertNull(first.receive(99, reply));
        assertNull(first.receive(20, new byte[1]));
        assertNull(first.receive(20, second.receive(10, new SteamPeerExchange(20, 999).ping())));
        assertFalse(first.confirmed());
        assertNull(first.receive(20, reply));
        assertTrue(first.confirmed());
    }

    @Test
    void ignoresMalformedAndUnknownMessages() {
        var exchange = new SteamPeerExchange(20, 123);
        byte[] badMagic = exchange.ping();
        badMagic[0] = 0;
        byte[] badKind = exchange.ping();
        badKind[4] = 99;
        assertNull(exchange.receive(20, badMagic));
        assertNull(exchange.receive(20, badKind));
        assertFalse(exchange.confirmed());
    }
}
