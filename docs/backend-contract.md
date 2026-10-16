# What the driver app expects from the backend

The backend and the delivery-escrow contract API are being built by another team. The driver app talks to them through one Kotlin interface, `PlatformApi` ([core/src/main/kotlin/com/galaxy/assettrail/core/platform/PlatformApi.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/platform/PlatformApi.kt)). Until their API exists, the `:platform-mock` module answers instead, in-process.

This page lists what the app currently assumes, so the two sides can be reconciled. Where the backend team's API differs, the app's side changes in one place: a new `PlatformApi` implementation (for example an HTTP client module), swapped in at [AppContainer.kt](../app/src/main/kotlin/com/galaxy/assettrail/driver/AppContainer.kt).

## Who does what

| On the phone (this app) | On the backend (other team) |
|---|---|
| Creates the driver's ed25519 key; only the public address leaves the phone | Creates and sponsors the driver account and USDC trustline (T2) |
| Builds the salted waypoint payload, canonicalises it (RFC 8785) and hashes it (SHA-256) | Stores the exact payload bytes and salt; never publishes the salt (BR-08) |
| Builds and signs every driver-sourced transaction: check-ins (T4/T5/delivery), advance claims (T7), cash-outs (T9) | Signer service: checks BR-03, co-signs, wraps in a fee bump paid by the fee account, submits |
| Keeps one ordered queue of signed transactions, offline included (BR-09) | Indexer: balance, account sequence, activity, contract and advance status |
| Re-signs the queue when sequence numbers shift; falls back to a record-only check-in when escrow can't pay (§8) | Ops decisions (advances, overrides), escrow contract calls, anchor (SEP-12, SEP-38, SEP-6) |

## Calls

All amounts are USDC with 7 decimals. Times are epoch milliseconds.

| Call | Purpose (spec) | Returns |
|---|---|---|
| `sendPhoneCode(phone)` | Onboarding step 1 | `CodeSent` |
| `verifyPhone(phone, code)` | Onboarding step 1 | driver id |
| `submitKyc(driverId, {fullName, idType, idNumber})` | ID check with the anchor, SEP-12 (§5.2 stage 1) | `{approved, provider}` |
| `createWallet(driverId, {accountId, fullName, phone, momoNumber, recovery})` | T2: sponsored account + trustline; SEP-30 signers when `recovery` | `{accountId, sequence, txHash}` |
| `snapshot(driverId, activeTripId?)` | Everything the screens show; polled every 4 s while online | `DriverSnapshot` |
| `acceptOffer` / `declineOffer(driverId, tripId)` | Trips › Offer (§5.2 stage 2) | the `TripPlan` |
| `submit(driverId, Submission)` | Signer service (§4.3, §4.4) | `Recorded`, `BadSequence`, `Rejected` or `Retry` |
| `requestOverride(driverId, {tripId, waypointIndex, fix, reason})` | Outside the geofence (§8) | `OverrideInfo` |
| `reportTagMismatch(driverId, tripId, waypointIndex, scannedTag)` | Tag mismatch alert (§8) | — |
| `requestAdvance(driverId, tripId, amount)` | Fuel advance request (§5.2 stage 5) | `AdvanceInfo` (status updates arrive in the snapshot) |
| `claimFinalPay(driverId, tripId)` | Release after the delivery deadline (§1, §4.6) | `ContractInfo` |
| `quote(driverId, sell)` | SEP-38 firm quote | `FirmQuote` with `expiresAt` |
| `startCashOut(driverId, quoteId, momoNumber)` | SEP-6 withdrawal | `{cashOutId, anchorAccount, memoHashHex, amount}` |

`DriverSnapshot` carries: server time, balance, the driver account's ledger sequence, open offers, the active trip's status (contract state, advances, overrides, legs paid late, closed flag), activity (newest first) and cash-outs.

## Submissions

`Submission = {kind, envelopeXdr, tripId?, payloadJson?, refId?}`, where `envelopeXdr` is a base64 transaction envelope signed by the driver.

Every submission:

