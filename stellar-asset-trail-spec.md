# Stellar Asset Trail — Product & Journey Spec

| | |
|---|---|
| **Version** | 0.2 (draft) |
| **Date** | 16 October 2026 |
| **Companion** | Design canvas "Stellar Asset Trail — User Journeys" (service blueprint, 4 journey maps, 10 key screens) |
| **Product name** | [Product] (placeholder) |
| **Changes in 0.2** | Final payment moves to a Soroban delivery escrow contract approved by the receiver's passkey (D1 decided). Waypoint hashes are salted. Cash-out screen assumes SEP-6 + SEP-38 |

---

## 1. Summary

A physical asset moves through a planned series of waypoints. At each waypoint the driver checks in. Each check-in is written to the Stellar network as a transaction that does two things at once: it records a hash of the waypoint data, and it pays the driver for that leg. The result is a custody trail that anyone can verify against the public ledger, plus driver pay that can't drift away from proof of location.

The final leg is different: it is locked in a smart contract (the **delivery escrow contract**) when the trip is dispatched, and the contract pays the driver when the receiver approves delivery with a passkey. The platform can't withhold that payment. If nobody approves or disputes by a deadline set at dispatch, the driver can claim it.

Four surfaces:

| Surface | User | Platform |
|---|---|---|
| Asset portal | Operations manager | Web (desktop first, works on phone) |
| Driver app | Driver | Mobile (Android / iOS) |
| Tracking link | Shipper / receiver | Mobile web, no login |
| Verify view | Auditor / insurer | Web, read-only link |

Out of scope for v0.1: waypoint/depot staff as a separate role (see decision D4), multi-asset loads, multi-driver hand-offs mid-route.

---

## 2. Design principles

1. **Hashes on-chain, data off-chain.** The ledger is public and permanent. Raw GPS, photos, documents and personal data stay in the platform's store; only salted SHA-256 hashes, payments and signatures go on Stellar.
2. **Proof and pay in one transaction.** A leg payment and its location record share one atomic transaction. Either both happen or neither does.
3. **No crypto for drivers or receivers.** No XLM to hold, no seed phrases, no jargon. The platform pays network fees and reserves.
4. **Verifiable without us.** An auditor can check every record against any Stellar node using an open-source script, even if the platform is gone.
5. **Two states, never mixed.** "Verified on ledger" (solid) and "live GPS estimate" (dashed) are visually distinct everywhere.

---

## 3. System architecture

### 3.1 Components

| Component | Responsibility |
|---|---|
| Portal (web) | Assets, routes, trip funding, monitoring, exceptions, payments, proof links |
| Driver app (mobile) | Trip offers, check-ins (online and offline), wallet, advances, cash-out |
| Tracking page (mobile web) | Verified trail, ETA, sign for delivery, report a problem |
| Verify view (web) | Re-hash payloads, compare with ledger, flags, proof-pack export |
| API + payload store | Canonical payloads, photos, documents, route plans, geofences |
| Signer service | Holds the platform key; co-signs only after server-side checks (geofence, tag match, time window) |
| Fee account | Pays network fees (fee-bump) and sponsors reserves; submits contract calls |
| Delivery escrow contract (Soroban) | Holds each trip's final leg; pays it on the receiver's approval, after the deadline, or on the arbiter's ruling (§4.6) |
| Passkey wallets | Smart-wallet contract accounts for receivers, deployed and paid for by the platform on first use |
| Indexer | Reads the ledger (Horizon or Stellar RPC) to build trails and reconcile payouts |
| Anchor partner | KYC and USDC → mobile money cash-out (MTN MoMo) |

### 3.2 Stellar accounts

| Account | One per | Controlled by | Purpose |
|---|---|---|---|
| Treasury (T) | Platform | Platform | Holds USDC that funds trips |
| Fee account (F) | Platform | Platform | Source of platform transactions; pays fees; sponsors reserves |
| Asset account (A) | Asset | Platform signer (master key weight 0) | The asset's on-chain identity; every custody event writes to it, so its history is one lookup |
| Trip account (E) | Trip (or pooled) | Platform signer | Holds the trip's per-leg pay and advance limit; pays legs and advances |
| Delivery escrow contract (C) | Platform (one deployment) | Contract code; admin key acts as arbiter in disputes only | Holds each trip's final leg |
| Driver wallet (D) | Driver | Key on the driver's phone (+ optional SEP-30 recovery signers) | Receives pay; signs check-ins; cashes out |
| Receiver wallet (R) | Receiver | Passkey on the receiver's phone (smart-wallet contract account) | Approves delivery or raises a dispute |

