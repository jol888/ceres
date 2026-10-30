package app.ceres.reader.source.fanqie

import org.junit.Assert.assertEquals
import org.junit.Test

class FanqieCryptoTest {

    @Test
    fun sm3MatchesKnownVectors() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            FanqieCrypto.bytesToHex(FanqieCrypto.sm3("abc".toByteArray())),
        )
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            FanqieCrypto.bytesToHex(FanqieCrypto.sm3(ByteArray(0))),
        )
    }

    @Test
    fun ladonMatchesPythonReference() {
        assertEquals(
            "AQIDBNexR26U7KPHw69Zqtgjs80QcM6T6qvGFdXhSWmnJGL5",
            FanqieCrypto.ladon(1, byteArrayOf(1, 2, 3, 4)),
        )
        assertEquals(
            "BQYHCBFTN17FffAqOeGOhbq6JLYNrHpkKC2Lk0Vx4Ix5LQsH",
            FanqieCrypto.ladon(5, byteArrayOf(5, 6, 7, 8)),
        )
    }

    @Test
    fun argusMatchesLiveVerifiedVector() {
        assertEquals(
            "8oFZDagBkk0W+1omxN0R5Us42bCuu8ki13RMtGgrUVRB9+r5Mgxm8PvFaVhLO1boEJrEuyWruAPbCMCjs6bFXhSysWnYpWYgXHpY3GgVu9o3C2vhyxvAUXteLCHvBr/kje5Bt9LZdTaJW8DGr2SCVRzkHDH4RgI/XlBSHZ31KKCh9QjRTIUljFfIGK+xM75vlVVdWab2iBc9hJso9gSdGXisI7noPrP8sImrFryqyB8Tx1LkfImMPXKS341E7ai7bGE=",
            FanqieCrypto.argus(
                query = "a=1&b=2",
                timestamp = 1_700_000_000L,
                deviceId = "3119787870260688",
                versionName = "7.3.7.33",
                deviceType = "P30",
                osVersion = "12",
                randVal = 123_456_789L,
            ),
        )
    }

    @Test
    fun syntheticUrlsRoundTrip() {
        assertEquals("fanqie://book/7143038691944959011", FanqieUrls.book("7143038691944959011"))
        assertEquals(
            "fanqie://chapter/7143038691944959011/7173216089122439711",
            FanqieUrls.chapter("7143038691944959011", "7173216089122439711"),
        )
        assertEquals("7143038691944959011", FanqieUrls.bookIdOf("fanqie://book/7143038691944959011"))
        assertEquals(
            "7143038691944959011",
            FanqieUrls.bookIdOf("fanqie://chapter/7143038691944959011/7173216089122439711"),
        )
        assertEquals("7143038691944959011", FanqieUrls.bookIdOf("https://fanqienovel.com/page/7143038691944959011"))
    }
}
