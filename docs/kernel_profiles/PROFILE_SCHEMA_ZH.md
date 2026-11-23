# Kernel Profile 结构文档（配置系统）

> English: [PROFILE_SCHEMA.md](PROFILE_SCHEMA.md)

本文档描述 `app/src/main/assets/kernel_profiles/` 下内核配置（profile）的完整结构、字段语义、路由机制与校验规则，以及配置在 Kotlin / native 之间的流转方式。

> 适配新内核的操作步骤见 [README_ZH.md](README_ZH.md)；`execution` 调优建议见 [defaults_ZH.md](defaults_ZH.md)。

> **几个"版本"是彼此独立的东西**（不要当成同一条版本序列）：
> - **Profile schema 版本**：HOCON 配置代际，由每份 profile 里的 `schema_version` 字段表达（见第 2 节）。当前为 **`3`**，也是唯一接受的版本。旧的 `1`/`2` 文档以及未版本化的 `remote/main` 时代 JSON 报告已于 2026-11-22 退役：写 `1`、`2` 或没有版本号的文档一律拒绝；请用当前构建重新生成或导出（见第 11 节）。
> - **Options report 版本**：提取器 `--options-out` 文档有自己的 `schema_version`（当前 `2`）；它不是 profile，也不能当 profile 使用。
> - **Binary wire 版本**：magic `0x0D000721` 之后的 GLK1 传输版本。当前 writer 写 wire version `2`
>   （对象分段，见第 9 节）；它是唯一版本，Kotlin 与 native 版本绑定。
>
> 下文全部描述 schema v3。

## 0. 文件格式（HOCON）

内置配置、`index.conf`、共享配置与导入的偏移文件都按 **HOCON** 解析：

- JSON 是 HOCON 的子集；
- 支持 `#` / `//` 注释、尾逗号、`${var}` 变量替换（可选替换写 `${?var}`）；
- 支持 `include "file.conf"`（相对同目录、可嵌套、防循环）：读取 assets 时由 `AssetConfigLoader` 展开；
  **导入**时优先用一并选中的文件展开，其次查包内共享文件，缺失会报错并要求重新选择；
- 解析发生在 Kotlin 侧（`HoconSupport`），随后以类型化二进制结构体传给 native（见第 9 节）；
- 应用内部存储与导出均为 HOCON（`ghostlock-extract --format conf` 输出 flatten 自包含
  profile，携带 `schema_version = 3`，走常规导入路径）。
- 提取器输出是**候选来源**：镜像实际获得多少字段就写出多少，未获得的字段省略，不会借用
  相邻内核族的猜测值。因此候选 profile 可能不完整；导入成功后由 App 的字段校验填充
  `invalidPaths` 并在执行前拦截，生成成功不等于设备受支持。

## 1. 数据流总览

```
assets/kernel_profiles/<release>.conf     内置配置（HOCON）
assets/kernel_profiles/execution-tuning.conf   execution 通用调优（所有内核）
assets/kernel_profiles/execution-<route>.conf   各路由的 execution 调优 preset（由 resolver 加载）
assets/kernel_profiles/credential-6x.conf      6.x 凭据模板共享值
assets/kernel_profiles/kernelsnitch-6x.conf    6.x KernelSnitch 共享值
assets/kernel_profiles/<major.minor>-template.conf  参考模板（登记 index，调试页可手动加载；不参与设备匹配与自动回退）
filesDir/offsets.conf                     解析/导入的偏移（imported，HOCON）
内部覆盖（advanced override，sparse）    高级参数覆盖页写入
        │
        ▼  ProfileConfigController.resolve（Kotlin）
   resolved profile（单对象，全字段已合并）
        │
        ├──▶ 运行：类型化二进制（direct 与 Shizuku 都把字节写入 native 的 stdin）
        │      native 只做解析与几何使用，不再做参数校验
        ├──▶ 快照：filesDir/<release>.conf（HOCON，导出配置的源）
        └──▶ UI：参数覆盖页 / 高级参数覆盖树
```

合并优先级（低 → 高）：`execution-tuning`（+ 按路由的 `execution-<route>.conf`）→ `内置/profile + imported offsets` → `高级覆盖`。

CPU 组合不是 profile 输入：native 在运行时自行选择（`cpu_select`）；wire 的 `recommended_cpus` 两个必填槽位固定写入中性回退值 `0/1`。

## 2. 顶层结构

