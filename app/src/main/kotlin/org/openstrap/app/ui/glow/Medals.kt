package org.openstrap.app.ui.glow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.openstrap.app.data.Rewards
import org.openstrap.app.presentation.RewardsCopy
import org.openstrap.app.ui.components.Motion
import org.openstrap.app.ui.components.rememberAnimationsOn

/*
 * The badges and personal bests as struck medals, in the glow language: the face lit from below like a glow card,
 * melting from the light colour into the body and the dark core, with the 1 dp hairline and its gloss (spec 2.1). The
 * tones come from the ink by the cards' own formula ([MedalTones]), so a badge reads as the same light as a card of
 * its colour. Each group has its own outline as well as its colour, so a collection reads at a glance, and each badge
 * its own line picture. A medal still to earn is drawn quiet: no light, the picture faint. How it's drawn is in
 * MedalDrawing.kt.
 */

/** A line picture on a 24 unit frame: [strokeData] drawn as lines, [fillData] filled, both SVG path data. */
@Immutable
internal class Emblem(private val strokeData: String?, private val fillData: String? = null) {
    val stroke: Path? by lazy { strokeData?.let(::parsePath) }
    val fill: Path? by lazy { fillData?.let(::parsePath) }
}

/** What a medal shows: its [shape] and [ink], the line picture in its middle and the few characters under it. */
@Immutable
internal class MedalLook(val shape: MedalShape, val ink: Color, val emblem: Emblem?, val label: String?)

/** The line pictures, one for each kind of badge and best. */
internal object Emblems {
    /** The first workout: the sun coming up. */
    val Sunrise = Emblem(
        "M3 17 H21 M7.5 17 A4.5 4.5 0 0 1 16.5 17 M12 7.5 V9.5 M6.3 10.3 L7.7 11.7 M17.7 10.3 L16.3 11.7 " +
            "M3.5 13.5 H5.5 M18.5 13.5 H20.5 M8.5 20 H15.5",
    )

    /** Steps: two footprints, one ahead of the other. */
    val Steps = Emblem(
        null,
        "M8 3.5 C9.9 3.5 10.7 5.5 10.5 7.9 C10.3 10.3 9.5 12 8 12 C6.5 12 5.7 10.3 5.5 7.9 C5.3 5.5 6.1 3.5 8 3.5 Z " +
            "M8 13.6 C9.1 13.6 9.7 14.4 9.7 15.4 C9.7 16.4 9.1 17.1 8 17.1 C6.9 17.1 6.3 16.4 6.3 15.4 C6.3 14.4 6.9 13.6 8 13.6 Z " +
            "M16 7 C17.9 7 18.7 9 18.5 11.4 C18.3 13.8 17.5 15.5 16 15.5 C14.5 15.5 13.7 13.8 13.5 11.4 C13.3 9 14.1 7 16 7 Z " +
            "M16 17.1 C17.1 17.1 17.7 17.9 17.7 18.9 C17.7 19.9 17.1 20.6 16 20.6 C14.9 20.6 14.3 19.9 14.3 18.9 " +
            "C14.3 17.9 14.9 17.1 16 17.1 Z",
    )

    /** The workout counts as ranks, one chevron more at each, and a star at the last. */
    val RankOne = Emblem("M6.5 13.5 L12 9.5 L17.5 13.5")
    val RankTwo = Emblem("M6.5 11 L12 7 L17.5 11 M6.5 16 L12 12 L17.5 16")
    val RankThree = Emblem("M6.5 8.5 L12 4.5 L17.5 8.5 M6.5 13.5 L12 9.5 L17.5 13.5 M6.5 18.5 L12 14.5 L17.5 18.5")
    val Star = Emblem("M12 3.8 L14.5 9 L20.2 9.7 L16 13.6 L17.1 19.2 L12 16.4 L6.9 19.2 L8 13.6 L3.8 9.7 L9.5 9 Z")

