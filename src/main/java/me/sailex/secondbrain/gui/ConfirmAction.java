package me.sailex.secondbrain.gui;

/** A pending yes/no confirmation shown in the confirm GUI. */
public class ConfirmAction {

    private final String description;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private final long expiresAt;

    public ConfirmAction(String description, Runnable onConfirm, Runnable onCancel) {
        this.description = description;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.expiresAt = System.currentTimeMillis() + 30_000L;
    }

    public String getDescription() { return description; }
    public Runnable getOnConfirm() { return onConfirm; }
    public Runnable getOnCancel() { return onCancel; }
    public boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
}