**Trust note.** Per-leg pay sits in platform-controlled trip accounts: it behaves like escrow because the signer service only releases funds against valid check-ins, but it is custodial. The final leg is not: once locked, the contract code decides who can move it (§4.6). Whether to move all leg pay into the contract later is decision D2.

---

## 4. Ledger design

### 4.1 What goes where

| On-chain (public, permanent) | Off-chain (private) |
|---|---|
| USDC payments (legs, advances, final pay, cash-out) | Raw GPS fix, accuracy, device time |
| `MEMO_HASH`: 32-byte SHA-256 of each waypoint payload | Photos, inspection notes, documents |
| Latest waypoint hash in the asset account's data entry `wp` | Route plan, geofences, time windows |
| Signatures: driver, platform; receiver's passkey approval (delivery) | Names, phone numbers, MoMo numbers, KYC data (held by the anchor) |
| Delivery escrow contract state and events (locked, delivered, disputed, resolved, released after deadline, cancelled) with the delivery or evidence hash | The random salt in every payload |

### 4.2 Waypoint payload and hashing

Canonical JSON (recommend RFC 8785, JSON Canonicalization Scheme), hashed with SHA-256. The 32-byte digest goes in the transaction's `MEMO_HASH` and in the asset account's `wp` data entry.

```json
{
  "v": 1,
  "trip": "TR-2041",
  "wp": 4,
  "event": "checkin",
  "asset": "GX400-0041",
  "tag": "04:A3:7E:91",
  "lat": 6.6167,
  "lon": -1.2167,
  "acc_m": 8,
  "t_device": "2026-10-16T11:51:37Z",
  "photos": ["sha256:…"],
  "salt": "<32 random bytes, base64>"
}
```

**The salt is required.** Without it, anyone could guess coordinates near a known waypoint and time, hash the guesses, and confirm a driver's location from the public memo. The salt is stored off-chain with the payload and released only through proof links.

### 4.3 Transaction catalogue

Notation: `[src X]` = operation source account. "Platform" = the signer-service key, which is a signer on A and E.

| # | Transaction | Tx source | Operations | Memo | Signers | Fees / reserves |
|---|---|---|---|---|---|---|
| T1 | Register asset | F | BeginSponsoringFutureReserves(A) `[src F]` · CreateAccount(A, 0) `[src F]` · SetOptions: add platform signer, master weight 0 `[src A]` · EndSponsoringFutureReserves `[src A]` | Hash of registration payload (metadata, tag ID, document hashes, salt) | F, A's one-time key | F sponsors A's reserves |
| T2 | Driver wallet | F | BeginSponsoring(D) `[src F]` · CreateAccount(D, 0) `[src F]` · ChangeTrust(USDC) `[src D]` · optional SetOptions for SEP-30 recovery signers `[src D]` · EndSponsoring `[src D]` | — | F, D (phone key) | F sponsors account + trustline |
| T3 | Fund trip | F | Sponsored create of E + ChangeTrust(USDC) + SetOptions (platform signer, master 0) · Payment T→E (leg total + advance limit) `[src T]` | Hash of route-plan payload | F, T, E's one-time key | F sponsors E |
| T4 | Pickup | D | ManageData("wp", h) `[src A]` | `MEMO_HASH` h | D, platform | Fee-bump by F |
| T5 | Waypoint check-in | D | ManageData("wp", h) `[src A]` · Payment E→D, leg amount, USDC `[src E]` | `MEMO_HASH` h | D, platform | Fee-bump by F |
| T6 | Fuel advance | F | BeginSponsoring(E) `[src F]` · CreateClaimableBalance `[src E]`: claimant D with BEFORE_ABSOLUTE_TIME(deadline); claimant E with NOT(BEFORE_ABSOLUTE_TIME(deadline)) · EndSponsoring `[src E]` | — | F, platform | F sponsors the claimant reserves |
| T7 | Claim advance | D | ClaimClaimableBalance `[src D]` | — | D | Fee-bump by F |
| T8 | Delivery | see D1 | Option B (classic): ManageData("wp", h) `[src A]` · ManageData("rcv", h) `[src R]` · Payment E→D final leg `[src E]`. Option A: a single Soroban contract call (see D1) | `MEMO_HASH` h | D, platform, receiver | Fee-bump by F |
| T9 | Cash-out | D | Payment D→anchor, using the account and memo the anchor returns (SEP-24 or SEP-6) | Anchor-specified | D | Fee-bump by F |
| T10 | Close trip | F | Payment E→T, remaining USDC `[src E]`; recycle E or remove trustline | — | F, platform | — |

