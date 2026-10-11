package io.github.pgodlews.wormhole

import org.junit.Assert.*
import org.junit.Test

class PairedClientsTest {

    @Test
    fun `serialization round-trips keys, device ids and names with separators`() {
        val original = PairedClient(
            publicKey = "q83vEjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJA=",
            deviceId = "AA:BB:CC:DD:EE:FF",
            name = "Piotr’s MacBook | 100% \nPro"
        )
        assertEquals(original, PairedClient.deserialize(original.serialize()))
    }

    @Test
    fun `serializeAll and deserializeAll keep every client in order`() {
        val clients = listOf(
            PairedClient("key1=", "id1", "iPhone"),
            PairedClient("key2=", "id2", "Mac\nmini")
        )
        assertEquals(clients, PairedClient.deserializeAll(PairedClient.serializeAll(clients)))
    }

    @Test
    fun `deserialization skips malformed and keyless entries`() {
        assertNull(PairedClient.deserialize(""))
        assertNull(PairedClient.deserialize("onlykey"))
        assertNull(PairedClient.deserialize("|id|name"))
        assertEquals(emptyList<PairedClient>(), PairedClient.deserializeAll(null))
        assertEquals(
            listOf(PairedClient("key=", "id", "name")),
            PairedClient.deserializeAll("garbage\nkey=|id|name\n")
        )
    }
}
