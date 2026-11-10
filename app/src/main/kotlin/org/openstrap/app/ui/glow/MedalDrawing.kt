package org.openstrap.app.ui.glow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import org.openstrap.app.ui.glow.draw.GlowPalette
import org.openstrap.app.ui.glow.draw.GlowStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * How a medal ([Medal]) is drawn: its outline for each group, the face lit from below as a glow card is, the laurel and
 * the rosette's edge, and the line pictures. On a frame [FRAME] units across, the pictures on [EMBLEM_FRAME].
 */

/** A medal's outline: the group's shape, with where its face starts and ends on the frame, and its picture's room. */
internal enum class MedalShape(val top: Float, val bottom: Float, val content: Float) {
    /** Firsts: a round medal. */
    Round(ROUND_TOP, ROUND_BOTTOM, ROUND_CONTENT),

    /** Milestones: a hexagon, a count reached. */
    Hex(HEX_TOP, HEX_BOTTOM, HEX_CONTENT),

    /** The weekly goal: a round medal in a laurel, weeks kept up. */
    Laurel(LAUREL_TOP, LAUREL_BOTTOM, LAUREL_CONTENT),

    /** Something new: a soft square. */
    Soft(SOFT_TOP, SOFT_BOTTOM, SOFT_CONTENT),

    /** A personal best: a rosette, its edge scalloped. */
    Rosette(ROSETTE_TOP, ROSETTE_BOTTOM, ROSETTE_CONTENT),
}

/** A medal's tones from its ink, by the glow cards' formula: the light at the foot, the body, the core, the picture. */
internal class MedalTones(ink: Color) {
    private val palette = GlowPalette(GlowStyle(color = ink))
    val light: Color = palette.light
    val body: Color = palette.mid
    val core: Color = palette.core
    val mark: Color = lerp(Color.White, palette.light, MARK_TINT)
}

/** The medal on the frame: the laurel or rosette's edge behind, then the face, lit or quiet, and its inner ring. */
internal fun DrawScope.drawFrame(shape: MedalShape, tones: MedalTones, locked: Boolean, lit: Float) {
    val face = faceOf(shape)
    when (shape) {
        MedalShape.Laurel -> drawLaurel(if (locked) LockedRim else tones.light)
        MedalShape.Rosette -> drawRosetteEdge(tones, locked)
        else -> Unit
    }
    if (locked) {
        drawPath(face, LockedFill)
        drawPath(face, LockedRim, style = Stroke(RIM_LINE))
    } else {
        drawLitFace(shape, face, tones, lit.coerceIn(0f, 1f))
    }
    // The second ring just inside the rim, as a struck medal has.
    scale(INNER_RING, pivot = Offset(MID, MID)) {
        drawPath(face, if (locked) LockedInner else tones.light.copy(alpha = INNER_ALPHA), style = Stroke(INNER_LINE / INNER_RING))
    }
}

/**
 * [e] filling this scope, in [color]: its filled parts, then its lines. The lines grow with the picture up to
 * [EMBLEM_LINE_MAX], so a large medal (a sheet, the best card) keeps fine line art rather than heavy strokes.
 */
internal fun DrawScope.drawEmblem(e: Emblem, color: Color) {
    val k = size.width / EMBLEM_FRAME
    val line = Stroke(minOf(EMBLEM_LINE_WIDTH, EMBLEM_LINE_MAX.toPx() / k), cap = StrokeCap.Round, join = StrokeJoin.Round)
    scale(k, pivot = Offset.Zero) {
        e.fill?.let { drawPath(it, color) }
        e.stroke?.let { drawPath(it, color, style = line) }
    }
}

/** SVG path data as a path. */
internal fun parsePath(d: String): Path = PathParser().parsePathString(d).toPath()

/**
 * The face lit from below, as a glow card is: the light colour at the foot, melting into the body and on into the dark
 * core at the top, a soft sheen at the upper left, and the hairline bright at the top (its gloss) and lit at the foot.
 */
