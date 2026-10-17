package dev.ramiro.fakegps.sim

import dev.ramiro.fakegps.model.GeoPoint
import dev.ramiro.fakegps.model.Route
import dev.ramiro.fakegps.model.TrafficLevel
import dev.ramiro.fakegps.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SimulationEngineTest {

    /** Recta de ~1 km hacia el norte. */
    private val start = GeoPoint(-8.10, -79.03)
    private val end = GeoPoint(-8.10 + 1000.0 / 110_574.0, -79.03)
    private val route = Route(legs = listOf(listOf(start, end)), distanceMeters = 1000.0, durationSeconds = 90.0)
    private val waypoints = listOf(Waypoint(1, start), Waypoint(2, end))

    /** Corre la simulación hasta terminar y devuelve los segundos que tardó. */
    private fun run(plan: SimPlan, speedKmh: Double, level: TrafficLevel, seed: Int): Int {
        val engine = SimulationEngine(plan, Random(seed))
        var seconds = 0
        while (!engine.isFinished) {
            val snap = engine.tick(1.0, speedKmh / 3.6, level)
            assertTrue("velocidad inválida", snap.speedMps.isFinite() && snap.speedMps >= 0.0)
            seconds++
            check(seconds < 3600) { "no terminó en una hora" }
        }
        return seconds
    }

    @Test
    fun trafficMakesTripsLonger() {
        val plan = SimPlan.build(route, waypoints)
        val free = (1..20).map { run(plan, 40.0, TrafficLevel.FREE, it) }.average()
        val moderate = (1..20).map { run(plan, 40.0, TrafficLevel.MODERATE, it) }.average()
        val heavy = (1..20).map { run(plan, 40.0, TrafficLevel.HEAVY, it) }.average()
        println("1 km a 40 km/h -> libre: %.0f s, moderado: %.0f s, pesado: %.0f s".format(free, moderate, heavy))
        assertTrue(free in 85.0..130.0)
        assertTrue(moderate > free * 1.2)
        assertTrue(heavy > moderate * 1.2)
    }

    @Test
    fun brakesBeforeRedLightAndStopsBeforeIt() {
        // Semáforo a 600 m, siempre en rojo, 30 s.
        val plan = SimPlan.build(route, waypoints, listOf(listOf(600.0)), signalSeconds = 30, redProbability = 1f, random = Random(1))
        val waits = plan.phases.filterIsInstance<Phase.Wait>()
        assertEquals(1, waits.size)
        assertTrue(waits[0].label.startsWith("Semáforo en rojo"))

        val engine = SimulationEngine(plan, Random(1))
        var lastDriveSpeed = 0.0
        while (!engine.isFinished) {
            val snap = engine.tick(1.0, 40 / 3.6, TrafficLevel.FREE)
            if (snap.isWaiting) {
                // Al llegar a la espera ya venía frenando: claramente por debajo de los 40 km/h (11,1 m/s).
                assertTrue("llegó sin frenar: $lastDriveSpeed m/s", lastDriveSpeed < 7.0)
                break
            }
            lastDriveSpeed = snap.speedMps
        }
    }
}