```hocon
# GhostLock kernel profile (HOCON; JSON stays valid)
release = "6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k"
schema_version = 3
kernel_major = 6
route {
  select_stack { waiter_shift = -2 }
}
fallback { to = "none" }
kernelsnitch { collisions = 4, mm_struct_sz = 1024 }  # 跨度：内置值或测量后写入
task_struct { prio = 132, cred = 2080, pi_lock = 2316 }
cred { copy_size = 136, caps_offset = 48, caps_count = 5 }
offset { init_task = 34464384, init_cred = 34538824 }
# execution 调优由 resolver 从 preset 文件提供；只写差异
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `release` | string | 必须与设备 `uname -r` 完全一致（区分大小写/后缀）；模板文件不参与匹配 |
| `schema_version` | int | 必须为 `3`；其它值（或缺省）一律拒绝。物理字段可为按设备映射（首选）、全设备标量或 `null`（native 默认） |
| `kernel_major` | int | `5` 或 `6`；用于地址解析与合法性检查，不再决定路由 |
| `pipeline` | list | 可选的有序执行链列表：`ghostlock`、`dirtyfrag`、`module`；缺省等价于 `[ "ghostlock" ]`，见下文 *执行链选择* |
| `route` | object | 显式路由父项，可含一个或多个分支；应用的选择器显示所有可行路由；见第 3 节 |
| `routes` | list | 遗留且忽略：应用没有路由顺序，按名称顺序显示可行路由；见第 3 节 |
| `fallback` | object | 回退声明（`to` + 可选 `route` 分支），见第 3 节 |
| 几何字段 | object/int | 按命名空间分组：`task_struct` / `cred` / `offset` / `kernelsnitch`。未使用的路由专属字段直接省略——不要写 `0` 或占位符；`null` 仅出现在模板和编辑过程中 |
| `execution` | object | 调优参数（advisory）；通用项来自 `execution-tuning.conf`，路由项来自 `execution-<route>.conf`；两者由 resolver 作为 preset 加载，设备 profile 不 include。只写差异 |
| `timer_98260` | object | 可选的定时器路由能力块（第 4.9 节）；缺省 = 仅原生 profile |
| `module` | object | 模块执行链的配对清单（第 4.10 节）；必须与 `pipeline` 中的 `module` 条目同时出现 |

### 执行链选择（`pipeline`）

`pipeline` 可选；缺少时等价于旧的默认值 `[ "ghostlock" ]`。它是一个有序链名列表：resolver 在任何阶段
开始前按顺序选择第一个可行的条目。合法取值：`ghostlock`、`dirtyfrag`、`module`。

`module` 是仅随内置包提供的链（无 W1 的 DirtyFrag，用每内核模块切换 SELinux，Magisk 作为用户态管理器）。
提取器从不生成它：只有与手编模块同版本一起随包提供的内置 profile 才能使用它，并由 `module` 块
（第 4.10 节）声明；外部导入的 profile 声明该值会被拒绝。该条目与块互为前提：只有其一都会被拒绝。

必填项按执行链区分：`release`、`schema_version`、`kernel_major` 始终必填；`route`、`task_struct`、
`cred`、`offset` 仅在列表包含需要 W1 的条目（`ghostlock` 或 `dirtyfrag`，包括旧默认值）时必填；
`[ "module" ]` 可以完全省略它们。若给出了 `route`，仍按第 3 节校验。KDP 镜像（`kdp = 1`）的顺序
必须是严格的 `[ "dirtyfrag" ]` 或 `[ "module" ]`。

## 3. 路由（route）机制

路由不再由内核版本或字段存在性推断，而是由配置显式声明。`route` 是父项，**允许一个或多个分支**——单分支即单路线配置，多分支构成应用路线选择器展示的超集（`docs/analysis/route-superset-plan.md`）：

```hocon
route { select_stack { waiter_shift = -2 } }
# 或者超集：
route {
  select_stack { waiter_shift = -2 }
  tcp_zerocopy { compact_waiter = 1 }
}
routes = ["select_stack", "tcp_zerocopy", "timer_98260"]
```

- **可用性是解析结果。** 应用逐分支校验（必填字段与静态守卫：waiter 形状、锚对齐、page-free 事实）以及可选的 `timer_98260` 块（完整的 fake 组）。只有可用项进入选择器；失败的分支不会使配置的其他分支失效。
- **没有路线顺序。** 应用按字母顺序展示可用路线；遗留的 `routes` 列表仍会被接受与校验，但被忽略。选择按精确 `uname -r` 记忆（不再可用的记忆项直接清除）；没有记忆时，字母序第一个可用路线为默认。native 路由需要 `route.<name>` 分支；定时器路由是 `timer_98260` 块，没有 route 分支。
- **载体（carrier）是分支内的列表键。** 路由可选载体用类似 `pipeline` 的列表键表达，块内携带各载体几何的并集（`docs/analysis/route-choice-matrix-plan.md`）：
  ```hocon
  route {
    select_stack {
      carriers = [ "native", "compat" ]
      waiter_shift = 1
      waiter_home = 488
      select_dst_depth = 496
      compat_futex_abi = 1
    }
  }
  ```
  没有 `carriers` 的分支保持旧规则：`compat_carrier = 1` 选 AArch32 载体，缺省为原生。列表多于一项时应用显示载体选择框，按 release+路由记忆选择，并据此注入 wire 的 `compat_carrier`；native wire 格式不变。
- **DirtyFrag 是运行选择，不是文档键。** 配置的 `pipeline` 有序列表是能力；仅当列表同时包含 `dirtyfrag` 与 `ghostlock` 时首页显示开/关框，"关" 在该次运行把顺序裁剪为仅 GhostLock。
- **运行时文档仍然是单路线。** 应用把选中的分支渲染进 native wire（定时器块渲染进定时器文档）；native 的 `fallback` 仍是主路由自身的内部回退。
- `fallback` 父项声明回退：`"to"` 取 `"none"` 或路由名；声明目标时同时给出 `"route"` 分支，
  存放回退执行所需的字段：
  ```hocon
  fallback {
    to = "select_stack"
    route { select_stack { waiter_shift = 1 } }
  }
  ```
  当前实现支持 `tcp_zerocopy` 失败后回退 `select_stack`；写 `"to": "none"` 即关闭。
- native 侧 `RouteKind` 枚举与 Kotlin 侧取值一一对应（`profile.h` / `ProfileConfig.Routes`）。

### 必填矩阵

| 字段组 | tcp_zerocopy | select_stack | multicast_waiter |
|---|:---:|:---:|:---:|
| `offset.init_task` / `offset.init_cred` / `offset.root_task_group` / `offset.selinux_enforcing` | 必填 | 必填 | 必填 |
| `task_struct.prio` / `task_struct.pi_lock` / `task_struct.pi_waiters` / `task_struct.pi_blocked_on` / `task_struct.cred` / `task_struct.seccomp` | 必填 | 必填 | 必填 |
| `kernel_major` ∈ {5,6}、`cred.copy_size`、`cred.caps_count` 及凭据模板边界 | 必填 | 必填 | 必填 |
| `route.tcp_zerocopy.compact_waiter` / `route.multicast_waiter.compact_waiter` | 必填 | | 必填 |
| `route.select_stack.waiter_shift` | | 必填（0 合法） | |
| `route.multicast_waiter.waiter_off`（>0）、`route.multicast_waiter.buffer_size`、`route.multicast_waiter.task_offset`、`route.multicast_waiter.lock_offset`、`offset.empty_zero_page`、`cred.ref_count`（>0） | | | 必填 |

带 `carriers` 列表的分支要求每个列出的载体字段：原生需要 `waiter_shift`（select），兼容需要 `waiter_home` + `select_dst_depth`（select）或 `compat_waiter_off` + `compat_buffer_size` + `compat_futex_abi`（multicast）。`compact_waiter` 对 tcp 与 multicast 始终必填；没有 `carriers` 的旧分支按原规则校验。

凭据模板边界（通用）：`cred.usage_offset + 4 ≤ cred.copy_size`；`cred.caps_offset + cred.caps_count × 8 ≤ cred.copy_size`；`cred.ref_count ≤ 4`；每个 `cred.refN_image` 非零、`cred.refN_offset + 8 ≤ cred.copy_size`。`multicast_waiter` 还要求 `cred_copy_size ≥ 0xa0` 且 `route.multicast_waiter.waiter_off + route.multicast_waiter.lock_offset + 8 ≤ route.multicast_waiter.buffer_size`。

## 4. 几何字段分组

### 4.0 内核对象与字段映射

配置字段名即内核结构成员名（`struct` 内偏移）：

```
task_struct
├── prio             → task_prio
├── normal_prio      → task_normal_prio
├── sched_task_group → task_sched_task_group
├── pi_lock          → task_pi_lock
├── pi_waiters       → task_pi_waiters
├── pi_top_task      → task_pi_top_task
├── pi_blocked_on    → task_pi_blocked_on
├── pid / tgid       → task_pid / task_tgid
├── atomic_flags     → task_atomic_flags
├── real_cred        → task_real_cred
├── cred             → task_cred
├── comm / tasks     → task_comm / task_tasks
└── seccomp          → task_seccomp
```

```
cred
├── usage            → cred_usage_offset / cred_usage_value
├── cap_*            → cred_caps_offset / cred_caps_count / cred_caps_value
└── 引用修复点        → cred_ref_count + cred_refN_offset / cred_refN_image
```

```
伪造多播对象（multicast_waiter 路由）
├── waiter 起点      → route.multicast_waiter.waiter_off
└── task / lock      → route.multicast_waiter.task_offset / lock_offset
```

`offset` 命名空间是内核映像符号偏移（`init_task`、`init_cred`、`empty_zero_page` 等），其中 `offset.slide_*` 是 KASLR 滑移探测锚点；它们与 `kernel_phys_load` 一起用于把符号地址换算为运行地址。

### 4.1 任务结构偏移（`task_struct`）

| 字段 | 含义 |
|---|---|
| `task_struct.prio` / `task_struct.normal_prio` | 任务优先级 / 常规优先级（PI 提升判定） |
| `task_struct.sched_task_group` | `sched_task_group` 偏移 |
| `task_struct.pi_lock` / `task_struct.pi_waiters` / `task_struct.pi_top_task` / `task_struct.pi_blocked_on` | `pi_lock`、`pi_waiters`、`pi_top_task`、`pi_blocked_on` |
| `task_struct.pid` / `task_struct.tgid` | PID / TGID |
| `task_struct.atomic_flags` | `atomic_flags`（用于现场清理判定） |
| `task_struct.real_cred` / `task_struct.cred` | real cred / cred 指针 |
| `task_struct.comm` / `task_struct.tasks` / `task_struct.seccomp` | `comm`、任务链表、`seccomp` |

### 4.2 凭据模板（`cred`）

| 字段 | 含义 |
|---|---|
| `cred.copy_size` | 凭据结构整体拷贝大小 |
| `cred.usage_offset` / `cred.usage_value` | 引用计数字段偏移 / 目标值 |
| `cred.caps_offset` / `cred.caps_count` / `cred.caps_value` | capability 集合偏移 / 数量 / 填充值 |
| `cred.ref_count` | 需要修复的引用字段数量（≤4） |
| `cred.refN_offset` / `cred.refN_image`（N=0..3） | 各引用字段偏移 / 应恢复的镜像值 |

### 4.3 内核符号与滑移（`offset`）

| 字段 | 含义 |
|---|---|
| `offset.init_task` / `offset.init_cred` | `init_task` / `init_cred` 相对内核镜像基址的偏移 |
| `offset.root_task_group` | `root_task_group` 偏移 |
| `offset.selinux_enforcing` | `selinux_state` 符号偏移；字段字节由 `selinux.enforcing` 块补充 |
| `offset.selinux_blob_sizes` / `offset.security_hook_heads` | SELinux/安全钩子相关偏移 |
| `offset.slide_nfulnl_logger` / `offset.slide_boot_id` / `offset.slide_loggers_0_1` | 地址滑移（KASLR）定位点 |
| `kernel_phys_load` | 内核物理加载地址（0 时按 SoC 公式回退） |
| `kernel_phys_offset` | DRAM 基址 / linear-map `PHYS_OFFSET`，用于 image→direct-map 换算（缺省用编译期 `P0_PHYS_OFFSET = 0x80000000`）。DRAM 基址不同的设备需填写（如 MTK `0x40000000`）；`boot.img` 不携带该值——联发科设备上提取器从 `vendor_boot.img` 内嵌 DTB 的 `/memory` 节点（或 `--dtbo`）读取，root 设备也可取自 `/proc/iomem` |

`recommend_shizuku` 已于 2026-11-23 从作者侧 schema 移除；现有 schema-v3 文档中仍可接受但会被忽略。Shizuku 仅由应用内开关手动启用。

### 4.4 select_stack / tcp 路由字段

| 字段 | 含义 |
|---|---|
| `route.select_stack.waiter_shift` | select 路由 waiter 在栈上的相对位移（0 合法）；回退声明下对应 `fallback.route.select_stack.waiter_shift` |
| `route.select_stack.lock_anchor_image` | 可选。以镜像相对偏移给出的、内容为零且无人写入的内核区域，用作 walk 的假 `rt_mutex`（替代回收页），使 `rt_mutex_enqueue` 的 descent 不论该页内容如何都必定在第一次判断退出。缺省 ⇒ 仍用回收页（旧行为）。按设备选择并验证（见 `docs/analysis/payload-page-option2-plan.md`） |
| `route.tcp_zerocopy.compact_waiter` | tcp 路由的紧凑 waiter 布局标记；multicast 分支下同样需要（`route.multicast_waiter.compact_waiter`） |

### 4.5 multicast_waiter 路由字段（`route.multicast_waiter`）

| 字段 | 含义 |
|---|---|
| `route.multicast_waiter.waiter_off` | 多播缓冲区中 waiter 的偏移（必须 > 0） |
| `route.multicast_waiter.buffer_size` | 伪造缓冲区大小 |
| `route.multicast_waiter.task_offset` / `route.multicast_waiter.lock_offset` | 缓冲区中任务 / 锁字段偏移 |
| `route.multicast_waiter.lock_anchor_image` | 可选。以镜像相对偏移给出的、内容为零且无人写入的内核区域，用作 walk 的假 `rt_mutex`（替代回收页），使 walk 的 descent 不论该页内容如何都必定在第一次判断退出。缺省 ⇒ 仍用回收页（旧行为）。此时锚点的 `lock` 字取自该区域而非该页。按设备与内核构建选择；填错会导致内核崩溃 |
| `route.multicast_waiter.lock_anchor_bytes` / `route.multicast_waiter.lock_anchor_stride` | 可选。该区域已验证安全部分的长度，以及其中槽位的步长（槽位数为 `bytes / stride`）。带锚点的路由要求**三项齐全**：没有可继承的默认值，不得把 diting 的网格（4096 / 32）套用到其他内核。native 会拒绝 `bytes` 非零但不是 `stride` 整数倍的几何，或 `stride` 小于假 `rt_mutex` 自身大小的几何。这三项写在本节而不是借用 `route.select_stack`，因为文档从不跨路由合并 |
| `offset.empty_zero_page` | `empty_zero_page` 偏移 |

### 4.6 KernelSnitch 参数（`kernelsnitch`）

所有路由通用（KernelSnitch 用于 `mm_struct` 泄漏搜索），不随路由取舍：

| 字段 | 含义 |
|---|---|
| `kernelsnitch.collisions` | 需要的 futex 碰撞数量 |
| `kernelsnitch.mm_struct_sz` | `mm_struct` 的 SLUB 对象跨度。内置配置携带各自内核家族的实测值（5.10：960；5.15/6.1：1024；6.6/6.12：1280，已用 QEMU 确认）；生成的配置只在测量后写入（生成时的选择器或 root 读取 `/proc/slabinfo`），绝不猜测。省略时使用编译默认值 `0x500`。它决定 spray 的每 slab 对象数（`ORDER3_SIZE / stride`），泄漏扫描本身仍与跨度无关。 |

### 4.7 SELinux 状态（`selinux`）

`selinux_state` 内部的字段偏移，供 W1 与 page-free scratch 的取值校验使用；它们不是镜像偏移，因此不在 `offset.*` 下。

| 字段 | 含义 |
|---|---|
| `selinux.enforcing` | `selinux_state` 内 `enforcing` 字段的字节偏移；5.10/6.x 为 `0`，5.4 系列为 `1`（其 `disabled` 字节无条件存在，使后续字段后移）。W1 写入值的第 0 字节落在此处。 |
| `selinux.initialized` | W1 写入值中落在初始化字段上的字节（`initialized_struct − enforcing_struct`）。page-free 写入值必须使该字节非零；提取器据此选择 scratch。无 BTF 时的回退：6.6 之前为 `2`，6.6 起为 `1`。 |

提取器在 5.15/6.x 上从 BTF 生成，在 5.10 与 5.4 上从实测版本表生成（表中配置前提仅作为来源记录，不作为门禁）；两者都无见证时失败关闭。缺少该块时使用版本回退（5.4：enforcing `1`、initialized `2`）。解析器将 `enforcing` 与 `initialized` 校验为 `0..7` 内的字节偏移。

### 4.8 W1 scratch 与 page-free 模式（`w1_scratch`）

`w1_scratch` 指定一个镜像内区域，其直接映射别名作为 W1 的写入值（`alias(image) + 0x100`）：写入目标是字段而非结构体基址，第 0 字节落在 `selinux.enforcing` 上且必须为 0，写入值中第 `selinux.initialized` 字节必须非零。提取器通过锁锚扫描选择位置：锚内窗口存在时使用锚内（native 会保留重叠的锁槽），否则使用第二个安全区域。profile 只记录镜像偏移；错误的值会导致内核崩溃。

| 字段 | 含义 |
|---|---|
| `w1_scratch.image` | 用于 W1 取值的零值、可写、无引用区域的镜像偏移。缺省 => 回收页路径 |

路由策略支持 page-free（`select_stack`、`sigreturn`、`multicast_waiter`；`tcp_zerocopy` 因假锁是构造的页对象而排除）、profile 带 `w1_scratch.image`、且带该路由的 `lock_anchor_*` 三元组时，本次运行即 page-free。该标志是每次运行的常量，运行存储首行给出：`page_free=1 reason=policy+scratch+anchor`，或 `page_free=0 reason=<第一个缺失条件>`（`route-not-capable` / `no-scratch` / `no-anchor`）。

page-free 路径不调用 `prepare_good_kernel_page()`：无 spray、无 KernelSnitch 泄漏、无回收套接字、无页面接受检查、无 `[spray]` 行、`base=0`。每次写入记录取值来源（W1/W1p 为 scratch，W2 为 `init_cred`，叶子写入为 `target-8`）。基于页的凭据复制/填充与 fops 哨兵仍留在页路径；缺少任一锚的 profile 行为不变。两个键都是可选的，且不改变 `schema_version`（始终为 `3`）；缺省键即页路径。

### 4.9 定时器路线的能力块（`timer_98260`）

供 Shizuku 投递的定时器工具（CVE-2026-98260）使用的可选顶层块。它是原生路线在 Kotlin 侧的
同级配置，不是 `route` 分支：原生利用从不运行该工具，原生 wire 也从不携带此块（同一 profile
带与不带此块生成的原生文档字节一致）。没有此块的 profile 即纯原生配置，定时器路线不可用。

设备上生成的 profile 也可以携带此块：解析流程对同一镜像运行定时器分析器，只有应用的解析器
接受时才合并该块（`docs/analysis/timer-block-generation-plan.md`）。只有 real 路径的块是合法
的，但定时器路线仍不可用（way 2 运行需要 fake 组）。

```hocon
timer_98260 {
  version = 1
  timer_site_lower_21bit = 507136
  timer_object_size = 264
  cred_request_size = 168
  cred_alloc_size = 192
  cred_uid_offset = 4
  timer_node_offset = 120
  page_alloc_gfp_offset = 20
  page_alloc_gfp_size = 4
}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `version` | int | 恒为 `1`；块的演进不改变 `schema_version` |
| `timer_site_lower_21bit` | int | `do_timer_create` 中 `kmem_cache_alloc(posix_timers_cache)` 的返回地址，按 21 位掩码（与 trace 扫描的比对方式一致） |
| `timer_object_size`、`cred_request_size`、`cred_alloc_size` | int | SLUB 尺寸：`posix_timers_cache` 对象大小、`cred_jar` 请求大小与分配大小 |
| `cred_uid_offset` | int | `cred.uid` 偏移 |
| `timer_node_offset` | int | `k_itimer.it.cpu.node` 偏移（可选，默认 `0x78`） |
| `static_timer_site`、`task_alloc_site_lower_21bit`、`task_alloc_size`、`task_cred_offset`、六个数据符号 | 仅 fake | fake 路径常量；数据符号是内核 VA，**必须写成带引号的十六进制字符串**（`"0xffffffc00a2293d0"`），HOCON/JSON 数字无法承载 |
| `cred_usage_size`、`cred_caps_offset`、`cred_caps_count`、`cred_security_offset`、`cred_user_offset`、`cred_user_ns_offset`、`cred_ucounts_offset`、`cred_group_info_offset` | 仅 fake | 伪造 cred 的布局：伪造对象各字段的位置。仅当 cred 有 `ucounts` 时才有 `cred_ucounts_offset`（5.10 省略它，并同时省略 `init_ucounts`） |
| `security_sid_offset` | 仅 fake | 伪造 cred 的 `security` 指针所指 **LSM blob 内**的 `sid` 偏移——不是 cred 偏移 |
| `page_alloc_gfp_offset`、`page_alloc_gfp_size` | int, int | 原始 `mm_page_alloc` 记录中 `gfp_flags` 的位置：5.10 为 `0x14`/`4`，6.x 为 `0x18`/`8`。回收的 frag/slab 判别依赖它；布局错误会静默关闭该判别 |

