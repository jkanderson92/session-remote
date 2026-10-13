package com.sessionremote.app

/**
 * HERO4 Session video modes (GoPro manual, firmware 2.0+). Cross-checked against
 * published specs: 1440p ≤30, 1080p ≤60, 960p 60/50, 720p ≤100, WVGA ≤120,
 * FOV only Ultra Wide / Medium (SuperView resolutions are their own FOV). No Narrow.
 * Setting values: res = setting 2, fps = setting 3, FOV = setting 4.
 */
object SessionModes {
    private val FPS_NUMBER = mapOf(1 to 120, 2 to 100, 5 to 60, 6 to 50, 7 to 48, 8 to 30, 9 to 25)

    private data class Mode(val fps: Set<Int>, val fov: Set<Int>)

    private val TABLE = mapOf(
        7 to Mode(setOf(8, 9), setOf(0)),             // 1440p: 30, 25 — Wide
        8 to Mode(setOf(7, 8, 9), setOf(0)),          // 1080p SuperView: 48, 30, 25
        9 to Mode(setOf(5, 6, 8, 9), setOf(0, 1)),    // 1080p: 60, 50, 30, 25 — Wide, Medium
        10 to Mode(setOf(5, 6), setOf(0)),            // 960p: 60, 50
        11 to Mode(setOf(5, 6), setOf(0)),            // 720p SuperView: 60, 50
        12 to Mode(setOf(2, 5, 6, 8, 9), setOf(0, 1)),// 720p: 100, 60, 50, 30, 25 — Wide, Medium
        13 to Mode(setOf(1, 2), setOf(0)),            // WVGA: 120, 100
    )

    /** Unknown resolution → don't block anything; the camera has the final say. */
    fun fpsAllowed(res: Int?, fps: Int): Boolean = TABLE[res]?.fps?.contains(fps) ?: true
    fun fovAllowed(res: Int?, fov: Int): Boolean = TABLE[res]?.fov?.contains(fov) ?: true

    /** Closest supported frame rate for [res] to the [current] one. */
    fun bestFps(res: Int, current: Int?): Int? {
        val allowed = TABLE[res]?.fps ?: return null
        if (current != null && current in allowed) return current
        val target = FPS_NUMBER[current] ?: 30
        return allowed.minByOrNull { kotlin.math.abs((FPS_NUMBER[it] ?: 0) - target) }
    }

    fun bestFov(res: Int, current: Int?): Int? {
        val allowed = TABLE[res]?.fov ?: return null
        return if (current != null && current in allowed) current else allowed.minOrNull()
    }
}
