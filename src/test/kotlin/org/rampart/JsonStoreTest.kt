package org.rampart

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonStoreTest {
    @Test
    fun `concurrent writes keep both changes`() {
        val previous = System.getProperty("rampart.config.dir")
        val directory = Files.createTempDirectory("rampart-store")
        val store = JsonStore("test.json")
        val firstEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            System.setProperty("rampart.config.dir", directory.toString())
            val first = pool.submit {
                store.write {
                    firstEntered.countDown()
                    Thread.sleep(200)
                    put("first", JsonPrimitive(true))
                }
            }
            firstEntered.await(1, TimeUnit.SECONDS)
            val second = pool.submit { store.write { put("second", JsonPrimitive(true)) } }
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals(setOf("first", "second"), store.read().keys)
        } finally {
            pool.shutdownNow()
            if (previous == null) System.clearProperty("rampart.config.dir")
            else System.setProperty("rampart.config.dir", previous)
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a write failure reaches the caller`() {
        val previous = System.getProperty("rampart.config.dir")
        val file = Files.createTempFile("rampart-store", ".file")
        try {
            System.setProperty("rampart.config.dir", file.toString())
            assertFalse(JsonStore("test.json").write {})
        } finally {
            if (previous == null) System.clearProperty("rampart.config.dir")
            else System.setProperty("rampart.config.dir", previous)
            Files.deleteIfExists(file)
        }
    }
}
