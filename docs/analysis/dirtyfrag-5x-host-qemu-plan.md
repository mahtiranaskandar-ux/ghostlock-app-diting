# DirtyFrag on 5.x — host/QEMU plan

**Status 2026-11-21: research plan, branch-local.** This file lives only on `feat/dirtyfrag-5x`.
If the experiment is dropped, the branch and this plan go with it; it is not a product design and
changes nothing until a gate below says it should. **G1 measured, G2–G4 passed (2026-11-21); G4c
(the same route on a real 5.10 phone) measured 2026-11-22** — see the result blocks under each
gate. G4's module build is valid only where the vendor's `struct module` matches (checked against
the ROM modules; a 6.1 device probe panicked because it did not; on the 5.10 diting an 8-byte
offset shift is masked by the kernel's own CFI jump-table rewrite, but a missing `.plt` and a
CFI-stripped init are refused or reset the phone). Only the device gate (G5) remains.

## The question

Can the DirtyFrag page-cache chain root a 5.4 Qualcomm GKI device without the GhostLock **W1**
step? Two concrete targets: a Mi 11 Pro on `5.4.233-qgki-gbb70cde46897` and a Mi 11 on
`5.4.274-qgki-g76ac0b265f44`. Decide it on host and QEMU; spend device time only at the last gate.

## Why this shape

The product's module-free DirtyFrag pipeline **shares W1 with GhostLock** — SELinux to permissive
written by the futex route — and then writes file page cache, hooks `libc++`'s sentry constructor
and lets init's own credential carry the root daemon. On the two 5.4 devices every page-free
attempt dies inside the first W1 call, so that pipeline never reaches its own stage. There is no
cheaper GhostLock to borrow: the only geometrically feasible route there is `sigreturn`, and it is
the one that resets the phone.

The public Android reference (`diabl0w/DFRoot`) takes the other branch of the same trade: it does
**not** do W1. The page-cache write patches `libc++.so`'s `std::ostream::sentry` ctor; init (uid 0,
tid 1) reaps an orphan and runs the stub; the stub's child writes `u:r:vendor_modprobe:s0` and
`exec`s `/vendor/bin/insmod <ko>`; the module itself sets `selinux_state = false`, bypasses DEFEX
where applicable, and `call_usermodehelper`s the bootstrap. No waiter stamp, no scheduler race, no
W1 — the module is the SELinux switch.

That trade costs a per-kernel module. On these two devices that is the whole risk, and it is a
build-and-ABI question, which is answerable without the phone.

The primitive itself is long-lived: `CVE-2026-43284` is in scope from 2017-01-17 to the 2026-05-05
fix (`f4c50a4034e6`), is a deterministic logic bug with no timing window, and does not panic on
failure. Reference implementations: `V4bel/dirtyfrag` (original), `diabl0w/DFRoot` (Android),
`linnemanlabs/dirtyfrag-arm64` (arm64 notes — the rxrpc leg oopses in `flush_dcache_page`; the ESP
leg is the arm64-safe one).

## What is already measured (host, 2026-11-21)

Both 5.4 images carry the pieces the ESP leg needs, built in:

```
INET_ESP=y INET6_ESP=y XFRM=y XFRM_USER=y XFRM_INTERFACE=y
CRYPTO_AES=y CRYPTO_CBC=y CRYPTO_HMAC=y CRYPTO_SHA256=y
KPROBES=y KALLSYMS_ALL=y SECURITY_SELINUX=y
AF_RXRPC absent
```

- `kallsyms_lookup_name` is **present but not exported** on both Images (corrected 2026-11-21:
  it sits in kallsyms, not in `__ksymtab`), and `selinux_state` is not exported either. A module
  therefore resolves names the reference way — a kprobe with `.symbol_name =
  "kallsyms_lookup_name"`, then a call through `kp.addr`; `register_kprobe` is exported GPL.
  `call_usermodehelper[_exec]` are exported.
- Both builds are LTO and rename the ESP entry points: `esp_input$<hash>`, `esp6_input$<hash>`.
  On the star Image `esp_input$…` calls `skb_cow_data` and carries **no** shared-frag (bit 5) test
  in the window before it — the pre-fix shape. `udp_sendpage` calls `ip_append_page`, i.e. the
  5.10-style splice path, not the 6.5+ `skb_splice_from_iter`.
- The extractor's current `dirtyfrag infeasible: the ESP fix level could not be measured` on these
  images is an **implementation gap** (the symbol fallback understands `.llvm.*` clones but not
  `$<hash>` ones), not a kernel fact.

