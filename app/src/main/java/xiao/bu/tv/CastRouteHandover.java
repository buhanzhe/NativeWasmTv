package xiao.bu.tv;

/** A changed address may only replace a still-live session owned by the same controller. */
final class CastRouteHandover {
    static boolean canEnd(String currentSession, String requestedSession) {
        return currentSession.length() > 0 && currentSession.equals(requestedSession);
    }
    static boolean preservePlayback(boolean changingRoute, int incomingCatalog,
            int appliedCatalog, boolean sameSelection) {
        return changingRoute && incomingCatalog >= 0
                && incomingCatalog == appliedCatalog && sameSelection;
    }

    static boolean accepts(boolean hello, String currentHost, String currentSession,
            String previousHost, String previousSession, String nextSession,
            long silentMs, long leaseMs) {
        return hello && currentHost.length() > 0 && currentSession.length() > 0
                && currentHost.equalsIgnoreCase(previousHost)
                && currentSession.equals(previousSession)
                && nextSession.length() > 0 && !currentSession.equals(nextSession)
                && silentMs >= 0 && silentMs < leaseMs;
    }
}