private fun DrawScope.drawLitFace(shape: MedalShape, face: Path, tones: MedalTones, lit: Float) {
    val height = shape.bottom - shape.top
    drawPath(
        face,
        Brush.radialGradient(
            0f to lerp(tones.body, tones.light, lit),
            LIGHT_REACH to tones.body,
            CORE_FROM to tones.core,
            center = Offset(MID, shape.bottom + LIGHT_BELOW),
            radius = height + LIGHT_BELOW,
        ),
    )
    drawPath(
        face,
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = SHEEN_ALPHA), Color.Transparent),
            center = Offset(SHEEN_X, shape.top + height * SHEEN_Y),
            radius = height * SHEEN_RADIUS,
        ),
    )
    drawPath(
        face,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = GLOSS_ALPHA), 1f to tones.light.copy(alpha = lit),
            startY = shape.top, endY = shape.bottom,
        ),
        style = Stroke(RIM_LINE),
    )
}

/** The laurel round the weekly goal's medal: a stem up each side with its leaves, smaller toward the top. */
private fun DrawScope.drawLaurel(color: Color) {
    val stem = Stroke(STEM_LINE, cap = StrokeCap.Round)
    val ring = Offset(MID - LAUREL_RADIUS, MID - LAUREL_RADIUS)
    val across = Size(LAUREL_RADIUS * 2, LAUREL_RADIUS * 2)
    drawArc(color, DOWN + LAUREL_FROM - STEM_LEAD, LAUREL_SPAN, false, ring, across, style = stem)
    drawArc(color, DOWN - LAUREL_FROM + STEM_LEAD - LAUREL_SPAN, LAUREL_SPAN, false, ring, across, style = stem)
    for (side in SIDES) {
        for (i in 0 until LEAVES) {
            val degrees = DOWN + side * (LAUREL_FROM + i * LAUREL_STEP)
            val radians = degrees * PI / HALF_TURN
            val c = Offset(MID + LAUREL_RADIUS * cos(radians).toFloat(), MID + LAUREL_RADIUS * sin(radians).toFloat())
            val k = 1f - i * LEAF_SHRINK
            rotate(degrees + QUARTER_TURN + side * LEAF_TILT, pivot = c) {
                drawOval(color, Offset(c.x - LEAF_LENGTH * k / 2, c.y - LEAF_WIDTH * k / 2), Size(LEAF_LENGTH * k, LEAF_WIDTH * k))
            }
        }
    }
}

/** A best's rosette: the scalloped edge round its face, a step darker than the face's body. */
private fun DrawScope.drawRosetteEdge(tones: MedalTones, locked: Boolean) {
    if (locked) {
        drawPath(RosetteEdge, LockedFill)
        drawPath(RosetteEdge, LockedRim, style = Stroke(RIM_LINE))
    } else {
        drawPath(RosetteEdge, lerp(tones.core, tones.body, EDGE_BODY))
        drawPath(RosetteEdge, tones.light.copy(alpha = EDGE_RIM), style = Stroke(RIM_LINE))
    }
}

private fun faceOf(shape: MedalShape): Path = when (shape) {
    MedalShape.Round -> RoundFace
    MedalShape.Hex -> HexFace
    MedalShape.Laurel -> LaurelFace
    MedalShape.Soft -> SoftFace
    MedalShape.Rosette -> RosetteFace
}

private fun circle(radius: Float): Path = Path().apply { addOval(Rect(Offset(MID, MID), radius)) }

