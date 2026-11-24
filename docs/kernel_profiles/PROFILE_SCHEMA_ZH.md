# Kernel Profile 结构文档（schema v3）

一份文档描述一个内核镜像：每台设备的物理地址对、引擎要读的内核结构与符号、
GhostLock 引擎的 syscall 目录，以及定时器引擎的块。到处都用同一形状——
内置资产、test-kernels store、app 解析后的文档、每个 native 后端读取的 JSON，
以及导出的 profile。

英文版：[PROFILE_SCHEMA.md](PROFILE_SCHEMA.md)。

## 0. 文件格式

HOCON。JSON 是合法 HOCON，所有工具也都能输出纯 JSON。注释（`#`）被忽略。

| 值 | 写法 |
|---|---|
| 内核地址（镜像相对符号、锚点、定时器 site） | 带引号十六进制字符串：`"0x2a3a590"` |
| 结构偏移、大小、计数 | 十进制数字：`2200` |
| 标记 | `true` / `false` |
| 枚举 | 带引号单词：`"feasible"`、`"compact"`、`"time64"` |
| 无值 | `null`——绝不省略键 |

**null 也是一个值。** 文档始终写出完整键集；没有值的键写 `null`。app 自有块里
出现未知键是导入错误，缺少键同样是错误。

## 1. 数据流

```
boot.img ──ghostlock-extract──> options-<release>.json（store：分析信封
                               + v3 `document`）
                                   │
                    timer-extract ─┘ 合并定时器片段
                                   │
                    ship_golden_profiles.py
                                   ▼
                     app/src/main/assets/kernel_profiles/<release>.conf
                                   │
   外部副本（SAF）> 内置选择 > 内置资产             （层次解析）
                                   ▼
             app 解析后的文档（+ 实测 overlay）
                                   ▼
             ProfileTransport.render → 每次运行一份 JSON 文档
                                   ▼
             libghostlock（kernel+ghostlock 块）   libtimer 工具（timer 块）
```

设备端生成对手机自己的镜像运行提取器和定时器分析器，在内存中合成同一份文档并导出；
导出文件被用户导入后即成为外部 profile。

## 2. 顶层

```hocon
schema_version = 3
release = "5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284"

devices = null            # 或 devices { <codename> { phys { load = "0x…", offset = "0x…" } } }
kernel = { … }
ghostlock = { … }         # GhostLock 引擎；纯定时器文档写 null
timer = null              # 定时器引擎；镜像没有定时器块时写 null
```

- `release` 与设备 `uname -r` 逐字一致。app 拒绝 release 与运行内核不符的文档。
- `schema_version` 固定为 `3`。v1/v2 文档被拒绝，不做升级。
- `devices` 是 `null` 或**文档支持的所有身份**的 map。map 中没有当前设备即拒绝
  （fail-closed），没有中性回退。当前设备的条目提供 `phys.load` / `phys.offset`
  （物理加载地址与 DRAM 基址）。
- `kernel` 永远存在。`ghostlock` 与 `timer` 指定引擎：至少一个非空。

### 2.1 引擎选择（没有 pipeline 列表）

app 从文档自身的事实推导执行梯；文档从不携带链列表。

| 事实 | 执行梯 |
|---|---|
| `kernel.kdp = true` | `["dirtyfrag"]` |
| `kernel.dirtyfrag = "feasible"` | `["dirtyfrag", "ghostlock"]` |
| `kernel.dirtyfrag = "infeasible"` / `"unknown"`，`ghostlock` 非空 | `["ghostlock"]` |
| `ghostlock.module` 非空 | `["module"]` |
| `ghostlock = null`，`timer` 非空 | `["timer"]` |

定时器引擎单独选择（它是经 Shizuku 交付的 shell 工具，不是 native route）：
`timer` 存在且 fake 组完整（`Timer98260.fakeSupported`）时 app 才提供。

## 3. `kernel`

```hocon
kernel {
  image_base = "0xffffffc008000000"
  symbols { … }
  struct { task {…}, cred {…}, waiter {…}, rt_mutex {…}, selinux_state {…} }
  heap { stride = 960, collisions = 8 }
  dirtyfrag = "feasible"
  kdp = false
}
```

