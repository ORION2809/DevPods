package com.openclaw.relay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayViewModelImportTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        resetRelayState()
        context = TestContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        resetRelayState()
    }

    @Test
    fun `pairing page import verifies the bridge and records healthy status`() {
        val server = MockWebServer()
        server.start()
        val bridgeBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bridgeBaseUrl": "$bridgeBaseUrl",
                      "relayToken": "relay-secret",
                      "workspace": "current_repo"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "brainMode": "local",
                      "openclawTransport": "local-cli",
                      "openclawRewritePolicy": "rewrite-only",
                      "openclawReady": true
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val viewModel = RelayViewModel()

            viewModel.importPairingUri(context, server.url("/pairing").toString())

            val pairingRequest = server.takeRequest(3, TimeUnit.SECONDS)
            val healthRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(pairingRequest)
            assertNotNull(healthRequest)

            awaitState {
                viewModel.state.value.bridgeStatus.startsWith("Healthy")
            }

            val state = viewModel.state.value
            assertEquals(bridgeBaseUrl, state.config.bridgeBaseUrl)
            assertEquals("relay-secret", state.config.relayToken)
            assertEquals("current_repo", state.config.workspace)
            assertEquals("", state.pendingPairingUri)
            assertTrue(state.bridgeStatus.startsWith("Healthy"))
            assertNull(state.errorMessage)
            assertEquals("/pairing", pairingRequest?.path)
            assertEquals("application/json", pairingRequest?.getHeader("Accept"))
            assertEquals("/health", healthRequest?.path)
            assertEquals("Bearer relay-secret", healthRequest?.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `direct pairing uri import reuses the existing import path and verifies health`() {
        val server = MockWebServer()
        server.start()
        val bridgeBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "brainMode": "local",
                      "openclawTransport": "local-cli",
                      "openclawRewritePolicy": "rewrite-only",
                      "openclawReady": true
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val viewModel = RelayViewModel()
            val pairingUri = "devpods://pair?bridgeBaseUrl=${java.net.URLEncoder.encode(bridgeBaseUrl, Charsets.UTF_8.name())}&relayToken=relay-secret&workspace=current_repo"

            viewModel.importPairingUri(context, pairingUri)

            val healthRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(healthRequest)

            awaitState {
                viewModel.state.value.bridgeStatus.startsWith("Healthy")
            }

            val state = viewModel.state.value
            assertEquals(bridgeBaseUrl, state.config.bridgeBaseUrl)
            assertEquals("relay-secret", state.config.relayToken)
            assertEquals("current_repo", state.config.workspace)
            assertEquals("", state.pendingPairingUri)
            assertTrue(state.bridgeStatus.startsWith("Healthy"))
            assertNull(state.errorMessage)
            assertEquals("/health", healthRequest?.path)
            assertEquals("Bearer relay-secret", healthRequest?.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `direct pairing uri with pairing code exchanges the code before verifying health`() {
        val server = MockWebServer()
        server.start()
        val bridgeBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "relayToken": "relay-secret"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "brainMode": "local",
                      "openclawTransport": "local-cli",
                      "openclawRewritePolicy": "rewrite-only",
                      "openclawReady": true
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val viewModel = RelayViewModel()
            val pairingUri = "devpods://pair?bridgeBaseUrl=${java.net.URLEncoder.encode(bridgeBaseUrl, Charsets.UTF_8.name())}&pairingCode=ABC123&workspace=current_repo"

            viewModel.importPairingUri(context, pairingUri)

            val verifyRequest = server.takeRequest(3, TimeUnit.SECONDS)
            val healthRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(verifyRequest)
            assertNotNull(healthRequest)

            awaitState {
                viewModel.state.value.bridgeStatus.startsWith("Healthy")
            }

            val state = viewModel.state.value
            assertEquals(bridgeBaseUrl, state.config.bridgeBaseUrl)
            assertEquals("relay-secret", state.config.relayToken)
            assertEquals("current_repo", state.config.workspace)
            assertEquals("/pairing/verify", verifyRequest?.path)
            assertEquals("POST", verifyRequest?.method)
            assertTrue(verifyRequest?.body?.readUtf8()?.contains("ABC123") == true)
            assertEquals("/health", healthRequest?.path)
            assertEquals("Bearer relay-secret", healthRequest?.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `latest pairing verification wins when imports happen back to back`() {
        val slowServer = MockWebServer()
        val fastServer = MockWebServer()
        slowServer.start()
        fastServer.start()
        val slowBridgeBaseUrl = slowServer.url("/").toString().trimEnd('/')
        val fastBridgeBaseUrl = fastServer.url("/").toString().trimEnd('/')

        val releaseSlowHealthResponse = CountDownLatch(1)
        slowServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                releaseSlowHealthResponse.await(3, TimeUnit.SECONDS)
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "ok": true,
                          "brainMode": "slow",
                          "openclawTransport": "local-cli",
                          "openclawRewritePolicy": "rewrite-only",
                          "openclawReady": true
                        }
                        """.trimIndent(),
                    )
            }
        }
        fastServer.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "brainMode": "fast",
                      "openclawTransport": "local-cli",
                      "openclawRewritePolicy": "rewrite-only",
                      "openclawReady": true
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val viewModel = RelayViewModel()
            val slowPairingUri = "devpods://pair?bridgeBaseUrl=${java.net.URLEncoder.encode(slowBridgeBaseUrl, Charsets.UTF_8.name())}&relayToken=slow-secret&workspace=current_repo"
            val fastPairingUri = "devpods://pair?bridgeBaseUrl=${java.net.URLEncoder.encode(fastBridgeBaseUrl, Charsets.UTF_8.name())}&relayToken=fast-secret&workspace=current_repo"

            viewModel.importPairingUri(context, slowPairingUri)
            viewModel.importPairingUri(context, fastPairingUri)

            assertNotNull(slowServer.takeRequest(3, TimeUnit.SECONDS))
            assertNotNull(fastServer.takeRequest(3, TimeUnit.SECONDS))

            awaitState {
                viewModel.state.value.bridgeStatus.contains("brain=fast")
            }

            releaseSlowHealthResponse.countDown()
            assertStateRemains(500) {
                viewModel.state.value.bridgeStatus.contains("brain=fast")
            }

            val state = viewModel.state.value
            assertEquals(fastBridgeBaseUrl, state.config.bridgeBaseUrl)
            assertEquals("fast-secret", state.config.relayToken)
            assertTrue(state.bridgeStatus.contains("brain=fast"))
        } finally {
            slowServer.shutdown()
            fastServer.shutdown()
        }
    }

    @Test
    fun `pairing page import clears stale errors and exposes importing state`() {
        val server = MockWebServer()
        server.start()
        val bridgeBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setBodyDelay(400, TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bridgeBaseUrl": "$bridgeBaseUrl",
                      "relayToken": "relay-secret",
                      "workspace": "current_repo"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "ok": true,
                      "brainMode": "local",
                      "openclawTransport": "local-cli",
                      "openclawRewritePolicy": "rewrite-only",
                      "openclawReady": true
                    }
                    """.trimIndent(),
                ),
        )

        try {
            val viewModel = RelayViewModel()
            RelayStateStore.setError("Old pairing error")

            viewModel.importPairingUri(context, server.url("/pairing").toString())

            awaitState {
                viewModel.state.value.isImportingPairing
            }

            val importingState = viewModel.state.value
            assertTrue(importingState.isImportingPairing)
            assertNull(importingState.errorMessage)

            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            awaitState {
                viewModel.state.value.bridgeStatus.startsWith("Healthy")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pairing page import keeps pairing but surfaces verification failures`() {
        val server = MockWebServer()
        server.start()
        val bridgeBaseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "bridgeBaseUrl": "$bridgeBaseUrl",
                      "relayToken": "relay-secret",
                      "workspace": "current_repo"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/plain")
                .setBody("bridge unavailable"),
        )

        try {
            val viewModel = RelayViewModel()

            viewModel.importPairingUri(context, server.url("/pairing").toString())

            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))

            awaitState {
                !viewModel.state.value.errorMessage.isNullOrBlank()
            }

            val state = viewModel.state.value
            assertEquals(bridgeBaseUrl, state.config.bridgeBaseUrl)
            assertEquals("", state.pendingPairingUri)
            assertTrue(state.bridgeStatus.startsWith("Pairing saved"))
            assertTrue(state.errorMessage?.contains("could not reach") == true)
        } finally {
            server.shutdown()
        }
    }

    private fun awaitState(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (predicate()) {
                return
            }
            Thread.sleep(20)
        }

        throw AssertionError("Timed out waiting for relay state to update")
    }

    private fun assertStateRemains(durationMs: Long, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs)
        while (System.nanoTime() < deadline) {
            if (!predicate()) {
                throw AssertionError("Relay state changed unexpectedly during stability check")
            }
            Thread.sleep(20)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetRelayState() {
        val field = RelayStateStore::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        val stateFlow = field.get(RelayStateStore) as MutableStateFlow<RelayUiState>
        stateFlow.value = RelayUiState()
    }
}

private class TestContext : ContextWrapper(null) {
    private val sharedPreferences = mutableMapOf<String, SharedPreferences>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        requireNotNull(name)
        return sharedPreferences.getOrPut(name) { InMemorySharedPreferences() }
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        ((values[key] as Set<String>?) ?: defValues)?.toMutableSet()

    override fun getInt(key: String?, defValue: Int): Int = values[key] as Int? ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as Float? ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
    }

    private class Editor(
        private val values: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val staged = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            staged[key.orEmpty()] = RemovedValue
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                values.clear()
                clearRequested = false
            }

            for ((key, value) in staged) {
                if (value === RemovedValue) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
            }
            staged.clear()
        }
    }
}

private object RemovedValue