package com.aiinterview.server.copilot;

/** 话轮判定结果。 */
public final class UtteranceDecision {

    public enum Action {
        IGNORE,
        UPDATE,
        COMPLETE
    }

    private final Action action;
    private final String reason;
    private final String text;

    private UtteranceDecision(Action action, String reason, String text) {
        this.action = action;
        this.reason = reason;
        this.text = text;
    }

    public static UtteranceDecision ignore(String reason, String text) {
        return new UtteranceDecision(Action.IGNORE, reason, text);
    }

    public static UtteranceDecision update(String reason, String text) {
        return new UtteranceDecision(Action.UPDATE, reason, text);
    }

    public static UtteranceDecision complete(String reason, String text) {
        return new UtteranceDecision(Action.COMPLETE, reason, text);
    }

    public Action action() {
        return action;
    }

    public String reason() {
        return reason;
    }

    public String text() {
        return text;
    }

    public boolean isComplete() {
        return action == Action.COMPLETE;
    }
}
