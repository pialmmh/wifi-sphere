package com.telcobright.routesphere.wifimachine.v2.event;

import com.telcobright.statewalk.v2.registry.consumes.StatemachineEvent;

/**
 * Wire-side events for the WiFi session machines. Bridges (Redis stream,
 * Kafka, portal HTTP) translate transport frames into these; the machines
 * never see a transport. Business-side naming throughout.
 */
public final class WifiEvents {

    private WifiEvents() {}

    /** A MAC appeared at the gateway (first frame). Opens a session. */
    public record DeviceSeen(String mac, String vlan) implements StatemachineEvent {
        @Override public boolean isFirst() { return true; }
    }

    /** A gardened device hit the captive lane; probeLabel = browser family. */
    public record CaptiveProbe(String mac, String ip, String probeLabel) implements StatemachineEvent {}

    /** The sign-in page was actually opened (a human is engaging). */
    public record PortalOpened(String mac) implements StatemachineEvent {}

    /** An OTP was sent to this phone number for this device. */
    public record OtpSent(String mac, String msisdn) implements StatemachineEvent {}

    /** The OTP was verified — the subscriber identity is proven. */
    public record OtpVerified(String mac, String msisdn) implements StatemachineEvent {}

    /** The device went to the payment gateway for this order. */
    public record PaymentInitiated(String mac, String purchaseId) implements StatemachineEvent {}

    /** The payment settled (money in); the grant follows via admission. */
    public record PaymentSettled(String mac, String purchaseId) implements StatemachineEvent {}

    /**
     * Admission approved internet for this device — the rich grant
     * (minutes and optional byte budget; 0 = unbounded by that dimension).
     */
    public record GrantApproved(String mac, String msisdn, int minutes, long volumeBytes,
                                String purchaseId) implements StatemachineEvent {}

    /** SHADOW path: an external authority flipped this MAC garden→release. */
    public record GrantObserved(String mac) implements StatemachineEvent {}

    /** An external authority flipped this MAC release→garden. */
    public record GrantRevoked(String mac) implements StatemachineEvent {}

    /** The MAC left the gateway's learned table. */
    public record DeviceGone(String mac) implements StatemachineEvent {}

    /** Ledger delta for one MAC from one usage batch (money — replayed fully). */
    public record CountersDelta(String mac, long bytesUp, long bytesDn,
                                long producedAtMs) implements StatemachineEvent {}

    /** Present-tense activity tick from the liveness hash (never from backlog). */
    public record LivenessTick(String mac, long lastActiveMs, long snapshotMs) implements StatemachineEvent {}

    /** RADIUS CoA / Disconnect arrived for this MAC. */
    public record CoaDisconnect(String mac) implements StatemachineEvent {}

    /** Officer or subscriber ended this session on purpose. */
    public record AdminKick(String mac, String reason) implements StatemachineEvent {}
}
