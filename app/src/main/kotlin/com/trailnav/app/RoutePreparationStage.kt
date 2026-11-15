package com.trailnav.app

enum class RoutePreparationStage(val wireName: String, val label: String) {
    PREPARING("preparing", "안내를 준비중"),
    START_FOUND("start-found", "시작점 잡힘"),
    DIRECTION_CONFIRMED("direction-confirmed", "경로 방향 잡힘 (안내를 시작합니다)");

    companion object {
        fun fromWire(value: String?): RoutePreparationStage =
            entries.firstOrNull { it.wireName == value } ?: PREPARING
    }
}
