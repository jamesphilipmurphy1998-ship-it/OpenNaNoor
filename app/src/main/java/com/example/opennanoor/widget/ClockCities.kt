package com.example.opennanoor.widget

/** A display label and the IANA zone id it resolves to - "New Zealand" is
 *  friendlier to pick from a list than "Pacific/Auckland", but ZoneId is
 *  what java.time actually needs to compute the current time there. */
data class ClockCity(val label: String, val zoneId: String)

/**
 * A curated list rather than every java.time.ZoneId.getAvailableZoneIds()
 * (a few hundred, mostly unrecognisable raw identifiers like
 * "America/Argentina/ComodRivadavia") - picking a widget city is meant to
 * feel like typing "new zealand", not knowing its IANA id up front.
 */
val CLOCK_CITIES = listOf(
    ClockCity("New Zealand (Auckland)", "Pacific/Auckland"),
    ClockCity("Sydney", "Australia/Sydney"),
    ClockCity("Melbourne", "Australia/Melbourne"),
    ClockCity("Perth", "Australia/Perth"),
    ClockCity("Tokyo", "Asia/Tokyo"),
    ClockCity("Seoul", "Asia/Seoul"),
    ClockCity("Beijing", "Asia/Shanghai"),
    ClockCity("Hong Kong", "Asia/Hong_Kong"),
    ClockCity("Singapore", "Asia/Singapore"),
    ClockCity("Bangkok", "Asia/Bangkok"),
    ClockCity("Mumbai", "Asia/Kolkata"),
    ClockCity("Dubai", "Asia/Dubai"),
    ClockCity("Moscow", "Europe/Moscow"),
    ClockCity("Istanbul", "Europe/Istanbul"),
    ClockCity("Athens", "Europe/Athens"),
    ClockCity("Berlin", "Europe/Berlin"),
    ClockCity("Paris", "Europe/Paris"),
    ClockCity("Madrid", "Europe/Madrid"),
    ClockCity("Rome", "Europe/Rome"),
    ClockCity("Amsterdam", "Europe/Amsterdam"),
    ClockCity("London", "Europe/London"),
    ClockCity("Lisbon", "Europe/Lisbon"),
    ClockCity("Reykjavik", "Atlantic/Reykjavik"),
    ClockCity("Sao Paulo", "America/Sao_Paulo"),
    ClockCity("Buenos Aires", "America/Argentina/Buenos_Aires"),
    ClockCity("New York", "America/New_York"),
    ClockCity("Toronto", "America/Toronto"),
    ClockCity("Chicago", "America/Chicago"),
    ClockCity("Denver", "America/Denver"),
    ClockCity("Los Angeles", "America/Los_Angeles"),
    ClockCity("Vancouver", "America/Vancouver"),
    ClockCity("Anchorage", "America/Anchorage"),
    ClockCity("Honolulu", "Pacific/Honolulu"),
    ClockCity("Cairo", "Africa/Cairo"),
    ClockCity("Johannesburg", "Africa/Johannesburg"),
    ClockCity("Lagos", "Africa/Lagos"),
    ClockCity("Nairobi", "Africa/Nairobi")
)
