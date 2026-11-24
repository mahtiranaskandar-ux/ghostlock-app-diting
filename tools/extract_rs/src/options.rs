//! The option superset: everything one derivation pass yields for an image.
//!
//! One document per kernel. The app consumes it for the route picker and the
//! page-vs-conditional prompt; the test corpus keeps it as the single golden.
//! A runnable profile is a **cut** of this document:
//! `(superset, route, plan, device)`, not a separate derivation. The document
//! carries every device pair (`devices.<codename>.phys`);
//! `device_analysis[]` keeps the per-device evidence — the pair's source and
//! the anchor/plan derivations that depend on it.
//!
//! Policy (operator, 2026-10-31):
//! - `dump_skip.zeroes`/`__dump_skip.zeroes`, `bm_pmd` and `serial8250_ports`
//!   are unconditioned: they may be applied without asking.
//! - `s3c24xx_serial_ports` is conditioned: its premise (no `samsung,*` node /
//!   driver) is a device fact the app cannot check before W1, so it enters
//!   only an attested plan.
//! - The auto chain is: first unconditioned anchor with a slot -> its in-anchor
//!   scratch -> an unconditioned second region. The attested plan is offered
//!   only when no unconditioned plan is page-free.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::analysis::RouteVerdict;
use crate::anchor::{self, W1Scratch};
use crate::pipeline::{PipelineFacts, PipelineOption};

pub const SCHEMA_VERSION: u32 = 3;

/// Named anchor/scratch classes, in policy order, with the operator's
/// `conditioned` flag. The scan verdict stays in the entry as evidence.
const NAMED: [(&str, bool); 5] = [
    ("__dump_skip.zeroes", false),
    ("dump_skip.zeroes", false),
    ("bm_pmd", false),
    ("serial8250_ports", false),
    ("s3c24xx_serial_ports", true),
];

#[derive(Debug, Serialize)]
pub struct Superset {
    pub schema_version: u32,
    pub release: String,
    pub kernel_major: Option<u32>,
    /// The image's link-time base (`_text`), the anchor for every symbol VA
    /// the timer engine states.
    #[serde(default)]
    pub image_base: Option<u64>,
    pub routes: Vec<RouteOption>,
    /// The image-side pipeline facts. They are recorded here and never written
    /// into the document; the document's ladder is derived from
    /// `kernel.dirtyfrag` / `kernel.kdp`.
    #[serde(default)]
    pub pipeline_facts: PipelineFacts,
    /// `ghostlock` and `dirtyfrag`, in stable order. No `chosen` key: the run's
    /// engine is the document's derived ladder, not the extractor's.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub pipelines: Vec<PipelineOption>,
    /// The default pipeline order (highest precedence first), derived from
    /// `pipelines[]` or the operator's `--pipeline-order` override. It ranks
    /// the envelope's verdicts; the document carries no chain list.
    #[serde(default)]
    pub pipeline_order: Vec<String>,
    /// The cleaned v3 profile document for this image: the route-superset form
    /// (every feasible syscall with its carriers, the shared anchor/scratch)
    /// and every device pair the entry derived, in the schema shape
    /// (`devices.<codename>.phys`). Null when the image has no feasible route.
    /// Every consumer reads this one shape; there is no old-keyed profile map.
    pub document: crate::document::Node,
    /// Per-device evidence: where the pair came from and the anchor/plan
    /// derivations that depend on it, in generation order. The pair itself is
    /// not repeated here — `document.devices.<codename>.phys` is its one home.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub device_analysis: Vec<DeviceDerivation>,
}

