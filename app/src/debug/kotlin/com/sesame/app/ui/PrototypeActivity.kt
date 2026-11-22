package com.sesame.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Debug-only visual prototype of the screens in docs/analysis/ui-visual-design.md.
 *
 * It is deliberately non-functional: every value is mock data, every control is
 * a no-op except the screen/variant switchers. Launch with
 *   adb shell am start -n com.sesame.app/.ui.PrototypeActivity
 * Nothing here exists in a release build.
 */
class PrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(
                colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                PrototypeRoot()
            }
        }
    }
}

private enum class ProtoScreen(val label: String) {
    Home("首页"),
    Parameters("参数"),
    Validation("校验"),
    Generation("生成"),
    RunSheet("日志"),
}

private enum class ProtoStatusKind { Success, Pending, Action, Blocked }

private data class StatusMock(
    val chip: String,
    val title: String,
    val summary: String,
    val detail: String?,
    val kind: ProtoStatusKind,
    val barLabel: String,
)

private val statusMocks = listOf(
    StatusMock(
        chip = "就绪",
        title = "受支持",
        summary = "配置已就绪",
        detail = "内置 5.10.236 · select_stack · 有效",
        kind = ProtoStatusKind.Success,
        barLabel = "执行",
    ),
    StatusMock(
        chip = "Shizuku",
        title = "通过 Shizuku 执行",
        summary = "Shizuku 未运行",
        detail = null,
        kind = ProtoStatusKind.Pending,
        barLabel = "配置 Shizuku",
    ),
    StatusMock(
        chip = "无效",
        title = "配置需要修正",
        summary = "2 项参数无效",
        detail = null,
        kind = ProtoStatusKind.Action,
        barLabel = "查看问题",
    ),
    StatusMock(
        chip = "待补",
        title = "需要物理内存参数",
        summary = "请提供 vendor_boot/dtbo 并重新解析（联发科）",
        detail = null,
        kind = ProtoStatusKind.Action,
        barLabel = "去补充",
    ),
    StatusMock(
        chip = "无配置",
        title = "需要加载配置",
        summary = "加载或生成配置后可执行",
        detail = null,
        kind = ProtoStatusKind.Action,
        barLabel = "去生成",
    ),
    StatusMock(
        chip = "不可读",
        title = "配置无法读取",
        summary = "所选文件无法读取，请重新导入",
        detail = null,
        kind = ProtoStatusKind.Blocked,
        barLabel = "去导入",
    ),
    StatusMock(
        chip = "阻断",
        title = "配置需要修正",
        summary = "the profile's kernel_phys_* map does not name diting",
        detail = null,
        kind = ProtoStatusKind.Blocked,
        barLabel = "去处理",
    ),
    StatusMock(
        chip = "不支持",
        title = "不支持",
        summary = "未找到匹配 5.10.236 的配置",
        detail = null,
        kind = ProtoStatusKind.Blocked,
        barLabel = "生成配置",
    ),
    StatusMock(
        chip = "失败",
        title = "配置解析失败",
        summary = "unexpected end of input at 'cred'",
        detail = null,
        kind = ProtoStatusKind.Blocked,
        barLabel = "查看",
    ),
)

@Composable
private fun PrototypeRoot() {
    var screen by remember { mutableStateOf(ProtoScreen.Home) }
    var variant by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Box(modifier = Modifier.weight(1f)) {
            when (screen) {
                ProtoScreen.Home -> HomeMock(statusMocks[variant])
                ProtoScreen.Parameters -> ParametersMock()
                ProtoScreen.Validation -> ValidationMock()
                ProtoScreen.Generation -> GenerationMock()
                ProtoScreen.RunSheet -> RunSheetMock()
            }
        }
        if (screen == ProtoScreen.Home) {
            TextButton(
                text = statusMocks[variant].barLabel,
                onClick = {},
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
            )
            VariantChips(variant) { variant = it }
        }
        ProtoSwitcher(screen) { screen = it }
    }
}

@Composable
private fun VariantChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        statusMocks.forEachIndexed { index, mock ->
            Chip(text = mock.chip, selected = index == selected) { onSelect(index) }
        }
    }
}

