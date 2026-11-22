package com.sesame.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sesame.app.R
import com.sesame.app.ui.state.GenerationActions
import com.sesame.app.ui.state.GenerationState
import com.sesame.app.ui.state.SessionState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * V3: profile generation is a four-step flow on its own screen. The parse never
 * opens the run log sheet; its progress is this screen's step 2.
 */
@Composable
internal fun GenerationScreen(
    state: SessionState,
    generation: GenerationState,
    actions: GenerationActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.home_generate_profile),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = actions::onCloseGeneration) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
        ) {
            StepRow(generation.step)
            Spacer(Modifier.height(16.dp))
            when (generation.step) {
                GenerationStep.Idle -> SourceStep(actions)
                GenerationStep.Parsing -> ParsingStep(state)
                GenerationStep.Confirm -> ConfirmStep()
                GenerationStep.Save -> SaveStep(actions)
                GenerationStep.Failed -> FailedStep(state, actions)
            }
        }
    }
}

/** The log line without its tone tag (the run sheet has the same latent issue). */
private fun GhostlockLogLine.displayText(): String = text
    .removePrefix("<-> ")
    .removePrefix("<b> ")
    .removePrefix("<s> ")
    .removePrefix("<k> ")
    .trimEnd('\r', '\n')

@Composable
private fun StepRow(step: GenerationStep) {
    val current = when (step) {
        GenerationStep.Idle -> 0
        GenerationStep.Parsing -> 1
        GenerationStep.Confirm -> 2
        GenerationStep.Save -> 3
        GenerationStep.Failed -> -1
    }
    val labels = listOf(
        R.string.gen_step_source,
        R.string.gen_step_parse,
        R.string.gen_step_confirm,
        R.string.gen_step_save,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, labelRes ->
            val selected = index == current
            Text(
                text = stringResource(labelRes),
                fontSize = 13.sp,
                color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SourceStep(actions: GenerationActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.action_parse_ota),
            summary = stringResource(R.string.parse_url_hint),
            onClick = actions::onParseOta,
        )
        ArrowPreference(
            title = stringResource(R.string.action_parse),
            summary = stringResource(R.string.gen_local_summary),
            onClick = actions::onParseImage,
        )
    }
    Text(
        text = stringResource(R.string.gen_source_hint),
        fontSize = 12.sp,
        color = secondaryText(),
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun ParsingStep(state: SessionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.gen_parsing),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            state.run.logLines.takeLast(3).forEach { line ->
                Text(
                    text = line.displayText(),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryText(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ConfirmStep() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = stringResource(R.string.gen_confirm_hint),
            fontSize = 14.sp,
            color = secondaryText(),
        )
    }
}

@Composable
private fun SaveStep(actions: GenerationActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.gen_generated),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.gen_save_hint),
                fontSize = 14.sp,
                color = secondaryText(),
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(
                text = stringResource(R.string.gen_save),
                onClick = actions::onSaveGeneratedProfile,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(52.dp),
            )
        }
    }
}

@Composable
private fun FailedStep(state: SessionState, actions: GenerationActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.gen_failed_hint),
                fontSize = 14.sp,
                color = secondaryText(),
            )
            state.run.logLines.lastOrNull()?.let { line ->
                Text(
                    text = line.displayText(),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryText(),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                TextButton(
                    text = stringResource(R.string.gen_retry),
                    onClick = actions::onRestartGeneration,
                )
                Spacer(Modifier.height(0.dp))
                TextButton(
                    text = stringResource(R.string.dialog_dismiss),
                    onClick = actions::onCloseGeneration,
                )
            }
        }
    }
}
