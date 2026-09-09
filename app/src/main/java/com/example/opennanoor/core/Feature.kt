package com.example.opennanoor.core

/**
 * What a feature needs from the user before it can run. Everything here is
 * achievable on an unrooted phone - Shizuku is a one-time ADB command that
 * grants this app system-level permissions without root.
 */
enum class Requirement(val label: String) {
    NONE("No permission needed"),
    OVERLAY("Display over other apps"),
    ACCESSIBILITY("Accessibility service"),
    NOTIFICATIONS("Notification access"),
    SHIZUKU("Shizuku (ADB-level access)")
}

/**
 * A single toggleable capability. [available] is false for things that are
 * scoped but not yet implemented, so the roadmap stays visible in the UI.
 */
data class Feature(
    val id: String,
    val title: String,
    val description: String,
    val requirement: Requirement,
    val available: Boolean
)

object FeatureCatalog {
    const val ID_TINT = "screen_tint"
    const val ID_APP_MONITOR = "app_monitor"
    const val ID_IOS_ICONS = "ios_icons"

    val all = listOf(
        Feature(
            id = ID_IOS_ICONS,
            title = "iOS icon shape",
            description = "Reshapes every app icon into Apple's squircle, full-bleed. Applies on the OpenNaNoor home screen.",
            requirement = Requirement.NONE,
            available = true
        ),
        Feature(
            id = ID_TINT,
            title = "Screen tint",
            description = "Draws a colour layer over the whole screen. Warmth, dimming below system minimum, or a colour wash.",
            requirement = Requirement.OVERLAY,
            available = true
        ),
        Feature(
            id = ID_APP_MONITOR,
            title = "Foreground app monitor",
            description = "Reports which app is in front. The foundation for any per-app UI rule.",
            requirement = Requirement.ACCESSIBILITY,
            available = true
        ),
        Feature(
            id = "animation_speed",
            title = "Animation speed",
            description = "Speed up, slow down, or disable system transition animations.",
            requirement = Requirement.SHIZUKU,
            available = false
        ),
        Feature(
            id = "nav_mode",
            title = "Navigation style",
            description = "Switch between gesture navigation and the three-button bar.",
            requirement = Requirement.SHIZUKU,
            available = false
        ),
        Feature(
            id = "density",
            title = "Display density and font scale",
            description = "Fit more on screen, or make everything larger, beyond the sliders Settings gives you.",
            requirement = Requirement.SHIZUKU,
            available = false
        ),
        Feature(
            id = "immersive",
            title = "Hide status and nav bars",
            description = "System-wide immersive mode, per app or always on.",
            requirement = Requirement.SHIZUKU,
            available = false
        ),
        Feature(
            id = "edge_gestures",
            title = "Custom edge gestures",
            description = "Invisible swipe zones along the screen edges bound to your own actions.",
            requirement = Requirement.OVERLAY,
            available = false
        ),
        Feature(
            id = "close_behaviour",
            title = "Custom close behaviour",
            description = "Change what back and home do per app - kill it, send it home, or block the gesture.",
            requirement = Requirement.ACCESSIBILITY,
            available = false
        ),
        Feature(
            id = "quick_tiles",
            title = "Quick Settings tiles",
            description = "Add your own tiles to the notification shade for any toggle in this app.",
            requirement = Requirement.NONE,
            available = false
        )
    )
}
