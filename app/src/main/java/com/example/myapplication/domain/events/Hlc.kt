package com.example.myapplication.domain.events

/**
 * Гибридные логические часы (EVENT_SOURCING_SPEC.md, раздел 2.2).
 * Порядок: wall, затем counter, затем deviceId. Одинаковый на всех устройствах.
 */
data class Hlc(
    val wall: Long,
    val counter: Int,
    val deviceId: String,
) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int {
        wall.compareTo(other.wall).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return deviceId.compareTo(other.deviceId)
    }
}

/**
 * Состояние часов устройства. Хранится в sync_state (hlcWall, hlcCounter).
 * [receive] учитывает удалённую метку, чтобы последующие локальные события были больше неё.
 */
class HlcClock(
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private var lastWall: Long = 0L,
    private var lastCounter: Int = 0,
) {

    /** Метка для нового локального события. */
    @Synchronized
    fun tick(): Hlc {
        val physical = now()
        if (physical > lastWall) {
            lastWall = physical
            lastCounter = 0
        } else {
            lastCounter += 1
        }
        return Hlc(lastWall, lastCounter, deviceId)
    }

    /** Учитывает метку удалённого события, чтобы следующая локальная метка была больше неё. */
    @Synchronized
    fun receive(remote: Hlc) {
        val physical = now()
        val maxWall = maxOf(physical, lastWall, remote.wall)
        lastCounter = when {
            maxWall == lastWall && maxWall == remote.wall -> maxOf(lastCounter, remote.counter) + 1
            maxWall == lastWall -> lastCounter + 1
            maxWall == remote.wall -> remote.counter + 1
            else -> 0
        }
        lastWall = maxWall
    }

    @Synchronized
    fun state(): Pair<Long, Int> = lastWall to lastCounter
}
