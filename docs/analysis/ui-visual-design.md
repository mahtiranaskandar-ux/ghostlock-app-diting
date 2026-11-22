# UI visual / IA design (2026-11-21)

**Status:** proposed — awaiting operator approval. This is the look-and-information-architecture
companion to `ui-architecture-refactor-plan.md`. That plan owns the structure (slices N0–N5); this
document owns the surfaces and the visual rules (slices V1–V4). Neither ships without the other's
slices being ordered as §6 below.

**Baseline:** `document` @ `867f1294`, captured on the diting (5.10.236, build 750) on 2026-11-21:
`ui-before-home.png`, `ui-before-home-bottom.png`, `ui-before-advanced.png`, `ui-before-params.png`,
`ui-before-loadconfig.png`, `ui-before-editor.png` (kept in `/tmp` until N0 files them).

## 1. Principles

1. **Generation-first.** Generating a profile for this kernel is a primary job; the generation path
   is always on Home, never behind Advanced.
2. **One primary action, always visible.** Every screen has at most one filled action; on Home it
   is pinned above the navigation bar.
3. **A status names the next action**, not just a colour. If the app knows what is wrong, it says
   what to do next.
4. **A card groups.** A card is a set of rows; it is not a row.
5. **Density where it is a debug surface** (validation report, run log, measurements); calm where
   it is the main path (Home, generation).
6. **Every visible state is a state.** No ad-hoc booleans in composables; the card renders a
   `SupportStatus`, the sheet renders a run state.

## 2. What the current screens show (and why they change)

| finding | evidence | change |
|---|---|---|
| The primary action is below the fold; the status card scrolls away before it | `ui-before-home.png`: 执行 is 1.5 screens down | pinned action bar (§3.1) |
| Every element has the same card weight | all captures | cards group rows; one accent (§4) |
| 导出报告 is a filled button in the action band | `ui-before-home-bottom.png` | a row in the run group; same flow from the run sheet |
| The device card takes a quarter of the first screen, kernel wraps mid-number | `ui-before-home.png` | one identity line + a dialog (§3.1) |
| The same flow has two doors (生成配置 on Home, 加载配置 → 解析… in Parameters) | home + parameters captures | one 获取配置 card on Home: 生成配置 / 加载配置 |
| The first Parameters card renders as a bare title | `ui-before-params.png` | fixed in V2 (it is a bug, not a design) |
| The destructive 取消外部配置 is a neutral full-width button between benign rows | `ui-before-loadconfig.png` | 危险操作 section, error colour, confirm (§3.2) |
| The editor renders one card per scalar field | `ui-before-editor.png` | editor deleted; validation report (§3.4) |
| The status glyph is decorative and huge | `ui-before-home.png` | 20 dp icon inside the section header (§4.4) |

## 3. Surfaces

### 3.1 Home

```
┌──────────────────────────────────────────────────┐
│ GhostLock                                        │
│ Redmi K50 Ultra · SM8475 · 5.10.236            ⓘ │   identity line → device dialog
├──────────────────────────────────────────────────┤
│ ✓  受支持                                         │   SupportStatus card
│    配置已就绪                                     │
│    内置 5.10.236 · select_stack · 有效            │   detail line
├──────────────────────────────────────────────────┤
│ 获取配置                                          │
│   生成配置   从 OTA 链接或本地镜像             ›  │
│   加载配置   选择已有的 .conf 文件             ›  │
├──────────────────────────────────────────────────┤
│ 上次运行   完成 · 2 分钟前                      › │
│ 导出报告   日志、崩溃证据与当前配置             › │
│ 高级选项   运行设置、参数与调试                 › │
└──────────────────────────────────────────────────┘
│                     执行                          │   pinned bar
```

- **Identity line.** Device name · SoC · short kernel (`5.10.236`, not the full UTS string). Tapping
  ⓘ opens `AppDialog.Notice` with the three full values (the old `DeviceInfoCard` contents).
- **Status card.** Title, summary, detail line. The detail is the current source · route ·
  validity when a profile resolved; empty otherwise. One of four variants (§3.1.1).
- **Acquisition card.** Two rows, always present — this is the generation-first surface:
  生成配置 opens the generation flow at its source step (OTA link / local image), 加载配置 opens
  the SAF picker for an existing `.conf` through the existing effect. Both acquisition paths are
  one tap from Home; there is no second door in Parameters.
- **Run group.** Three rows in one card: 上次运行 (opens the run log/sheet for the last run),
  导出报告 (opens the report flow), 高级选项 (pushes Advanced). Rows, not buttons.
