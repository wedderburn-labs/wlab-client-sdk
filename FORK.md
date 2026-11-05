# Wedderburn Labs fork

Fork of [traccar/traccar-client-sdk](https://github.com/traccar/traccar-client-sdk), Apache License 2.0. The changes are offered upstream. `main` here follows upstream, and our work sits on top of it.

## What changed from upstream

All of it is additive. With `batch.enabled` left off, and without calling `queuePosition`, the SDK behaves as upstream does.

- **Batched uploader** (`Config.batch`, `HttpBatchUploader`, `BatchEncoder`): one gzip JSON array per request, up to 500 positions or 1 MB uncompressed. The newest fix goes first, then the backlog oldest to newest. Only ids in the response's `accepted` list are deleted. Sends after a gap in coverage are spread by a random delay, and the shared HTTP client reuses its connection.
- **Queue status** (`Tracker.queueStatus`, `getQueueStatus` in React Native): counts of queued and rejected positions.
- **Offline one-off position** (`Tracker.queuePosition`, `queuePosition` in React Native): stored before it is sent, so an SOS survives no signal and app restarts. Alarms lead every batch and skip the flush delay and jitter.
- **Queue schema** (migration `3.sqm`): each queued position keeps a client id, its device, alarm, motion, extras and rejection reason. Keeping the device means a later config change can't relabel a position.

Files changed from upstream: `git diff upstream/main...main --stat`.

Fork-only, not for upstream: this file, `.github/workflows/wlab.yml`, `scripts/package-react-native.sh`, the repository URLs in `react-native/`, and the repository guard in `.github/workflows/release.yml`.

## Wire format

```
POST <serverUrl>/
Content-Type: application/json
Content-Encoding: gzip

[{ "id": "<uuid>", "device_id": "<id>", "location": {
     "timestamp": "2026-11-05T09:30:00.250Z",
     "coords": { "latitude": 0, "longitude": 0, "accuracy": 0, "speed": 0, "heading": 0, "altitude": 0 },
     "is_moving": true,
     "battery": { "level": 0.82, "is_charging": false },
     "event": "sos", "alarm": "sos",
     "extras": { "sosId": "…" } } }]

200 { "accepted": ["<uuid>"], "rejected": [{ "id": "<uuid>", "reason": "…" }] }
```

- `speed` is metres per second. Unknown `accuracy`, `speed` and `heading` are `-1`; unknown `altitude` is `0`.
- `coords`, `battery`, `event`, `alarm` and `extras` are left out when there is nothing to send, never sent as null.
- A rejected position is kept, marked and not re-sent, unless its `reason` starts with `retry`.
- 413 halves the batch. Any other failure keeps everything and backs off, and `Retry-After` is obeyed.

## Using a release

Releases are built by `.github/workflows/wlab.yml` when a `v*-wlab.*` tag is pushed. The job only runs after the tests and a smoke build on both platforms pass.

The React Native package is a tarball on the GitHub release that already contains the Android SDK and the iOS XCFramework, so nothing else is fetched and no credentials are needed:

```json
"react-native-traccar-client-sdk": "https://github.com/wedderburn-labs/traccar-client-sdk/releases/download/v1.1.4-wlab.1/react-native-traccar-client-sdk-1.1.4-wlab.1.tgz"
```

The Maven Central, pub.dev and npm publishing in `release.yml` does not run in this fork.

## Tests

```bash
./gradlew :core:testAndroidHostTest
./gradlew :core:iosSimulatorArm64Test
```
