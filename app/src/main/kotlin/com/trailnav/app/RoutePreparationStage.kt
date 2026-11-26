package com.trailnav.app

import androidx.annotation.StringRes

enum class RoutePreparationStage(val wireName: String, @param:StringRes val labelRes: Int) {
    PREPARING("preparing", R.string.route_preparation_preparing),
    START_FOUND("start-found", R.string.route_preparation_start_found),
    DIRECTION_CONFIRMED("direction-confirmed", R.string.route_preparation_direction_confirmed);

    companion object {
        fun fromWire(value: String?): RoutePreparationStage =
            entries.firstOrNull { it.wireName == value } ?: PREPARING
    }
}