- **Pinned bar.** One filled button, height 52 dp, label and destination from the status table.
  Disabled (grey, no tap) only while a run is in progress, where it becomes a progress label.
- **No settings on Home.** CPU pair, safe mode, Shizuku, vr.ko all move to Advanced → 运行设置
  (§3.6). The Shizuku state still surfaces through `AccessPending`.

#### 3.1.1 Status variants

| status | variant | title | summary (detail on the third line) | bar label |
|---|---|---|---|---|
| `Ready` | success (green) | 受支持 | 配置已就绪 | 执行 |
| `AccessPending` | pending (neutral) | 通过 Shizuku 执行 | the Shizuku status line | 配置 Shizuku |
| `Missing` | needs action (amber) | 需要加载配置 | 加载或生成配置后可执行 | 去生成 |

Three states only, corrected 2026-11-21 (see `support-status-state-plan.md`, "Model correction"):
import is transactional and the physical pair is required for every device, so a rejected profile
never reaches Home. The detail line is the current source (内置 / 外部) and the declared route when
there is one. A rejected import or a failed generation is reported where it happens, with the field
paths — not as a Home state.

Bar destinations: 执行 → run; 配置 Shizuku → the Shizuku permission flow; 去生成 → the generation
flow.

### 3.2 Parameters — the profile manager

Acquisition lives on Home (§3.1); Parameters manages what is loaded. Today's 加载配置 screen
dissolves: its load action is Home's 加载配置 row, its parse actions are the generation flow, and
its builtin list lands here.

```
参数配置
┌ 当前配置 ────────────────────────────────────────┐
│ 外部配置 · imported_profile.conf                 │
│ 5.10.236 · select_stack · 有效                   │
│ [ 查看问题 ]                     [ 导出配置 ]    │
└──────────────────────────────────────────────────┘
┌ 其他来源 ────────────────────────────────────────┐
│ 内置配置                                      ›  │
└──────────────────────────────────────────────────┘
┌ 危险操作 ────────────────────────────────────────┐
│ 取消外部配置                            (红色)   │
└──────────────────────────────────────────────────┘
```

- The current-config card always has content (the empty-card bug is fixed here): name, release,
  route, validity; 查看问题 appears only when errors exist; 导出配置 is the SAF export of the
  current copy.
- 内置配置 is the one acquisition path kept off Home: a builtin is chosen automatically when it
  matches, so selecting one by hand is an override, not a first-run action.
- There is no 编辑配置 entry. The flow is 导出 → edit in a text editor → 重新导入.
- 取消外部配置 is an error-coloured text button in its own 危险操作 section; it confirms.

### 3.3 Generation flow — one screen, four steps

The five dialog types become steps with a header.

```
生成配置                                   1/4
●━━○━━○━━○
```

**1 来源**

```
┌ OTA 链接 ────────────────────────────────────────┐
│ https://…                                        │
└──────────────────────────────────────────────────┘
┌ 本地镜像 ────────────────────────────────────────┐
│ boot.img · ota.zip · vendor_boot · dtbo          │
│ 选择文件后自动判定需要补充哪些镜像               │
└──────────────────────────────────────────────────┘
                      [ 开始解析 ]
```

The sidecar prompts (vendor_boot / dtbo / xbl_config / uefi) are rows that appear inside this step,
not dialogs. Each says which step needs it and why (the existing `PhysPromptState` copy).

**2 解析** — progress with the extractor's key lines (`phys_source`, kernel, release); failure
names what is missing and returns to step 1 with the row highlighted.

**3 确认** — one summary card: kernel release, physical pair (load/offset), route verdict, stride
with its evidence level, and warnings. The route choice, the plan choice and the stride picker are
inline expansions of this step, not dialogs:
- ambiguous route → a choice list inside the step;
- stride → the recommendation rows inline (the existing `StridePicker` content);
- overwrite → a confirm at the bottom of the step.

**4 保存** — the profile always becomes a file first. There is no in-memory "apply":

```
保存配置
保存为文件后，用「加载配置」导入才会生效。
[ 保存到文件 ]                     ← SAF CreateDocument, suggested name from the release
```

After a successful save:

```
已保存 · 5.10.236-ghostlock.conf
[ 导入此配置 ]             [ 完成 ]
```

- 保存到文件 is mandatory and is the only way out of the step. A generated profile that is not
  imported never becomes the active profile.
- 导入此配置 is a shortcut for the picker, not a second activation path: it calls the same import
  (copy to app storage, select, refetch) with the URI the save just returned. If the read-back
  fails, it falls back to 加载配置.