- Source account is the driver's; sequence is the account's next one. Otherwise answer `BadSequence(accountSequence)`; the app re-signs its whole queue from there and retries.
- Time bounds: `maxTime` is set. Check-ins use capture time + 6 hours (the spec's `[N hours]`); past it, answer `Rejected(TOO_LATE)`.
- A rejected transaction never reaches the ledger, so it must not use up the sequence number. The app re-signs everything behind it one number lower.
- `Retry(message)` means "not now, nothing wrong with it" (for example, fee account low). The app keeps it queued and shows the message.

Check-in (`kind = CHECK_IN`, carries `payloadJson`):

- `MEMO_HASH` = h = SHA-256 of the RFC 8785 form of the payload.
- Operation 0: `ManageData("wp", h)`, source = the asset account.
- Operation 1, checkpoints only: `Payment` trip account → driver, the leg's pay in USDC. Pickup and delivery carry no payment (the final leg is in the contract).
- Backend checks: driver signature; the hash; payload `trip` and `asset`; BR-03 (next expected waypoint, tag matches, inside the geofence or `payload.override` names an approved override); payment matches the route plan.
- If the trip account can't pay, answer `Rejected(ESCROW_UNDERFUNDED)`. The app re-signs the same check-in without the payment (same sequence) and marks the leg's pay pending; the backend pays it later and lists it in `latePayments`.
- On success: co-sign with the platform signer, fee-bump from the fee account, submit, and return `Recorded(txHash, ledgerTime, balance)`.

Advance claim (`kind = CLAIM_ADVANCE`, `refId` = advance id): one `ClaimClaimableBalance` with the advance's `balanceId`.

Cash-out (`kind = CASH_OUT`, `refId` = cash-out id): one `Payment` driver → `anchorAccount`, amount = the quote's sell amount, `MEMO_HASH` = `memoHashHex` from `startCashOut`.

Reject codes the app understands: `MALFORMED`, `BAD_SIGNATURE`, `OUTSIDE_GEOFENCE`, `TAG_MISMATCH`, `NOT_NEXT_WAYPOINT`, `TOO_LATE`, `ESCROW_UNDERFUNDED`, `UNKNOWN_TRIP`, `CLAIM_UNAVAILABLE`, `CASH_OUT_UNKNOWN`, `INSUFFICIENT_BALANCE`. The `message` is shown to the driver as written.

## Waypoint payload

Hashed in RFC 8785 canonical form; [CanonicalJson.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/payload/CanonicalJson.kt) is the app's implementation and is tested against the RFC's examples.

| Field | Value |
|---|---|
| `v` | `1` |
| `trip`, `asset`, `tag` | trip id, asset id, scanned tag UID (`04:A3:7E:91`) |
| `wp` | waypoint index, 0 = pickup |
| `event` | `pickup`, `checkin` or `delivery` |
| `lat`, `lon`, `acc_m` | GPS to 6 decimals, accuracy in metres |
| `t_device` | phone time, UTC, to the second (`2026-10-16T11:51:37Z`) |
| `photos` | `["sha256:<hex>"]`, pickup only today |
| `salt` | 32 random bytes, base64 |
| `override` | approved override id, only when ops allowed a check-in outside the geofence |

## Delivery escrow contract

The app never calls the contract itself. It reads `ContractInfo` (`LOCKED`, `DELIVERED`, `DISPUTED`, `RESOLVED`, `RELEASED_AFTER_DEADLINE`, `CANCELLED`, plus locked amount, deadline, payout, advance deduction and tx hash) from the snapshot, and calls `claimFinalPay` after the deadline. The receiver's passkey approval happens on the tracking page, not in this app.

## To agree with the backend team

- Transport and auth. The app sends a driver id today; a session token or SEP-10 auth with the driver key is still to be chosen.
- Push instead of polling. The app polls `snapshot` every 4 s while online; a push channel would cut that.
- Which hash `Recorded.txHash` is: the mock returns the fee-bump hash. Either works for the screens; the auditor's lookup needs to know which.
- Advance cap. BR-06 deducts advances from the final leg, but the sample trip's advance limit (40.00) is bigger than its final leg (25.00). The app caps requests at the smaller of the two; the backend should enforce the same rule.
- Photo upload. The app hashes load photos and keeps the files; there is no upload call yet.
- Overrides. The app sends the request with GPS evidence; the anchored override event (BR-07) is the backend's.
- Network and USDC issuer. The app is set to the Stellar testnet and Circle's testnet USDC issuer ([LedgerConfig.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/ledger/LedgerConfig.kt)).
