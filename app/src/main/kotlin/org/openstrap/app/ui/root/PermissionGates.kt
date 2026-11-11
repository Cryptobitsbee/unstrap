package org.openstrap.app.ui.root

import androidx.compose.ui.res.stringResource
import org.openstrap.app.R
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.openstrap.app.PendingAction
import org.openstrap.app.PendingStrapAction
import org.openstrap.app.data.SportSettings
import org.openstrap.app.data.WorkoutPlan
import org.openstrap.app.device.BlePermissions
import org.openstrap.app.ui.glow.PageConfirm

/** The Bluetooth, location and notification gates in front of a workout and the Strap page's actions. */
internal class PermissionGates(
    /** Runs the action once Bluetooth may be used. */
    val requestBluetooth: (() -> Unit) -> Unit,
    /** Starts a plan once Bluetooth (and location, for GPS) may be used. [SportSettings] is what Ready keeps. */
    val startPlan: (WorkoutPlan, SportSettings?) -> Unit,
    /** Asks for location, then runs the action whatever the answer. */
    val requestLocation: (() -> Unit) -> Unit,
    private val blocked: MutableState<BlockedPermission?>,
) {
    /** What was turned down for good: Android shows no dialog any more, so the app offers its settings page. */
    val blockedPermission: BlockedPermission? get() = blocked.value

    fun dismissBlocked() {
        blocked.value = null
    }
}

/** A permission turned down for good, saved by name so the dialog survives a recreate. */
internal enum class BlockedPermission { BLUETOOTH, LOCATION, NOTIFICATIONS }

/** What waits on the location answer besides a workout: a name rather than a closure, so it can be saved. */
private enum class GateAction {
    /** Ready's "Turn on location": the Ready screen reads the phone's location again. */
    REFRESH_READY,
}

/**
 * The permission gates. A workout waits as saveable data, so the answer still starts it after the activity was
 * recreated behind the dialog. A Strap page action waits in [pendingStrap], which outlives a fold, and one Bluetooth
 * answer runs both. Location for a GPS workout is asked only when GPS is on, and the workout starts either way (steps
 * without it). Ready's "Turn on location" is a saved [GateAction] tag, with the closure that refreshes Ready kept
 * beside it.
 */
@Composable
internal fun rememberPermissionGates(
    pendingStrap: PendingStrapAction,
    locationGranted: () -> Boolean,
    start: (PendingAction.StartPlan) -> Unit,
): PermissionGates {
    val context = LocalContext.current
    val activity = LocalActivity.current
    // Android 11 asks for location in place of Nearby devices (BlePermissions).
    val android11 = BlePermissions.scanNeedsLocationOn
    val bluetoothRequired = stringResource(if (android11) R.string.day_bluetooth_required_location else R.string.day_bluetooth_required)
    val locationDeclined = stringResource(R.string.day_location_declined)
    var pending by rememberSaveable(stateSaver = PendingAction.Saver) { mutableStateOf<PendingAction?>(null) }
    val blocked = rememberSaveable { mutableStateOf<BlockedPermission?>(null) }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val waiting = pending as? PendingAction.StartPlan
        val strapAction = pendingStrap.action
        pending = null
        pendingStrap.action = null
        when {
            // Whoever queued behind this one question gets the answer: the workout and the Strap page action both.
            BlePermissions.granted(result) -> {
                waiting?.let(start)
                strapAction?.invoke()
                // Notifications are not needed to go ahead, but a no for good leaves the workout notification missing.
                if (BlePermissions.notificationsDenied(result) && activity != null &&
                    BlePermissions.turnedDownForGood(activity, BlePermissions.notifications)
                ) {
                    blocked.value = BlockedPermission.NOTIFICATIONS
                }
            }
            activity != null && BlePermissions.turnedDownForGood(activity, BlePermissions.bluetooth) ->
                blocked.value = BlockedPermission.BLUETOOTH
            else -> Toast.makeText(context, bluetoothRequired, Toast.LENGTH_LONG).show()
        }
    }
    val requestBluetooth: (() -> Unit) -> Unit = { then ->
        pendingStrap.action = then
        btLauncher.launch(BlePermissions.required())
    }
    val withBluetooth: (PendingAction.StartPlan) -> Unit = { action ->
        // Asked again while notifications are missing, so a no for good shows its dialog at the start of a workout.
        if (BlePermissions.granted(context) && BlePermissions.notificationsGranted(context)) {
            start(action)
        } else {
            pending = action
            btLauncher.launch(BlePermissions.required())
        }
    }
    // Which follow-up waits on the location answer is saved, so it survives a recreate. Ready's closure behind it only
    // refreshes the Ready screen that asked, which a recreate replaces: the new Ready reads location itself when it
    // shows and on every resume, so a lost closure leaves nothing undone.
    var afterLocation by rememberSaveable { mutableStateOf<GateAction?>(null) }
    var refreshReady by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.none { it }) {
            if (activity != null && BlePermissions.turnedDownForGood(activity, locationPermissions.asList())) {
                blocked.value = BlockedPermission.LOCATION
            } else {
                Toast.makeText(context, locationDeclined, Toast.LENGTH_LONG).show()
            }
        }
        val waiting = pending as? PendingAction.StartPlan
        if (waiting != null) {
            pending = null
            withBluetooth(waiting)
        }
        if (afterLocation == GateAction.REFRESH_READY) refreshReady?.invoke()
        afterLocation = null
        refreshReady = null
    }
    val requestLocation: (() -> Unit) -> Unit = { then ->
        afterLocation = GateAction.REFRESH_READY
        refreshReady = then
        locationLauncher.launch(locationPermissions)
    }
    val startPlan: (WorkoutPlan, SportSettings?) -> Unit = { plan, saved ->
        val action = PendingAction.StartPlan(plan, saved)
        if (plan.useGps && !locationGranted()) {
            pending = action
            locationLauncher.launch(locationPermissions)
        } else {
            withBluetooth(action)
        }
    }
    return PermissionGates(requestBluetooth, startPlan, requestLocation, blocked)
}

/**
 * Whatever was turned down for good (Nearby devices, location, notifications): the way to unstrap's settings page,
 * where it can be turned on. Named for Bluetooth, which it first served.
 */
@Composable
internal fun BluetoothBlockedDialog(gates: PermissionGates) {
    val blocked = gates.blockedPermission ?: return
    val context = LocalContext.current
    val location = BlePermissions.scanNeedsLocationOn
    val title = when (blocked) {
        BlockedPermission.BLUETOOTH -> if (location) R.string.day_nearby_title_location else R.string.day_nearby_title
        BlockedPermission.LOCATION -> R.string.day_nearby_title_location
        BlockedPermission.NOTIFICATIONS -> R.string.day_notifications_blocked_title
    }
    val text = when (blocked) {
        BlockedPermission.BLUETOOTH -> if (location) R.string.day_nearby_text_location else R.string.day_nearby_text
        BlockedPermission.LOCATION -> R.string.day_location_blocked_text
        BlockedPermission.NOTIFICATIONS -> R.string.day_notifications_blocked_text
    }
    PageConfirm(
        title = stringResource(title),
        text = stringResource(text),
        confirm = stringResource(R.string.day_nearby_open_settings),
        onConfirm = {
            gates.dismissBlocked()
            context.startActivity(BlePermissions.settingsIntent(context))
        },
        onDismiss = gates::dismissBlocked,
        dismiss = stringResource(R.string.day_nearby_not_now),
    )
}
