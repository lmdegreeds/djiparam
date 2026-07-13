package com.djiparam;

import org.json.JSONObject;

/**
 * Seam for the optional connection-telemetry module. MainActivity talks only to this interface and loads
 * the implementation ({@code com.djiparam.Telemetry}) by reflection, so builds that ship WITHOUT the
 * telemetry source simply run with no reporter (the class is absent → the loader returns null → no-op).
 * This keeps the telemetry code (endpoint, auth, payload) out of the public source tree.
 */
public interface TelemetrySink {
    /** Report one identified connection; the implementation dedupes/queues/sends as it sees fit. */
    void reportConnection(String crcHex, long count, String serial, String acModel,
                          String friendly, JSONObject quickIds, String appVersion);
    /** Retry anything queued from an earlier offline connection. */
    void flush();
}
