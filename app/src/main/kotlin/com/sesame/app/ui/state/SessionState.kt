package com.sesame.app.ui.state

import androidx.compose.runtime.Stable
import com.sesame.app.data.profile.ModuleConfig
import com.sesame.app.domain.model.MeasuredKernelInfo
import com.sesame.app.domain.model.MeasurementMergeRow
import com.sesame.app.domain.model.ProfileLayers
import com.sesame.app.domain.model.RouteCandidate
import com.sesame.app.domain.model.ShizukuStatus
import com.sesame.app.domain.model.VrKoMode
import com.sesame.app.ui.GhostlockLogLine
import com.sesame.app.ui.RunStatus

/**
 * The session holder: one immutable snapshot of everything the screens read,
 * sliced by concern so a screen can take only the part it needs.
 *
 * It is derived from the view model's single state flow (no second source of
 * truth); every mutation still goes through the view model's actions.
 */
@Stable
data class SessionState(
    val device: DeviceState = DeviceState(),
    val support: SupportState = SupportState(),
    val access: AccessState = AccessState(),
    val runSettings: RunSettingsState = RunSettingsState(),
    val run: RunState = RunState(),
    val measurements: MeasurementsState = MeasurementsState(),
) {
    /** The device identity line. */
    @Stable
    data class DeviceState(
        val deviceName: String = "",
        val kernelRelease: String = "",
        val socName: String = "",
    )

    /** The resolved profile: what it is, which route it takes, and its layers. */
    @Stable
    data class SupportState(
        val hasProfile: Boolean = false,
        /** The resolved document carries validation errors, so the run is blocked. */
        val profileInvalid: Boolean = false,
        val release: String = "",
        val route: String? = null,
        val importedProfilePresent: Boolean = false,
        val activeBuiltinProfile: String? = null,
        val layers: ProfileLayers? = null,
        /** The viable routes, in the profile's order; the picker's only source. */
        val routeCandidates: List<RouteCandidate> = emptyList(),
        /** The remembered picker choice (a native route or the timer). */
        val selectedRoute: String? = null,
        /** The active native route's carriers, and the remembered choice. */
        val carrierCandidates: List<String> = emptyList(),
        val selectedCarrier: String? = null,
        /** DirtyFrag is a choice when the document's ladder offers both engines. */
        val dirtyFragSelectable: Boolean = false,
        val dirtyFragOn: Boolean = false,
        /** The document's derived ladder (the DirtyFrag capability source). */
        val pipelineOrder: List<String> = emptyList(),
        /**
         * The module chain's pair manifest, when the ladder selects `module`.
         * The chain has no route to pick, so the home and the parameters screen
         * present this block instead of a route line.
         */
        val module: ModuleConfig? = null,
        /** The bundled `.ko` opens and matches its `asset_sha256`. */
        val moduleAssetVerified: Boolean = false,
        /** Installed Magisk manager package; null = the chain cannot run. */
        val magiskManager: String? = null,
    )

    /** Privileged-access facts: the Shizuku switch and the chosen root manager. */
    @Stable
    data class AccessState(
        val shizukuEnabled: Boolean = false,
        val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
        /** The manager package is installed; NOT_RUNNING alone folds "absent". */
        val shizukuInstalled: Boolean = false,
        /** Root-manager choice (package name); null means Auto. */
        val selectedManagerPackage: String? = null,
    )

    /** The persisted run knobs, as the run-settings and advanced screens edit them. */
    @Stable
    data class RunSettingsState(
        val safeModeEnabled: Boolean = false,
        val forceAttackTestEnabled: Boolean = false,
        val vrKoTagClear: VrKoMode = VrKoMode.AUTO,
        val vrKoSelinuxBlock: VrKoMode = VrKoMode.AUTO,
        val debugKernelLogEnabled: Boolean = true,
    )

    /** The last run: its verdict, its log lines, and the log sheet's state. */
    @Stable
    data class RunState(
        val running: Boolean = false,
        val status: RunStatus = RunStatus.Idle,
        val logLines: List<GhostlockLogLine> = emptyList(),
        val sheetVisible: Boolean = false,
        val sheetDismissible: Boolean = false,
        /** Wall-clock start of the run, for the sheet's elapsed line. */
        val startedAtMs: Long = 0,
    )

    /** The measured database and the facts that would change the profile. */
    @Stable
    data class MeasurementsState(
        val measuredKernels: List<MeasuredKernelInfo> = emptyList(),
        val mergeRows: List<MeasurementMergeRow> = emptyList(),
    )
}
