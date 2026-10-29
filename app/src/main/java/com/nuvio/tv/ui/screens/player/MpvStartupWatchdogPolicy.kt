package com.nuvio.tv.ui.screens.player

internal object MpvStartupWatchdogPolicy {
    const val POLL_INTERVAL_MS = 500L
    const val SURFACE_WAIT_MS = 8_000L
    const val IDLE_AFTER_LOAD_MS = 4_000L
    const val STALL_TIMEOUT_MS = 20_000L
    const val ABSOLUTE_TIMEOUT_MS = 30_000L
    const val MIN_CACHE_GROWTH_SEC = 0.25

    val surfaceWaitTicks: Int = (SURFACE_WAIT_MS / POLL_INTERVAL_MS).toInt()
    val idleTicksLimit: Int = (IDLE_AFTER_LOAD_MS / POLL_INTERVAL_MS).toInt()
    val stallTicksLimit: Int = (STALL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
    val absoluteTicksLimit: Int = (ABSOLUTE_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()

    enum class Action {
        Continue,
        SurfaceTimeout,
        IdleError,
        StallTimeout,
        AbsoluteTimeout,
    }

    data class Counters(
        val surfaceWaitTicks: Int = 0,
        val idleTicks: Int = 0,
        val stallTicks: Int = 0,
        val absoluteTicks: Int = 0,
    )

    data class Input(
        val enabled: Boolean,
        val waitingForSurface: Boolean,
        val idleActive: Boolean,
        val cacheProgressing: Boolean,
        val counters: Counters,
        val serverPrepared: Boolean = false,
    )

    data class Step(
        val action: Action,
        val counters: Counters,
    )

    fun cacheIsProgressing(previousSec: Double, currentSec: Double): Boolean {
        return currentSec > previousSec + MIN_CACHE_GROWTH_SEC
    }

    fun step(input: Input): Step {
        if (!input.enabled) {
            return Step(Action.Continue, Counters())
        }
        if (input.waitingForSurface) {
            val next = input.counters.surfaceWaitTicks + 1
            if (next >= surfaceWaitTicks) {
                return Step(Action.SurfaceTimeout, Counters())
            }
            return Step(Action.Continue, Counters(surfaceWaitTicks = next))
        }

        val absoluteLimit = if (input.serverPrepared) 180_000L / POLL_INTERVAL_MS else absoluteTicksLimit.toLong()
        val stallLimit = if (input.serverPrepared) 150_000L / POLL_INTERVAL_MS else stallTicksLimit.toLong()
        val idleLimit = if (input.serverPrepared) 150_000L / POLL_INTERVAL_MS else idleTicksLimit.toLong()
        val absolute = input.counters.absoluteTicks + 1
        if (input.idleActive) {
            val idle = input.counters.idleTicks + 1
            if (idle >= idleLimit) {
                return Step(Action.IdleError, Counters())
            }
            if (absolute >= absoluteLimit) {
                return Step(Action.AbsoluteTimeout, Counters())
            }
            return Step(
                Action.Continue,
                Counters(
                    idleTicks = idle,
                    stallTicks = input.counters.stallTicks,
                    absoluteTicks = absolute
                )
            )
        }

        if (absolute >= absoluteLimit) {
            return Step(Action.AbsoluteTimeout, Counters())
        }
        val stall = if (input.cacheProgressing) 0 else input.counters.stallTicks + 1
        if (stall >= stallLimit) {
            return Step(Action.StallTimeout, Counters())
        }
        return Step(
            Action.Continue,
            Counters(stallTicks = stall, absoluteTicks = absolute)
        )
    }
}
