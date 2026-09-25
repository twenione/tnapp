package com.trailnav.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.trailnav.core.ProgressDirection
import kotlin.math.max

/** Compact, glanceable route-relative status view for the active navigation screen. */
class RouteRibbonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private var state: RouteRibbonState? = null
    private var preparationStage = RoutePreparationStage.PREPARING
    private var showIdleLabel = true

    init {
        backgroundPaint.color = Color.rgb(24, 28, 36)
        isFocusable = false
        contentDescription = "안내 대기 중"
    }

    fun update(newState: RouteRibbonState?) {
        state = newState
        showIdleLabel = false
        if (newState != null) preparationStage = RoutePreparationStage.DIRECTION_CONFIRMED
        invalidate()
    }

    fun updatePreparation(stage: RoutePreparationStage) {
        state = null
        showIdleLabel = false
        preparationStage = stage
        contentDescription = stage.label
        invalidate()
    }

    fun reset() {
        state = null
        preparationStage = RoutePreparationStage.PREPARING
        showIdleLabel = true
        contentDescription = "안내 대기 중"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundPaint.color)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = width - paddingRight.toFloat()
        val bottom = height - paddingBottom.toFloat()
        if (right <= left || bottom <= top) return

        val current = state
        if (current == null) {
            drawText(canvas, if (showIdleLabel) "안내 대기 중" else preparationStage.label, left + dp(12f), top + dp(40f), 22f)
            return
        }

        val centerX = (left + right) / 2f
        // Keep the route offset row and the band labels on separate baselines.
        // The previous 6dp gap caused e.g. "오른쪽 · 경로에서 21m" and
        // "진입 20m" to collide on compact screens.
        val ribbonTop = top + dp(78f)
        val ribbonBottom = bottom - dp(30f)
        val maximumBand = max(current.enterBandMeters, 1.0)
        val usableHalfWidth = ((right - left) * 0.40f).coerceAtLeast(dp(30f))
        val scale = usableHalfWidth / maximumBand.toFloat()
        val exitHalfWidth = (current.exitBandMeters.toFloat() * scale).coerceAtLeast(dp(2f))
        val enterHalfWidth = (current.enterBandMeters.toFloat() * scale).coerceAtLeast(exitHalfWidth)

        // Paint the entire ribbon from edge to edge in three discrete blue
        // stages.  Hard color changes and boundary lines keep the recovery
        // and enter thresholds visible at a glance.
        val ribbonWidth = (right - left).coerceAtLeast(1f)
        val halfWidth = ribbonWidth / 2f
        val boundedExitHalfWidth = exitHalfWidth.coerceIn(0f, halfWidth)
        val boundedEnterHalfWidth = enterHalfWidth.coerceIn(boundedExitHalfWidth, halfWidth)
        val outerBlue = Color.rgb(34, 72, 118)
        val enterBlue = Color.rgb(74, 139, 202)
        val recoveryBlue = Color.rgb(31, 96, 164)
        bandPaint.color = outerBlue
        canvas.drawRect(left, ribbonTop, right, ribbonBottom, bandPaint)
        bandPaint.color = enterBlue
        canvas.drawRect(
            centerX - boundedEnterHalfWidth,
            ribbonTop,
            centerX + boundedEnterHalfWidth,
            ribbonBottom,
            bandPaint,
        )
        bandPaint.color = recoveryBlue
        canvas.drawRect(
            centerX - boundedExitHalfWidth,
            ribbonTop,
            centerX + boundedExitHalfWidth,
            ribbonBottom,
            bandPaint,
        )
        boundaryPaint.color = Color.argb(220, 190, 225, 255)
        boundaryPaint.strokeWidth = dp(1.5f)
        canvas.drawLine(centerX - boundedEnterHalfWidth, ribbonTop, centerX - boundedEnterHalfWidth, ribbonBottom, boundaryPaint)
        canvas.drawLine(centerX + boundedEnterHalfWidth, ribbonTop, centerX + boundedEnterHalfWidth, ribbonBottom, boundaryPaint)
        canvas.drawLine(centerX - boundedExitHalfWidth, ribbonTop, centerX - boundedExitHalfWidth, ribbonBottom, boundaryPaint)
        canvas.drawLine(centerX + boundedExitHalfWidth, ribbonTop, centerX + boundedExitHalfWidth, ribbonBottom, boundaryPaint)
        axisPaint.color = Color.WHITE
        axisPaint.strokeWidth = dp(2f)
        canvas.drawLine(centerX, ribbonTop, centerX, ribbonBottom, axisPaint)

        val pointX = (centerX + current.signedOffsetMeters.toFloat() * scale)
            .coerceIn(left + dp(8f), right - dp(8f))
        val pointY = (ribbonTop + ribbonBottom) / 2f
        val accuracyRadius = (current.accuracyRadiusMeters.toFloat() * scale)
            .coerceIn(dp(5f), (right - left) * 0.46f)
        accuracyPaint.color = if (current.accuracyRadiusMeters > current.enterBandMeters) {
            Color.argb(230, 255, 193, 7)
        } else {
            Color.argb(220, 180, 220, 255)
        }
        canvas.drawCircle(pointX, pointY, accuracyRadius, accuracyPaint)
        pointPaint.color = if (current.offRoute) Color.rgb(255, 82, 82) else Color.rgb(80, 220, 120)
        canvas.drawCircle(pointX, pointY, dp(7f), pointPaint)

        val direction = when (current.direction) {
            ProgressDirection.FORWARD -> "정방향"
            ProgressDirection.REVERSE -> "역방향"
            ProgressDirection.STATIONARY -> "정지"
            ProgressDirection.UNKNOWN -> "방향 확인 중"
        }
        val status = if (current.offRoute) "경로 이탈" else "경로 위"
        drawText(canvas, "$status · $direction", left + dp(12f), top + dp(25f), 20f)
        val side = when (current.side) {
            RibbonSide.LEFT -> "왼쪽"
            RibbonSide.RIGHT -> "오른쪽"
            RibbonSide.CENTER -> "중심"
        }
        drawText(canvas, "$side · 경로에서 ${formatMeters(current.perpendicularDistanceMeters)}", left + dp(12f), top + dp(52f), 14f, Color.LTGRAY)
        drawText(canvas, "↑ 진행 방향", centerX + dp(8f), ribbonTop + dp(16f), 12f, Color.WHITE)
        drawText(canvas, "↓ 지난 경로", centerX + dp(8f), ribbonBottom - dp(4f), 12f, Color.LTGRAY)
        drawText(canvas, "진입 ${formatMeters(current.enterBandMeters)}", left + dp(10f), ribbonTop + dp(22f), 14f, Color.LTGRAY)
        drawText(canvas, "복귀 ${formatMeters(current.exitBandMeters)}", right - dp(10f), ribbonTop + dp(22f), 14f, Color.LTGRAY, Paint.Align.RIGHT)
        drawText(canvas, "목적지까지 ${formatMeters(current.remainingDistanceMeters)}", left + dp(10f), ribbonBottom - dp(12f), 14f, Color.WHITE)
        drawText(canvas, "GPS 정확도 ${formatMeters(current.accuracyRadiusMeters)}", right - dp(10f), ribbonBottom - dp(12f), 13f, Color.LTGRAY, Paint.Align.RIGHT)
        current.nextTurn?.let { next ->
            val sideText = if (next.side == RibbonTurnSide.LEFT) "왼쪽" else "오른쪽"
            drawText(
                canvas,
                "다음 꺾임 ${formatMeters(next.distanceMeters)} · $sideText",
                centerX,
                top + dp(68f),
                14f,
                Color.rgb(180, 225, 255),
                Paint.Align.CENTER,
            )
        }
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sizeSp: Float,
        color: Int = Color.WHITE,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        textPaint.textSize = sp(sizeSp)
        textPaint.color = color
        textPaint.textAlign = align
        canvas.drawText(text, x, y, textPaint)
    }

    private fun formatMeters(value: Double): String = if (value >= 100.0) "%.0fm".format(value) else "%.1fm".format(value)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
