package com.centers25.naturalregen;

/** A long-running job (regen or undo). Only one runs at a time. */
public interface Task {
    String status();

    void cancel(String reason);

    boolean isFinished();
}