A T5-shaped transaction (driver source, ManageData on A, payment from E, 32-byte hash memo, wrapped in a fee-bump) was built and signed with the Stellar Python SDK v16.1.0 to confirm the structure is valid. It was not submitted to a network.

### 4.4 Offline check-ins

- The phone builds and signs T5 offline, using its locally tracked sequence number for D, and queues it.
- On reconnect, the queue goes to the signer service **in order**. The service re-checks the geofence and tag against the payload, co-signs, fee-bumps and submits.
- **Sequence rule:** every transaction sourced from D (check-ins, claim advance, cash-out) goes through one local queue. Cash-out and claims are disabled while check-ins are pending.
- **Time bounds:** set `maxTime` to capture time + [N hours]. A check-in older than that needs an ops override, which is itself an anchored event.
- Both times are always shown: `t_device` from the payload and the ledger close time.

### 4.5 Fees and reserves

- Platform-initiated transactions use F as the transaction source. Driver-sourced transactions are wrapped in fee-bump transactions paid by F. The sequence number still comes from D.
- Account, trustline and claimable-balance reserves are sponsored by F, so A, D and E can be created with a 0 XLM starting balance.
- Each ManageData entry raises the account's minimum balance. Overwriting the single key `wp` keeps the asset account at one entry; the full history stays in its transaction records.
- The portal monitors F's XLM balance and alerts below [threshold].

---

## 5. User journeys

Each row maps to a column on the canvas journey maps.

### 5.1 Operations manager — portal

*Runs dispatch for the asset owner or logistics firm. Wants every asset on a funded route, every hand-off provable, every driver paid on time.*

| Stage | Steps | Screen | On the ledger | Risk | Design response |
|---|---|---|---|---|---|
| 1. Register asset | Add type, serial, value, documents; attach NFC/QR tag | Assets › Asset detail | T1: asset account created, reserve sponsored; document hashes anchored | Tag on the wrong item ties the trail to the wrong asset | Asset goes live only after a tag scan; Stellar address shown on the record |
| 2. Plan route | Origin, waypoints, destination; geofence radius and time window each | Route planner | Nothing yet | Geofences too tight cause false "missed" check-ins | Default radius by waypoint type, previewed on the map |
| 3. Assign & fund | Driver, vehicle, pay per leg, advance limit; fund from treasury | Route planner › Funding | T3: trip account funded; route-plan hash in memo | Underfunded trip stops paying mid-route | Dispatch blocked until escrow ≥ leg total + advance limit |
| 4. Monitor | Live trips; alerts for late, off-route, missed waypoints | Trips board | Each check-in appears with its transaction link | Live GPS and ledger check-ins treated as equal proof | Solid = ledger, dashed = live estimate |
| 5. Handle exceptions | Approve advance, extend window, reroute, log incident | Trips board › Needs attention | T6 advances; incident evidence hashed and anchored | Manual overrides weaken credibility | Every override is a signed, anchored event with reason and name |
| 6. Settle & report | Receiver signs; final pay; unused escrow returns; export | Payments | T8 delivery; T10 close | Slow receiver delays driver's final pay | Reminders, then escalation after [SLA hours] |

### 5.2 Driver — mobile app

*Drives between waypoints, often on patchy signal. Wants to know the pay is real before setting off, get it per leg, and cash out to MoMo without learning crypto.*