- 完成 returns Home with the status card unchanged; the file stays on the card until imported.
- Why: one activation path and one source of truth. Validation, the copy, the selection and the
  migration run identically whether the profile came from generation, another device, or a text
  editor — and the user always has the file before anything else can consume it.

Back goes one step; the session survives navigation away and back (it is `ParseState`, not a dialog).

### 3.4 Validation report

Replaces the editor's red fields. Reached from the Home card (`FieldsInvalid`, `ProfileBlocked`) and
from Parameters → 查看问题.

```
配置需要修正 · 2 项                    [ 导出当前配置 ]
┌──────────────────────────────────────────────────┐
│ route.select_stack.waiter_shift                  │
│ 当前 -4 · 必须在 -2..2                    [复制]  │
├──────────────────────────────────────────────────┤
│ cred.security_offset                             │
│ 当前 128 · 必须 ≥ 136                     [复制]  │
└──────────────────────────────────────────────────┘
路径即配置文件中的键名；修改后重新导入。
```

- Row anatomy: dotted path (mono, selectable), current value, the failed rule, a copy action.
- Copy yields `key = value` for pasting into an editor's search.
- The top action exports the current copy so the user has the file to edit.
- No line numbers: the parser resolves to a map with no source positions; the key path is unique.
- `CopyUnreadable` and `ResolutionFailed` use the same frame with one reason block and no rows.
- Empty state should be unreachable; if it is reached, show 没有发现问题 and offer 重新检查 (a
  refetch), never a blank screen.

**Validation is a verdict, not a document model.** It is computed once per trigger from the
resolved profile (`supportOutcome`), never per keystroke or per field: no draft, no dirty state,
no as-you-type validation, no per-field validity. It is still state — `SessionState.support`, one
writer, a generation, and the trigger list in `support-status-state-plan.md` §2.5 — but the UI
never validates anything itself. A new way for the inputs to change is a new trigger; that list is
the whole refresh model. There is no file observer: the app's copy is app-private and changes only
through import, so editing the original externally requires a re-import, which is itself a trigger.

### 3.5 Run sheet — a log window

```
日志                                  运行中 · 00:12
────────────────────────────────────────────────────
[W1] …
[W2] …
────────────────────────────────────────────────────
完成 · KernelSU ready        [ 打开管理器 ] [ 导出报告 ]
```

- The log is the surface. Monospace 13 sp, auto-scroll while running, copy in the header — the
  current sheet's shape, kept.
- One status line at the top: state and elapsed while running, the terminal verdict after
  (from the run store, not from a parallel UI state).
- **No stage indicator.** W1/W2/W3 already appear as log lines and in the run-state record; a
  separate stepper would be a second truth about progress, and the two would drift.
- No default collapse. The log is the evidence; the sheet opens on it and stays on it.
- Not dismissible while running; the close control appears with the terminal status.
- Footer at terminal state: the verdict line plus 打开管理器 and 导出报告 (the same report flow as
  Home). A failure shows the exit code and the store's state — the log above explains it.

### 3.6 Advanced and run settings

`高级选项` keeps its list shape and gains one entry:

```
高级选项
运行设置             CPU 核组、安全模式、Shizuku、vr.ko   ›
强制攻击测试                                             [switch]
root 管理器                                              ›
参数配置                                                 ›
测量数据                                                 ›
导出内核日志                                             [switch]
关于                                                     ›
```

`运行设置` is a screen holding the four rows that left Home (CPU 核组 spinner, 安全模式, 通过
Shizuku 执行, vr.ko 反 root 对策) plus the Shizuku status line. The Shizuku switch stays here but its
effect is visible on Home through `AccessPending`.

### 3.7 Report flow

Keep the five existing steps (Choice / Developer / Generate / Import / Export) and give the flow a
header:

```
导出报告                                     2/4
●━━●━━○━━○
```

Direct export is one step and shows no counter (as today: `reportFlowSteps` of length 1). The
entry points are Home → 导出报告 and the run sheet's result card.

### 3.8 Screens that keep their shape

About, Measurements, MergePreview, Legacy, Builtin and VrKo keep their content; they receive the
density rules (§4) and the back stack (N1). None gains or loses an action in V1–V4.

## 4. Visual system

### 4.1 Type

| role | size / weight | use |
|---|---|---|
| screen title | 30 sp / 600 | the one large title per screen |
| card title | 17 sp / 600 | status title, section headers |
| body | 16 sp / 400 | row titles, summary |
| secondary | 14 sp / 400 | row summaries, detail lines |
| caption | 12 sp / 400 | hints, step counters |
| mono | 13 sp / 400 | log, key paths, values in the validation report |

One large title per screen. Section headers inside a page are 14 sp / 600, secondary colour.