    /** Distance: a road winding off to a flag. */
    val Road = Emblem("M7 21 C7 16.5 17 16.5 17 12 C17 8.5 12.5 7.5 12.5 5 M12.5 5 V1.8 M12.5 2 H16.5 L15.5 3.3 L16.5 4.6 H12.5")

    /** The first weekly goal: a tick. */
    val Check = Emblem("M5.5 12.5 L10 17 L18.5 7.5")

    /** The first run: a runner in stride. */
    val Runner = Emblem(
        "M14 7.6 L11.6 13 M13.7 8.4 L16.6 10.6 L18.6 9.2 M13.7 8.4 L10.6 9.6 L8.6 11.8 M11.6 13 L14.6 15.6 L13.6 20.4 " +
            "M11.6 13 L9.6 16.6 L6.2 16.2",
        "M13.1 4.5 A1.9 1.9 0 1 0 16.9 4.5 A1.9 1.9 0 1 0 13.1 4.5 Z",
    )

    /** A distance run in one go: the chequered flag. */
    val Finish = Emblem("M6 21 V3.5 M6 4 H18 V12.5 H6", "M6 4 H10 V8.25 H6 Z M14 4 H18 V8.25 H14 Z M10 8.25 H14 V12.5 H10 Z")

    /** Indoors: a roof over a beating heart's line. */
    val Indoor = Emblem("M3.5 11 L12 4 L20.5 11 M5.5 9.5 V20 H18.5 V9.5 M7.5 15 H9.8 L11 12.4 L13 17.6 L14.2 15 H16.5")

    /** Strength: a barbell with its plates. */
    val Barbell = Emblem("M2.5 12 H21.5 M5.5 8.5 V15.5 M8 6.5 V17.5 M16 6.5 V17.5 M18.5 8.5 V15.5")

    /** Tabata: a stopwatch with its work slice lit. */
    val Interval = Emblem(
        "M12 21 A7.5 7.5 0 1 0 12 6 A7.5 7.5 0 1 0 12 21 Z M10 2.5 H14 M12 2.5 V6",
        "M12 13.5 L12 6 A7.5 7.5 0 0 1 18.5 9.75 Z",
    )

    /** Run intervals: laps of a track. */
    val Track = Emblem(
        "M8 6 H16 A6 6 0 0 1 16 18 H8 A6 6 0 0 1 8 6 Z M8.5 9.5 H15.5 A2.5 2.5 0 0 1 15.5 14.5 H8.5 " +
            "A2.5 2.5 0 0 1 8.5 9.5 Z M12 6 V9.5",
    )

    /** Yoga and calm: a lotus. */
    val Lotus = Emblem(
        "M12 5 C14.6 8 14.6 13 12 16.5 C9.4 13 9.4 8 12 5 Z M12 16.5 C8.6 16.5 5.2 14.4 4.2 10.5 C7.8 10.5 10.6 12.6 12 16.5 Z " +
            "M12 16.5 C15.4 16.5 18.8 14.4 19.8 10.5 C16.2 10.5 13.4 12.6 12 16.5 Z M6.5 19.5 H17.5",
    )

    /** Games and sports: a ball with its seams. */
    val Ball = Emblem(
        "M12 20 A8 8 0 1 0 12 4 A8 8 0 1 0 12 20 Z M4 12 H20 M12 4 V20 M6.4 6.4 C8.9 9.4 8.9 14.6 6.4 17.6 " +
            "M17.6 6.4 C15.1 9.4 15.1 14.6 17.6 17.6",
    )

    /** The longest workout: an hourglass, its sand fallen. */
    val Hourglass = Emblem(
        "M6.5 3 H17.5 M6.5 21 H17.5 M8 3 C8 8 12 9.5 12 12 C12 14.5 8 16 8 21 M16 3 C16 8 12 9.5 12 12 C12 14.5 16 16 16 21",
        "M9.4 19.6 C10.2 17.4 11.5 16.6 12 16.6 C12.5 16.6 13.8 17.4 14.6 19.6 Z M10.2 7 H13.8 C13.4 8.4 12.6 9.3 12 9.8 " +
            "C11.4 9.3 10.6 8.4 10.2 7 Z",
    )