| Stage | Steps | Screen | On the ledger | Risk | Design response |
|---|---|---|---|---|---|
| 1. Onboard | Phone number, ID check with anchor, link MTN MoMo | Onboarding (3 steps) | T2: wallet + USDC trustline, sponsored | Lost phone = lost keys; no XLM | Keys in secure hardware; SEP-30 recovery; no crypto words |
| 2. Accept trip | Review route, pay per leg, deadline; accept | Trips › Offer | Nothing new; offer shows escrow is funded | Unclear pay → disputes | Total and per-leg pay plus "Funded" badge before Accept |
| 3. Pick up | Scan tag at origin; photograph load | Check-in | T4: custody-start hash | Wrong asset loaded | Check-in blocked unless the tag matches the trip's asset |
| 4. Check in | Enter geofence, tap Check in, scan tag | Active trip › Checked in | T5: location hash + leg pay, one transaction | No signal at waypoint | Sign offline, queue, auto-submit; "Saved — will record when online" |
| 5. Manage money | Earnings per leg; claim advance; log expenses | Wallet | Leg pay in USDC; advance as claimable balance | Cash needed before next leg pays | One-tap advance within ops limit; deduction shown up front |
| 6. Cash out | Amount, quote, confirm to MoMo | Wallet › Cash out | T9: USDC to anchor; anchor pays MoMo | Hidden spread or fees | Rate, fee, amount received and arrival time shown before Confirm; receipt kept |

### 5.3 Shipper / receiver — tracking link

*The customer or receiving site. No account, no wallet. Wants a trustworthy ETA and a delivery record that settles disputes.*

| Stage | Steps | Screen | On the ledger | Risk | Design response |
|---|---|---|---|---|---|
| 1. Get the link | SMS link at dispatch | SMS › Tracking page | Page reads the trail from the ledger | Phishing look-alikes | One short branded domain; asset's Stellar address shown |
| 2. Track | Verified waypoints, live position, ETA | Tracking page | Each waypoint links to its transaction | Verified vs estimated blur | Solid vs dashed, never mixed |
| 3. Prepare | Arrival alert; share gate/dock details | SMS + Tracking page | — | Truck waits at the gate | ETA updates per check-in; alert [N] min before arrival |
| 4. Inspect & sign | Scan tag, inspect, sign or report | Tracking page › Sign | T8: receiver signature releases final pay | No wallet, won't install one | Sign with a passkey (see D1) |
| 5. Resolve a problem | Report damage or shortage with photos | Tracking page › Report | Evidence hash anchored; final pay held | Dispute used to withhold pay unfairly | Photo evidence required; ops deadline [SLA hours] |

### 5.4 Auditor / insurer — verify view

*Checks a trail for an audit, claim or dispute. Shouldn't need to trust the platform's database.*

| Stage | Steps | Screen | On the ledger | Risk | Design response |
|---|---|---|---|---|---|
| 1. Get access | Read-only proof link or claim pack | Email › Verify | Ledger is public; link unlocks payloads and salts | Payloads shared too widely | One asset per link, time-limited, views logged |
| 2. Look up | Search asset ID, serial or Stellar address | Verify › Search | Read from a public Stellar node | Trusting the platform's copy | Show data source; allow the auditor's own node |
| 3. Verify events | Re-hash payloads; compare with memos; check signers | Verify › Trail | SHA-256(payload) = `MEMO_HASH`; signers D + platform (+ receiver on delivery) | Mismatch or gap buried | Every row: Match / Mismatch / Missing |
| 4. Review gaps | Time gaps, offline check-ins, overrides | Verify › Flags | Device time vs ledger time | Late records look suspicious | Flag gaps > [threshold]; show both times and reason |
| 5. Export | Proof pack for the file | Verify › Export | Pack lists transaction hashes | Evidence depends on the platform existing | Pack includes payloads, salts, hashes and an open-source verifier |

---

## 6. Screens and functional requirements

Screen names match the canvas artboards.

### 6.1 Portal

