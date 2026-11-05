# Functional check: batched upload and SOS queue

Run on one iPhone and one Android before merging any change to the uploader, the queue or `queuePosition`, and before the first release the app depends on. Write down the date, version and phones at the bottom.

**Needs:** a build of WLAB Track with the S3 rider screen, and a server that implements S6 of `docs/API-CONTRACT.md` (array body, `accepted` and `rejected` in the response). Leave `batch.enabled` off until the server does. On a plain 200 nothing is deleted, so the queue only grows.

`uploadUrl` comes from the profile and is `https://track.wlab.co.za`. Confirm in the app log that nothing goes to port 5055.

| # | Do | Expect |
|---|---|---|
| 1 | Start tracking with signal and ride or walk for 2 minutes | Positions reach the server within about 10 seconds of each other. `queued` stays near 0. |
| 2 | Airplane mode on, keep moving for 5 minutes | `queued` climbs. Nothing is lost and the app stays open without errors. |
| 3 | Airplane mode off | Within 30 seconds, after the random delay, one request carries the newest fix first, then the backlog oldest to newest. `queued` returns to 0. |
| 4 | Look at the track on the server | The gap is filled with positions at their real times. The live marker never jumped backwards, and no live alerts fired for the backlog. |
| 5 | Airplane mode on, then trigger SOS | `queuedAlarms` is 1 straight away. Force quit the app and reopen it: `queuedAlarms` is still 1. |
| 6 | Airplane mode off | The SOS leads the next batch with no flush delay or jitter. `queuedAlarms` returns to 0, and the status screen moves to "received". |
| 7 | Trigger SOS in the open with signal, with tracking off | The server stores one SOS at the current time with a fix. `extras.fixTime` holds the fix's own time. |
| 8 | Trigger SOS indoors with no GPS fix | It still sends within about 10 seconds, with the last known position or none. |
| 9 | Make the test server refuse one position with a final reason | That position is counted in `rejected` and not re-sent. The rest of the batch is removed from `queued`. |
| 10 | Queue positions under one team, switch team, then let them send | Each position keeps the device it was queued under. Nothing appears under the other team. |
| 11 | Build up more than 500 queued positions offline, then go back online | They go in several requests of at most 500. A 413 from the test server halves the batch and nothing is lost. |

## Runs

| Date | Version | Phones | Result |
|---|---|---|---|
| | | | |