### 3.1 `image_base` 与 `symbols`

`image_base` 是镜像的链接基址（`_text`）。每个符号都是镜像相对偏移，带引号十六进制；
native 加上运行期 KASLR 滑移。

十五个键固定：

| 键 | 含义 | 来源 |
|---|---|---|
| `init_task`、`init_cred`、`root_task_group`、`empty_zero_page` | 内核对象 | 主提取器 |
| `selinux_state`、`selinux_blob_sizes`、`security_hook_heads` | SELinux 状态与策略 | 主提取器 |
| `nf_logger`、`loggers_0_1` | netfilter logger 锚点（滑移校验） | 主提取器 |
| `boot_id` | boot-id 字 | 主提取器 |
| `panic_on_warn`、`root_user`、`init_user_ns`、`init_ucounts`、`init_groups` | 定时器引擎的符号组 | 定时器分析器 |

没有 ucounts 的内核（5.x）`init_ucounts` 为 `null`。

### 3.2 `struct.task`（15 个偏移）

`prio`、`normal_prio`、`sched_task_group`、`pi_lock`、`pi_waiters`、
`pi_top_task`、`pi_blocked_on`、`pid`、`tgid`、`atomic_flags`、`real_cred`、
`cred`、`comm`、`tasks`、`seccomp`——`task_struct` 内偏移，十进制。

### 3.3 `struct.cred`

```hocon
struct.cred {
  copy_size = 168
  usage { offset = 0, size = 4, value = 256 }
  uid = 4
  security = 120
  security_sid = 4
  caps { offset = 40, count = 5, mask = "0x1ffffffffff" }
  refs {
    user       { offset = 128, image = "0xffffffc0827af5e0" }
    user_ns    { offset = 136, image = "0xffffffc0827af678" }
    ucounts    { offset = null, image = null }
    group_info { offset = 144, image = "0xffffffc0827b0b08" }
  }
}
```

- `copy_size` 是 W2 拷贝移动的字节数。
- `usage` 是引用计数字段；`value` 是修复写入的目标值。
- `caps.offset`/`count` 是修复写入的五个 `kernel_cap_t` 字；`mask` 是写入值。
- `refs` **按布局成员命名，不按位置**。5.x 内核只有三个成员（`user`、`user_ns`、
  `group_info`），`ucounts` 的 offset/image 均为 `null`。`image` 是拷贝后该成员
  必须指向的内核 VA。
- 定时器分析器提供 `security_sid`、引用偏移以及它 BTF 能给出的块；它没有给出的值
  保持 `null`（无意见）。

### 3.4 `struct.waiter`

```hocon
struct.waiter {
  shape = "compact"    # 或 "node"（rt_waiter_node，6.6+）
  pi_tree = 24
  task = 48
  lock = 56
  wake_state = 64
}
```

`shape` 是 stamp 编码器使用的 waiter 布局。镜像的 waiter 是 compact 而 native
按 13 字 node 布局写（或反之）会写到错误的 strip；解析器拒绝在 `node` 镜像上
选择 `multicast_waiter`/`sigreturn`。

### 3.5 其余内核键

- `struct.rt_mutex.size`——锚点 stride 的下界。
- `struct.selinux_state.enforcing` / `.initialized`——W1 写入的 `selinux_state` 字段值。
- `heap.stride`——实测 `mm_struct` slab 步长。提取器从不生成：生成的配置写入探测值，
  内置配置携带 store 的实测值，手填模板由填写者给出。需要 W1 的链路（ghostlock 或
  dirtyfrag）必须带值；`null` 在导入、运行和导出时都会被拒绝。唯一的例外是生成流程
  的内存探测帧：它本身就是测量请求，因此可以不带值（该文档不会被导出或直接运行）。
  只有 root 的 `/proc/slabinfo` 实测（实测 overlay）可在运行时覆盖它。
