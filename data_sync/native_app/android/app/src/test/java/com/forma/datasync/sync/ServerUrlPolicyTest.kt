package com.forma.datasync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlPolicyTest {
    private fun lan(value: String) = ServerUrlPolicy.normalize(value, development = false, allowLanHttp = true)

    @Test fun httpsNormalizesCaseDefaultPortAndTrailingSlash() {
        assertEquals("https://forma.example.com", lan(" HTTPS://FORMA.EXAMPLE.COM:443/ "))
        assertEquals("https://[2001:db8::1]:8443", lan("https://[2001:db8::1]:8443/"))
    }

    @Test fun lanAcceptsEachPrivateRangeBoundaryOnlyWhenExplicitlyEnabled() {
        for (host in listOf("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.31.255.255", "192.168.0.0", "192.168.255.255")) {
            val origin = "http://$host:8000"
            assertEquals(origin, lan(origin))
            assertThrows(IllegalArgumentException::class.java) { ServerUrlPolicy.normalize(origin, false, false) }
        }
        assertEquals("http://192.168.1.10", lan(" HTTP://192.168.1.10:80/ "))
    }

    @Test fun lanRejectsPublicLoopbackAndHostnames() {
        for (host in listOf("9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
            "127.0.0.1", "169.254.1.1", "0.0.0.0", "8.8.8.8", "localhost", "forma.local", "[::1]", "[fd00::1]")) {
            assertThrows(host, IllegalArgumentException::class.java) { lan("http://$host:8000") }
        }
    }

    @Test fun lanRejectsNumericShorthandLeadingZerosAndEscapedHosts() {
        for (host in listOf("10.1", "167772161", "0x0a000001", "012.0.0.1", "10.00.0.1", "10.0.0.256", "%31%30.0.0.1")) {
            assertThrows(host, IllegalArgumentException::class.java) { lan("http://$host:8000") }
        }
    }

    @Test fun lanRejectsCredentialsPathsQueriesAndFragments() {
        for (origin in listOf("http://user@192.168.1.10:8000", "http://user:secret@192.168.1.10:8000",
            "http://192.168.1.10.evil.test:8000", "http://192.168.1.10:8000/api", "http://192.168.1.10:8000?token=x",
            "http://192.168.1.10:8000#token", "http://192.168.1.10:8000?", "http://192.168.1.10:8000#")) {
            assertThrows(origin, IllegalArgumentException::class.java) { lan(origin) }
        }
    }

    @Test fun portsMustBeWithinValidRange() {
        for (port in listOf(0, 65536, 70000)) {
            assertThrows(IllegalArgumentException::class.java) { lan("http://192.168.1.10:$port") }
            assertThrows(IllegalArgumentException::class.java) { lan("https://forma.example.com:$port") }
        }
        assertEquals("http://192.168.1.10:65535", lan("http://192.168.1.10:65535"))
    }

    @Test fun developmentHttpRemainsAvailableSeparately() {
        assertEquals("http://localhost:8000", ServerUrlPolicy.normalize("http://localhost:8000", true, false))
        assertThrows(IllegalArgumentException::class.java) { ServerUrlPolicy.normalize("http://localhost:8000", false, false) }
        assertThrows(IllegalArgumentException::class.java) { ServerUrlPolicy.normalize("ftp://192.168.1.10:8000", true, true) }
    }
}
