package dev.ramiro.fakegps.sim

import dev.ramiro.fakegps.model.TrafficLevel
import kotlin.math.min
import kotlin.random.Random

/**
 * Simula el tráfico como un factor (0..1) que multiplica la velocidad elegida.
 *
 * Cada 10-30 s elige una nueva velocidad "objetivo" dentro del rango del nivel y se acerca a ella de a
 * poco. Además, al azar, hay frenazos en los que el auto queda detenido unos segundos.
 */
class TrafficModel(private val random: Random = Random.Default) {
    var factor = 1.0
        private set

    /** true mientras dura un frenazo (auto detenido por el tráfico). */
    var inJam = false
        private set

    private var target = 1.0
    private var untilChange = 0.0
    private var jamLeft = 0.0
    private var initialized = false

    fun update(dt: Double, level: TrafficLevel) {
        if (dt <= 0.0) return

        if (jamLeft > 0.0) {
            jamLeft -= dt
            inJam = jamLeft > 0.0
            factor = 0.0
            if (!inJam) untilChange = 0.0 // al salir del frenazo, nueva velocidad objetivo
            return
        }
        inJam = false

        if (level.jamsPerMinute > 0.0 && random.nextDouble() < level.jamsPerMinute * dt / 60.0) {
            jamLeft = random.nextDouble(5.0, 20.0)
            inJam = true
            factor = 0.0
            return
        }

        untilChange -= dt
        if (untilChange <= 0.0 || target !in level.minFactor..level.maxFactor) {
            target = random.nextDouble(level.minFactor, level.maxFactor)
            untilChange = random.nextDouble(10.0, 30.0)
        }
        if (!initialized) {
            factor = target
            initialized = true
        }
        // Cambio gradual (constante de ~5 s); la aceleración real la limita el motor.
        factor += (target - factor) * min(1.0, dt / 5.0)
    }
}
