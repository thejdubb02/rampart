package org.rampart

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneAlertTest {
    @BeforeTest
    fun useAScratchDirectory() {
        if (System.getProperty("rampart.config.dir").isNullOrBlank()) {
            val dir = java.nio.file.Files.createTempDirectory("rampart-phone-alert-test")
            dir.toFile().deleteOnExit()
            System.setProperty("rampart.config.dir", dir.toString())
        }
    }

    @Test
    fun `gotify request carries correct url, header, and json payload`() {
        val received = ArrayBlockingQueue<Map<String, String>>(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/message") { exchange ->
            received.put(
                mapOf(
                    "path" to exchange.requestURI.path,
                    "method" to exchange.requestMethod,
                    "key" to exchange.requestHeaders.getFirst("X-Gotify-Key"),
                    "contentType" to exchange.requestHeaders.getFirst("Content-Type"),
                    "body" to exchange.requestBody.bufferedReader().readText(),
                ),
            )
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()
        try {
            Gotify.send(
                serverUrl = "http://127.0.0.1:${server.address.port}/",
                token = "secret-app-token",
                title = "Important new mail",
                message = "Boss: Project update",
                priority = 5,
            )
            val request = received.poll(5, TimeUnit.SECONDS)
            assertEquals(
                mapOf(
                    "path" to "/message",
                    "method" to "POST",
                    "key" to "secret-app-token",
                    "contentType" to "application/json",
                    "body" to """{"title":"Important new mail","message":"Boss: Project update","priority":5}""",
                ),
                request,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `gotify check validates configuration before sending`() {
        assertEquals("Give Rampart the Gotify server URL.", Gotify.check("", "token"))
        assertEquals("Give Rampart the Gotify application token.", Gotify.check("https://gotify.example.org", ""))
        assertEquals("The server URL has to use https.", Gotify.check("http://gotify.example.org", "token"))
    }

    @Test
    fun `gotify check succeeds when server responds ok`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/message") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val result = Gotify.check("http://127.0.0.1:${server.address.port}", "valid-token")
            assertNull(result)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `phone alert provider setting defaults to ntfy and allows switching to gotify`() {
        assertEquals("ntfy", Settings.phoneAlertProvider())
        Settings.setPhoneAlertProvider("gotify")
        assertEquals("gotify", Settings.phoneAlertProvider())
        Settings.setPhoneAlertProvider("ntfy")
        assertEquals("ntfy", Settings.phoneAlertProvider())
    }
}
