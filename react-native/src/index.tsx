import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-traccar-client-sdk' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n';

const TraccarClientSdk = NativeModules.TraccarClientSdk
  ? NativeModules.TraccarClientSdk
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

/** Location-accuracy preset. Maps to the SDK's native `Accuracy` enum. */
export type Accuracy = 'HIGHEST' | 'HIGH' | 'MEDIUM' | 'LOW';

/** Tuning parameters for the location pipeline. */
export interface LocationConfig {
  accuracy?: Accuracy;
  distanceMeters?: number;
  intervalSeconds?: number;
  angleDegrees?: number;
  stopDetection?: boolean;
  stopTimeoutSeconds?: number;
  stationaryRadiusMeters?: number;
  heartbeatIntervalSeconds?: number;
}

/** Foreground-service notification settings (Android only). */
export interface NotificationConfig {
  text?: string;
}

/**
 * Batched upload: one gzip JSON array per request, confirmed per position.
 * Off by default. The server must answer with `accepted` and `rejected` ids,
 * and nothing is deleted from the queue until it does.
 */
export interface BatchConfig {
  enabled?: boolean;
  /** Most positions in one request. */
  maxPositions?: number;
  /** Most uncompressed JSON bytes in one request. */
  maxBytes?: number;
  /** How long live positions gather before a send. */
  flushSeconds?: number;
  /** Upper bound of the random delay before sending after a gap in coverage. */
  jitterSeconds?: number;
}

/** Tracker configuration. Pass to `init` or `setConfig`. */
export interface Config {
  serverUrl: string;
  /** Additional HTTP headers, including Authorization. Persisted with the config. */
  headers?: Record<string, string>;
  deviceId: string;
  location?: LocationConfig;
  /** Hold a wakelock while tracking (Android only). */
  wakeLock?: boolean;
  /**
   * When true, persist positions to a local queue and retry on network
   * failure. When false, attempt a direct upload for each position and drop
   * it on failure (real-time only).
   */
  buffer?: boolean;
  /**
   * When true, the Android SDK uses the platform `LocationManager` directly
   * even when Google Play Services is available. Ignored on iOS.
   */
  preferPlatformProviders?: boolean;
  notification?: NotificationConfig;
  batch?: BatchConfig;
}

/** The local upload queue. */
export interface QueueStatus {
  /** Positions waiting to upload. */
  queued: number;
  /** Positions the server refused for good. Kept, never re-sent. */
  rejected: number;
  /** How many of `queued` carry an alarm. */
  queuedAlarms: number;
  /** How many of `rejected` carry an alarm. */
  rejectedAlarms: number;
}

/** A single diagnostic log entry. */
export interface LogEntry {
  /** Epoch milliseconds at which the entry was recorded. */
  time: number;
  message: string;
}

function normalizeConfig(config: Config): Required<Config> {
  const location = config.location ?? {};
  const notification = config.notification ?? {};
  const batch = config.batch ?? {};
  return {
    serverUrl: config.serverUrl,
    headers: config.headers ?? {},
    deviceId: config.deviceId,
    location: {
      accuracy: location.accuracy ?? 'MEDIUM',
      distanceMeters: location.distanceMeters ?? 75,
      intervalSeconds: location.intervalSeconds ?? 300,
      angleDegrees: location.angleDegrees ?? 0,
      stopDetection: location.stopDetection ?? true,
      stopTimeoutSeconds: location.stopTimeoutSeconds ?? 60,
      stationaryRadiusMeters: location.stationaryRadiusMeters ?? 100,
      heartbeatIntervalSeconds: location.heartbeatIntervalSeconds ?? 0,
    },
    wakeLock: config.wakeLock ?? false,
    buffer: config.buffer ?? true,
    preferPlatformProviders: config.preferPlatformProviders ?? false,
    notification: {
      text: notification.text ?? 'Location tracking',
    },
    batch: {
      enabled: batch.enabled ?? false,
      maxPositions: batch.maxPositions ?? 500,
      maxBytes: batch.maxBytes ?? 1_000_000,
      flushSeconds: batch.flushSeconds ?? 10,
      jitterSeconds: batch.jitterSeconds ?? 30,
    },
  };
}

/**
 * Initializes the SDK with `config` if it isn't already initialized.
 * Idempotent — subsequent calls return the existing tracker without touching
 * its config. Call once at app startup to seed defaults; use `setConfig` to
 * change settings on a running tracker.
 */
export function init(config: Config): Promise<void> {
  return TraccarClientSdk.initTracker(normalizeConfig(config));
}

/**
 * Replaces the running tracker's configuration with `config`. Requires that
 * `init` (or a prior session) has installed a tracker; rejects otherwise.
 */
export function setConfig(config: Config): Promise<void> {
  return TraccarClientSdk.setConfig(normalizeConfig(config));
}

/**
 * Starts background location tracking. Requires that the SDK has been
 * initialized. Rejects if required permissions were denied or no config has
 * ever been provided.
 */
export function start(): Promise<void> {
  return TraccarClientSdk.start();
}

/** Stops tracking. */
export function stop(): Promise<void> {
  return TraccarClientSdk.stop();
}

/**
 * Requests a single position fix and uploads it to the server. Resolves with
 * whether the upload succeeded. Works independently of `start` / `stop`.
 * Prompts for permission if needed and rejects when it is denied; a `false`
 * result means a fix or upload failure, not a permission problem. Pass `alarm`
 * (e.g. `"sos"`) to tag the upload. The one-off path does not buffer.
 */
export function requestPosition(alarm?: string): Promise<boolean> {
  return TraccarClientSdk.requestPosition(alarm ?? null);
}

/**
 * Takes one position and stores it in the upload queue, so it survives no
 * signal and app restarts. Resolves with the position's client id once stored.
 * Works independently of `start` / `stop`, but the queue only sends while the
 * app process is running.
 *
 * An `alarm` (e.g. `"sos"`) goes first and skips the flush delay and jitter.
 * Waits up to 10 s for a fix, then uses the last known place, or no
 * coordinates at all: a missing fix or location permission never stops it.
 * Its time is always now; `extras.fixTime` holds the time of the location.
 * `extras` travel with the position, e.g. `{ sosId }`.
 */
export function queuePosition(
  alarm?: string,
  extras?: Record<string, string>
): Promise<string> {
  return TraccarClientSdk.queuePosition(alarm ?? null, extras ?? null);
}

/** Resolves with the current state of the upload queue. */
export function getQueueStatus(): Promise<QueueStatus> {
  return TraccarClientSdk.getQueueStatus();
}

/** Resolves with whether tracking is currently active. */
export function isTracking(): Promise<boolean> {
  return TraccarClientSdk.isTracking();
}

/** Resolves with recent diagnostic entries, oldest first. */
export function getLogs(): Promise<LogEntry[]> {
  return TraccarClientSdk.getLogs();
}

/** Clears all stored diagnostic entries. */
export function clearLogs(): Promise<void> {
  return TraccarClientSdk.clearLogs();
}