**Trips board** (`OpsTrips`)
- Live map (schematic in the mock; map provider TBD) with solid verified segments, dashed live segment, next waypoint ringed.
- "Needs attention" queue: missed waypoint, advance request, signature overdue, behind schedule. Each item has its actions inline (Call driver, Extend window, Approve/Decline, Remind receiver).
- Trips table: trip, asset, driver, last verified waypoint + tx link, next waypoint + ETA, escrow left, status.
- Statuses: On route · Behind schedule · Missed waypoint · Awaiting signature · Delivered · Disputed.

**Route planner** (`OpsRoutePlanner`)
- Trip details: asset (only tag-activated assets selectable), driver (only wallet-ready drivers), vehicle, receiver phone.
- Waypoints table: name, type (Pickup / Checkpoint / Delivery), geofence radius, time window, leg pay.
- Funding panel: leg total, advance limit, escrow required, treasury available, "anchor route-plan hash" toggle.
- "Fund escrow and dispatch" is disabled until treasury covers the escrow requirement.

**Asset detail & custody trail** (`OpsAsset`)
- Header: name, serial, tag ID, current status.
- Custody trail, newest first: event, place, time(s), actor, leg pay, tx link, hash result, offline flag.
- On-chain identity: Stellar address (copy, open on explorer), sponsorship note.
- Documents with "hash on ledger" date. "Create proof link" → Verify view.

**Payments** (`OpsPayments`)
- Tiles: treasury balance, total held in trip escrow, fee-account XLM balance with alert threshold.
- Escrow by trip: funded, released, advances, left.
- Advance requests with Approve / Decline (Approve creates T6).
- Recent payouts with tx links. Export reconciliation (CSV).

### 6.2 Driver app

**Active trip** (`DriverTrip`)
- Next stop, distance, window, ETA; pay for this leg with "Funded" badge.
- Primary "Check in at [waypoint]" button, enabled inside the geofence.
- Leg list: done (with time and pay), next, remaining; final leg labelled "paid when the receiver signs".
- Secondary: request a fuel advance.

**Checked in** (`DriverCheckin`)
- Three checks: tag matched, inside geofence (distance from centre), recorded on ledger (tx link).
- Leg pay received and new balance. Next stop. Continue / View wallet.

**Wallet** (`DriverWallet`)
- Balance in USDC; local-currency value shown at the anchor's rate at cash-out.
- Cash out to MoMo; Request advance; "Fuel advance ready" card with claim deadline (T7).
- Activity: legs, advances, cash-outs. Note that network fees are paid by the platform.

**Cash out** (`DriverCashout`)
- Amount (Max), destination MoMo number, quote (you send, anchor fee, rate, you receive, arrival time, quote validity), Confirm.
- The native quote layout fits SEP-6 with a SEP-38 firm quote. With SEP-24 the anchor shows its own hosted page instead (decision D3).

**States required but not drawn yet:** onboarding; trip offer; outside geofence; tag mismatch; check-in saved offline / pending; submission failed; advance requested / declined; cash-out pending / completed / failed.

### 6.3 Tracking page (`ReceiverTracking`)
- Asset, trip, driver first name; status banner ("Arrived … please inspect and sign").
- Verified waypoints with tx codes; current stop dashed.
- Checklist: tag scanned and matches; no visible damage.
- "Sign for delivery with passkey" and "Report a problem". Note that signing releases the driver's final pay; asset's Stellar address shown.

### 6.4 Verify view (`AuditorVerify`)
- Search; data-source panel with "use my own node".
- Summary: asset, Stellar account, trip, "n of n hashes match", flag count.
- Event table: event, place, device time, ledger time, signers, payload hash, on-chain memo, result, tx.
- Flag detail (e.g. offline gap) with "View payload" and "Mark as reviewed".
- Proof-pack download (PDF + JSON) and a link to the open-source verifier.

---

## 7. Business rules