/** The rosette's outline: a circle whose radius rises and falls [ROSETTE_POINTS] times round. */
private fun rosette(): Path = Path().apply {
    for (i in 0..ROSETTE_STEPS) {
        val t = 2 * PI * i / ROSETTE_STEPS
        val r = ROSETTE_RADIUS + ROSETTE_WAVE * cos(ROSETTE_POINTS * t)
        val x = MID + (r * cos(t)).toFloat()
        val y = MID + (r * sin(t)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val RoundFace: Path by lazy { circle(ROUND_RADIUS) }
private val LaurelFace: Path by lazy { circle(LAUREL_FACE_RADIUS) }
private val RosetteFace: Path by lazy { circle(ROSETTE_FACE_RADIUS) }
private val RosetteEdge: Path by lazy { rosette() }

/** A hexagon standing on a point, its corners rounded. */
private val HexFace: Path by lazy {
    parsePath(
        "M55.2 6 L85.5 23.5 Q90.7 26.5 90.7 32.5 V67.5 Q90.7 73.5 85.5 76.5 L55.2 94 Q50 97 44.8 94 L14.5 76.5 " +
            "Q9.3 73.5 9.3 67.5 V32.5 Q9.3 26.5 14.5 23.5 L44.8 6 Q50 3 55.2 6 Z",
    )
}

/** A square with its sides bowed out, between a square and a circle. */
private val SoftFace: Path by lazy {
    parsePath("M50 5 C82 5 95 18 95 50 C95 82 82 95 50 95 C18 95 5 82 5 50 C5 18 18 5 50 5 Z")
}

/** A medal still to earn: a faint face and hairline, its inner ring fainter. */
private val LockedFill = Color.White.copy(alpha = 0.045f)
private val LockedRim = Color.White.copy(alpha = 0.16f)
private val LockedInner = Color.White.copy(alpha = 0.07f)

/** The laurel's two sides: right (toward lower angles from the foot) and left. */
private val SIDES = intArrayOf(-1, 1)

/** The frame a medal is drawn on, units across, and its middle. */
internal const val FRAME = 100f
private const val MID = 50f
private const val EMBLEM_FRAME = 24f
private const val EMBLEM_LINE_WIDTH = 1.6f

/** The picture's lines are never drawn thicker than this, however large the medal. */
private val EMBLEM_LINE_MAX = 2.4.dp

private const val ROUND_RADIUS = 46f
private const val ROUND_TOP = MID - ROUND_RADIUS
private const val ROUND_BOTTOM = MID + ROUND_RADIUS
private const val ROUND_CONTENT = 0.8f
private const val HEX_TOP = 3f
private const val HEX_BOTTOM = 97f
private const val HEX_CONTENT = 0.74f
private const val SOFT_TOP = 5f
private const val SOFT_BOTTOM = 95f
private const val SOFT_CONTENT = 0.78f
private const val LAUREL_FACE_RADIUS = 34f
private const val LAUREL_TOP = MID - LAUREL_FACE_RADIUS
private const val LAUREL_BOTTOM = MID + LAUREL_FACE_RADIUS
private const val LAUREL_CONTENT = 0.6f
private const val ROSETTE_FACE_RADIUS = 36f
private const val ROSETTE_TOP = MID - ROSETTE_FACE_RADIUS
private const val ROSETTE_BOTTOM = MID + ROSETTE_FACE_RADIUS
private const val ROSETTE_CONTENT = 0.58f

/** The rosette's edge: its mean radius, how far it rises and falls, how many times round, and how finely drawn. */
private const val ROSETTE_RADIUS = 46.5
private const val ROSETTE_WAVE = 2.6
private const val ROSETTE_POINTS = 16
private const val ROSETTE_STEPS = 160
private const val EDGE_BODY = 0.55f
private const val EDGE_RIM = 0.7f

/** The laurel: its radius, the leaves from the foot up each side, their size and lean, and the stems under them. */
private const val LAUREL_RADIUS = 42.5f
private const val LEAVES = 6
private const val LAUREL_FROM = 26f
private const val LAUREL_STEP = 24f
private const val LAUREL_SPAN = 132f
private const val STEM_LEAD = 8f
private const val STEM_LINE = 1.1f
private const val LEAF_LENGTH = 10f
private const val LEAF_WIDTH = 4.4f
private const val LEAF_SHRINK = 0.07f
private const val LEAF_TILT = 22f
private const val DOWN = 90f
private const val QUARTER_TURN = 90f
private const val HALF_TURN = 180.0

/**
 * The light: where it melts into the body and the core, and how far below the face it comes from. It reaches only the
 * foot, so a label under the picture sits in the body, as text sits in the dark on a glow card (spec 2.1).
 */
private const val LIGHT_REACH = 0.27f
private const val CORE_FROM = 0.92f
private const val LIGHT_BELOW = 6f
private const val SHEEN_ALPHA = 0.09f
private const val SHEEN_X = 36f
private const val SHEEN_Y = 0.26f
private const val SHEEN_RADIUS = 0.42f
private const val GLOSS_ALPHA = 0.55f
private const val RIM_LINE = 1.6f
private const val INNER_RING = 0.87f
private const val INNER_LINE = 1f
private const val INNER_ALPHA = 0.32f

/** How far the picture is from white toward the light colour: near white, with the medal's hue. */
private const val MARK_TINT = 0.18f