/// One device's analysis record for a kernel: the pair's source and the
/// candidates/plans derived against it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceDerivation {
    /// The sidecar device this record was derived for; null for a run with no
    /// device sidecar set (an excluded corpus entry still keeps its scan).
    pub device: Option<String>,
    /// `xbl_config FDT`, `uefi memory map`, `vendor_boot header (MediaTek)`,
    /// `pair.conf`, … — the source of `document.devices.<device>.phys`.
    pub phys_source: String,
    /// Named anchor candidates with this device's scratch placements.
    pub candidates: Vec<CandidateOption>,
    /// The device's page/auto/attested plans (the scratch is pair-dependent).
    pub plans: Vec<RoutePlans>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RouteOption {
    pub route: String,
    /// The carrier of the primary verdict: `native` or `compat`.
    #[serde(default)]
    pub carrier: String,
    pub verdict: String,
    pub reason: String,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub numbers: BTreeMap<String, i64>,
    pub anchor_capable: bool,
    pub geometry: BTreeMap<String, i64>,
    /// The route's other feasible carriers, each with its own geometry.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub alternatives: Vec<RouteVariantOption>,
}

/// One non-primary carrier of a route. The app's route picker offers these as
/// the carrier choice; a cut renders the selected variant's geometry.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RouteVariantOption {
    pub carrier: String,
    pub verdict: String,
    pub reason: String,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub numbers: BTreeMap<String, i64>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub geometry: BTreeMap<String, i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnchorTriple {
    pub image: u64,
    pub bytes: u64,
    pub stride: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScratchPlacement {
    pub image: u64,
    pub window: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reserved_first: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reserved_last: Option<u64>,
}

impl ScratchPlacement {
    fn of(scratch: &W1Scratch) -> Self {
        Self {
            image: scratch.image,
            window: scratch.window,
            reserved_first: scratch.inside_anchor.then_some(scratch.reserved_first),
            reserved_last: scratch.inside_anchor.then_some(scratch.reserved_last),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CandidateOption {
    pub name: String,
    pub image: u64,
    pub extent: u64,
    pub clean_prefix: u64,
    pub verdict: String,
    pub conditioned: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub anchor: Option<AnchorTriple>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub in_anchor_scratch: Option<ScratchPlacement>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub second_region_scratch: Option<ScratchPlacement>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Plan {
    pub route: String,
    pub anchor: Option<AnchorTriple>,
    pub scratch: Option<ScratchPlacement>,
    pub page_free: bool,
    pub conditioned: bool,
    /// The anchor candidate's symbol name, for the UI label.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub anchor_name: Option<String>,
    /// `in-anchor` or the second-region symbol name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scratch_source: Option<String>,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RoutePlans {
    pub route: String,
    pub page: Plan,
    /// The safe anchor with a page W1 (no scratch): the lock-source "anchor"
    /// option when no page-free plan is selected.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub anchor_only: Option<Plan>,
    /// Every page-free plan, safe first then conditioned. The extractor omits
    /// nothing; the UI decides what to show and when to prompt.
    #[serde(default)]
    pub page_free: Vec<Plan>,
}

pub struct Input<'a> {
    pub release: &'a str,
    pub kernel_major: Option<u32>,
    pub phys_load: Option<u64>,
    pub phys_offset: Option<u64>,
    pub phys_source: &'a str,
    /// The image's link-time base (`_text`).
    pub image_base: Option<u64>,
    /// The device these sidecars belong to. When set, the superset records a
    /// [DeviceDerivation] entry (the CLI and the store harness run once per
    /// device and merge the entries).
    pub device: Option<&'a str>,
    /// Route verdicts, in the extractor's stable order.
    pub verdicts: &'a [RouteVerdict],
    /// The measured pipeline facts and verdicts (recorded, not cut).
    pub pipeline_facts: &'a PipelineFacts,
    pub pipelines: &'a [PipelineOption],
    /// The order the cut writes into the profile.
    pub pipeline_order: &'a [String],
    /// Full geometry per `(route, carrier)` (the cut needs all of them).
    pub geometry: &'a BTreeMap<(String, String), BTreeMap<String, i64>>,
    /// `selinux_state.initialized`; `None` fails the scratch arithmetic closed.
    pub initialized: Option<u32>,
    pub kernel: &'a [u8],
    pub rel_symbols: &'a BTreeMap<String, BTreeSet<u64>>,
    /// The shared profile facts (symbols, structs, credential layout, the
    /// stage-1 blocks), rendered into the document by the caller's builder.
    pub facts: &'a crate::report::ConfInputs<'a>,
}

/// Build the superset. Deterministic: candidates in `NAMED` order, routes in
/// verdict order, maps as `BTreeMap`.
pub fn build(input: &Input<'_>) -> Superset {
    let candidates = scan_candidates(&CandidateInput {
        kernel: input.kernel,
        rel_symbols: input.rel_symbols,
        phys_load: input.phys_load,
        phys_offset: input.phys_offset,
        initialized: input.initialized,
    });
    let mut plans = Vec::new();
    for verdict in input.verdicts {
        if verdict.verdict != crate::analysis::Feasibility::Feasible {
            continue;
        }
        plans.push(plans_for(verdict.route, &candidates));
    }
    let routes: Vec<RouteOption> = input
        .verdicts
        .iter()
        .map(|verdict| {
            let carrier = if verdict.is_compat_carrier() || verdict.is_compat_multicast() {
                "compat"
            } else {
                "native"
            };
            let geometry = |name: &str| {
                input
                    .geometry
                    .get(&(verdict.route.to_string(), name.to_string()))
                    .cloned()
                    .unwrap_or_default()
            };
            RouteOption {
                route: verdict.route.to_string(),
                carrier: carrier.to_string(),
                verdict: verdict.verdict.label().to_string(),
                reason: verdict.reason.clone(),
                numbers: verdict.numbers.clone(),
                anchor_capable: verdict.route != "tcp_zerocopy",
                geometry: geometry(carrier),
                alternatives: verdict
                    .alternatives
                    .iter()
                    .map(|alt| RouteVariantOption {
                        carrier: alt.carrier.label().to_string(),
                        verdict: alt.verdict.label().to_string(),
                        reason: alt.reason.clone(),
                        numbers: alt.numbers.clone(),
                        geometry: geometry(alt.carrier.label()),
                    })
                    .collect(),
            }
        })
        .collect();
    let device_analysis = vec![DeviceDerivation {
        device: input.device.map(str::to_string),
        phys_source: input.phys_source.to_string(),
        candidates: candidates.clone(),
        plans: plans.clone(),
    }];
    let document = scene_document(input, &routes, &plans);
    Superset {
        schema_version: SCHEMA_VERSION,
        release: input.release.to_string(),
        kernel_major: input.kernel_major,
        image_base: input.image_base,
        routes,
        pipeline_facts: input.pipeline_facts.clone(),
        pipelines: input.pipelines.to_vec(),
        pipeline_order: input.pipeline_order.to_vec(),
        document,
        device_analysis,
    }
}

/// The inputs the candidate scan needs. Shared by the superset builder and
/// the CLI's `--format conf --anchor`, which follows the same auto plan.
pub struct CandidateInput<'a> {
    pub kernel: &'a [u8],
    pub rel_symbols: &'a BTreeMap<String, BTreeSet<u64>>,
    pub phys_load: Option<u64>,
    pub phys_offset: Option<u64>,
    pub initialized: Option<u32>,
}

pub fn scan_candidates(input: &CandidateInput<'_>) -> Vec<CandidateOption> {
    let bounds = anchor::bounds_from(input.rel_symbols);
    let refs = anchor::scan_references(input.kernel, &bounds);
    let mut out = Vec::new();
    for (name, conditioned) in NAMED {
        let Some(candidate) = anchor::named_candidate(input.rel_symbols, &bounds, name) else {
            continue;
        };
        let assessment = anchor::assess(&candidate, &refs, input.rel_symbols, &bounds);
        let triple = anchor::profile_triple(&assessment);
        let delta = input.phys_load.and_then(|load| {
            let offset = input.phys_offset.unwrap_or(0x8000_0000);
            let delta = load.checked_sub(offset)?;
            (delta & 0xffff == 0).then_some(delta)
        });
        let in_anchor_scratch = delta.and_then(|delta| {
            let initialized = input.initialized?;
            let (image, bytes, stride) = triple?;
            anchor::pick_w1_scratch(image, bytes, stride, delta, initialized)
                .map(|scratch| ScratchPlacement::of(&scratch))
        });
        let second_region_scratch = delta.and_then(|delta| {
            let initialized = input.initialized?;
            anchor::pick_w1_scratch_in_symbol(
                candidate.offset,
                candidate.extent,
                input.kernel,
                delta,
                initialized,
            )
            .map(|scratch| ScratchPlacement::of(&scratch))
        });
        out.push(CandidateOption {
            name: candidate.name,
            image: candidate.offset,
            extent: candidate.extent,
            clean_prefix: anchor::clean_prefix(&assessment, family_stride()),
            verdict: assessment.verdict.label().to_string(),
            conditioned,
            anchor: triple.map(|(image, bytes, stride)| AnchorTriple {
                image,
                bytes,
                stride,
            }),
            in_anchor_scratch,
            second_region_scratch,
        });
    }
    out
}

fn family_stride() -> u64 {
    crate::family::ANCHOR_STRIDE
}

/// The default plan for a route: the first safe (unconditioned) page-free
/// option, else the safe anchor with a page W1, else page. The CLI renders
/// this; the app applies the user-facing rules over the full list.
pub fn auto_plan(route: &str, candidates: &[CandidateOption]) -> Plan {
    let plans = plans_for(route, candidates);
    plans
        .page_free
        .iter()
        .find(|plan| !plan.conditioned)
        .cloned()
        .or_else(|| plans.anchor_only.clone())
        .unwrap_or(plans.page)
}

fn plans_for(route: &str, candidates: &[CandidateOption]) -> RoutePlans {
    let page = Plan {
        route: route.to_string(),
        anchor: None,
        scratch: None,
        page_free: false,
        conditioned: false,
        anchor_name: None,
        scratch_source: None,
        reason: "page".to_string(),
    };
    if route == "tcp_zerocopy" {
        return RoutePlans {
            route: route.to_string(),
            page,
            anchor_only: None,
            page_free: Vec::new(),
        };
    }
    let mut page_free = Vec::new();
    for anchor in candidates
        .iter()
        .filter(|candidate| candidate.anchor.is_some())
    {
        if let Some(scratch) = &anchor.in_anchor_scratch {
            page_free.push(page_free_plan(
                route,
                anchor,
                scratch,
                anchor.conditioned,
                "in-anchor",
            ));
        } else {
            for region in candidates
                .iter()
                .filter(|region| region.name != anchor.name)
            {
                if let Some(scratch) = &region.second_region_scratch {
                    page_free.push(page_free_plan(
                        route,
                        anchor,
                        scratch,
                        anchor.conditioned || region.conditioned,
                        &region.name,
                    ));
                }
            }
        }
    }
    /* Stable sort: safe plans first, NAMED order preserved inside each group. */
    page_free.sort_by_key(|plan| plan.conditioned);
    let anchor_only = candidates
        .iter()
        .find(|candidate| !candidate.conditioned && candidate.anchor.is_some())
        .map(|candidate| Plan {
            route: route.to_string(),
            anchor: candidate.anchor.clone(),
            scratch: None,
            page_free: false,
            conditioned: false,
            anchor_name: Some(candidate.name.clone()),
            scratch_source: None,
            reason: "anchor lock, page W1".to_string(),
        });
    RoutePlans {
        route: route.to_string(),
        page,
        anchor_only,
        page_free,
    }
}

fn page_free_plan(
    route: &str,
    anchor: &CandidateOption,
    scratch: &ScratchPlacement,
    conditioned: bool,
    source: &str,
) -> Plan {
    Plan {
        route: route.to_string(),
        anchor: anchor.anchor.clone(),
        scratch: Some(scratch.clone()),
        page_free: true,
        conditioned,
        anchor_name: Some(anchor.name.clone()),
        scratch_source: Some(source.to_string()),
        reason: if conditioned {
            "conditional page-free plan".to_string()
        } else {
            "unconditional page-free plan".to_string()
        },
    }
}

/// The cleaned v3 route-superset document for this image: every feasible
/// syscall with its carriers, the shared anchor/scratch, and the primary
/// device's pair. The store records it; the CLI prints it and the ship reads
/// it. Null when no route is feasible (the image yields no profile).
fn scene_document(
    input: &Input<'_>,
    routes: &[RouteOption],
    plans: &[RoutePlans],
) -> crate::document::Node {
    use crate::document::{AnchorDoc, CarrierDoc, DocumentInput, Geometry, RouteDoc};

    let mut chosen: Vec<(&RouteOption, &Plan)> = Vec::new();
    for option in routes {
        if option.verdict != "feasible" {
            continue;
        }
        let Some(route_plans) = plans.iter().find(|entry| entry.route == option.route) else {
            continue;
        };
        let plan = route_plans
            .page_free
            .iter()
            .find(|plan| !plan.conditioned)
            .or(route_plans.anchor_only.as_ref())
            .unwrap_or(&route_plans.page);
        chosen.push((option, plan));
    }
    if chosen.is_empty() {
        return crate::document::Node::Null;
    }

    fn carriers_of(option: &RouteOption) -> Vec<&str> {
        let mut carriers: Vec<&str> = Vec::new();
        if !option.carrier.is_empty() {
            carriers.push(option.carrier.as_str());
        }
        for alt in &option.alternatives {
            if alt.verdict == "feasible" && !carriers.contains(&alt.carrier.as_str()) {
                carriers.push(alt.carrier.as_str());
            }
        }
        carriers
    }

    let mut doc_routes = Vec::new();
    let mut fallback_geometries: Vec<&BTreeMap<String, i64>> = Vec::new();
    for (option, _) in &chosen {
        fallback_geometries.push(&option.geometry);
        let mut carriers = Vec::new();
        for carrier in carriers_of(option) {
            let geometry = if carrier == option.carrier {
                &option.geometry
            } else {
                option
                    .alternatives
                    .iter()
                    .find(|alt| alt.carrier == carrier && alt.verdict == "feasible")
                    .map(|alt| &alt.geometry)
                    .unwrap_or(&option.geometry)
            };
            carriers.push(CarrierDoc {
                name: carrier,
                geometry: Geometry::Map(geometry),
            });
        }
        doc_routes.push(RouteDoc {
            name: option.route.as_str(),
            carriers,
        });
    }
    let selected = chosen
        .iter()
        .map(|(option, _)| option.route.as_str())
        .min();
    let anchors: Vec<&AnchorTriple> =
        chosen.iter().filter_map(|(_, plan)| plan.anchor.as_ref()).collect();
    let shared_anchor = anchors.first().copied().filter(|anchor| {
        anchors.iter().all(|other| {
            (other.image, other.bytes, other.stride)
                == (anchor.image, anchor.bytes, anchor.stride)
        })
    });
    let shared_scratch = chosen
        .iter()
        .map(|(_, plan)| plan.scratch.as_ref().map(|scratch| scratch.image))
        .reduce(|left, right| if left == right { left } else { None })
        .flatten()
        .and_then(|image| {
            chosen
                .iter()
                .find_map(|(_, plan)| plan.scratch.as_ref().filter(|s| s.image == image))
        });
    let anchor = shared_anchor.map(|anchor| AnchorDoc {
        image: anchor.image,
        bytes: Some(anchor.bytes),
        stride: Some(anchor.stride),
        scratch: shared_scratch.map(|scratch| scratch.image),
    });

    crate::document::build(&DocumentInput {
        facts: input.facts,
        devices: input
            .device
            .map(|name| (name, input.phys_load, input.phys_offset)),
        routes: doc_routes,
        selected,
        anchor,
        geometry: Geometry::Maps(fallback_geometries),
    })
}

/// Render the route-superset document: every feasible route's block, the
/// shared anchor, and the primary device's pair. The document is built once
/// in `build` (the store records it); this is its HOCON form for the CLI.
pub fn render_superset(superset: &Superset) -> Option<String> {
    match &superset.document {
        crate::document::Node::Null => None,
        document => Some(crate::document::render_hocon(document)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    static EMPTY_SYMBOLS: BTreeMap<String, Option<u64>> = BTreeMap::new();
    static EMPTY_STRUCTS: BTreeMap<String, Option<u32>> = BTreeMap::new();
    static EMPTY_OFFSETS: crate::report::ConfExtraOffsets =
        crate::report::ConfExtraOffsets { empty_zero_page: None };

    fn facts(release: &str) -> crate::report::ConfInputs<'_> {
        crate::report::ConfInputs {
            release,
            phys: None,
            phys_offset: None,
            phys_source: "unset",
            image_base: None,
            symbols: &EMPTY_SYMBOLS,
            structs: &EMPTY_STRUCTS,
            pipeline_order: &[],
            kdp: None,
            route: None,
            route_geometry: &[],
            cred: &[],
            extra_offsets: &EMPTY_OFFSETS,
            layouts: None,
            stage1: None,
        }
    }

    #[test]
    fn the_superset_document_lists_every_feasible_route_and_carrier() {
        use std::collections::BTreeMap;
        let plan = |route: &str, anchored: bool| Plan {
            route: route.to_string(),
            /* A tcp route cannot be anchored (its fake lock is a crafted
             * object); in the live superset its Auto plan is the page plan. */
            anchor: anchored.then_some(AnchorTriple {
                image: 0x1000,
                bytes: 0x400,
                stride: 32,
            }),
            scratch: Some(ScratchPlacement {
                image: 0x2000,
                window: 0x100,
                reserved_first: Some(0),
                reserved_last: Some(0),
            }),
            page_free: true,
            conditioned: false,
            anchor_name: Some("dump_skip.zeroes".to_string()),
            scratch_source: Some("in-anchor".to_string()),
            reason: String::new(),
        };
        let option = |route: &str,
                      geometry: BTreeMap<String, i64>,
                      alternatives: Vec<RouteVariantOption>| RouteOption {
            route: route.to_string(),
            carrier: "native".to_string(),
            verdict: "feasible".to_string(),
            reason: String::new(),
            numbers: BTreeMap::new(),
            anchor_capable: true,
            geometry,
            alternatives,
        };
        let routes = vec![
            option("tcp_zerocopy", BTreeMap::new(), vec![]),
            option(
                "select_stack",
                BTreeMap::from([("waiter_shift".to_string(), -2)]),
                vec![RouteVariantOption {
                    carrier: "compat".to_string(),
                    verdict: "feasible".to_string(),
                    reason: String::new(),
                    numbers: BTreeMap::new(),
                    geometry: BTreeMap::from([
                        ("compat_carrier".to_string(), 1),
                        ("compat_futex_abi".to_string(), 1),
                        ("waiter_home".to_string(), 488),
                        ("select_dst_depth".to_string(), 496),
                    ]),
                }],
            ),
        ];
        let plans = vec![
            RoutePlans {
                route: "tcp_zerocopy".to_string(),
                page: plan("tcp_zerocopy", false),
                anchor_only: None,
                page_free: vec![plan("tcp_zerocopy", false)],
            },
            RoutePlans {
                route: "select_stack".to_string(),
                page: plan("select_stack", true),
                anchor_only: None,
                page_free: vec![plan("select_stack", true)],
            },
        ];
        let facts = facts("6.1.138-x");
        let input = Input {
            release: "6.1.138-x",
            kernel_major: Some(6),
            phys_load: None,
            phys_offset: None,
            phys_source: "unset",
            image_base: None,
            device: None,
            verdicts: &[],
            pipeline_facts: &crate::pipeline::PipelineFacts::default(),
            pipelines: &[],
            pipeline_order: &["ghostlock".to_string()],
            geometry: &BTreeMap::new(),
            initialized: None,
            kernel: &[],
            rel_symbols: &BTreeMap::new(),
            facts: &facts,
        };
        let document = scene_document(&input, &routes, &plans);
        let text = crate::document::render_hocon(&document);
        assert!(text.contains("tcp_zerocopy {"), "{text}");
        /* The four-branch catalogue carries every carrier's own block. */
        assert!(text.contains("select_stack {"), "{text}");
        assert!(text.contains("compat {"), "{text}");
        assert!(text.contains("waiter_home = 488"), "{text}");
        assert!(text.contains("dst_depth = 496"), "{text}");
        assert!(text.contains("futex_abi = \"time64\""), "{text}");
        assert!(!text.contains("compat_carrier"), "{text}");
        assert!(text.contains("tcp_zerocopy {\n      native {}"), "{text}");
        assert!(text.contains("syscall = \"select_stack\""), "{text}");
        assert!(!text.contains("routes = "), "no route order is written\n{text}");
        assert!(text.contains("ghostlock {"), "{text}");
        assert!(text.contains("anchor {"), "{text}");
        assert!(text.contains("image = \"0x1000\""), "{text}");
        assert!(text.contains("scratch = \"0x2000\""), "{text}");
    }

    #[test]
    fn an_empty_catalogue_has_no_document() {
        let superset = Superset {
            schema_version: SCHEMA_VERSION,
            release: "r".to_string(),
            kernel_major: None,
            image_base: None,
            routes: Vec::new(),
            pipeline_facts: PipelineFacts::default(),
            pipelines: Vec::new(),
            pipeline_order: Vec::new(),
            document: crate::document::Node::Null,
            device_analysis: Vec::new(),
        };
        assert!(render_superset(&superset).is_none());
    }

    fn placement(image: u64, inside: bool) -> ScratchPlacement {
        ScratchPlacement {
            image,
            window: 0x100,
            reserved_first: inside.then_some(8),
            reserved_last: inside.then_some(8),
        }
    }

    fn candidate(name: &str, conditioned: bool, in_anchor: bool, second: bool) -> CandidateOption {
        CandidateOption {
            name: name.to_string(),
            image: 0x1000,
            extent: 0x1000,
            clean_prefix: 0x1000,
            verdict: "needs-review".to_string(),
            conditioned,
            anchor: Some(AnchorTriple {
                image: 0x1000,
                bytes: 0x1000,
                stride: 32,
            }),
            in_anchor_scratch: in_anchor.then(|| placement(0x1000, true)),
            second_region_scratch: second.then(|| placement(0x2000, false)),
        }
    }

    #[test]
    fn all_safe_page_free_options_are_listed_and_auto_takes_the_first() {
        let candidates = vec![
            candidate("dump_skip.zeroes", false, true, false),
            candidate("bm_pmd", false, false, true),
            candidate("serial8250_ports", false, true, false),
        ];
        let plans = plans_for("select_stack", &candidates);
        let safe: Vec<_> = plans
            .page_free
            .iter()
            .filter(|plan| !plan.conditioned)
            .collect();
        /* dump_skip and serial8250 have in-anchor slots; bm_pmd has no scratch
         * that another safe region can host in this synthetic set. */
        assert_eq!(safe.len(), 2, "every safe anchor space is offered");
        assert_eq!(safe[0].anchor_name.as_deref(), Some("dump_skip.zeroes"));
        assert_eq!(safe[0].scratch_source.as_deref(), Some("in-anchor"));
        let auto = auto_plan("select_stack", &candidates);
        assert!(auto.page_free);
        assert_eq!(auto.anchor_name.as_deref(), Some("dump_skip.zeroes"));
        assert_eq!(auto.scratch.as_ref().unwrap().image, 0x1000);
    }

    #[test]
    fn an_anchor_without_an_in_anchor_slot_gets_second_region_options() {
        let mut dump = candidate("dump_skip.zeroes", false, false, false);
        dump.second_region_scratch = None;
        let candidates = vec![
            dump,
            candidate("bm_pmd", false, false, true),
            candidate("serial8250_ports", false, false, true),
        ];
        let plans = plans_for("select_stack", &candidates);
        let safe: Vec<_> = plans
            .page_free
            .iter()
            .filter(|plan| !plan.conditioned)
            .collect();
        /* Every anchor without an in-anchor slot gets every *other* safe
         * second region: dump x {bm_pmd, serial}, bm_pmd x serial, serial x
         * bm_pmd. The UI filters/groups; the extractor omits nothing. */
        assert_eq!(safe.len(), 4);
        assert_eq!(safe[0].anchor_name.as_deref(), Some("dump_skip.zeroes"));
        assert_eq!(safe[0].scratch_source.as_deref(), Some("bm_pmd"));
        assert_eq!(safe[1].anchor_name.as_deref(), Some("dump_skip.zeroes"));
        assert_eq!(safe[1].scratch_source.as_deref(), Some("serial8250_ports"));
        let auto = auto_plan("select_stack", &candidates);
        assert!(auto.page_free);
        assert_eq!(auto.scratch_source.as_deref(), Some("bm_pmd"));
    }

    #[test]
    fn a_conditional_second_region_is_listed_but_not_auto() {
        let candidates = vec![
            candidate("dump_skip.zeroes", false, false, false),
            candidate("bm_pmd", false, false, false),
            candidate("serial8250_ports", false, false, false),
            candidate("s3c24xx_serial_ports", true, false, true),
        ];
        let plans = plans_for("sigreturn", &candidates);
        assert!(
            plans.page_free.iter().all(|plan| plan.conditioned),
            "only conditioned plans exist"
        );
        assert!(!plans.page_free.is_empty());
        let auto = auto_plan("sigreturn", &candidates);
        assert!(!auto.page_free);
        assert!(auto.anchor.is_some(), "auto falls back to the safe anchor");
    }

    #[test]
    fn a_conditional_anchor_option_is_listed() {
        let mut conditioned = candidate("s3c24xx_serial_ports", true, true, false);
        conditioned.anchor = Some(AnchorTriple {
            image: 0x3000,
            bytes: 0x1000,
            stride: 32,
        });
        let candidates = vec![
            candidate("dump_skip.zeroes", false, false, false),
            conditioned,
        ];
        let plans = plans_for("select_stack", &candidates);
        let conditioned_plans: Vec<_> = plans
            .page_free
            .iter()
            .filter(|plan| plan.conditioned)
            .collect();
        assert_eq!(conditioned_plans.len(), 1);
        assert_eq!(conditioned_plans[0].anchor.as_ref().unwrap().image, 0x3000);
        assert!(!auto_plan("select_stack", &candidates).page_free);
    }

    #[test]
    fn tcp_has_no_page_free_options() {
        let candidates = vec![candidate("dump_skip.zeroes", false, true, false)];
        let plans = plans_for("tcp_zerocopy", &candidates);
        assert!(plans.page_free.is_empty());
        assert!(plans.anchor_only.is_none());
        assert!(!auto_plan("tcp_zerocopy", &candidates).page_free);
    }

}
