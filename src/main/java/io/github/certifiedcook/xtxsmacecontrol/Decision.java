package io.github.certifiedcook.xtxsmacecontrol;

public record Decision(boolean allowed, String reason) {
    public static Decision allow() {
        return new Decision(true, "allowed");
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason);
    }
}
