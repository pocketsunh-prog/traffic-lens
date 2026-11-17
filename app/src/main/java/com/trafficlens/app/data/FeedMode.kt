package com.trafficlens.app.data

/** Where the dashboard gets its frames from. */
enum class FeedMode {
    /** Deterministic on-device simulation (no network, no camera). */
    DEMO,

    /** Companion Flask + YOLO server over MJPEG. */
    SERVER,

    /** Camera + bundled TFLite model, fully offline. */
    ON_DEVICE
}