- `heap.collisions`——KernelSnitch 碰撞数（5.x 为 8；6.x 布局已验证时为 4，否则 `null`）。
- `dirtyfrag`——DirtyFrag 静态判决：`"feasible"`、`"infeasible"` 或 `"unknown"`（未测）。
- `kdp`——Samsung RKP/KDP 事实；`true` 强制只走 DirtyFrag。

## 4. `ghostlock`

```hocon
ghostlock {
  syscall = "select_stack"          # 选中的 syscall
  carrier = null                    # null = 原生 64 位；"compat" = 32 位
  anchor { image = "0x2a3a590", bytes = 4096, stride = 32, scratch = "0x2a3a5c0" }
  syscalls {
    select_stack { native { waiter_shift = -2 }, compat = null }
    sigreturn = null
    multicast_waiter { … }
    tcp_zerocopy { native {}, compat = null }
  }
  module = null
  execution = null
}
```

### 4.1 `syscall` 与 `carrier`

`syscall` 指定一次运行使用的分支；`carrier` 在分支内选择 ABI。选中的组合必须存在于
`syscalls` 中，且所选 carrier 的块必须存在。

### 4.2 `anchor`

`image`（镜像相对）、`bytes`、`stride` 描述清零的锚点区域；`scratch` 是其内部的
W1 scratch 槽（或另一个区域）。页面方案写 `anchor = null`。app 检查附带写窗口是否
仍在锚点内。

### 4.3 `syscalls`——四个固定分支

每个分支键永远存在；不可行的 syscall 写 `null`。每个分支有 `native` 与 `compat`
两个 carrier 槽；存在的块就是镜像能跑的 carrier。

| 分支 | native 块 | compat 块 |
|---|---|---|
| `select_stack` | `waiter_shift` | `waiter_home`、`dst_depth`、`futex_abi` |
| `sigreturn` | `waiter_off { plain, sve }` | `null` |
| `multicast_waiter` | `waiter_off`、`buffer_size` | `waiter_off`、`buffer_size`、`futex_abi` |
| `tcp_zerocopy` | `{}` | `null` |

`futex_abi` 为 `"time32"` 或 `"time64"`。

### 4.4 `module`

模块链（内核模块交付）取代 syscall 目录：

```hocon
ghostlock.module = {
  asset = "modules/device.ko"
  sha256 = "<64 hex>"
  vendor_files = ["/vendor/lib64/libx.so"]
}
```

`syscalls` 与 `module` 恰好一个非空。

### 4.5 `execution`

可选调优。app 把 preset 合并到该块下并填满每个 `null`；文档可覆盖任意键。
完整键表见 [defaults_ZH.md](defaults_ZH.md)。

## 5. `timer`（CVE-2026-98260）

```hocon
timer {
  trigger = "killable"        # 或 "core_state"
  match_bits = 21
  sites {
    timer      { offset = "0x191af8" }
    task_alloc { low_bits = "0xab680" }
  }
  object_size = 264
  task_alloc_size = 4800
  node_offset = 120
  cred { request_size = 176, alloc_size = 192 }
  trace { gfp { offset = 24, size = 8 } }
}
```

`timer = null` 表示镜像没有定时器块（引擎不可用）。块本身的键集固定；分析器无法推导的
值写 `null`。引擎从同一份文档读取共享事实（`kernel.symbols`、`kernel.struct.cred`、
`kernel.image_base`）；缺少符号或引用时引擎不可用，而不是半可运行。

## 6. 校验与反馈

`ProfileResolver` 在任何运行前校验合并后的文档：

- 固定键集，接受 `null`、拒绝缺键；
- 选中的 syscall/carrier 及每个声明的 carrier 块的完整性；
- 执行梯前提（`kdp` ⇒ 只能 DirtyFrag；KDP profile 不能选 GhostLock）；
- 锚点包含关系与 waiter 形状 vs. syscall；
- 模块文档允许 `kernel` 全为 null；
- 纯定时器文档是完整形状、`ghostlock = null`。

导入失败时**整体拒绝**：报告字段路径、当前值与规则，之前的选择保持有效。

## 7. 加载层次与存储