## Gates

Each gate has a pass criterion and a stop condition. The order is cheapest-first; a failed gate
ends the branch's question at that point.

### G1 — static verdict (host)

Add the `$<hash>` LTO-clone fallback to the extractor's symbol resolution (unique match only, fail
closed on two candidates), then run the pipeline derivation on both 5.4 Images.

- **Pass:** `dirtyfrag feasible` on both, with the ESP guard found in the renamed functions and
  the send path found through `udp_sendpage → ip_append_page`.
- **Guard:** the full existing store corpus must be unchanged (no verdict or pipeline order moves).
- **Cross-check:** the hand disassembly of `esp_input$…` above.

**Result (2026-11-21).** Both Images carry `esp_input$<32-hex>` and `esp6_input$<32-hex>` (the
same two suffixes in both builds; the `.cfi_jt` thunks are separate functions and never match).
With the clone-aware lookup in the pipeline, the facts become measurable on both:
`esp_guard absent`, `esp_fix absent`, `send_path ip_append_page`. Hand-checked: both ESP entry
points call `skb_cow_data` at `+0xdc` from the function entry with no bit-5 test in the window,
and `udp_sendpage` calls `ip_append_page` at `+0xb4` (addresses in the gate's evidence folder).

The literal `dirtyfrag feasible` verdict is **not** reached, and the reason is now a different,
pre-existing one: `no route is feasible for W1`. The extractor cannot derive any route on a 5.4
Image (no BTF, no 5.10 waiter template; `pselect6` is `__arm64_sys_pselect6 →
__do_sys_pselect6 → core_sys_select`, a chain the model does not accept). That dependency belongs
to the module-free pipeline; the module-delivery chain measured at G2 never runs W1. G1 is
therefore recorded as **pass on the kernel facts it exists to measure, not on the written
criterion** — stated here rather than adjusted silently.

`send_marking: present` is a heuristic false positive (a 2023/2025 5.4 build cannot carry the 2026
fix; the matched `orr …, #0x20` stores target `sk_buff` fields at `+0x82`/`+0x48`, not
`skb_shared_info.flags`). It does not move the verdict — the guard is absent, so the effective fix
is absent — and tightening that scan is left as a follow-up.

**Corpus guard:** the store tests (`options_ondevice`, `route_verdicts_ondevice`,
`cut_ondevice`) pass unchanged against `external/test-kernels`; the resolver never falls back on an
Image that has the exact name, and every store entry does (no golden carries "could not be
measured").

### G2 — primitive gate on the exact Images (QEMU)

Boot each Image with an initramfs running a static probe that performs the reference operation
verbatim:

1. program a transport-mode ESP SA (AES-CBC + HMAC-SHA256) over `NETLINK_XFRM`, plus a
   UDP-encapsulation socket;
2. `splice()` a page of a file we own in the initramfs into a UDP send;
3. send with `IV = AES_ECB_DEC(key, old_block) ⊕ new_block`;
4. read the file back.

Non-escalating, disposable guest; the host distinguishes "page cache changed" from "file copied".

- **Pass:** the file's content changes on both `5.4.233` and `5.4.274`.
- **Fail:** the write does not land — the ESP leg is out on these images and the branch ends.
- **Control:** the same run with the fix's shape forced (or a no-splice variant) must not write.

**Result (2026-11-21): pass on both Images.** A disposable QEMU guest (guest root, initramfs
only) installs a transport-mode AES-256 `cbc(aes)` input SA with `UDP_ENCAP_ESPINUDP` over
`NETLINK_XFRM` — encryption only, no authentication, as the reference transform is — then
`vmsplice`s a 24-byte ESP header and `splice`s the 16-byte file block into the same pipe and on
into a connected UDP socket (`udp_sendpage → ip_append_page`). The in-place `esp_input` decrypt
writes `DF-G2-WRITE!!!\0;` (the trailing bytes are a valid empty trailer: padlen 0, next header
59) into the page cache of both `5.4.233` and `5.4.274`. **Both controls stay clean**: the same
bytes sent with `write()` (a copied skb) and a spliced send under a foreign SPI leave the file
byte-identical.

The probe and its driver are now a reusable 5.x gate in this project's shared 5.4 tools tree
(the same shape as the existing route gate); the console records and the exact addresses are in
the gate's evidence folder. Non-escalating throughout: no device, no root on a phone, no prebuilt
exploit binary.

### G3 — ROM preflight (host)

The kernel is not the whole chain; the ROM must supply the hook. From the two ROM payloads, check:

> **Result (2026-11-21): pass on both ROMs.** The two fastboot packages were fetched (7.07 GB
> star, 6.81 GB venus), and the `system`/`vendor` logical partitions were carved out of their
> `super.img` (both are EROFS). Every item is present:
>
> - `libc++.so` exports the sentry constructor and the app's own preflight passes on both
>   (reasons empty; the cave page remainder is ~34 KiB / ~58 KiB against the 420 B stub), and
>   `libbase.so` imports the symbol — init's call path exists. The runtime APEX's
>   `/lib64/libc++.so` is a symlink to `/system/lib64/libc++.so`, so that is the file checked.
> - `crash_dump64` is in the runtime APEX; the policy allows any domain to execute
>   `crash_dump_exec`, transitions it to `crash_dump`, and lets `crash_dump` open/read
>   `vendor_file` (read only — the page cache is written by ESP, not by the descriptor).
> - `/vendor/bin/insmod` is a symlink to `toybox_vendor` with the `insmod` applet;
>   `vendor_modprobe` is a type on both ROMs, `init` may transition to it, and it may execute
>   `vendor_toolbox_exec`, `module_load` `vendor_file`, and holds `sys_module`.
> - `IpSecManager$UdpEncapsulationSocket` (framework) and `IpSecService` (service) are in the
>   `com.android.tethering` module.
>
> The gate's remaining unknowns are runtime-only (init timing, the orphan reap, live SELinux),
> which is G5's business. Records: the gate's evidence folder (ROM hashes, policy lines, the
> preflight lines).

- `libc++.so` / `libbase.so` carry the sentry constructor and a usable code cave — feed the real
  files to the app's own preflight;
- `crash_dump64` exists and can open `vendor_file`-labelled files;
- `/vendor/bin/insmod` exists and `u:r:vendor_modprobe` is a domain in the platform policy;
- `IpSecManager`/`IpSecService` are present (the app-side delivery, which QEMU cannot exercise).

- **Pass:** every item present on both ROMs.
- **Fail:** the chain needs a different bridge/hook on that ROM; record which item is missing.

### G4 — module ABI gate (host + QEMU)

Build a minimal module (look up `selinux_state` through `kallsyms_lookup_name`; call
`call_usermodehelper`) against a 5.4 GKI tree at the matching sublevel, patch `vermagic` and the
`__versions` CRCs from the Image's own `__ksymtab` data, then `insmod` it as guest root in a QEMU
boot of the exact Image.

- **Pass:** the vendor kernel accepts the module and it can resolve the symbols it needs.
- **Fail:** the vendor kernel is not GKI-compatible at that layer — the module form is dead and
  the branch ends. This is the cheap kill for the whole reference-style chain.
- **Caveat:** QEMU root is not the delivery path; the phone-side delivery is init's `insmod` after
  the page-cache hook (G2 + G3).

**Result (2026-11-21): pass on both Images.** Built against a shallow ACK
`android12-5.4.233_r00` tree prepared with the Image's own IKCONFIG and the exact
`UTS_RELEASE`, with a `Module.symvers` generated from the Image's `__kcrctab` (the venus module
reuses the same tree with its vermagic string and CRCs). On both Images the guest's
`finit_module` returned 0, and the module resolved its targets the reference way:

```
star   register_kprobe addr=ffffffc0103c8d64  selinux_state=ffffffc01314b000  call_usermodehelper=ffffffc010301380
venus  register_kprobe addr=ffffffc0103b7c6c  selinux_state=ffffffc013121000  call_usermodehelper=ffffffc0102efd24
```

Both `selinux_state` addresses match the earlier W1 QEMU gate's values. Two controls prove the
check is real: a corrupted `module_layout` CRC is refused (`disagrees about version of symbol`,
`ENOEXEC`), and so is the venus module on the star kernel — **not** by vermagic, because with
`MODVERSIONS` the kernel compares vermagic only past the first space; the per-Image
`module_layout` CRC is the ABI gate, and the two builds' CRCs differ. Two facts from this gate
shape the chain: module signing is **off** (`# CONFIG_MODULE_SIG is not set`), and CFI built by
clang 21 runs on the clang-11.0.2 kernels (the module's indirect calls and the kernel's call to
its init both pass).

**G4b — the same route on a real device (2026-11-21, off-target), corrected.** The module was
loaded on the POCO at hand (`duchamp`, 6.1.138, rooted via the app). The first build — from ACK
with the Image's config but **BTF disabled because the host lacked libelf/pahole** — has a
1024-byte `struct module` against the vendor's 1088 (its BTF), so the loader overran it and the
kernel panicked (`sys.boot.reason=kernel_panic`). That delta was **our build workaround**, not a
vendor patch: with `libelf`/`pahole` and the exact config, ACK reproduces the vendor layout
exactly (1088 B, `init` 0x170, `exit` 0x3D8) and the module loads on the phone, resolving its
symbols. The 5.4 targets' vendor modules still show the 896-byte layout our build carries.

The load matrix on that phone then mapped the checks: the vermagic **release** half is ignored
under `MODVERSIONS`; the rest of vermagic, a present-but-wrong CRC, the struct layout, kCFI on
the init call and unexported symbols are all **enforced** (the last two panic or refuse); while a
missing `module_layout` entry, an empty `__versions` section and an absent signature **load** —
the kernel's own compatibility paths. `IGNORE_MODVERSIONS`/`IGNORE_VERMAGIC` are dead ends with
`MODULE_FORCE_LOAD` off. The rule: reproduce the config **and the host tooling**, verify the
layout against the ROM's modules or BTF, and never treat a patched `module_layout` CRC as a
pass. The full table is in the gate's evidence folder.

**G4c — the same route on a real 5.10 phone (2026-11-22, off-target).** The probe module was
rebuilt for the `diting` (`5.10.236-android12-9-00003-gfb24cf99ad97-ab14313284`) from the ACK
tree at its exact build, with the phone's own config (byte-identical to the stored copy) and the
vendor clang-r416183b; the Image's CRCs came from `symvers-from-image.py` as at G4. Two things
are 5.10-specific. First, the shared build tree's `.config` had drifted after its kernel build
(`LTO_NONE`, no `CFI_CLANG`), which shrank `struct module` by the 8-byte `cfi_check` field and
moved `init` to 0x188; the vendor `.ko` and the stock-config DWARF both say 0x190. Second, that
shift is **masked at load**: `cfi_init()` rewrites `mod->init`/`mod->exit` from the module's
`__cfi_jt_*` symbols, so a module with the wrong init offset still loads and runs (measured) —
what matters is that the section fits the kernel's struct, and that the module carries the `.plt`
sections the LTO build emits (a non-LTO build is refused before any layout question). The matrix
itself is the 6.1 one: the vermagic release half, a missing `module_layout` entry and an empty
`__versions` load; the magic half, a wrong CRC, exports and CFI are enforced; the force flags are
dead; `kallsyms_lookup_name` is unexported and the kprobe method resolves the symbols; the module
needs no signature (`CONFIG_MODULE_SIG` is not set). Stripping `__cfi_check` reset the phone —
the 6.1 row-11 class. The full table is in the private repo's 2026-11-22 gate record.

## What this deliberately avoids

W1, stack stamps, carrier geometry, KASLR/offset derivation and scheduler races — the whole class
of failure this fork has measured on these phones. If a gate above fails, the answer is "this
device needs a different chain", not "another series of device runs".

## Deliverables

- The `$<hash>` clone fallback and its regression evidence (G1) — **done 2026-11-21**.
- A reusable "page-cache primitive gate" harness next to the existing route gate (G2), applicable
  to any 5.x image — **done 2026-11-21**.
- A ROM preflight table per device (G3) — **done 2026-11-21** — and an ABI verdict per kernel
  (G4) — **done 2026-11-21, pass on both**.
- If G1–G4 pass, an L-level plan for a module-delivery DirtyFrag pipeline alongside the module-free
  one — that is a separate design, not this file. **Written 2026-11-22:**
  `module-magisk-implementation-plan.md` (the module as the SELinux switch, Magisk as the
  userspace manager, and the additive profile design).

## Risks

- The module ABI (G4) is the concentrated risk, but the vendor tree turned out not to be needed:
  with the Image's **exact config and host tooling** (pahole/libelf for BTF), ACK reproduces the
  vendor's `struct module` (1088 B on the 6.1 phone, 896 B on both 5.4 Images). The failure mode
  is a build environment that silently drops config — the first 6.1 build did, and the phone
  panicked. Verify the layout against the Image BTF or a vendor `.ko` before any load, and never
  treat a patched `module_layout` CRC as a pass.
- QEMU does not model the phone's SELinux or init; G3 is the static stand-in, G5 the only real
  test.
- A page-cache write contaminates the cache; a device run must drop caches or reboot afterwards
  (a QEMU guest simply exits).

No prebuilt exploit binaries are run at any gate: the reference sources are read, and the probe is
re-implemented in this repo.