### 4.2 Spacing and cards

- Page side padding: the existing 800 dp max-width rule (`pageContentPadding`), minimum 16 dp.
- Gap between cards: 12 dp. Inside a card: 16 dp horizontal, 12 dp vertical.
- Row height: 56 dp minimum for settings rows, 44 dp for debug lists (validation, measurements).
- Dividers: 1 dp, inset 16 dp, only between rows of the same card.
- A card has one job. No card inside a card. A single row is never its own card.

### 4.3 Colour

- One accent (Miuix primary) for filled buttons and the selected state.
- State colours only for state, mapped to Miuix containers so dynamic colour still works:
  success → a green container (dynamic-aware), needs action → amber, blocked → `errorContainer`,
  pending → `surfaceVariant`.
- Text: `onBackground` for titles, `onSurfaceVariantSummary` for secondary lines.
- The status card is the only tinted full-width surface on Home.

### 4.4 Icons

- 20–24 dp; the status icon is 20 dp inline before the title, not a centred illustration.
- No decorative glyphs larger than 32 dp anywhere.
- Every icon carries a content description; the status icon's description is the status title.

### 4.5 Buttons and actions

- **Primary**: filled, full width, only in the pinned bar (Home) or as the flow's forward action
  (generation, report). One per screen.
- **Secondary**: text links, not buttons. Miuix's `TextButton` is a filled pill by default
  (`ButtonDefaults.textButtonColors()`); a secondary action is a primary-coloured `Text` with a
  click modifier and 8 dp padding, so it never competes with the filled primary.
- **Chips** (step selectors, prototype switchers): 16 dp radius, 13 sp, primary container when
  selected, no container otherwise.
- **Destructive**: error-coloured text button inside a 危险操作 section, always followed by a
  confirm dialog. Never in the primary band, never between benign rows.
- Disabled actions explain themselves in the row summary, not with a toast.

### 4.6 Motion and state

- Keep the current navigation transitions; no new animation.
- The status card and the bar label change in place when the verdict changes; no cross-fade needed.
- The run sheet's status line updates in place; the log auto-scrolls only while running.

### 4.7 Accessibility

- 48 dp minimum touch targets; icon-only buttons get descriptions.
- No fixed heights on text-bearing rows (dynamic type must grow them).
- Contrast: state containers use the Miuix scheme's pairs; verify both themes.
- The status must be readable without colour: the icon and the title carry the meaning.

## 5. Strings

New or changed keys land in `values/` and `values-zh/` in the same commit; the string-pair test
guards them.

| key | zh | en |
|---|---|---|
| `home_device_info` | 设备信息 | Device info |
| `home_acquisition_title` | 获取配置 | Get a profile |
| `home_generate_title` | 生成配置 | Generate profile |
| `home_generate_summary` | 从 OTA 链接或本地镜像 | From an OTA link or a local image |
| `home_load_title` | 加载配置 | Load profile |
| `home_load_summary` | 选择已有的 .conf 文件 | Choose an existing .conf file |
| `generation_source_ota` | OTA 链接 | OTA link |
| `generation_source_image` | 本地镜像 | Local image |
| `generation_save` | 保存到文件 | Save to file |
| `generation_save_hint` | 保存为文件后，用「加载配置」导入才会生效。 | Save the file first; it takes effect only after you load it. |
| `generation_saved` | 已保存 · %1$s | Saved · %1$s |
| `generation_import_now` | 导入此配置 | Load this profile |
| `generation_done` | 完成 | Done |
| `home_last_run` | 上次运行 | Last run |
| `home_export_report` | 导出报告 | Export report |
| `home_export_report_summary` | 日志、崩溃证据与当前配置 | Logs, crash evidence and the current profile |
| `home_advanced_summary` | 运行设置、参数与调试 | Run settings, parameters and debugging |
| `home_action_run` | 执行 | Run |
| `home_action_shizuku` | 配置 Shizuku | Set up Shizuku |
| `home_action_issues` | 查看问题 | View issues |
| `home_action_generate` | 去生成 | Generate |
| `home_action_import` | 去导入 | Import |
| `home_action_supplement` | 去补充 | Add |
| `home_action_fix` | 去处理 | Resolve |
| `home_action_view` | 查看 | View |
| `validation_title` | 配置需要修正 | Profile needs fixes |
| `validation_count` | %1$d 项 | %1$d issues |
| `validation_copy` | 复制 | Copy |
| `validation_export` | 导出当前配置 | Export current profile |
| `validation_hint` | 路径即配置文件中的键名；修改后重新导入。 | The path is the key name in the file; edit it and re-import. |
| `validation_empty` | 没有发现问题 | No issues found |
| `params_current` | 当前配置 | Current profile |
| `params_view_issues` | 查看问题 | View issues |
| `params_other_sources` | 其他来源 | Other sources |
| `params_builtin` | 内置配置 | Bundled profiles |
| `params_danger` | 危险操作 | Dangerous |
| `run_settings_title` | 运行设置 | Run settings |