规则：块内未知键报错；真实路径的五个字段总是必需。块没有模式键：fake 结局由结构声明，
因此**任一** fake 键出现就要求整个 fake 字段组（含扁平的 cred 键），而没有这些键的块只支持真实结局。
`0` 只在它就是真实值时才写（5.10 的 cred 没有 `ucounts`，因此省略
`init_ucounts`/`cred_ucounts_offset`）；每个 `cred_*_offset` 必须落在 `cred_request_size` 内
（caps 区间也要放得下），fake 路径的
`task_cred_offset` 必须落在 `task_alloc_size` 内；`static_timer_site` 的低
21 位必须等于 `timer_site_lower_21bit`（二者来自同一镜像站点，不一致会推出错误的 KASLR slide）；
`timer_site_lower_21bit` 与
`task_alloc_site_lower_21bit` 为 21 位；存在实测 `/proc/slabinfo` 行时，
`timer_object_size`/`cred_request_size`/`cred_alloc_size` 必须与其一致。

app 把合并并通过校验的 profile 渲染为规范的后端文档（`ProfileDocument`）：顶层
`release`/`schema_version`/`kernel_major`、`kernel` 块（共享几何）、`native` 块
（route/fallback/pipeline/execution）以及本块。定时器工具只读 `release` 与 `timer_98260` 并
自行复核不变量；本版本中原生仍使用二进制 wire。文档中的 64 位值一律写成 `"0x..."` 字符串。

