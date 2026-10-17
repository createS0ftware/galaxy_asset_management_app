# How the driver app talks to the backend

The backend is [galaxy_asset_management_backend](../../galaxy_asset_management_backend): a Soroban contract (`asset-trail`) plus a Fastify API. The app reaches it through one Kotlin interface, `PlatformApi` ([core/src/main/kotlin/com/galaxy/assettrail/core/platform/PlatformApi.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/platform/PlatformApi.kt)), with two implementations picked in [AppContainer.kt](../app/src/main/kotlin/com/galaxy/assettrail/driver/AppContainer.kt):

- `live` builds: `HttpPlatformApi` in `:platform-http`, against `BuildConfig.API_BASE_URL` (`http://10.0.2.2:3000`, the development machine from the emulator). The driver JWT from the OTP sign-in is kept encrypted with a Keystore key.
- `debug` (demo) builds: `:platform-mock`, in-process, behaving like the backend for the phone.

## The model

The contract is the escrow (backend PLAN.md D2/D5). The **fee account is the source of every contract transaction**, so the driver's account has no sequence number in play. The phone signs **Soroban authorization entries** for exactly one contract call each:

| Call | Arguments (ScVal types) | Signed by |
|---|---|---|
| `check_in` | `trip: String, wp: u32, payload_hash: Bytes(32)` | driver, then the platform co-signs |
| `claim_advance` | `trip: String` | driver |
| `claim_final_pay` | `trip: String, claim_hash: Bytes(32)` | driver only |

[TrailCalls.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/ledger/TrailCalls.kt) builds the invocations and [AuthEntries.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/ledger/AuthEntries.kt) signs them (`ADDRESS_V2` credentials, as the backend's JS SDK). Each entry has a random nonce, so check-ins signed offline never block each other. Its last valid ledger replaces the spec's `maxTime`: latest ledger seen + elapsed time + `maxOfflineHours` (backend `offlineValidUntil`).

The two classic transactions the driver signs are **built by the backend** and checked on the phone before signing ([DriverTransactions.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/ledger/DriverTransactions.kt)):

- T2 wallet set-up: fee account sponsors and creates the account; the driver's account adds the USDC trustline and ends the sponsorship. Anything else is refused.
- T9 cash-out: one payment of the quoted USDC from the driver to the anchor's `payTo`, with its text `memo`.

## Calls

All routes are under `/v1`. Amounts are USDC with 7 decimals (decimal strings on the wire).

| `PlatformApi` | Backend | Notes |
|---|---|---|
| `sendPhoneCode`, `verifyPhone` | `POST /auth/driver/otp`, `POST /auth/driver/verify` | Returns a 30-day driver JWT. Backend only signs in drivers that ops registered |
| `submitKyc` | none | Sample data |
| `network` | `GET /network` | Passphrase, contract id, pay asset |
| `prepareWallet`, `submitWallet` | `POST /driver/wallet/prepare {publicKey}`, `POST /driver/wallet/submit {xdr}` | Sign and submit within 60 s |
| `snapshot` | `GET /driver/trips`, `/driver/wallet`, `/network` (and `/driver/me` once) | No single snapshot route; the client composes it. Offers are `funded` trips; the final leg's state comes from the trip status and its closing custody event |
| `acceptOffer` | `POST /driver/trips/:id/accept` | No decline route (not implemented) |
| `submitCheckIns` | `POST /driver/trips/:id/checkins/batch {items: [{payload, authEntry}]}` | In order, up to 64, stops at the first failure; re-sends are idempotent |
| `reportTagMismatch` | none | Sample data; the backend alerts ops when it refuses with `TAG_MISMATCH` |
| `requestAdvance` | `POST /driver/trips/:id/advances {amount}` | |
| `claimAdvance` | `POST /driver/trips/:id/advances/claim {authEntry}` | |
| `claimFinalPay` | `POST /driver/trips/:id/final-pay {payload, authEntry}` | After the delivery deadline, with no signature or dispute |
| `quote` | `POST /driver/cashouts/quote {amount}` | Returns the cash-out id, `payTo` and `memo` |
| `prepareCashOut`, `submitCashOut` | `POST /driver/cashouts/:id/prepare`, `POST /driver/cashouts/:id/submit {xdr}` | Fee-bumped by the fee account |

## Payloads

Hashed in RFC 8785 canonical form ([CanonicalJson.kt](../core/src/main/kotlin/com/galaxy/assettrail/core/payload/CanonicalJson.kt)), SHA-256, with a 32-byte base64 `salt`.

Waypoint (`check_in`): `v`, `trip`, `wp`, `event` (`pickup` at 0, `arrival` at the last stop, `checkin` in between), `asset`, `tag` (exact match with the asset's registered tag id, e.g. `04:A3:7E:91`), `lat`, `lon`, `acc_m`, `t_device` (UTC to the second), `photos` (`sha256:<64 lowercase hex>`), `salt`.

Final-pay claim (`claim_final_pay`): `v`, `event: "release"`, `trip`, `asset`, `t_device`, `salt`.

## Refusal codes

Check-ins (`RejectCode`): `BAD_PAYLOAD`, `TRIP_MISMATCH`, `ASSET_MISMATCH`, `WRONG_WAYPOINT`, `EVENT_MISMATCH`, `TAG_MISMATCH`, `LOW_ACCURACY`, `OUTSIDE_GEOFENCE`, `FUTURE_TIME`, `STALE_CHECKIN`, `OUTSIDE_WINDOW`, `BAD_AUTH_ENTRY`, `TRIP_NOT_MOVING`. Unknown codes map to `UNKNOWN` and show the backend's message.

Other calls raise `PlatformException(code, message)` with the backend's code, for example `QUOTE_EXPIRED`, `CHECKINS_PENDING`, `NO_MOMO`, `ADVANCE_EXPIRED`, `DEADLINE_NOT_PASSED`, `WALLET_READY`, `TX_MISMATCH`.

## Where the demo differs from the backend

- Time windows aren't checked and a check-in outside the geofence is accepted, so the demo runs anywhere in minutes. The trip screen warns when the driver is outside the geofence; the backend refuses with `OUTSIDE_WINDOW` / `OUTSIDE_GEOFENCE`.
- Sign-up, KYC and the MoMo number are sample data. On the backend, ops registers the driver and their MoMo number; `submitKyc` is answered by the client itself.
- The mock shares the asset's tag id, so the phone blocks a wrong scan before signing. The backend keeps tag ids to itself, so on live builds the signer service refuses a wrong tag (`TAG_MISMATCH`) when the check-in arrives.
- Trips can't be declined.

## Still to do

- Push instead of polling: each poll is three requests, and `/driver/wallet` and `/network` each ask Stellar RPC.
- Live position pings (`POST /driver/trips/:id/location`) and photo upload to the payload store.
