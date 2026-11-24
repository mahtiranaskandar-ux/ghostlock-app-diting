# 5.x / 5.15 profile 模板

`kernel-5.x.template.conf` 是无法提取镜像时的手填后备。权威参考是
[PROFILE_SCHEMA_ZH.md](../PROFILE_SCHEMA_ZH.md)；本页列出要填的内容。

**null 也是一个值。** 固定形状的每个键都存在；没有值的键写 `null`，app 自有块里出现未知键
是导入错误。提取器会从镜像生成文档并记录每个事实的来源，优先使用它。

家族说明：waiter `compact`；cred 只有三个引用（user/user_ns/group_info，ucounts 两个值都写 null）；5.10 无 BTF，按模板/实测填写。

## 要填的内容

### `kernel`

| 路径 | 填写 |
|---|---|
| `image_base` | 镜像链接基址（`_text`） |
| `symbols.*` | 15 个键；镜像相对带引号十六进制；无 ucounts 的内核 `init_ucounts` 为 `null` |
| `struct.task.*` | 15 个 `task_struct` 字段偏移（十进制） |
| `struct.cred.copy_size` / `usage.*` / `uid` / `security` / `security_sid` | W2 凭据拷贝 |
| `struct.cred.caps.offset/count/mask` | 五个 `kernel_cap_t` 字 |
| `struct.cred.refs.<name>.offset/image` | 具名成员偏移与拷贝后应指向的 VA |
| `struct.waiter.shape/pi_tree/task/lock/wake_state` | stamp 编码器使用的 waiter 布局 |
| `struct.rt_mutex.size` | 锚点 stride 下界 |
| `struct.selinux_state.enforcing/initialized` | W1 写入的字段值 |
| `heap.stride` | 实测 slab 步长；需要 W1 的链路必填（生成的配置会写入文档） |
| `heap.collisions` | KernelSnitch 计数（5.x 为 8，已验证 6.x 为 4） |
| `dirtyfrag` | `"feasible"` / `"infeasible"` / `"unknown"` |
| `kdp` | RKP/KDP 镜像写 `true`（只走 DirtyFrag） |

### `ghostlock`

| 路径 | 填写 |
|---|---|
| `syscall` | `select_stack` / `sigreturn` / `multicast_waiter` / `tcp_zerocopy` |
| `carrier` | `null`（原生 64 位）或 `"compat"`（32 位） |
| `anchor.image/bytes/stride/scratch` | 锚点三元组与 W1 scratch；页面方案写 `anchor = null` |
| `syscalls.<name>.native` / `.compat` | carrier 块；不可行的 syscall 写 `null` |
| `module.asset/sha256/vendor_files` | 模块链（`syscalls`/`module` 恰好一个非空） |
| `execution` | 可选调优；app 会填入 preset |

### `timer`

| 路径 | 填写 |
|---|---|
| `trigger` | `"killable"` 或 `"core_state"` |
| `match_bits` | 21 |
| `sites.timer.offset` / `sites.task_alloc.low_bits` | 镜像相对 site |
| `object_size` / `task_alloc_size` / `node_offset` | slab 与节点常量 |
| `cred.request_size` / `alloc_size` | fake-cred 分配 |
| `trace.gfp.offset/size` | page-alloc trace 字段 |

## 检查
在 app 中导入；被拒绝时会报告字段路径、当前值与规则。native 拒绝选中的
syscall/carrier 不存在、或锚点不包含 W1 scratch 窗口的 profile。