    /** The fastest stretches: a stopwatch. */
    val Stopwatch = Emblem(
        "M12 21 A7.5 7.5 0 1 0 12 6 A7.5 7.5 0 1 0 12 21 Z M10 2.5 H14 M12 2.5 V6 M17.6 7.2 L19.2 5.6 M12 13.5 L15 10.5",
    )
}

/** Each badge's line picture, by id. The weekly runs have none: their number, in the laurel, is the picture. */
private val BADGE_EMBLEMS: Map<String, Emblem> = mapOf(
    "first_workout" to Emblems.Sunrise,
    "first_10k_steps" to Emblems.Steps,
    "workouts_10" to Emblems.RankOne,
    "workouts_50" to Emblems.RankTwo,
    "workouts_100" to Emblems.RankThree,
    "workouts_500" to Emblems.Star,
    "km_100" to Emblems.Road,
    "km_500" to Emblems.Road,
    "km_1000" to Emblems.Road,
    "steps_1m" to Emblems.Steps,
    "goal_first" to Emblems.Check,
    "first_run" to Emblems.Runner,
    "first_5k" to Emblems.Finish,
    "first_10k" to Emblems.Finish,
    "first_indoor" to Emblems.Indoor,
    "first_log" to Emblems.Barbell,
    "first_tabata" to Emblems.Interval,
    "first_reps" to Emblems.Track,
    "first_mind" to Emblems.Lotus,
    "first_game" to Emblems.Ball,
)

/** The badges whose art carries their number or distance under the picture ("10", "1000", "5K"). */
private val LABELLED_BADGES = setOf(
    "first_10k_steps", "workouts_10", "workouts_50", "workouts_100", "workouts_500", "km_100", "km_500", "km_1000",
    "steps_1m", "goal_4", "goal_12", "goal_26", "goal_52", "first_5k", "first_10k",
)

private const val GOAL_FIRST = "goal_first"

private fun shapeOf(group: Rewards.Group): MedalShape = when (group) {
    Rewards.Group.Firsts -> MedalShape.Round
    Rewards.Group.Milestones -> MedalShape.Hex
    Rewards.Group.Weekly -> MedalShape.Laurel
    Rewards.Group.Kinds -> MedalShape.Soft
}

/**
 * [b]'s medal, its art reading [face] ([RewardsCopy.face]). The first weekly goal shows the goal it was met at ("3/3")
 * in place of its tick when there is one.
 */
internal fun badgeLook(b: Rewards.Badge, face: String): MedalLook {
    val goalShown = b.id == GOAL_FIRST && '/' in face
    val emblem = if (goalShown) null else BADGE_EMBLEMS[b.id]
    val label = face.takeIf { goalShown || b.id in LABELLED_BADGES }
    return MedalLook(shapeOf(b.group), badgeColor(b.group), emblem, label)
}

/** A personal best of [kind] as a rosette in the best's gold, a fastest stretch with its distance under ("5K", "HM"). */
internal fun bestLook(kind: String): MedalLook {
    val fastest = Rewards.Kind.fastestMeters(kind) != null
    val emblem = when {
        fastest -> Emblems.Stopwatch
        Rewards.Kind.liftName(kind) != null -> Emblems.Barbell
        kind == Rewards.Kind.LONGEST_RUN -> Emblems.Road
        kind == Rewards.Kind.MOST_STEPS -> Emblems.Steps
        else -> Emblems.Hourglass
    }
    return MedalLook(MedalShape.Rosette, BestColor, emblem, RewardsCopy.bestShort(kind).takeIf { fastest })
}

/**
 * A medal [diameter] across: the [look]'s outline and lit face, its picture and label. [locked] draws it quiet, still
 * to earn. [lit] (0..1) is how far its light has risen, read only while drawing, so a medal can light up as its sheet
 * opens ([rememberLightRise]) without composing again.
 */