| ID | Rule |
|---|---|
| BR-01 | An asset can't be assigned to a trip until its tag has been scanned and activated. |
| BR-02 | Dispatch requires trip escrow ≥ sum of leg pay + advance limit. |
| BR-03 | The signer service co-signs a check-in only if the GPS fix is inside the geofence, the tag matches the trip's asset, and the waypoint is the next expected one. |
| BR-04 | Leg pay and its location hash are always in the same transaction. |
| BR-05 | Final pay releases only with the receiver's signature (or an ops override after a resolved dispute, itself anchored). |
| BR-06 | Advances are capped at the trip's advance limit, go out as claimable balances with a deadline, and are deducted from the final leg. |
| BR-07 | Every manual override (window extension, reroute, forced check-in, pay hold or release) is an anchored event with reason and staff name. |
| BR-08 | Payload salts are never published; they're released only through proof links and proof packs. |
| BR-09 | All driver-sourced transactions go through one ordered local queue. |
| BR-10 | Unused escrow returns to treasury when the trip closes. |

---

## 8. Exceptions and edge cases

| Case | Behaviour |
|---|---|
| No signal at waypoint | Sign offline, queue, submit in order on reconnect; flag gaps > [threshold] |
| Outside geofence | Check-in disabled; "Request override" sends to ops with GPS evidence |
| Tag mismatch | Check-in blocked; ops alerted |
| Missed time window | Ops can extend (anchored override) or mark missed |
| Escrow payment would fail (underfunded, trustline issue) | Whole transaction fails, so the location isn't recorded either. Fallback: submit a record-only check-in (no payment) and mark leg pay "pending". Prevent with BR-02 |
| Fee account low on XLM | Alert at [threshold]; check-ins queue until topped up |
| Receiver doesn't sign | Reminders, then escalation after [SLA hours] |
| Damage reported | Final pay held; evidence anchored; ops resolves within [SLA hours] |
| Lost or replaced phone | SEP-30 recovery (if enabled), or re-key D with a platform-assisted signer swap |
| Driver changes mid-trip | Out of scope for v0.1 |

---

## 9. Security and privacy

- Driver keys in secure hardware (Android Keystore / iOS Secure Enclave), with SEP-30 recovery as an option.
- Platform key in an HSM or managed KMS; the signer service enforces BR-03 before signing.
- **Location spoofing:** combine device attestation (Play Integrity / App Attest), mock-location detection on Android, and tag proof. Consider cryptographic NFC tags (e.g. NTAG 424 DNA, which produces authenticated per-tap messages) so a tag scan can't be replayed.
- Salted hashes (§4.2); no personal data on-chain; proof links scoped, expiring and logged.
- The ledger is pseudonymous but public: account addresses and payment amounts are visible to anyone.

---

## 10. Open decisions

| ID | Decision | Options | Notes |
|---|---|---|---|
| D1 | How the receiver signs | **A.** Passkey smart wallet + Soroban escrow contract: `confirm_delivery` requires the receiver's passkey auth and transfers final pay via the USDC contract. **B.** Classic ed25519 key for the receiver, held in browser or platform custody, unlocked by OTP. **C.** Off-chain WebAuthn assertion; platform anchors its hash and releases pay | Classic Stellar accounts accept only ed25519, pre-auth-tx, hash-x and ed25519-signed-payload signers. Passkeys (secp256r1) work through smart contracts, enabled by Protocol 21 (CAP-51). Only one InvokeHostFunction operation is allowed per transaction, so with A the delivery step is one contract call, not the classic bundle in T8. A gives on-chain proof the receiver signed; C is quickest to build but release isn't enforced by the ledger |
| D2 | Escrow model | Platform-controlled trip accounts (classic) vs Soroban escrow contract | Classic is simpler; Soroban removes the platform's ability to withhold pay |
| D3 | Cash-out protocol | SEP-24 (anchor-hosted page) vs SEP-6 + SEP-38 (in-app quote) | The designed Cash-out screen assumes in-app quote rows (SEP-38 firm quote with `expires_at`). With SEP-24 the anchor's page shows the quote. Depends on what the anchor supports |
| D4 | Depot co-signer | Driver-only proof vs depot staff co-signing hand-offs | Co-signing makes location much harder to fake; adds a fifth persona |
| D5 | Where location records live | Memo + `wp` data entry (classic) vs Soroban contract events | Classic is cheap and simple; events are richer to query but tie the design to contracts |
| D6 | Market and anchor | [Anchor partner] supporting MTN MoMo payouts in [country] | Check the Stellar Anchor Directory and the partner's SEP support |
| D7 | Pay asset | USDC vs a local-currency stablecoin | Affects FX exposure for drivers |

