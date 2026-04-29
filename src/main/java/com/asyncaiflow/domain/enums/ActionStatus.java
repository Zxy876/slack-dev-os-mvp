package com.asyncaiflow.domain.enums;

public enum ActionStatus {
    BLOCKED,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    DEAD_LETTER;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == DEAD_LETTER;
    }
}