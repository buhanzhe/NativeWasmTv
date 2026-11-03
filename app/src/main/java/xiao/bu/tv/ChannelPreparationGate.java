package xiao.bu.tv;

/** UI-thread barrier: resolve a URL early, apply it only after the old proxy retires. */
final class ChannelPreparationGate {
    private boolean ready;
    private Runnable pending;

    boolean defer(Runnable action) {
        if (ready) return false;
        pending = action;
        return true;
    }

    void open() {
        if (ready) return;
        ready = true;
        Runnable action = pending;
        pending = null;
        if (action != null) action.run();
    }
}
