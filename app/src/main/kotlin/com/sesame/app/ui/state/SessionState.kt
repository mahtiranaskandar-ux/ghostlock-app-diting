package com.sesame.app.ui.state

import androidx.compose.runtime.Stable
import com.sesame.app.domain.model.CpuPair
import com.sesame.app.domain.model.MeasuredKernelInfo
import com.sesame.app.domain.model.MeasurementMergeRow
import com.sesame.app.domain.model.ProfileLayers
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
    /** The device identity line and the CPU-pair picker's facts. */
    @Stable
    data class DeviceState(
        val deviceName: String = "",
        val kernelRelease: String = "",
        val socName: String = "",
        val cpuPairLabels: List<String> = emptyList(),
        val cpuPairIndex: Int = 0,
        /** CPU pair from the resolved profile when it differs from the device pick. */
        val customCpuPair: CpuPair? = null,
    )

    /** The resolved profile: what it is, which route it takes, and its layers. */
    @Stable
    data class SupportState(
        val hasProfile: Boolean = false,
        val release: String = "",
        val route: String? = null,
        val fallback: String? = null,
        val importedProfilePresent: Boolean = false,
        val activeBuiltinProfile: String? = null,
        val layers: ProfileLayers? = null,
    )

    /** Privileged-access facts: the Shizuku switch and the chosen root manager. */
    @Stable
    data class AccessState(
        val shizukuEnabled: Boolean = false,
        val shizukuStatus: ShizukuStatus = ShizukuStatus.NOT_REQUIRED,
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
    )

    /** The measured database and the facts that would change the profile. */
    @Stable
    data class MeasurementsState(
        val measuredKernels: List<MeasuredKernelInfo> = emptyList(),
        val mergeRows: List<MeasurementMergeRow> = emptyList(),
    )
}
