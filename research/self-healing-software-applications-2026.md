# Self-Healing Software Applications in 2026

A literature and community-forum review covering **calendar year 2026 only**
(January through early September). Focus: academic papers and open-source
community forums. Industry vendor blogs appear only when they document
open-source systems or community-visible operational patterns.

Sources were gathered from ACM/IEEE conference programs (especially
[SEAMS 2026](https://conf.researchr.org/track/seams-2026/seams-2026-research-track)),
arXiv HTML/PDF, GitHub issues/PRs/RFCs, CNCF publications, Hacker News, and
r/kubernetes. Venue quality is called out explicitly: CORE-A conference papers
are treated as the year's scholarly core; arXiv preprints as working papers;
IEEE workshop/regional conferences as mixed; and several 2026 “journal” PDFs
as low-trust.

---

## 1. What 2026 actually changed

Self-healing software in 2026 is no longer a MAPE-K loop that restarts pods.
The year's literature and forums converge on four claims:

1. **Diagnosis is largely solved; actuation is not.** Microsoft Research's
   R2Act benchmark (July 2026) is the sharpest statement: RAG-based LLMs
   reach 91–99% root-cause *service* accuracy, yet only 37–60% of proposed
   recovery plans are valid. Wrong operation (67.9%) and invalid plan
   structure (29.7%) dominate failures. Self-healing research has moved from
   “can the model see the fault?” to “can it pick an admissible action and
   prove the system recovered?”
   ([arXiv:2607.04623](https://arxiv.org/abs/2607.04623))

2. **The control loop is back, with an LLM in it.** SEAMS 2026 — still the
   dedicated CORE-A venue for self-* software — spent a full session on
   *Self-Healing, Diagnosis & Adaptive Repair*. MAPE-K is not discarded; it
   is extended with an LLM reasoner (MAPER), an asynchronous self-improvement
   loop (VAI, Distinguished Paper), and dynamically generated diagnostic agents
   (AWARE).

3. **Open-source operators refuse the academic “full autonomy” story.**
   Production-facing projects (K8sGPT, HolmesGPT, k8s-mechanic, Helix)
   converge on *propose-and-gate*: GitOps PRs, Slack approve buttons, Mutation
   CRs with similarity thresholds, MCP tools that auto-approve reads and
   human-gate writes. The CNCF 2026 forecast still *predicts* AIOps 2.0
   auto-remediation, but the GitHub RFCs and PRs of 2026 are about making
   existing repair *more conservative* (Karpenter node repair as voluntary
   disruption, HolmesGPT fail-closed auth).

4. **Safety architecture is the new research object.** PASE verifies
   LLM-generated recovery plans in a neural-symbolic world model. ORCA
   verifies patches by replaying telemetry, not just tests. MPI-SWS
   “Rebooting Microreboot” treats LLM agents as untrusted and puts a typed
   remediation ISA + actuation microkernel in front of them (95% harm
   reduction). The community independently reinvented the same idea:
   HolmesGPT's Kubernetes Remediation MCP splits auto-approved diagnostics
   from approval-gated `run_kubectl_command`, then spent mid-2026 closing
   auth bypasses (ROB-900, ROB-910).

---

## 2. Academic core: SEAMS 2026 (ACM, Rio, 13–14 April)

SEAMS 2026 was co-located with ICSE 2026. The Tuesday morning session
*Self-Healing, Diagnosis & Adaptive Repair* (chair: Ilias Gerostathopoulos)
is the year's canonical academic snapshot.

| Paper | Kind | Result that matters |
|-------|------|---------------------|
| Sanwouo, Temple, Quinton. *Dynamic Agent Generation for Self-Adaptive Root Cause Analysis*. ACM SEAMS 2026, pp. 36–47. [program](https://conf.researchr.org/details/seams-2026/seams-2026-research-track/5/Dynamic-Agent-Generation-for-Self-Adaptive-Root-Cause-Analysis) | Full + Artifact Award | LLM Parser + Executor compose *incident-specific* diagnostic agents over logs/metrics/traces. +12% accuracy on Nezha (fault-injected), +12–22% on OpenRCA (real). Artifact: [brellsanwouo/Aware](https://github.com/brellsanwouo/Aware) (created 2026-04-14). |
| Benecchi, Cardone, Camilli, Lestingi, Mirandola. *Verify, Augment, Improve…* ACM SEAMS 2026. **Distinguished Paper.** [program](https://conf.researchr.org/details/seams-2026/seams-2026-research-track/14/Verify-Augment-Improve-Self-Adaptation-Repair-via-Automated-Knowledge-Augmentation) | Full | MAPE-K plus an *asynchronous* self-improvement loop: ineffective adaptations are verified against a ground-truth simulator, explained, used to augment the surrogate's training data, and retrained. Average requirement-success gains 6.89% and 10.88% on two CPS subjects. This is self-healing of the *adapter*, not just the managed system. |
| Wiesinger, Engelhardt, Laas (Fraunhofer FOKUS). *Leveraging Low-Parameter LLMs for Self-Healing in Kubernetes-Based Container Orchestration*. ACM SEAMS 2026, pp. 194–206. [DOI](https://doi.org/10.1145/3788550.3794882), [PDF](https://publica.fraunhofer.de/bitstreams/b63585fa-0390-47f7-9a9d-b715bb770037/download) | Full | On-prem Mistral-7B, two-agent prompt chain (RCA → remediation), iterative cluster-feedback refinement. 15 live Kubernetes faults: **96.67% recovery accuracy**, MTTR 345 s with refinement. In-context learning and RAG helped little; iterative refinement helped a lot and added latency. Direct answer to “do we need GPT-class models for cluster healing?”: no, for this fault set. |
| Alves, Oliveira, Chagas, Bandeira, Maia, Rocha. *MAPER: Extending MAPE-K with LLM-Based Reasoning…* ACM SEAMS 2026, pp. 239–250. [program](https://conf.researchr.org/details/seams-2026/seams-2026-research-track/26/MAPER-Extending-MAPE-K-with-LLM-Based-Reasoning-to-Manage-Unanticipated-Situations-i) | Full | Adds an LLM Reasoner to MAPE-K for *unanticipated* situations. Evaluated on the SWIM self-adaptive web-application exemplar vs. a classical MAPE-K baseline; fewer policy violations under high uncertainty. |

Related SEAMS 2026 papers that are self-adaptation rather than classical
self-healing, but feed the same stack:

- **CRAFTER** (Hajj Hassan, Kattepur, Conan, Bouloukakis). Causal graphs +
  causal RL for IoT QoS adaptation. +25% vs. SOTA RL.
  [DOI](https://doi.org/10.1145/3788550.3794868).
- **Ripple** (Casimiro et al.). Long-sighted self-adaptation for retraining
  ML-enabled systems. Best Student Paper.
- **TA-REC / VORTEX 2026** (Mathews et al., ECOOP/VORTEX, not SEAMS but
  adjacent): topology-aware MAPE-K recovery of cloud performance SLO
  violations using end-to-end service topology. Recovery percentage 0.57–1.29
  across fault scenarios.
  [VORTEX 2026](https://2026.ecoop.org/details/vortex-2026-vortex-2026/6/Autonomous-Runtime-Management-of-Cloud-Performance-Anomalies-Using-End-to-End-Service).

**SEAMS takeaway.** The field still talks MAPE-K. What is new in 2026 is
(a) LLM reasoners for the long tail of unforeseen states, (b) self-healing
of the *adaptation knowledge* itself (VAI), (c) evidence that 7B-class
local models can close Kubernetes recovery loops if you chain RCA and
remediation and iterate on cluster feedback.

---

## 3. Cloud / microservice auto-remediation (arXiv and Microsoft Research)

These papers treat self-healing as *incident → executable recovery*, usually
on Kubernetes or microservices. Ordered by how strongly they constrain
actuation.

### 3.1 The diagnosis-to-action gap — R2Act (July 2026)

**Qian et al.** *Can LLMs Really Recover Microservice Failures? A
Recovery-Aware Evaluation of Diagnosis-to-Action Reasoning.*
[arXiv:2607.04623](https://arxiv.org/html/2607.04623v1), also listed by
[Microsoft Research](https://www.microsoft.com/en-us/research/publication/can-llms-really-recover-microservice-failures-a-recovery-aware-evaluation-of-diagnosis-to-action-reasoning/).

- 302 quality-audited Kubernetes incidents from Online Boutique.
- Each incident has multimodal observations, RCA labels, an
  *incident-specific action space*, and annotated valid/invalid plans.
- Offline validity + validity-gated live replay.
- Headline: RCA-service accuracy 91.4–99.7%; recovery validity 36.8–60.3%.
  Even given a correct service *and* fault type, 39.5–62.0% of plans are
  still invalid. Failures are not “the model refuses to act”; they are
  **wrong operation** and **ill-formed plans**.
- Live replay: 146/302 Qwen-RAG plans were both offline-valid *and* recovered
  the live cluster.

This is the paper every 2026 self-healing system should be scored against.
It also explains why open-source operators keep humans in the loop: the
models that look brilliant at RCA are not yet trustworthy actuators.

Related diagnostic-only benchmark (agentic RCA, not recovery):
[LLM4Ops/Cloud-OpsBench](https://github.com/LLM4Ops/Cloud-OpsBench) —
snapshot-based Kubernetes RCA with golden diagnostic trajectories.

### 3.2 End-to-end playbook generation — E2E-REME (April 2026)

**Zhang et al.** *E2E-REME: Towards End-to-End Microservices Auto-Remediation
via Experience-Simulation Reinforcement Fine-Tuning.*
[arXiv:2604.11094](https://arxiv.org/abs/2604.11094).

Defines **E2E-MR**: generate an *executable Ansible playbook* from a diagnosis
report and restore the system. Contributions:

- **MicroRemed** benchmark: deploy → inject failure → execute playbook →
  post-repair verification.
- **ThinkRemed** three-agent scaffold: probe, execute, verify/refine.
- Experience-simulation reinforcement fine-tuning rather than prompt-only
  playbook translation.
- Claims superiority vs. nine representative LLMs on public + industrial
  platforms. Model released on ModelScope.

This is the “close the R2Act gap with RL on simulated recovery experience”
answer. It still assumes playbook execution is the right actuation surface
(closer to SRE runbooks than to in-place kubectl).

### 3.3 Observability-grounded program repair — ORCA (August 2026)

**Tian, Li, Zhang, Jacobsen.** *ORCA: Observability-Grounded Program Repair
for Microservice Incidents.* [arXiv:2608.17018](https://arxiv.org/html/2608.17018).

Most APR (automated program repair) starts from failing tests or issue
text. Production incidents start from *telemetry*. ORCA:

1. Distills a **fault signature** from paired failure vs. reference telemetry.
2. Localizes candidate *code and deployment-config* sites.
3. Repair-graph agents + an exploration agent emit unified-diff patches.
4. **Telemetry-Grounded Patch Verifier (TGPV)** checks patch validity,
   syntactic/semantic correctness, test-oracle integrity, *and telemetry
   replay*.

575-case mix of synthetic code faults, synthetic config faults, and real
incidents. Outperforms agentic baselines on cost-effectiveness; uses fewer
tokens than the strongest agent baseline on real incidents. The conceptual
move: telemetry is not just for RCA dashboards — it is the repair oracle.

### 3.4 Verified LLM plans — PASE (July 2026)

**PASE: Planning-Aware Semantic self-hEaling engine.**
*Safe and Adaptive Cloud Healing: Verifying LLM-Generated Recovery Plans with
a Neural-Symbolic World Model.* [arXiv:2607.01595](https://arxiv.org/html/2607.01595).

Recasts recovery as neuro-symbolic program synthesis:

- LLM Plan Synthesis Engine composes recovery plans from semantic primitives.
- Neural-symbolic **world model** simulates plan feasibility *before*
  execution.
- DRL-trained **Meta-Prompt Optimizer** learns prompts that produce better
  plans.
- Loop: reason → plan → verify → adapt.

Reports >40% reduction in average recovery time and better unknown-fault
detection vs. loosely coupled LLM+DRL baselines, on a cloud fault-injection
dataset. This is the academic twin of “don't let the LLM kubectl apply
until a model says the plan is feasible.”

### 3.5 Typed actuation, untrusted agents — Rebooting Microreboot (April 2026)

**Laurent Bindschaedler, MPI-SWS.** *Rebooting Microreboot: Architectural
Support for Safe, Parallel Recovery in Microservice Systems.*
[arXiv:2604.09963](https://arxiv.org/abs/2604.09963).

Candea-style microreboot is unsafe in modern graphs (Alibaba traces: median
restart blast radius 8, P99 59, max 177). LLM agents with raw kubectl make
it worse. Design:

- Three untrusted agents: diagnosis, planning, verification.
- **Seven-action typed ISA** with explicit side-effect / rollback semantics.
- Trusted **actuation microkernel** validates and executes transactionally.
- Recovery groups inferred *online* from distributed traces (21 ms P99),
  not from static deployment descriptors.

Harm from agent actuation: 77% → 4% in simulation, **0% online vs. 90% for
unconstrained agents**. Primary value is safety, not speed — LLM overhead
can *increase* TTR for services that already auto-restart quickly.

This paper is the strongest 2026 argument that self-healing research should
invest in the *actuation kernel*, not a smarter prompt.

### 3.6 Full-lifecycle AI SRE — ES Guardian (April 2026)

*Deploy, Calibrate, Monitor, Heal — No Human Required: An Autonomous AI SRE
Agent for Elasticsearch.* [arXiv:2604.03933](https://arxiv.org/html/2604.03933).

Eleven-phase lifecycle agent (Evaluate … Heal … Upgrade) with a predictive
failure engine over metrics, logs, and kernel telemetry (dmesg, NVMe SMART,
NIC bonds, thermals). Claims 300 autonomous investigation-and-repair
cycles, recovery from an 18-hour cross-system outage, and a six-nines
availability *target*. Treat as a systems-experience paper, not a
controlled benchmark: the evaluation is production narrative plus a
latency-vs-shard-size finding (0.26 ms per MB/shard). Useful as a picture
of what “heal” looks like when the managed system is a single well-known
datastore rather than arbitrary microservices.

### 3.7 Neuro-symbolic planning for containers (IEEE AIMLA 2026)

*Neuro-Symbolic Planning for Autonomous Self-healing in Large-Scale
Container Orchestration Systems.*
[IEEE AIMLA 2026](https://doi.org/10.1109/aimla67915.2026.11522371).

Transformer anomaly detector on telemetry + PDDL symbolic planner.
Docker Swarm 10-node injected-fault testbed: 97.33% detection, 96.7% plan
execution success, MTTR 22.3 (units as reported by the publisher abstract).
Venue is a regional AI/ML conference, not SEAMS/ICSE; numbers are not
independently reproduced here. Architecturally it rhymes with PASE (neural
detect + symbolic plan) without the world-model verification loop.

### 3.8 Multi-agent Kubernetes recovery — SHADE

*Autonomous Infrastructure Healing: A Multi-Agent Kubernetes Recovery
Framework* (SHADE). [IEEE Xplore 11333843](https://ieeexplore.ieee.org/document/11333843).

Six agents: log classification, anomaly detection, historical comparison,
decision modeling, execution, feedback. Production-like Kubernetes eval:
65.5% MTTR reduction, 38.2% resiliency-score increase, 50% explainability
improvement. Useful as an industrial-pattern paper (explainability as a
first-class metric). Venue/reproducibility weaker than SEAMS artifacts.

---

## 4. Self-healing of *agents*, not just applications

A second 2026 cluster treats the LLM agent *itself* as the software that
must heal — relevant both as an application class and as the control plane
for the systems above.

### 4.1 Self-healing agentic orchestrators (June 2026)

*Self-Healing Agentic Orchestrators for Reliable Tool-Augmented Large
Language Model Systems.* [arXiv:2606.01416](https://arxiv.org/html/2606.01416v1).
Artifact: [R-Suresh/self-healing-agentic-orchestrator](https://github.com/R-Suresh/self-healing-agentic-orchestrator)
(release `v1.0-arxiv`).

Reliability as a **bounded runtime control problem**: map observable failure
signals → failure classes → targeted recovery under explicit budgets →
verify recovered trajectories → record traces. Explicitly related to
MAPE-K, but specialized to tool-timeouts, malformed arguments, stale
context, contradictory evidence, retry loops, and *semantic silent failures*.

Controlled 100-task fault-injection benchmark:

| Strategy | Task success |
|---------|--------------|
| Self-healing | **98.8%** (99.6% primary / 97.3% stress) |
| Retry-only | 94.5% |
| Full replanning | 93.8% |
| Single-attempt gap | 94.0% vs 85.3% / 88.2% |

Verifier-guided self-healing: **0.0% semantic silent failures**. Authors are
explicit that this is *not* a production LLM-agent measurement.

### 4.2 AgentTether (July 2026)

*AgentTether: Graph-Guided Diagnosis and Runtime Intervention for Reliable LLM
Agent Operations.* [arXiv:2607.06273](https://arxiv.org/pdf/2607.06273).

Does not modify the underlying agent. Abstracts runs into Transition Units,
builds a Critical Transition Graph, localizes failure-critical
subtrajectories (offline HGT + run-local detector), then injects
behavior-scoped guidance plus optional guarded runtime intervention. Repair
memory persists across iterations. Deployable offline (diagnose/guide) or
online (repair layer). Addresses the “blind retry / ungrounded
self-reflection” failure modes that the orchestrator paper also names.

### 4.3 AgentRewind (August 2026)

*AgentRewind: Recoverable Execution for Long-Horizon LLM Agents.*
[arXiv:2608.14380](https://arxiv.org/pdf/2608.14380).
Code: [Futuresis/replay-agent-recorder](https://github.com/Futuresis/replay-agent-recorder).

Checkpoints aligned agent context *and* controlled environment so a stalled
long-horizon agent can rewind, restore, and resume with rewind-memory from
failed attempts. Introduces **MettleBench** (engineering assignments with
checklist progress, not just binary success). Limitation stated by authors:
only controlled state is restored; stall detection is external.

### 4.4 SelfHeal / AgentDefect (EASE 2026)

Islam, Raza, Wardat. *SelfHeal: Empirical Fix Pattern Analysis and Bug Repair
in LLM Agents.* [arXiv:2604.17699](https://arxiv.org/abs/2604.17699),
targeted at EASE 2026 (Glasgow, 9–12 June).

This paper is unusual in using **open-source community forums as primary
data**, not just as related work:

- 930 unique buggy instances: 665 Stack Overflow, 51 GitHub issues, 129
  GitHub commits, 85 HuggingFace Forums (plus multi-pattern expansion to
  1,025 annotated entries).
- First taxonomy of *fix patterns inside agentic systems* (eight patterns
  not in prior SE/DL taxonomies).
- **AgentDefect**: 37 executable runtime bugs with tests and pinned
  requirements.
- SelfHeal: ReAct fix-agent + critic agent, internal fix rules + web
  search. Gemini 3 Pro backbone reported to beat baselines/SoTA on
  AgentDefect.

This is the 2026 paper that most directly mines GitHub/SO/HF forums for
self-healing *of* agent software.

### 4.5 AutoHeal (JDIT 2026) — low trust

Gubbala. *Adaptive Self-Healing Software Systems Using Large Language Models…*
*Journal of Data Science and Information Technology* 3(1), 2026.
[PDF](https://jdit.sciforce.org/article/view/281/281.pdf).

Proposes a unified LLM framework (config validation + vuln detection +
spec regeneration) with adaptive prompt tuning. Venue is not a recognized
SE venue; treat claims as unverified. Included only because web search
surfaces it aggressively under “self-healing software 2026.”

Same caution for *IJEDR 2026* “Evaluating the Impact of Self-Healing”
(Minikube + Isolation Forest + Kubernetes restart/scale) and *IJCOPE 2026*
“An Intelligent Self-Healing AI Framework…” (96.4% detection, MTTR 8.7s →
2.1 s in simulation). The architectures (MAPE-K + anomaly model + kubectl)
are real patterns; the numbers should not be cited as established results.

---

## 5. Open-source community forums and projects (2026)

Academic papers optimize recovery rate. 2026 forums optimize **who is
allowed to mutate the cluster, and how that mutation is audited**.

### 5.1 CNCF: forecast vs. working-group reality

**Forecast (23 Jan 2026).** Asif Awan, *The autonomous enterprise and the
four pillars of platform control: 2026 forecast*, CNCF blog.
[cncf.io/blog/2026/01/23/…](https://www.cncf.io/blog/2026/01/23/the-autonomous-enterprise-and-the-four-pillars-of-platform-control-2026-forecast/)

Self-healing is pillar three, “safety nets”:

- Predictive SRE (avert incidents).
- **AIOps 2.0**: identify RCA, correlate runbook, execute
  (traffic-shift / restart / rollback) without waiting for a human.
- SRE role becomes: set error budgets and handle novel failures.

This is the community *narrative*. The same post still keeps a fourth
pillar — **manual review for high-risk actions** — which is what the
GitHub threads actually implement.

**Working group.** CNCF TAG Runtime Cloud Native AI WG
([tag-runtime.cncf.io/wgs/cnaiwg](https://tag-runtime.cncf.io/wgs/cnaiwg/)):
biweekly meetings, Slack `#wg-artificial-intelligence`. 2026 discussion
energy is MCP standardization, agentic gateways, observability/policy —
the *control plane for agents* — more than a new self-healing CRD.

### 5.2 Kubernetes SIG / Karpenter: node repair as a first-class disruption

The most substantial 2026 *forum* document on classical (non-LLM)
self-healing is the Karpenter node-repair RFC series. Repair already exists
as an alpha `node.health` controller that **force-deletes** NodeClaims
behind a hardcoded 20% unhealthy circuit breaker. That breaker is
AZ-blind, latches, and either freezes all repair or stampedes.

**PR [#3192](https://github.com/kubernetes-sigs/karpenter/pull/3192)**
(*docs: RFC for making node repair voluntary disruption*, opened 2026-07-30,
still open as of 2026-09-09) proposes:

1. Repair becomes a `DisruptionReason` on the existing budget machinery
   (default cap **10%**, tighter than today's 20%).
2. Starvation-free ordering: `rank + age/τ − backoff`.
3. Per-condition drain policy (graceful vs. forceful).
4. Dedicated repair veto (shape deferred).

Follow-up RFCs in the same month cluster:

| PR | Topic |
|----|--------|
| [#3277](https://github.com/kubernetes-sigs/karpenter/pull/3277) | Node Repair Veto |
| [#3273](https://github.com/kubernetes-sigs/karpenter/pull/3273) | Repair under correlated failure |
| [#3270](https://github.com/kubernetes-sigs/karpenter/pull/3270) | Candidate resolution, admission, escalation |
| [#3263](https://github.com/kubernetes-sigs/karpenter/pull/3263) | Reason-aware repair policy matching |
| [#3259](https://github.com/kubernetes-sigs/karpenter/pull/3259) | Reboot as a node action |
| [#3311](https://github.com/kubernetes-sigs/karpenter/pull/3311) | Implementation: node repair as voluntary disruption |

This is classical autonomic computing (detect unhealthy node → drain →
replace) colliding with disruption budgets, PDBs, and correlated-failure
physics. The community's 2026 move is **not** “repair faster”; it is
“repair like consolidation: paced, vetoable, budgeted.”

### 5.3 K8sGPT (CNCF Sandbox): experimental auto-remediation

[k8sgpt-ai/k8sgpt-operator](https://github.com/k8sgpt-ai/k8sgpt-operator)
and [k8sgpt.ai/auto-remediation](https://k8sgpt.ai/auto-remediation).

2026 operator README (live at research time) is unusually explicit:

> Auto-remediation is here — now with a real safety gate. … The LLM never
> receives write authority: the operator re-fetches the object, calculates
> the semantic JSON patch itself, allows exactly one approved image path,
> dry-runs it with the Kubernetes API server, and rejects stale, broad, or
> unsafe proposals.

That sentence is the community's distilled lesson from R2Act/PASE/Microreboot,
implemented as an operator:

- Findings → `Mutation` CR (intent + audit log).
- Similarity / `riskThreshold` gate (often 90).
- Owned pods are repaired via the owning Deployment, not the ephemeral Pod.
- Alpha, opt-in, “not ready for production” in the docs — even after a
  live ImagePullBackOff → Successful Mutation demo.

Operational bug that made it to a 2026 commit:
[k8sgpt-operator@b94e41f](https://github.com/k8sgpt-ai/k8sgpt-operator/commit/b94e41f434417bd1fa5f2c3112dddabe9fcc3f9e)
(2026-02-01) — when auto-remediation is *disabled*, the reconciler ignored
`spec.analysis.interval` and requeued every 30 s. Community care is about
control-loop hygiene, not just LLM quality.

KodeKloud's 2026 tutorial
([Using AI to Troubleshoot Kubernetes Clusters](https://kodekloud.com/blog/using-ai-to-troubleshoot-kubernetes/))
is the practitioner-forum version: k8sgpt analyzers work without an API
key; `--explain` is the privacy boundary (`--anonymize`, local Ollama).
Auto-remediation is mentioned as optional and dangerous.

### 5.4 HolmesGPT: diagnose-and-act MCP, then lock it down

[HolmesGPT](https://holmesgpt.dev/latest/data-sources/builtin-toolsets/kubernetes-remediation-mcp/)
is the 2026 open-source AIOps agent that most clearly encodes
**read-auto / write-gated** self-healing.

| Tool | Mutating | Approval |
|------|----------|----------|
| `read_file_from_container` | no | auto (secret mounts always denied) |
| `run_preapproved_kubectl_command` | no | auto (ps/top/df/ls/netstat/ss) |
| `run_preapproved_diagnostic_image` | no | auto (pinned netshoot/busybox/curl) |
| `run_kubectl_command` | **yes** | **human** |

The 2026 PR history is a security incident log for self-healing agents:

- [PR #2148](https://github.com/HolmesGPT/holmesgpt/pull/2148) — replace
  `restricted_tools` with explicit approval-based tool separation; default
  ClusterRole no longer `cluster-admin`; NetworkPolicy on by default.
- [PR #2396](https://github.com/HolmesGPT/holmesgpt/pull/2396) (merged
  2026-08-24, ROB-900) — the remediation MCP was an **unauthenticated HTTP
  socket**; client-side human approval is bypassed if you hit the socket
  directly. Chart now generates a bearer-token Secret by default.
- [PR #2412](https://github.com/HolmesGPT/holmesgpt/pull/2412) (merged
  2026-08-23, ROB-910) — diagnostic-pod target policy against
  SSRF/exfiltration.
- Open follow-ups: fail-closed API auth ([#2400](https://github.com/HolmesGPT/holmesgpt/pull/2400)),
  binary allowlists for exec ([#2229](https://github.com/HolmesGPT/holmesgpt/pull/2229)).

Forum lesson: **self-healing that can exec in the cluster is a privileged
control plane**. 2026 contributor energy went into authentication, SSRF,
and least-privilege RBAC, not into raising auto-remediation recall.

### 5.5 k8s-mechanic (née k8s-mendabot): GitOps-only healing

[lenaxia/k8s-mechanic](https://github.com/lenaxia/k8s-mechanic) — created
2026-02-21, ~50 stars by 2026-09-09. r/kubernetes launch thread:
[k8s-mendabot: automate your gitops fixes](https://www.reddit.com/r/kubernetes/comments/1re80qk/k8smendabot_automate_your_gitops_fixes/).

Design that the 2026 Reddit/GitHub community actually applauds:

- Watch CrashLoopBackOff, ImagePullBackOff, OOMKilled, degraded Deployments,
  unschedulable pods, failed Jobs, PVC failures, NotReady nodes.
- Stabilisation window + dedup so a restart storm is one investigation.
- In-cluster agent Job with **read-only RBAC**, secret redaction before
  LLM, clones the GitOps repo, opens a PR.
- **Never mutates the cluster. Never merges.**
- Feb 2026 worklog: auto-close the PR when the cluster self-heals
  (Deployment recovers, Node Ready) — including PRs that went stale
  because Kubernetes' *built-in* self-healing already won.

This is the GitOps encoding of Helix's Slack button and HolmesGPT's
approval gate: the healing artifact is a reviewable diff.

Adjacent r/kubernetes thread
[Kubernetes Auto Remediation](https://www.reddit.com/r/kubernetes/comments/1ou2vat/kubernetes_auto_remediation/)
is the skeptical baseline: operators still answer “OOMKilled → fix the
app or use VPA,” “node reboot → Cluster API / Karpenter / kured,” and
“don't auto-delete CrashLoop pods with a Kyverno sledgehammer.” LLM
self-healing is not the default recommendation in that thread; classical
controllers are.

### 5.6 Helix: crash-tracker → TDD → PR → Slack (Hacker News)

[Show HN: Helix – open-source self-healing back end for production crashes](https://news.ycombinator.com/item?id=47776525)
([github.com/88hours/helix-community](https://github.com/88hours/helix-community),
created 2026-04-09).

Pipeline:

1. Sentry/Rollbar webhook → Crash Handler.
2. QA agent writes a **failing test first** (TDD) and opens a GitHub Issue.
3. Dev agent patches to the test, runs the suite, opens a PR.
4. Slack: Approve PR / Reject PR.

HN discussion (small) focused on (a) name collision with Helix Editor and
(b) **context-window limits on large codebases** — the same scaling
objection R2Act makes about diagnosis-to-action, applied to application
source. Architecture is Python + Redis pub/sub + Claude or local Ollama,
Docker Compose self-host.

Same pattern as k8s-mechanic, one layer down: heal *application code* via PR,
not live mutation.

Related HN 2026 posts in the same reliability cluster (not full
self-healing, but the control layer the papers say is missing):

- [Agent-triage](https://news.ycombinator.com/item?id=47334775) — diagnose
  agent failures from LangSmith/Langfuse/OTel traces (npx, local).
- [Agent Gate](https://news.ycombinator.com/item?id=47047698) — intercept
  tool calls, OPA-style policy, vault-backed rollback of destructive targets
  before the agent can act. Author frames it as “Permissive Action Links
  for agents.” This is the HN vernacular of the Microreboot ISA.

### 5.7 Other 2026 OSS implementations

- **ARES** — [Nitanshu715/ARES-Autonomous-Reliability-Engineering-System](https://github.com/Nitanshu715/ARES-Autonomous-Reliability-Engineering-System).
  MAPE-K over Docker Engine API, Isolation Forest, Prometheus/Grafana.
  Claims ICATEET 2026 for the anomaly detector. Teaching/demo grade, not
  a cluster operator.
- **Axon** — r/kubernetes
  [axon: Kubernetes-native framework for orchestrating autonomous AI coding agents](https://www.reddit.com/r/kubernetes/comments/1r77vxu/axon_the_kubernetesnative_framework_for/).
  Ephemeral pods for Claude/Codex/Gemini/OpenCode; relevant as the
  *execution substrate* k8s-mechanic uses, not as a healer itself.
- **Sosivio** MicroK8s addon thread on Discuss Kubernetes
  ([t/addon-sosivio/34707](https://discuss.kubernetes.io/t/addon-sosivio/34707),
  June 2026): commercial “eventless failure prediction + automated
  resolution,” agentless. Included as a forum data point, not an open
  design.
- GitHub search noise: dozens of 2026 PRs titled “self-healing CI” that
  retry flaky jobs. That is not application self-healing; it is CI
  retry with branding.

---

## 6. Cross-cutting synthesis

### 6.1 A shared 2026 architecture (drawn, not prescribed)

Every serious 2026 system, academic or OSS, is some subset of:

```
  telemetry / crash / k8s events
           │
           ▼
     Monitor ──────────────────────────────► Knowledge (traces, incident memory)
           │
           ▼
     Analyze / RCA  (LLM, causal graph, Isolation Forest, k8sgpt analyzers)
           │
           ▼
     Plan  (playbook, PDDL, unified-diff, Mutation CR, Ansible, ISA ops)
           │
           ▼
     Verify  (world model, TGPV telemetry replay, dry-run, tests, critic agent)
           │
           ▼
     Gate  (budget, similarity, human PR/Slack, typed microkernel)
           │
           ▼
     Execute  (kubectl / GitOps merge / Ansible / restart group)
           │
           ▼
     Confirm  (finding cleared, SLO restored, Mutation Successful) → learn
```

MAPER puts the LLM in Analyze/Plan. VAI puts a second loop around Verify.
R2Act says Plan is where systems fail. PASE/ORCA/Microreboot/HolmesGPT
invest in Verify+Gate. Helix/k8s-mechanic *are* the Gate (PR).

### 6.2 Two meanings of “self-healing” that 2026 still conflates

| Sense | What heals | 2026 exemplars |
|-------|------------|----------------|
| **Runtime recovery** | The live system (pod, node, SLO, ES cluster) | K8sGPT Mutation, Karpenter repair, PASE, E2E-REME, SHADE, ES Guardian, SEAMS K8s 7B prototype |
| **Artifact repair** | Source, config, agent code, GitOps YAML | ORCA, Helix, k8s-mechanic, SelfHeal/AgentDefect |

Kubernetes' built-in replica restart is still the most widely deployed
self-healing, and k8s-mechanic's auto-close-PR feature admits that
explicitly. LLM layers sit *on top* of that, mostly to handle
misconfiguration, image tags, and playbooks — not to replace the
kubelet.

### 6.3 The actuation gap is the research result of the year

R2Act's numbers should be read next to:

- SEAMS 96.67% K8s recovery — on **15** faults, with iterative cluster
  feedback (the missing piece R2Act says models lack).
- E2E-REME's RL on simulated playbook execution — training the mapping
  R2Act says is unlearned.
- HolmesGPT/K8sGPT refusing to give the LLM write authority.
- Microreboot measuring *harm*, not just success.

A 96% recovery rate without an action-validity metric is not comparable
to a 60% valid-plan rate. 2026 finally has a language for that mismatch.

### 6.4 Local / small models are in scope

Fraunhofer FOKUS (SEAMS): Mistral-7B on-prem, privacy and cost as first
requirements. Helix offers Ollama. k8sgpt supports Ollama/LocalAI.
Community privacy advice (`--anonymize`, never ship secrets to hosted
models) matches the academic on-prem prototype. Self-healing that
requires a frontier API is not the only 2026 design.

### 6.5 Self-healing software now includes self-healing *agents*

SelfHeal, AgentTether, AgentRewind, and the agentic orchestrator paper
treat orchestration failures (tool args, silent wrong answers, long-horizon
stalls) as the fault model. That matters for any product whose “application”
*is* an LLM agent. The orchestrator paper's verifier-eliminates-silent-failures
result is the agent-world analogue of ORCA's telemetry replay.

---

## 7. What is still open at 2026-09

1. **Live, multi-service, adversarial evaluation.** MicroRemed, R2Act's
   302 Online Boutique incidents, and ORCA's 575-case mix are the start of
   a benchmark culture. None yet combine: real multi-tenant traffic,
   GitOps constraints, PDBs, and an untrusted LLM with a typed ISA.
2. **Correlated failure.** Karpenter's 2026 RFC cluster exists because
   naive self-healing *causes* outages under false-positive waves. Academic
   papers still mostly inject independent faults.
3. **Authority and authentication.** HolmesGPT ROB-900 (unauthenticated
   mutating MCP) will not be the last. Papers that “execute kubectl” without
   an auth story are incomplete as systems papers.
4. **Healing vs. hiding.** Restart-based healing (Microreboot, kubelet,
   Isolation-Forest-then-restart papers) can mask memory leaks and bad
   deploys. Artifact-repair systems (ORCA, Helix) attack the cause;
   runtime systems often attack the symptom. 2026 does not yet have a
   standard for when to escalate from restart to patch.
5. **Human-gate UX at volume.** If R2Act-class validity is ~50%, a
   production auto-healer that opens a PR per CrashLoop will drown review.
   k8s-mechanic's dedup + stabilisation window + auto-close is the only
   2026 OSS design that treats reviewer attention as a resource.
6. **Predatory-journal contamination.** Web search for this topic is
   polluted by JDIT/IJEDR/IJCOPE-class PDFs that copy MAPE-K diagrams
   and report 94–96% accuracy. They should not be used in related-work
   sections without a venue check.

---

## 8. Annotated source list (2026 only)

### Academic — high confidence

- SEAMS 2026 program:
  [conf.researchr.org/track/seams-2026/seams-2026-research-track](https://conf.researchr.org/track/seams-2026/seams-2026-research-track)
- Sanwouo, Temple, Quinton. Dynamic Agent Generation… SEAMS 2026. Artifact:
  [github.com/brellsanwouo/Aware](https://github.com/brellsanwouo/Aware),
  HAL [hal-05402186](https://hal.science/hal-05402186)
- Benecchi et al. Verify, Augment, Improve. SEAMS 2026 Distinguished Paper
- Wiesinger, Engelhardt, Laas. Low-parameter LLMs for K8s self-healing.
  SEAMS 2026. [DOI 10.1145/3788550.3794882](https://doi.org/10.1145/3788550.3794882)
- Alves et al. MAPER. SEAMS 2026, pp. 239–250
- Hajj Hassan et al. CRAFTER. SEAMS 2026.
  [DOI 10.1145/3788550.3794868](https://doi.org/10.1145/3788550.3794868)
- Qian et al. R2Act. [arXiv:2607.04623](https://arxiv.org/abs/2607.04623)
- Zhang et al. E2E-REME. [arXiv:2604.11094](https://arxiv.org/abs/2604.11094)
- Tian et al. ORCA. [arXiv:2608.17018](https://arxiv.org/abs/2608.17018)
- PASE. [arXiv:2607.01595](https://arxiv.org/abs/2607.01595)
- Bindschaedler. Rebooting Microreboot. [arXiv:2604.09963](https://arxiv.org/abs/2604.09963)
- Self-Healing Agentic Orchestrators. [arXiv:2606.01416](https://arxiv.org/abs/2606.01416)
- AgentTether. [arXiv:2607.06273](https://arxiv.org/abs/2607.06273)
- AgentRewind. [arXiv:2608.14380](https://arxiv.org/abs/2608.14380)
- Islam, Raza, Wardat. SelfHeal. [arXiv:2604.17699](https://arxiv.org/abs/2604.17699)
- ES Guardian. [arXiv:2604.03933](https://arxiv.org/abs/2604.03933)
- Mathews et al. TA-REC. VORTEX 2026 / ECOOP 2026

### Academic — mixed / cite with venue caveat

- IEEE AIMLA 2026 neuro-symbolic container healing.
  [10.1109/aimla67915.2026.11522371](https://doi.org/10.1109/aimla67915.2026.11522371)
- SHADE. [IEEE Xplore 11333843](https://ieeexplore.ieee.org/document/11333843)

### Academic — do not treat as established

- Gubbala, AutoHeal, JDIT 3(1) 2026
- IJEDR 2026 self-healing dashboard paper
- IJCOPE 2026 “intelligent self-healing AI framework”

### Open-source forums and repos

- CNCF 2026 forecast (safety nets / AIOps 2.0):
  [cncf.io/blog/2026/01/23/…](https://www.cncf.io/blog/2026/01/23/the-autonomous-enterprise-and-the-four-pillars-of-platform-control-2026-forecast/)
- CNCF TAG Runtime CNAI WG:
  [tag-runtime.cncf.io/wgs/cnaiwg](https://tag-runtime.cncf.io/wgs/cnaiwg/)
- Karpenter node-repair RFC:
  [kubernetes-sigs/karpenter#3192](https://github.com/kubernetes-sigs/karpenter/pull/3192)
- K8sGPT operator auto-remediation:
  [AUTO_REMEDIATION.md](https://github.com/k8sgpt-ai/k8sgpt-operator/blob/main/AUTO_REMEDIATION.md)
- HolmesGPT Kubernetes Remediation MCP + ROB-900/910:
  [docs](https://holmesgpt.dev/latest/data-sources/builtin-toolsets/kubernetes-remediation-mcp/),
  [PR 2396](https://github.com/HolmesGPT/holmesgpt/pull/2396),
  [PR 2412](https://github.com/HolmesGPT/holmesgpt/pull/2412),
  [PR 2148](https://github.com/HolmesGPT/holmesgpt/pull/2148)
- [lenaxia/k8s-mechanic](https://github.com/lenaxia/k8s-mechanic) and
  [r/kubernetes thread](https://www.reddit.com/r/kubernetes/comments/1re80qk/k8smendabot_automate_your_gitops_fixes/)
- [r/kubernetes Auto Remediation](https://www.reddit.com/r/kubernetes/comments/1ou2vat/kubernetes_auto_remediation/)
- Helix Show HN: [item?id=47776525](https://news.ycombinator.com/item?id=47776525)
- Agent Gate Show HN: [item?id=47047698](https://news.ycombinator.com/item?id=47047698)
- Agent-triage Show HN: [item?id=47334775](https://news.ycombinator.com/item?id=47334775)
- Discuss Kubernetes Sosivio addon:
  [t/addon-sosivio/34707](https://discuss.kubernetes.io/t/addon-sosivio/34707)
- [LLM4Ops/Cloud-OpsBench](https://github.com/LLM4Ops/Cloud-OpsBench)
- [R-Suresh/self-healing-agentic-orchestrator](https://github.com/R-Suresh/self-healing-agentic-orchestrator)

---

## 9. Method notes

- **Inclusion:** publication or forum post dated 2026, or a 2026
  conference proceedings paper. AdaptiFlow ([arXiv:2512.23499](https://arxiv.org/abs/2512.23499),
  December 2025) was excluded as out of year despite being adjacent MAPE-K
  microservice work.
- **Forum definition:** GitHub issues/PRs/RFCs, CNCF blog + TAG pages,
  Hacker News, Reddit r/kubernetes, Discuss Kubernetes. Slack archives were
  not systematically readable; TAG Runtime meeting notes are cited via
  public pages only.
- **Not used as academic evidence:** AWS product blogs (Intuit EWOK,
  Mary Kay Bedrock) — they document 2026 industrial *practice* of agentic
  DR/triage but are not papers or OSS forums.
- Retrieval date: 2026-09-10. Preprints and open PRs may have been revised
  after this note.
