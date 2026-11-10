package org.openstrap.app.ui.glow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import org.openstrap.app.R
import org.openstrap.app.data.Rewards
import org.openstrap.app.data.db.PersonalBest
import org.openstrap.app.presentation.RewardsCopy
import org.openstrap.app.presentation.RewardsView
import org.openstrap.app.ui.components.pressClickable

/*
 * The parts of Goals and bests and All badges (ui/glow/GoalsAndBests.kt): the personal bests as rosettes, the next
 * badge to earn, and the badges as medals. Each shows only its medal, its name and its figure; a tap opens its sheet
 * (BadgeSheets.kt) with the rest.
 */

/** What a badge's art reads on these pages: the first weekly goal shows the goal it was met at, or this week's while locked. */
internal fun RewardsView.face(b: Rewards.Badge): String =
    earned.firstOrNull { it.id == b.id }?.let { RewardsCopy.face(b, Rewards.goalAt(it, weeks, firstDay, ZoneId.systemDefault())) }
        ?: RewardsCopy.face(b, goal)

/**
 * The personal bests as rosettes, three to a row, each with its figure and name. Before the first, the kinds there are
 * to set, quiet, with a line on when they come.
 */
@Composable
internal fun BestsPanel(view: RewardsView, onOpen: (String) -> Unit) {
    val shown = view.bests.sortedBy { order(it.kind) }
    ListPanel(title = stringResource(R.string.glow_goals_personal_bests)) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.glow_goals_no_bests), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    FIRST_BESTS.forEach { kind -> BestCell(kind, null, onOpen, Modifier.weight(1f)) }
                }
            }
            shown.chunked(BESTS_PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    row.forEach { b -> BestCell(b.kind, b, onOpen, Modifier.weight(1f)) }
                    repeat(BESTS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * The badge closest to done, as a picture card in its group's ink: its medal still to earn, its name, and how far along
 * as a bar and in words. Opens its sheet.
 */
@Composable
internal fun NextBadgeCard(badge: Rewards.Badge, view: RewardsView, onOpen: (Rewards.Badge) -> Unit) {
    val ink = badgeColor(badge.group)
    val (have, need) = view.progress[badge.id] ?: (0L to 1L)
    PictureCard(ink, Modifier.fillMaxWidth(), onClick = { onOpen(badge) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Medal(badgeLook(badge, view.face(badge)), NEXT_MEDAL, locked = true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.glow_goals_next_badge), color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    TapCaret(CARD_CARET_ALPHA)
                }
                Text(badge.title, color = Color.White, style = GlowType.body, maxLines = 2)
                ShareBar(have.toFloat() / need, ink, Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(
                    RewardsCopy.progressLine(have, need, badge.unit), color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA),
                    style = GlowType.secondary,
                )
            }
        }
    }
}

/**
 * The badges earned, newest first, as medals four to a row with only their names; before the first, the first ones to
 * go for, quiet. The note opens All badges.
 */
