# Asset Trail · driver app (Android)

Kotlin and Jetpack Compose build of the driver app from [stellar-asset-trail-spec.md](stellar-asset-trail-spec.md) and the design canvas "Stellar Asset Trail — User Journeys" (artboards DriverTrip, DriverCheckin, DriverWallet, DriverCashout, plus the states the spec lists as not drawn yet). `[Product]` is still a placeholder in the spec; the app calls itself "Asset Trail" in [strings.xml](app/src/main/res/values/strings.xml).

The backend and the Asset Trail contract live in [galaxy_asset_management_backend](../galaxy_asset_management_backend). The app reaches them through one interface; [docs/backend-contract.md](docs/backend-contract.md) maps it to the backend's routes.

## Modules

| Module | What's in it |
|---|---|
| `:core` | Plain Kotlin, no Android. Trip model, RFC 8785 canonical JSON and salted SHA-256 payloads, Soroban authorization entries the phone signs (`check_in`, `claim_advance`, `claim_final_pay`) and checks on the platform-built wallet and cash-out transactions, with `network.lightsail:stellar-sdk`, the `PlatformApi` boundary, and `DriverRepository`: the ordered offline queue and business rules |
| `:platform-mock` | Stand-in for the backend until the HTTP client exists: signer service with BR-03 checks and auth-entry verification, a toy ledger and contract, ops and an SEP-6/SEP-38 anchor. Drives the demo build and the end-to-end tests |
| `:app` | Compose UI, Keystore-wrapped wallet key, connectivity, GPS (simulated in the demo), NFC tag reading, load photos |

## Build and run

Needs JDK 17 or newer and the Android SDK with platform 37. Gradle 9.8.1 comes with the wrapper; AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00. Min SDK 28.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:installDebug
```

```bash
./gradlew :core:test :platform-mock:test
```

## Demo walkthrough

The debug build is a demo build: the simulated platform runs inside the app and purple "demo" chips stand in for the other people and for hardware you may not have.

1. Sign up. No SMS is sent; the screen shows the code.
2. Open TR-2041 (Tema Port to Kumasi, 400 kVA generator) and accept it.
3. Pick up at Tema Port: scan the tag (NFC, or "Scan the right tag"), photograph the load.
4. "Drive to Nsawam", then check in: the leg pays 18.00 USDC in the same transaction that records the location.
5. Account › Demo › "Simulate no signal", then check in at the next stop: its authorization is signed and saved on the phone. Turn signal back on and it records, in order.
6. Request a fuel advance, wait for ops, claim it in Wallet. It comes off the final leg.
7. Check in at Kumasi, then in Demo controls have the receiver sign (or report a problem, or skip past the deadline and claim the final leg yourself).
8. Cash out to MTN MoMo at a firm quote; "Fail the next cash out" shows the refund path.

## What's real and what's simulated

Real on the phone: the ed25519 wallet key (AES-GCM wrapped by an Android Keystore key, StrongBox when present), payload canonicalisation and hashing, every Soroban authorization entry the driver signs, and the checks on the transactions the platform asks the driver to sign.

Simulated until the HTTP client is in: the signer service, fee account, ledger, contract, ops decisions, KYC and the anchor. The mock verifies the phone's real authorization entries, signatures included, but nothing is submitted to a Stellar network. It skips time windows and accepts check-ins outside the geofence (the app warns), which the backend refuses.

## Not done yet

- An HTTP `PlatformApi` client for the backend (driver JWT, error mapping, composing the snapshot), and push instead of polling.
- QR tags, cryptographic NFC tags (NTAG 424 DNA), Play Integrity attestation.
- Uploading load photos to the payload store.
- Release signing and R8 rules (release builds aren't minified).
- The other surfaces (portal, tracking page, verify view) and iOS.