@Composable
internal fun Medal(look: MedalLook, diameter: Dp, modifier: Modifier = Modifier, locked: Boolean = false, lit: () -> Float = { 1f }) {
    val tones = remember(look.ink) { MedalTones(look.ink) }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            scale(this.size.width / FRAME, pivot = Offset.Zero) { drawFrame(look.shape, tones, locked, lit()) }
        }
        MedalContent(look, diameter * look.shape.content, if (locked) LockedMark else tones.mark, if (locked) LockedLabel else Color.White)
    }
}

/**
 * The picture over the label, centred on the face, the picture sized from the face's room [box]. The label takes a type
 * role (spec 2.3) by the medal's size and whether it stands alone, and scales down to the face's width when it's
 * longer than the face holds ("14/14").
 */
@Composable
private fun MedalContent(look: MedalLook, box: Dp, mark: Color, text: Color) {
    val emblemShare = if (look.label != null) EMBLEM_OVER_LABEL else EMBLEM_ALONE
    val large = box >= LARGE_FACE
    // Under a picture the label is a caption to it, a size down; on its own (the weeks in a row) it is the picture.
    val style = when {
        look.emblem == null -> if (large) GlowType.goalValue else GlowType.headerDate
        else -> if (large) GlowType.smallValue else GlowType.secondary.copy(fontWeight = FontWeight.SemiBold)
    }
    // Under a picture the label sits low, where a round face narrows, so it keeps to less of the width there.
    val room = box * if (look.emblem != null) LABEL_ROOM_UNDER else LABEL_ROOM_ALONE
    Column(
        Modifier.size(box), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(box * LABEL_GAP, Alignment.CenterVertically),
    ) {
        look.emblem?.let { e -> Canvas(Modifier.size(box * emblemShare)) { drawEmblem(e, mark) } }
        look.label?.let { label ->
            Text(
                label, Modifier.fitWidth(room), color = text, maxLines = 1, softWrap = false,
                style = style.copy(fontFeatureSettings = "tnum", lineHeight = style.fontSize),
            )
        }
    }
}

/** Lays the content out at its own width, then scales it down, about its centre, to fit [max] when it's wider. */
private fun Modifier.fitWidth(max: Dp): Modifier = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    val room = max.roundToPx()
    val k = if (p.width > room) room.toFloat() / p.width else 1f
    val w = (p.width * k).roundToInt()
    val h = (p.height * k).roundToInt()
    layout(w, h) {
        p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) {
            scaleX = k
            scaleY = k
        }
    }
}

/**
 * How far a medal's light has risen: from [RISE_FROM] to full over [RISE_MS] when [on], as an earned medal's sheet
 * opens, else full at once. Full with the system's animations off.
 */
@Composable
internal fun rememberLightRise(on: Boolean): () -> Float {
    val animate = rememberAnimationsOn()
    val level = remember { Animatable(if (on && animate) RISE_FROM else 1f) }
    LaunchedEffect(on, animate) {
        if (on && animate) level.animateTo(1f, tween(RISE_MS, easing = Motion.easing)) else level.snapTo(1f)
    }
    return remember(level) { { level.value } }
}

/** A medal still to earn: its picture and label dim. */
private val LockedMark = Color.White.copy(alpha = 0.34f)
private val LockedLabel = Color.White.copy(alpha = 0.42f)

/**
 * The picture's share of the face's room, alone or over a label; how much of the face's width a label may take, under
 * a picture or on its own; and the space between the two. Every label keeps clear of the inner ring.
 */
private const val EMBLEM_ALONE = 0.56f
private const val EMBLEM_OVER_LABEL = 0.34f
private const val LABEL_ROOM_UNDER = 0.5f
private const val LABEL_ROOM_ALONE = 0.68f
private const val LABEL_GAP = 0.05f

/** From this much room on the face, the label takes the large display roles: a sheet's medal, the best card's. */
private val LARGE_FACE = 80.dp

/** An earned medal's light rises from here as its sheet opens. */
private const val RISE_FROM = 0.15f
private const val RISE_MS = 700
