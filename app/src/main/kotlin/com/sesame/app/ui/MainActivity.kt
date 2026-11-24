package com.sesame.app.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sesame.app.GhostlockApplication
import com.sesame.app.BuildConfig
import com.sesame.app.R

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<GhostlockViewModel> {
        viewModelFactory {
            initializer {
                GhostlockViewModel(
                    (application as GhostlockApplication).createRepository(),
                    application,
                )
            }
        }
    }

    private var pendingDocumentRequest: DocumentRequest? = null
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val request = pendingDocumentRequest
        pendingDocumentRequest = null
        if (uri != null && request != null) viewModel.onDocumentResult(request, uri.toString())
    }

    private val profileDocumentCreator =
        /* octet-stream, not text/plain: SAF appends ".txt" to a text/plain
         * document whose name is not a recognised text extension, which turned
         * "…conf" into "…conf.txt". */
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            viewModel.onExportProfileDocumentPicked(uri?.toString())
        }

    /* The whole report: every run plus the crash-evidence captures. */
    private val reportBundleDocumentCreator =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            viewModel.onExportReportBundleDocumentPicked(uri?.toString())
        }

    /* A user-supplied bugreport. The picker is opened at the system's "Bug
     * reports" root when it exists; the importer validates the zip. */
    private val bugreportPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.onBugreportPicked(result.data?.data?.toString())
            }
        }

    /* The run-status notification is the run's visible proof and the reason the
     * app keeps running when the KSU manager takes the foreground. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize()
        handleSendIntent(intent)
        /* Debug-only: `am start --ez probe_check true` runs the DirtyFrag
         * viability probe without an attack and records a clear result. */
        if (BuildConfig.DEBUG && intent?.hasExtra("probe_check") == true) {
            if (intent.getBooleanExtra("probe_marker", false)) {
                /* Seeds the watchdog marker a killed probe would leave. */
                File(filesDir, ".df-probe-in-progress").writeText("seeded by probe_marker")
            }
            viewModel.runDirtyfragProbeCheck()
        }
        /* Debug-only: `am start --ez preflight_check true` runs the ROM
         * preflight (the stage's cave/hook analysis) without an attack. */
        if (BuildConfig.DEBUG && intent?.hasExtra("preflight_check") == true) {
            viewModel.runDirtyfragPreflightCheck()
        }
        /* Debug-only: `am start --ez timer_test true` delivers the timer tool
         * over Shizuku and runs its safe `probe` mode (no root, no reboot). */
        if (BuildConfig.DEBUG && intent?.hasExtra("timer_test") == true) {
            viewModel.runTimerProbeCheck()
        }
        /* Debug-only: `am start --ez timer_run true` runs the timer way-2
         * route. It can panic/reboot; one attempt per boot (see the boot
         * contract), and the parked tool is the cleanup's job. */
        if (BuildConfig.DEBUG && intent?.hasExtra("timer_run") == true) {
            viewModel.runTimerRouteCheck()
        }
        /* Debug-only: `am start --ez derive_test true --es derive_image <path>`
         * runs the profile derivation on a local image and records the timer
         * block's fate in logcat and files/derive-test.txt. No UI, no export. */
        if (BuildConfig.DEBUG && intent?.hasExtra("derive_test") == true) {
            intent.getStringExtra("derive_image")?.let { viewModel.runDeriveTest(it) }
        }
        setContent {
            GhostlockRoute(viewModel, ::handleEffect)
        }
        setupSystemBars()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessStatus()
        /* Returning from About phone is how the Developer-options gate ends;
         * the report screen advances to the generation instructions. */
        viewModel.onReportResumed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSendIntent(intent)
    }

    /** "Share" from the system bugreport notification lands here. */
    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) viewModel.onBugreportPicked(uri.toString())
    }

    private fun handleEffect(effect: GhostlockEffect) {
        when (effect) {
            is GhostlockEffect.PickDocument -> {
                pendingDocumentRequest = effect.request
                /* A profile document may carry any extension/MIME, so the
                 * picker stays unfiltered; the app validates the entry. */
                documentPicker.launch(arrayOf("*/*"))
            }

            is GhostlockEffect.CreateReportBundleDocument ->
                reportBundleDocumentCreator.launch(effect.suggestedName)

            GhostlockEffect.PickBugreport -> launchBugreportPicker()

            GhostlockEffect.OpenDeveloperOptions ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

            GhostlockEffect.OpenAboutPhone ->
                startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

            is GhostlockEffect.ShareFile -> shareFile(effect.uri.toUri())

            is GhostlockEffect.ToastText ->
                Toast.makeText(this, effect.text, Toast.LENGTH_LONG).show()

            is GhostlockEffect.CreateProfileDocument ->
                profileDocumentCreator.launch(effect.suggestedName)

            is GhostlockEffect.Share -> shareOffsets(effect.uri.toUri())
            is GhostlockEffect.Toast -> Toast.makeText(this, effect.resourceId, Toast.LENGTH_SHORT).show()
            is GhostlockEffect.Clipboard -> {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("ghostlock-log", effect.text))
            }

            is GhostlockEffect.KeepScreenAwake -> if (effect.enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            GhostlockEffect.OpenShizuku -> {
                val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launch != null) {
                    startActivity(launch)
                } else {
                    /* No Shizuku manager installed: land on the project page
                     * instead of silently doing nothing. The effect is only
                     * sent from an explicit tap. */
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
                }
            }

            GhostlockEffect.RequestNotificationPermission -> if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            is GhostlockEffect.OpenManager -> {
                val launch = packageManager.getLaunchIntentForPackage(effect.packageName)
                if (launch != null) {
                    startActivity(launch)
                } else {
                    Toast.makeText(this, R.string.run_notif_manager_missing, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Opens the system picker at the shell app's "Bug reports" root. The
     * provider is protected by MANAGE_DOCUMENTS — which the app cannot hold —
     * but the system picker has it, and the returned document comes with a
     * read grant the importer can use. On a ROM without the provider the
     * initial URI is ignored and the normal picker location is shown.
     *
     * The root URI is the working form; the document URI is not accepted, and
     * an already-running picker instance may ignore a new initial URI.
     */
    private fun launchBugreportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            /* The report is the full bugreport zip; the sibling dumpstate log
             * and stats files are text and are not the report. The extras
             * enable the zip while dimming those text siblings. */
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri(
                    SHELL_BUGREPORT_AUTHORITY,
                    SHELL_BUGREPORT_ROOT,
                ),
            )
        }
        bugreportPicker.launch(intent)
    }

    /** Hands the finished bundle to the system share sheet. */
    private fun shareFile(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(intent, getString(R.string.report_share_chooser)),
        )
    }

    private fun shareOffsets(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_share)))
    }

    private fun setupSystemBars() {
        val controller = window.decorView.windowInsetsController ?: return
        val lightStatus = if (resources.getBoolean(R.bool.window_light_status_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        } else {
            0
        }
        val lightNavigation = if (resources.getBoolean(R.bool.window_light_navigation_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        } else {
            0
        }
        controller.setSystemBarsAppearance(
            lightStatus or lightNavigation,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /* The shell app's DocumentsProvider and the root it exposes for
         * completed bugreports (AOSP's BugreportStorageProvider). */
        const val SHELL_BUGREPORT_AUTHORITY = "com.android.shell.documents"
        const val SHELL_BUGREPORT_ROOT = "bugreport"
    }
}

@Composable
private fun GhostlockRoute(
    viewModel: GhostlockViewModel,
    onEffect: (GhostlockEffect) -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val overlay by viewModel.overlay.collectAsStateWithLifecycle()
    val generation by viewModel.generation.collectAsStateWithLifecycle()
    val builtin by viewModel.builtin.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel.effects) {
        viewModel.effects.collect(onEffect)
    }
    GhostlockApp(
        session = session,
        report = report,
        overlay = overlay,
        generation = generation,
        builtin = builtin,
        nav = viewModel.nav,
        homeActions = viewModel.homeActions(),
        runSettingsActions = viewModel.runSettingsActions(),
        advancedActions = viewModel.advancedActions(),
        parameterActions = viewModel.parametersActions(),
        reportActions = viewModel.reportActions(),
        overlayActions = viewModel.overlayActions(),
        generationActions = viewModel.generationActions(),
        builtinActions = viewModel.builtinActions(),
        vrKoActions = viewModel.vrKoActions(),
        measurementsActions = viewModel.measurementsActions(),
        mergePreviewActions = viewModel.mergePreviewActions(),
    )
}