### 4.10 模块链的配对清单（`module`）

模块链（无 W1 的 DirtyFrag、每内核模块切换 SELinux、Magisk 作为用户态管理器）的可选顶层块。
它是 Kotlin 侧的块：原生利用从不读取它，原生 wire 也从不携带它（同一 profile 带与不带此块生成的
原生文档字节一致）。它是仅随内置包提供的配对的唯一声明——内置 profile 与它命名的、手工构建并
一起验证的 `.ko`——不存在单独的资产索引。

块与 `pipeline` 条目互为前提：`pipeline = [ "module" ]` 要求该块；没有条目的块会因无意义而被拒绝。

```hocon
module {
  version = 1
  asset = "modules/5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284.ko"
  asset_sha256 = "…"                       # 64 个十六进制字符
  vendor_files = [                         # 可选；缺省 = 内置探测列表
    "/vendor/lib64/libbinderdebug.so",
    "/vendor/lib64/libstagefrighthw.so",
    "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
  ]
}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `version` | int | 恒为 `1`；块的演进不改变 `schema_version` |
| `asset` | string | 内置工件，相对于 `assets/`：`modules/<name>.ko`；不允许绝对路径、`..` 或空路径段 |
| `asset_sha256` | string | 64 个十六进制字符；哈希不符的工件会被拒绝 |
| `vendor_files` | list | 可选的有序 `vendor_file` 投递目标；缺省 = 应用内置列表。使用第一个存在、可通过 bridge 读取且容纳模块的路径。每项为绝对 `/vendor/…` 路径，短于 stub 的 64 字节目标字段（63 字节 + NUL）；显式空列表会报错 |

规则：未知键报错；`version`、`asset`、`asset_sha256` 必填；有 `module` 条目而无块、有块而无条目，
都会被拒绝。

镜像的模块 ABI（vermagic、`sizeof(struct module)`、`module_layout` CRC）刻意不作为 profile 键：
构建关卡固定了镜像的精确配置，加载器强制 ABI（错误的 magic 半段或 CRC 会干净地返回 `ENOEXEC`），
而 `asset_sha256` 绑定了确切工件。唯一不干净的加载结果——`struct module` 过小——由“使用镜像精确
配置构建”在编写阶段避免；应用读不到运行内核的 ABI（5.10 无 BTF，也无法读取配置），因此 profile
里的副本无法与之核对。

应用在任何投递之前解析配对：打开 `assets/<asset>` 并校验哈希，然后探测选择 `vendor_files` 目标。
该块会渲染进规范后端文档（`ProfileDocument`），以便往返保留；外部导入的 profile 仍不能启用该链
（仅限内置包）。

## 5. execution 调优参数（advisory）

`execution` 全部为建议值，随 profile 合并后传入 native；数值语义与默认值见 [defaults_ZH.md](defaults_ZH.md)。通用分组来自 `execution-tuning.conf`，路由分组由 resolver 从 `execution-<route>.conf` 加载（主路由 + 回退路由），设备 profile 不再 include 它们：

- `recommended_cpus`：wire 的 CPU 组合回退值。不在 profile 中编写、不在任何编辑器中显示；应用写入 `0/1`，native 在运行时自行选择。
- `heap`：KernelSnitch 搜索的尝试次数与超时
- `race`：路由竞态等待/稳定/轮询间隔
- `stages`：W1/W2/W3 尝试次数与稳定时间
- `routes.tcp_zerocopy` / `routes.select_stack` / `routes.multicast_waiter`：各路由的重试与等待参数，分别位于 `execution-tcp-zerocopy.conf` / `execution-select-stack.conf` / `execution-multicast-waiter.conf`；Kotlin 在合成 native 文档时会为缺失的路由组补默认值，因此 native 始终收到完整 `routes`
- `handoff`：root 交接与 KernelSU 加载轮询

## 6. 校验与反馈

校验在 Kotlin（`AndroidProfileConfigController.validateProfileFields`）完成：

1. 按第 3 节矩阵检查公共与所选路由的字段：缺失（`null`）或为 `0` 的必填项、越界组合（凭据/多播边界）都会记录到 `ProfileConfig.invalidPaths`。
2. 参数覆盖页与高级参数覆盖中，非法项以红色 label 显示（未填写同样标红）；已覆盖且合法项为黄色。
3. `fallback.to` 必须是 `"none"` 或合法路由名；声明回退时，目标分支的必填字段同样会被校验（如回退 `select_stack` 需要 `fallback.route.select_stack.waiter_shift` 存在，0 合法）。
4. 主页“执行”按钮在 `invalidPaths` 非空时置灰，点击提示修正红色项；即使绕过，`runExploit` 也会在启动 native 前拦截并写入日志。
5. native 不再做几何校验，只解析 v2 二进制并按组件选择与字段执行。

## 7. 加载层次与存储位置

| 层 | 来源 | 位置 | 写入者 |
|---|---|---|---|
| shared | 共享值；tuning preset 由 resolver/exporter 加载，core 共享值由设备 profile `include` | `execution-tuning.conf` / `execution-<route>.conf` / `credential-6x.conf` / `kernelsnitch-6x.conf` | 随包发布 |
| builtin | 精确 `uname -r` 命中；未命中即视为不支持 | `assets/kernel_profiles/*.conf` | 随包发布 |
| imported | 解析/导入的偏移（同一 release entry） | `filesDir/offsets.conf` | 解析 OTA / 导入配置文件 |
| general override | `execution.*` | `filesDir/offsets.conf` 的 release entry | 参数覆盖页“一般参数覆盖 / 重设参数” |
| route override | `route` / `fallback.to` | `filesDir/offsets.conf` 的 release entry | 高级配置覆盖页“路由 / 回退” |
| advanced override | 任意数值路径（sparse，HOCON 文本） | 内部 `debug_profile_overrides` | 高级配置覆盖（自动保存） |
| 快照 | 合并后的完整 HOCON | `filesDir/<release>.conf` | 任何覆盖保存后、导出前 |
| 手动内置来源 | 指定其他真实 release（危险） | 内部 `debug_builtin_release` | 参数页“加载其他内置配置（危险）”；覆盖仍绑定本机 release |

导出配置：把该 release 的合并快照（HOCON 单文件，include 已合并、已按路由精简调优项）写入用户选择的文件夹（SAF）。

## 8. 模板配置（*-template）

- 每个大版本一个：`5.15-template` / `6.1-template` / `6.6-template` / `6.12-template`，登记在 `index.conf`，仅作**开发与调试参考**。
- 所有几何/偏移字段为 `null`（未填写），`route` / `fallback` 给出完整分支结构；每个字段上方都有中文说明注释，可直接复制填写。
- 在内置选择页的“模板（参考，未填写）”分区可手动加载，用于查看字段结构；模板**不参与设备匹配，也不做自动回退**——设备无精确命中且无 imported 时视为不支持。
- 模板只含 core 字段（以及 core 的 include）；execution 调优由 resolver 从 `execution-*.conf` 提供，复制模板无需补 tuning include。仓库内的同步副本见 `docs/kernel_profiles/templates/`，assets 中的原文件可用 adb 查看。

## 9. native 传输与解析

运行时的配置传输是**类型化二进制结构体**（v2，对象分段），不再是 JSON 文本。v2 是唯一版本：Kotlin 与 native 版本绑定，不做旧版本兼容解码。

- Kotlin 侧由 `NativeProfileDocument` 经 `toBinary()` 序列化：先是 16 字节小端头
  `u32 magic(0x0D000721) + u16 version(2) + u16 frontend + u16 backend + u16 middleware + u16 release_len + u16 reserved`，
  随后是 `release` 文本、`u16 section_count`，每个 section 为 `u8 name_len + name + u32 entry_count`，每个条目为 `u8 key_len + key + u64 value`。
  presence 由键是否出现表达（缺席 ≠ 提供的 0）；值为 u64 原始位型（有符号为二补数），不做 clamp；只写/接受**当前 route** 的 `route.*` section；未知 section/键忽略；重复键 last-wins。`middleware` 承载 route。权威 section/键表在 `profile/binary.cpp`（`kSections`），`NativeProfile.kt` 的对象 section 必须逐字对齐。
- 传输路径：direct 与 Shizuku 都把 profile 以 **stdin** 交给 native（`--ghostlock-app-call`），不再落盘 `active-profile.bin`、也不再使用 `--profile`。
- native 只有一条解码路径：`profile/entry.cpp` 把 stdin（或文件）字节交给 `profile/binary.cpp::parse`；它不检测 magic 之外的格式，也没有 JSON 回退。
- 内部存储与“导出配置”均为 HOCON、schema v3（人类可读）；旧版本文档一律拒绝、不做转换（见第 11 节）。
- 运行时路由与能力判断（`TargetProfile::route()`、`TargetProfile::supports()`、`route_capability`）全部基于解析后的 route。

## 10. 修改配置的检查清单

1. 每份 profile 都要包含当前路由的全字段与公共几何，且每项都必须出现；镜像或设备无法提供的值写显式 `null`（不要用 `0` 占位，除非 0 就是真实值）。非当前路由（及其声明的 fallback）的字段省略。`ghostlock-extract --format conf` 会输出这份完整骨架；`null` 使字段在 app 中可见可编辑，而不是悄无声息地缺失。
2. `route` 只能有一个分支，且分支内必须给出该路由的必填字段；`fallback.to` 声明了回退目标时，`fallback.route` 分支内同样要补齐。
   共享 core 值通过 `include` 引入，不要复制：`credential-6x.conf`（6.x 凭据模板）、`kernelsnitch-6x.conf`（6.x collisions）。execution 调优（`execution-tuning.conf` / `execution-<route>.conf`）由 resolver 作为 preset 加载，设备 profile 不要 include。
3. 修改 `execution` 需要设备实测依据；否则保持 defaults。
4. 本地验证：`make native-host-tests`（profiles 解码/校验向量）与 `./gradlew :app:assembleDebug`。
5. 修改字段命名/分组时同步更新：`FieldLabels.kt` + `values*/strings.xml`、可能的 `docs/kernel_profiles/defaults*.md`。

## 11. 旧 schema 版本（2026-11-22 退役）

**v3 是唯一接受的 profile 格式。** 以下内容已删除，不做转换：

- **v1 / v2 profile**（`schema_version = 1` 或 `2`）：导入与加载时以 `schema_version` 错误拒绝；应用写出的每份文档都是 v3。
- **未版本化的 `remote/main` 时代 JSON 报告**（`symbols` / `struct_fields` 扁平 offsets）：不再转换。`ghostlock-extract --format json` 仍为外部工具输出该报告形状，但它不是 profile。
- 旧版转换器、导入副本的自动版本升级，以及 v0→v1→v2 的迁移测试一并删除。

旧 profile 需要迁移时，请用当前构建从镜像重新生成（`ghostlock-extract --format conf`），或用仍能读取它的应用版本导出；结果携带 `schema_version = 3`。