@Composable
private fun ProtoSwitcher(current: ProtoScreen, onSelect: (ProtoScreen) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ProtoScreen.entries.forEach { screen ->
            Chip(text = screen.label, selected = screen == current) { onSelect(screen) }
        }
    }
}

/** A text-only action: primary colour, no container. */
@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.primary,
        fontSize = 15.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** A compact selectable chip for the prototype's switchers. */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/* ---------- Home ---------- */

@Composable
private fun HomeMock(status: StatusMock) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(text = "Sesame", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Redmi K50 Ultra · SM8475 · 5.10.236",
            fontSize = 14.sp,
            color = secondary(),
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        StatusCard(status)
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ArrowPreference(
                title = "生成配置",
                summary = "从 OTA 链接或本地镜像",
                onClick = {},
            )
            ArrowPreference(
                title = "加载配置",
                summary = "选择已有的 .conf 文件",
                onClick = {},
            )
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ArrowPreference(
                title = "上次运行",
                summary = "完成 · 2 分钟前",
                onClick = {},
            )
            ArrowPreference(
                title = "导出报告",
                summary = "日志、崩溃证据与当前配置",
                onClick = {},
            )
            ArrowPreference(
                title = "高级选项",
                summary = "运行设置、参数与调试",
                onClick = {},
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(status: StatusMock) {
    val dark = isSystemInDarkTheme()
    val dynamic = MiuixTheme.isDynamicColor
    val container = when (status.kind) {
        ProtoStatusKind.Success -> when {
            dynamic -> MiuixTheme.colorScheme.secondaryContainer
            dark -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }

        ProtoStatusKind.Pending -> if (dark) Color(0xFF2C2C2C) else Color(0xFFF2F2F2)

        ProtoStatusKind.Action -> when {
            dynamic -> MiuixTheme.colorScheme.tertiaryContainer
            dark -> Color(0xFF3E2F1B)
            else -> Color(0xFFFFF0DB)
        }

        ProtoStatusKind.Blocked -> MiuixTheme.colorScheme.errorContainer
    }
    val iconTint = when (status.kind) {
        ProtoStatusKind.Success -> if (dynamic) MiuixTheme.colorScheme.primary else Color(0xFF36D167)
        ProtoStatusKind.Pending -> secondary()
        ProtoStatusKind.Action ->
            if (dynamic) MiuixTheme.colorScheme.onTertiaryContainer else Color(0xFFF5A623)

        ProtoStatusKind.Blocked ->
            if (dynamic) MiuixTheme.colorScheme.onErrorContainer else Color(0xFFF72727)
    }
    val icon = when (status.kind) {
        ProtoStatusKind.Success -> Icons.Rounded.CheckCircleOutline
        ProtoStatusKind.Pending -> Icons.Rounded.RemoveCircleOutline
        ProtoStatusKind.Action, ProtoStatusKind.Blocked -> Icons.Rounded.ErrorOutline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = status.title,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = status.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = status.summary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                status.detail?.let {
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        color = secondary(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/* ---------- Parameters ---------- */

@Composable
private fun ParametersMock() {
    MockScreen(title = "参数配置") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
        ) {
            Column {
                Text(text = "当前配置", fontSize = 12.sp, color = secondary())
                Text(
                    text = "外部配置 · imported_profile.conf",
                    fontSize = 17.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "5.10.236 · select_stack · 有效",
                    fontSize = 14.sp,
                    color = secondary(),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    LinkButton(text = "查看问题", onClick = {})
                    Spacer(Modifier.width(4.dp))
                    LinkButton(text = "导出配置", onClick = {})
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ArrowPreference(
                title = "内置配置",
                summary = "选择一个内置配置作为来源",
                onClick = {},
            )
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {}
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "取消外部配置", fontSize = 16.sp, color = Color(0xFFF72727))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/* ---------- Validation ---------- */

@Composable
private fun ValidationMock() {
    MockScreen(title = "配置需要修正") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "2 项参数无效",
                fontSize = 14.sp,
                color = secondary(),
                modifier = Modifier.weight(1f),
            )
            LinkButton(text = "导出当前配置", onClick = {})
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            ErrorRow(
                path = "route.select_stack.waiter_shift",
                rule = "当前 -4 · 必须在 -2..2",
            )
            ErrorRow(
                path = "cred.security_offset",
                rule = "当前 128 · 必须 ≥ 136",
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "路径即配置文件中的键名；修改后重新导入。",
            fontSize = 12.sp,
            color = secondary(),
        )
    }
}

@Composable
private fun ErrorRow(path: String, rule: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = rule,
                fontSize = 12.sp,
                color = secondary(),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        LinkButton(text = "复制", onClick = {})
    }
}

/* ---------- Generation ---------- */

@Composable
private fun GenerationMock() {
    var step by remember { mutableIntStateOf(1) }
    MockScreen(title = "生成配置") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("1 来源", "2 解析", "3 确认", "4 保存").forEachIndexed { index, label ->
                Chip(text = label, selected = step == index + 1) { step = index + 1 }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (step) {
            1 -> GenSource()
            2 -> GenParse()
            3 -> GenConfirm()
            else -> GenSave()
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GenSource() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        ArrowPreference(title = "OTA 链接", summary = "从官方完整包解析", onClick = {})
        ArrowPreference(title = "本地镜像", summary = "boot.img · ota.zip · vendor_boot · dtbo", onClick = {})
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "选择后自动判定需要补充哪些镜像。",
        fontSize = 12.sp,
        color = secondary(),
    )
    Spacer(Modifier.height(16.dp))
    TextButton(
        text = "开始解析",
        onClick = {},
        colors = ButtonDefaults.textButtonColorsPrimary(),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    )
}

@Composable
private fun GenParse() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("内核", "5.10.236-android12-9-00003-gfb24cf99ad97")
            InfoRow("物理地址", "0xa8000000 / 0x80000000")
            InfoRow("路由", "select_stack")
            InfoRow("进度", "解析 boot.img …")
        }
    }
}

@Composable
private fun GenConfirm() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("内核", "5.10.236")
            InfoRow("物理地址", "0xa8000000 / 0x80000000")
            InfoRow("路由", "select_stack · 唯一可行")
            InfoRow("步长", "960 · 已确认")
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "步长与路由将写入生成的配置。",
        fontSize = 12.sp,
        color = secondary(),
    )
}

@Composable
private fun GenSave() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            Text(text = "保存配置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = "保存为文件后，用「加载配置」导入才会生效。",
                fontSize = 14.sp,
                color = secondary(),
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(
                text = "保存到文件",
                onClick = {},
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column {
            Text(text = "已保存 · 5.10.236-ghostlock.conf", fontSize = 15.sp)
            Row(modifier = Modifier.padding(top = 8.dp)) {
                LinkButton(text = "导入此配置", onClick = {})
                Spacer(Modifier.width(4.dp))
                LinkButton(text = "完成", onClick = {})
            }
        }
    }
}

/* ---------- Run sheet ---------- */

@Composable
private fun RunSheetMock() {
    var finished by remember { mutableStateOf(false) }
    MockScreen(title = "日志") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (finished) "完成 · KernelSU ready" else "运行中 · 00:12",
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            LinkButton(
                text = "切换状态",
                onClick = { finished = !finished },
            )
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            insideMargin = PaddingValues(12.dp),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                logLines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            LinkButton(text = "打开管理器", onClick = {})
            Spacer(Modifier.width(4.dp))
            LinkButton(text = "导出报告", onClick = {})
        }
    }
}

private val logLines = listOf(
    "[spray] mm_struct stride=960 objects_per_slab=34",
    "W1: SELinux attempt 1/1",
    "W1: selinux permissive",
    "W2: cred repair at init_cred+8",
    "W2: child uid = 0",
    "W3: seccomp mode cleared",
    "route select_stack done=1 calls=1 success=1 status=0 clean=1/1 step=0",
    "exploit complete",
    "handoff: KernelSU ready",
)

/* ---------- shared ---------- */

@Composable
private fun MockScreen(title: String, content: @Composable () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = {}) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
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
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, fontSize = 14.sp, color = secondary(), modifier = Modifier.width(84.dp))
        Text(text = value, fontSize = 14.sp)
    }
}

@Composable
private fun secondary(): Color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f)
