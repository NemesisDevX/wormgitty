package com.wormgitty.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Mining state machine on-device: transitions, conflict rejection, and the
 * auto-stop path. The engine is a test double — the shipped miner is native
 * (NativeMiner); this suite owns MinerController's logic only.
 */
@RunWith(AndroidJUnit4::class)
class MinerControllerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    class FakeEngine(private val ok: Boolean = true, private val rate: Double = 123.4) :
        MinerController.MinerEngine {
        var started = 0
        var stopped = 0
        override fun start(context: android.content.Context, cfg: MinerController.Config): Boolean {
            started++
            return ok
        }
        override fun stop() { stopped++ }
        override fun hashrate(): Double = rate
    }

    @Before
    fun setUp() {
        MinerController.resetForTest(context)
        MinerController.swapFgsForTest { _, _ -> }
    }

    @After
    fun tearDown() {
        MinerController.resetForTest(context)
        MinerController.swapEngineForTest(null)
        MinerController.swapFgsForTest(null)
    }

    private fun payload(json: String = """{"algo":"rx/0","pool":"p:443","wallet":"w","threads":2}""") =
        Json.parseToJsonElement(json).jsonObject

    @Test
    fun startTransitionsToRunningAndRejectsSecondStart() {
        val engine = FakeEngine()
        MinerController.swapEngineForTest(engine)
        val (s1, _) = MinerController.start(context, payload())
        assertEquals("executed", s1)
        assertEquals(MinerController.State.RUNNING, MinerController.state())
        assertEquals(123.4, MinerController.hashrate(), 0.01)
        val (s2, err2) = MinerController.start(context, payload())
        assertEquals("failed", s2)
        assertEquals("already_running", err2)
        assertEquals(1, engine.started)
        MinerController.stop(context)
    }

    @Test
    fun engineUnavailableLandsInError() {
        MinerController.swapEngineForTest(FakeEngine(ok = false))
        val (s, err) = MinerController.start(context, payload())
        assertEquals("failed", s)
        assertEquals("engine_unavailable", err)
        assertEquals(MinerController.State.ERROR, MinerController.state())
        // ERROR is re-startable (not latched forever).
        MinerController.swapEngineForTest(FakeEngine())
        val (s2, _) = MinerController.start(context, payload())
        assertEquals("executed", s2)
        MinerController.stop(context)
    }

    @Test
    fun stopFromRunningLandsStoppedAndEngineStops() {
        val engine = FakeEngine()
        MinerController.swapEngineForTest(engine)
        MinerController.start(context, payload())
        val (s, reason) = MinerController.stop(context, "manual")
        assertEquals("executed", s)
        assertEquals("manual", reason)
        assertEquals(MinerController.State.STOPPED, MinerController.state())
        assertEquals(1, engine.stopped)
        assertEquals(0.0, MinerController.hashrate(), 0.0)
    }

    @Test
    fun stopWhenStoppedFailsNotRunning() {
        val (s, err) = MinerController.stop(context)
        assertEquals("failed", s)
        assertEquals("not_running", err)
    }

    @Test
    fun autoStopRecordsTheReason() {
        MinerController.swapEngineForTest(FakeEngine())
        MinerController.start(context, payload())
        val (s, reason) = MinerController.stop(context, "thermal_45c")
        assertEquals("executed", s)
        assertEquals("thermal_45c", reason)
        assertEquals("thermal_45c", MinerController.stopReason())
        assertEquals(MinerController.State.STOPPED, MinerController.state())
    }

    @Test
    fun threadsDefaultIsHalfCoresCappedAtFour() {
        val cores = Runtime.getRuntime().availableProcessors()
        val expected = (cores / 2).coerceIn(1, 4)
        assertEquals(expected, MinerController.defaultThreads())
    }

    @Test
    fun payloadThreadsClampToRange() {
        val engine = FakeEngine()
        MinerController.swapEngineForTest(engine)
        MinerController.start(context, payload("""{"threads":99}"""))
        // clamped, engine still started
        assertTrue(engine.started == 1)
        MinerController.stop(context)
    }
}