---

## 11. Placeholders to fill

| Placeholder | Where |
|---|---|
| [Product] | All surfaces |
| [Anchor partner], fee, rate, payout time, quote validity | Cash out, onboarding |
| [SLA hours] | Receiver signature escalation, dispute resolution |
| [threshold] | Offline gap flag, fee-account balance alert |
| [N hours] | Offline `maxTime` |
| [N] minutes | Arrival alert |
| [N] days | Proof link expiry |
| [repo link] | Open-source verifier |
| [Public Stellar node URL] | Verify view data source |

---

## 12. Sample data used in the designs

Illustrative only. Names, amounts and transaction IDs are invented.

| Waypoint | Type | Geofence | Window | Leg pay (USDC) | Device time | Ledger time | Tx |
|---|---|---|---|---|---|---|---|
| Escrow funded | — | — | — | 120.00 funded | — | 06:50:14 | 9b2e…11fa |
| Tema Port | Pickup | 300 m | 07:00–08:00 | — | 07:11:48 | 07:12:03 | a41c…7d02 |
| Nsawam | Checkpoint | 200 m | 09:00–10:00 | 18.00 | 09:31:02 | 09:47:19 (offline) | 5f0d…e3b9 |
| Nkawkaw | Checkpoint | 200 m | 10:15–11:15 | 22.00 | 10:41:40 | 10:42:02 | c7a2…0b44 |
| Konongo | Checkpoint | 300 m | 11:30–12:30 | 15.00 | 11:51:37 | 11:52:10 | 2e88…9c51 |
| Kumasi · Warehouse B | Delivery | 150 m | 12:45–14:00 | 25.00 (on signature) | 13:05:12 | 13:14:45 | 81fd…a7c3 |

Trip TR-2041 · Diesel generator 400 kVA (GX400-0041) · driver Kwame A. · leg total 80.00 + advance limit 40.00 = 120.00 USDC escrow.

---

## 13. References

- Stellar — Memos (MEMO_HASH is a 32-byte hash): https://developers.stellar.org/docs/encyclopedia/memos
- Stellar — Fee-bump transactions: https://developers.stellar.org/docs/build/guides/transactions/fee-bump-transactions
- Stellar — Sponsored reserves (sandwich, both sign, 0 starting balance, sponsored trustlines): https://developers.stellar.org/docs/build/guides/transactions/sponsored-reserves
- Stellar — Claimable balances (claimants, time predicates, reclaim pattern): https://developers.stellar.org/docs/build/guides/transactions/claimable-balances
- Stellar — List of operations, Manage Data (name and value up to 64 bytes; each entry raises minimum balance): https://developers.stellar.org/docs/learn/fundamentals/transactions/list-of-operations#manage-data
- Stellar — Contract invocation in a transaction (one InvokeHostFunctionOp per transaction): https://developers.stellar.org/docs/learn/fundamentals/contract-development/contract-interactions/stellar-transaction
- Stellar — Announcing Protocol 21 (CAP-51, secp256r1 verification for passkeys in contracts): https://stellar.org/blog/developers/announcing-protocol-21
- SEP-24 — Hosted deposit and withdrawal: https://stellar.org/protocol/sep-24
- SEP-6 — Programmatic deposit and withdrawal: https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0006.md
- SEP-38 — Anchor RFQ API (indicative prices, firm quotes with `expires_at`): https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0038.md
- SEP-10 — Web authentication: https://stellar.org/protocol/sep-10
- SEP-12 — KYC API: https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md
- SEP-30 — Account recovery: https://developers.stellar.org/docs/build/apps/wallet/sep30
- Stellar — Anchors: https://developers.stellar.org/docs/learn/fundamentals/anchors
- Stellar — Ramps (MoneyGram Ramps, USDC cash-in/out): https://developers.stellar.org/docs/tools/ramps
- RFC 8785 — JSON Canonicalization Scheme: https://www.rfc-editor.org/rfc/rfc8785
- Stellar Python SDK v16.1.0 (`stellar_sdk.operation.ManageData`, `xdr.SignerKeyType`), used to check the 64-byte limit, the classic signer types, and the T5 transaction structure