Changed: `kernel_profile_invalid_summary` (now the count, not "marked fields"), and the
`FieldsInvalid` row in `support-status-state-plan.md` §4 follows it. Deleted with the editor:
`profile_override_*`, `advanced_override_*`, `profile_diff_*`, `rebase_*`, `edit_config_*` — grep
for each before deleting; the string-pair test does not catch an orphan.

## 6. Slices and order

V-slices carry no structural change; each is a UI commit series with the screenshots as its gate.

| slice | content | depends on |
|---|---|---|
| **V0** | the static prototype (§7.1): `app/src/debug` only, mock data, no product code — the look gate | — |
| **N1** | typed navigation (plan §3.2) | — |
| **N2** | typed dialogs + `ParseState` (plan §3.3) | N1 |
| **N3** | `SupportStatus` + `FieldsInvalid(errors)` (support plan) | N1 |
| **N4a** | delete the editor screens; land the validation report (§3.4) | N3 |
| **V1** | Home (§3.1) — identity line, status card, acquisition card, run group, pinned bar | N4a |
| **N4b** | per-screen states/actions, ViewModel split (plan §3.5) | V1 |
| **V2** | Parameters manager (§3.2); LoadConfig dissolves — load → Home, parse → the generation flow, builtin → Parameters | N4b |
| **V3** | generation flow (§3.3) | N2, V1 |
| **V4** | run sheet (§3.5), Advanced/run settings (§3.6), report header (§3.7) | N4b |
| **N5** | durability and tests | V1–V4 |

V1 comes before N4b so the status card is built once, against the final verdict; its composables
take plain values and move unchanged when N4b splits the screen. The generation flow (V3) is the
one surface that changes behavior rather than shape: the dialog chain becomes a stepper, and the
parse session becomes navigable state.

## 7. Verification

### 7.1 The static prototype (V0)

`app/src/debug/kotlin/com/sesame/app/ui/PrototypeActivity.kt` renders every surface with mock
data; `app/src/debug/AndroidManifest.xml` declares it, so no release build contains either file.
Launch on the device:

```sh
adb shell am start -n com.sesame.app/.ui.PrototypeActivity
```

The bottom switcher selects 首页 / 参数 / 校验 / 生成 / 日志; on Home a second row selects the nine
status variants. Nothing is wired: buttons are no-ops except the switchers and the generation
step chips. Its purpose is to judge layout and hierarchy on the phone before any product code
moves — a screen that reads badly here is cheaper to change than after N1–N4.

Captured 2026-11-21 on the diting (build 789, debug): Home Ready / invalid, Parameters,
Validation, generation steps 1 and 4, run sheet.

- **Screenshots per slice**, the state list in the refactor plan §6.2 plus: Home in the nine
  verdict states; the validation report valid/invalid/unreadable/failed; the generation flow at
  steps 1–4 and its two failure states; the run sheet idle/running/success/failure.
- **Harness compatibility:** the pinned bar's bounds are stable; re-run the 执行 tap gate
  (`scripts/sigret-run-once.sh`'s live-bounds check) after V1 and record the bounds.
- **Both themes**, one capture each for Home/Parameters/validation/run sheet.
- **Strings:** the pair test plus a grep for deleted keys.

## 8. Out of scope

- No new visual language, icon set, animation system, tablet/foldable layout or onboarding.
- No change to the resolver's semantics, the profile format, the wire or the run protocol.
- No bottom navigation; one Home plus hubs, as today.
- The report flow's steps keep their content; only the header is new.

## 9. Risks

| risk | mitigation |
|---|---|
| The pinned bar moves 执行's bounds and the harness taps stale coordinates | the harness reads live bounds today; re-record after V1 and keep the bar at the same height across states |
| Nine verdict fixtures are needed for the screenshots; some are hard to produce on a device | craft profiles per the support plan's S5 (unreadable, edited release, schema 3, invalid field); `am start` debug extras where they exist |
| The generation stepper changes a flow with sidecar prompts and a SAF picker — the most stateful surface | V3 last; `ParseState` tests; the four failure states captured |
| Deleting editor strings orphans references | grep each key; the compiler catches code references, not resource-only ones |
| Dark/light divergence | one capture per theme per slice |
