package com.thorpad.app

/**
 * The numbers that decide how stick aiming feels.
 *
 * Separate from the control layout because none of them can be reasoned out —
 * every one has to be found against real gameplay, and they change together
 * while the game is running. Kept free of Android types so the engine they
 * drive stays testable off-device.
 */
data class AimSettings(
    /** Fraction of full deflection below which the stick is treated as resting. */
    val deadzone: Float = 0.12f,

    /** 1 is linear; higher makes a small tilt much slower than a big one. */
    val curve: Float = 2.0f,

    /** Screen widths per second at full tilt. */
    val maxSpeed: Float = 2.2f,

    val invertY: Boolean = false,

    /**
     * The patch of screen the finger may use, in fractions of it.
     *
     * The whole screen by default, because travel is the whole problem: a
     * stroke ends at the region edge, so anything smaller throws away swing.
     */
    val regionLeft: Float = 0f,
    val regionTop: Float = 0f,
    val regionRight: Float = 1f,
    val regionBottom: Float = 1f,

    /**
     * 0 starts every stroke in the middle of the region; 1 starts it hard
     * against the far side, facing the way the stick points — worth nearly
     * double the travel per stroke.
     */
    val anchorBias: Float = 0.85f,

    /** How long the finger stays down after the stick centres, in ms. */
    val holdMs: Int = 220,

    val pollHz: Int = 120,
)