@Composable
internal fun BadgesPanel(view: RewardsView, onOpen: (Rewards.Badge) -> Unit, onAllBadges: () -> Unit) {
    val earned = view.earned.sortedByDescending { it.earnedAt }.mapNotNull { Rewards.badge(it.id) }
    val shown = earned.ifEmpty { FIRST_BADGES.mapNotNull(Rewards::badge) }.take(PANEL_BADGES)
    ListPanel(
        title = stringResource(R.string.glow_goals_badges),
        note = stringResource(R.string.glow_goals_earned_count, view.earned.size, Rewards.BADGES.size),
        onNote = onAllBadges,
    ) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            shown.chunked(PANEL_PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    row.forEach { b -> BadgeCell(b, view, onOpen, GlowType.caption, Modifier.weight(1f)) }
                    repeat(PANEL_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A badge's medal and its name in [nameStyle], lit when earned; a locked one with a count shows how far along as a thin
 * bar in its ink. Opens its sheet.
 */
@Composable
internal fun BadgeCell(
    badge: Rewards.Badge,
    view: RewardsView,
    onOpen: (Rewards.Badge) -> Unit,
    nameStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val row = view.earned.firstOrNull { it.id == badge.id }
    val share = view.progress[badge.id]?.let { (have, need) -> have.toFloat() / need }?.takeIf { row == null && it > 0f }
    val state = row?.let { stringResource(R.string.glow_goals_earned_on, RewardsCopy.dayLong(it.earnedAt)) }
        ?: stringResource(R.string.glow_goals_not_earned)
    val described = stringResource(R.string.glow_goals_badge_described, badge.title, state)
    Column(
        modifier.clip(GlowLayout.TileShape).pressClickable({ onOpen(badge) }, GlowLayout.TileShape)
            .clearAndSetSemantics {
                contentDescription = described
                role = Role.Button
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Medal(badgeLook(badge, view.face(badge)), CELL_MEDAL, locked = row == null)
        Text(
            badge.title, color = Color.White.copy(alpha = if (row != null) 1f else LOCKED_NAME_ALPHA), style = nameStyle,
            textAlign = TextAlign.Center, maxLines = 2, minLines = 2,
        )
        if (share != null) ShareBar(share, badgeColor(badge.group), Modifier.width(CELL_BAR))
    }
}

/** How far along, 0..1, as a thin rounded bar: [ink] over a faint track. */
@Composable
internal fun ShareBar(share: Float, ink: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = TRACK_ALPHA))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).clip(CircleShape).background(ink))
    }
}

/** A best's rosette, its figure and its name; [best] null for one not set yet, quiet. Opens its sheet. */
@Composable
private fun BestCell(kind: String, best: PersonalBest?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val title = RewardsCopy.bestTitle(kind)
    val figure = best?.let { RewardsCopy.bestValue(kind, it.value) } ?: stringResource(R.string.glow_goals_not_set)
    val described = stringResource(R.string.glow_goals_badge_described, title, figure)
    Column(
        modifier.clip(GlowLayout.TileShape).pressClickable({ onOpen(kind) }, GlowLayout.TileShape)
            .clearAndSetSemantics {
                contentDescription = described
                role = Role.Button
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Medal(bestLook(kind), CELL_MEDAL, locked = best == null)
        Text(
            figure, color = Color.White.copy(alpha = if (best != null) 1f else GlowType.CAPTION_ALPHA),
            style = if (best != null) GlowType.cardTitle else GlowType.caption, maxLines = 1,
        )
        Text(
            title, color = Color.White.copy(alpha = if (best == null) LOCKED_NAME_ALPHA else GlowType.SECONDARY_ALPHA),
            style = GlowType.caption, textAlign = TextAlign.Center, maxLines = 2, minLines = 2,
        )
    }
}

/** Bests in a fixed order: runs, then the longest workout, lifts, steps. */
private fun order(kind: String): Int = Rewards.Kind.fastestMeters(kind)
    ?: when {
        kind == Rewards.Kind.LONGEST_RUN -> RUN_ORDER
        kind == Rewards.Kind.LONGEST_WORKOUT -> RUN_ORDER + 1
        Rewards.Kind.liftName(kind) != null -> RUN_ORDER + 2
        else -> STEPS_ORDER
    }

/** Before any badge is earned, the first ones to go for: one of each kind of start. */
private val FIRST_BADGES = listOf("first_workout", "goal_first", "first_run", "workouts_10")

/** Before any best, the kinds there are to set: the ones any workout or day can set. */
private val FIRST_BESTS = listOf(Rewards.Kind.LONGEST_WORKOUT, Rewards.Kind.MOST_STEPS, Rewards.Kind.fastest(FIVE_KM))

/** After every fastest stretch (their metres), the longest run, the longest workout, the lifts, then the steps. */
private const val RUN_ORDER = 100_000
private const val STEPS_ORDER = 100_003
private const val FIVE_KM = 5000
private const val BESTS_PER_ROW = 3
private const val PANEL_PER_ROW = 4
private const val PANEL_BADGES = 8
private const val LOCKED_NAME_ALPHA = 0.55f
private const val TRACK_ALPHA = 0.12f
private val CELL_GAP = 8.dp
private val CELL_MEDAL = 64.dp
private val CELL_BAR = 44.dp
private val NEXT_MEDAL = 76.dp
