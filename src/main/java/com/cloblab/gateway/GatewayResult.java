package com.cloblab.gateway;

/**
 * Wire-level accept/reject. Validation failures are {@link Status#REJECTED}, never thrown.
 */
public record GatewayResult(Status status, String reason) {
    public enum Status {
        ACCEPTED,
        REJECTED
    }

    public static GatewayResult ok() {
        return new GatewayResult(Status.ACCEPTED, null);
    }

    public static GatewayResult rejected(String reason) {
        return new GatewayResult(Status.REJECTED, reason);
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }
}