1. **内置资产**（`app/src/main/assets/kernel_profiles/<release>.conf`）——随 app 发布；
   未被覆盖时权威。
2. **内置选择**——手动选择另一个内置 release。
3. **外部副本**——通过 SAF 导入的文档。导入时复制进 app 数据并成为选择；源文档消失后
   副本仍有效。选择只作用于它自己的 `release`；不包含运行 release 的文档是内容未命中，
   不是回退。

**实测 overlay**（`mm` 步长、DirtyFrag 探测结果）不是层次：它是按内核的实测事实库，
运行期合并进解析后的文档。

## 8. Store 与工具

- `external/test-kernels/<release>/options-<release>.json`——提取器的分析信封
  （schema 3）：cleaned `document`（每个设备对都在
  `devices.<codename>.phys`）、镜像级的 `routes`、`pipelines`、
  `pipeline_facts`、`pipeline_order`、`image_base`，以及 `device_analysis[]`
  （按 codename：设备对的 `phys_source`、锚点 `candidates` 与路由 `plans`；
  设备对不重复存放）。`timer_analysis` 保留定时器分析器的证据与其合并的片段。
- `tools/extract_rs`——`ghostlock-extract`（文档构建器）。
- `tools/timer_extract`——`timer-extract`（定时器片段）。
- `tools/sweep_timer_store.py`——把定时器片段合并进每个 store 文档。
- `tools/ship_golden_profiles.py`——从 store 文档渲染每个内置资产（文档内的设备对 +
  实测步长 + 头注释），并维护 `index.conf`。`--check` 比对事实；
  `--apply` / `--refresh-all` 只重写生成的资产。

store 条目从镜像确定性再生：
`ghostlock-extract <Image> --format conf --anchor --device <id> --options-out
<options.json>`（`generate_options` 语料测试对整个 store 做这件事），随后运行定时器
扫描。

## 9. 模板

`templates/kernel-{5.x,6.1,6.6,6.12}.template.conf` 是无法提取镜像时的手填后备。
它们带着全部固定键（`null`）与各家族的结构默认值。优先使用提取器：它从镜像推导每个值，
并标注每个事实的来源。

## 10. 旧格式（已退役）

预发布期的 v1/v2 schema 与键名已删除；v1/v2 文档被拒绝。阅读旧笔记时参考：

| 已退役 | 现在 |
|---|---|
| `route.<name>.*`、`route.tcp_zerocopy.compact_waiter` | `ghostlock.syscalls.<name>.<carrier>.*`；waiter 形状是 `kernel.struct.waiter.shape` |
| `route.<name>.lock_anchor_image/bytes/stride` | `ghostlock.anchor.image/bytes/stride` |
| `w1_scratch.image` | `ghostlock.anchor.scratch` |
| `fallback.to`、`fallback.route.*` | 删除（没有 route 回退） |
| `recommend_shizuku` | 删除（仅手动 Shizuku） |
| `pipeline = [...]` | 由 `kernel.dirtyfrag` / `kernel.kdp` / `ghostlock.module` / `timer` 推导 |
| `kernelsnitch.collisions` | `kernel.heap.collisions` |
| `kernelsnitch.mm_struct_sz` | `kernel.heap.stride` |
| `task_struct.*` | `kernel.struct.task.*` |
| `cred.caps_offset` | `kernel.struct.cred.caps.offset` |
| `cred.ref0_offset` … `ref3_*` | `kernel.struct.cred.refs.{user,user_ns,ucounts,group_info}.*` |
| `offset.*` | `kernel.symbols.*`（镜像相对十六进制） |
| `selinux.enforcing/initialized` | `kernel.struct.selinux_state.*` |
| `waiter_layout.*` | `kernel.struct.waiter.*` |
| `kernel_phys_load/offset`（标量或 `{device = x}`） | `devices.<codename>.phys.load/offset` |
| `timer_98260 { … }` | `timer { … }` |

GLK1 二进制 wire 也已退役：所有后端读取同一份 JSON 文档，native 的读取器各管不重叠的块
（`kernel`+`ghostlock`，或 `kernel`+`timer`）。
