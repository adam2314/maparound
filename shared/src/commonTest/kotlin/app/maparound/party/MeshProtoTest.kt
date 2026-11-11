package app.maparound.party

import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import kotlin.test.Test

/** Reference bytes were produced with the official `meshtastic` Python package (protobuf runtime). */
class MeshProtoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun encodesLikeTheOfficialLibrary() {
        assertEquals("18f8acd19101", Mesh.wantConfig(0x12345678).hex())
        assertEquals(
            "0a1d15ffffffff220f088002120a00010203040506070809350d0c0b0a4803",
            Mesh.sendPacket(ByteArray(10) { it.toByte() }, Mesh.PORT_PRIVATE, 0x0A0B0C0D).hex(),
        )
    }

    @Test
    fun parsesPositionPacket() {
        val f = Mesh.parseFromRadio(hex("080512380dd4c3b2a115ffffffff1801221e0803121a0d526eef121537c1f41418d6ffffffffffffffff012500f153653d01f15365450000d0404802"))
        val p = (f as Mesh.FromRadio.PacketIn).packet
        assertEquals(0xA1B2C3D4L, p.from)
        assertEquals(Mesh.BROADCAST, p.to)
        assertEquals(1, p.channel)
        assertEquals(Mesh.PORT_POSITION, p.portnum)
        assertEquals(1_700_000_001L, p.rxTime)
        val pos = Mesh.parsePosition(PbReader(p.payload))!!
        assertEquals(31.7681234, pos.lat, 1e-7)
        assertEquals(35.1584567, pos.lon, 1e-7)
        assertEquals(-42, pos.altM)
        assertEquals(1_700_000_000L, pos.time)
    }

    @Test
    fun parsesNodeInfoMyInfoAndConfigComplete() {
        val n = (Mesh.parseFromRadio(hex("223508c4e688890112170a0921313132323333343412064a65657020321a024a321a0a0d4045cbeb15009ef70a25000050402d64f15365")) as Mesh.FromRadio.Node).info
        assertEquals(0x11223344L, n.num)
        assertEquals("Jeep 2", n.user!!.longName)
        assertEquals("J2", n.user!!.shortName)
        assertEquals(-33.9, n.position!!.lat, 1e-7)
        assertEquals(18.4, n.position!!.lon, 1e-7)
        assertEquals(1_700_000_100L, n.lastHeard)
        assertEquals(0xCAFEBABEL, (Mesh.parseFromRadio(hex("1a0808bef5fad70c4003")) as Mesh.FromRadio.MyInfo).myNodeNum)
        assertEquals(0x12345678L, (Mesh.parseFromRadio(hex("38f8acd19101")) as Mesh.FromRadio.ConfigComplete).id)
    }

    @Test
    fun partyPacketsFitInOneLoRaMessage() {
        val key = PartyKey.create("x")
        val env = Envelope.encode(key, 1, 1, 0, PartyMessage.Text("x".repeat(200)))
        val toRadio = Mesh.sendPacket(env, Mesh.PORT_PRIVATE, 1)
        assertTrue("payload ${env.size} must be <= 233", env.size <= 233)
        assertTrue(toRadio.size < 512)
    }
}
