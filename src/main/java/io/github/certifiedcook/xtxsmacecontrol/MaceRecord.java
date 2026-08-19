package io.github.certifiedcook.xtxsmacecontrol;

import java.util.UUID;

public record MaceRecord(
        String serial,
        UUID owner,
        UUID issuedTo,
        long issuedAt,
        String issuedToName,
        String source,
        String status,
        int transfers,
        long lastTransferAt,
        long lastSeenAt
) {
    public MaceRecord withOwner(UUID newOwner, int newTransfers, long now) {
        return new MaceRecord(serial, newOwner, issuedTo, issuedAt, issuedToName, source, status, newTransfers, now, now);
    }

    public MaceRecord withStatus(String newStatus, long now) {
        return new MaceRecord(serial, owner, issuedTo, issuedAt, issuedToName, source, newStatus, transfers, lastTransferAt, now);
    }

    public MaceRecord seen(long now) {
        return new MaceRecord(serial, owner, issuedTo, issuedAt, issuedToName, source, status, transfers, lastTransferAt, now);
    }
}
