package xiao.bu.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** UI-thread cache for only the channel currently displayed in the EPG column. */
final class EpgDisplayCache {
    private List<EpgManager.Program> source;
    private List<EpgManager.Program> displayed = Collections.emptyList();
    private long displayedDay;

    List<EpgManager.Program> programsFor(List<EpgManager.Program> programs) {
        // EpgManager publishes complete snapshots, never mutating a published list.
        // Identity is an O(1) cache check; a refreshed guide has new list identities.
        long day = EpgManager.dayStart(System.currentTimeMillis());
        java.util.Calendar end = java.util.Calendar.getInstance();
        end.setTimeInMillis(day);
        end.add(java.util.Calendar.DATE, 1);
        long nextDay = end.getTimeInMillis();
        if (source == programs && displayedDay == day) {
            return displayed;
        }
        List<EpgManager.Program> result = programs;
        if (programs == null || programs.isEmpty()) {
            result = Collections.emptyList();
        } else {
            Set<Key> seen = new HashSet<Key>();
            ArrayList<EpgManager.Program> unique = new ArrayList<EpgManager.Program>();
            for (EpgManager.Program program : programs) {
                if (program.stopMillis > day && program.startMillis < nextDay
                        && seen.add(new Key(program))) {
                    unique.add(program);
                }
            }
            // Reuse the original snapshot if nothing was removed. Do not sort or
            // modify it: other consumers still need the original guide data.
            if (unique.size() != programs.size()) {
                result = Collections.unmodifiableList(unique);
            }
        }
        source = programs;
        displayedDay = day;
        displayed = result;
        return displayed;
    }

    private static final class Key {
        private final EpgManager.Program program;

        Key(EpgManager.Program program) {
            this.program = program;
        }

        @Override
        public int hashCode() {
            int hash = (int) (program.startMillis ^ (program.startMillis >>> 32));
            hash = 31 * hash + (int) (program.stopMillis ^ (program.stopMillis >>> 32));
            return 31 * hash + (program.title == null ? 0 : program.title.hashCode());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            EpgManager.Program right = ((Key) other).program;
            return program.startMillis == right.startMillis
                    && program.stopMillis == right.stopMillis
                    && (program.title == null ? right.title == null
                    : program.title.equals(right.title));
        }
    }
}
