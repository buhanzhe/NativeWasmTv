package xiao.bu.tv;

/** Chooses a receiver decode profile while preserving the requested frame rate first. */
final class CastReceiverProfileSelector {
    private CastReceiverProfileSelector() { }

    static int[] select(int requestedWidth, int requestedHeight, int requestedFps,
            int[][] profiles) {
        if (profiles == null || profiles.length == 0) return null;
        int[] selected = null;
        for (int[] profile : profiles) {
            if (!valid(profile) || profile[2] < requestedFps) continue;
            if (selected == null || area(profile) > area(selected)
                    || area(profile) == area(selected) && profile[2] < selected[2]) {
                selected = profile;
            }
        }
        if (selected == null) {
            for (int[] profile : profiles) {
                if (!valid(profile)) continue;
                if (selected == null || profile[2] > selected[2]
                        || profile[2] == selected[2] && area(profile) > area(selected)) {
                    selected = profile;
                }
            }
        }
        if (selected == null) return null;
        return new int[] {
                Math.min(requestedWidth, selected[0]) & ~1,
                Math.min(requestedHeight, selected[1]) & ~1,
                Math.min(requestedFps, selected[2])
        };
    }

    private static boolean valid(int[] profile) {
        return profile != null && profile.length >= 3
                && profile[0] > 0 && profile[1] > 0 && profile[2] > 0;
    }

    private static long area(int[] profile) {
        return (long) profile[0] * profile[1];
    }
}
