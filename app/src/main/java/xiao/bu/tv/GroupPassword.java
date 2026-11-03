package xiao.bu.tv;

/** Playlist suffix and an in-memory grant for one visit; never persisted. */
final class GroupPassword {
    final String title, password;

    GroupPassword(String raw, boolean enabled) {
        String value = raw == null ? "" : raw;
        int separator = enabled ? value.lastIndexOf('_') : -1;
        boolean protectedGroup = separator > 0 && separator < value.length() - 1;
        for (int i = separator + 1; protectedGroup && i < value.length(); i++) {
            char digit = value.charAt(i);
            if (digit < '0' || digit > '9') protectedGroup = false;
        }
        title = protectedGroup ? value.substring(0, separator) : value;
        password = protectedGroup ? value.substring(separator + 1) : "";
    }

    static final class Session {
        private Object scope, owner;

        boolean allows(Object group, Object protectedGroup, String password) {
            return password.isEmpty() || scope == group && owner == protectedGroup;
        }

        void visit(Object group) { if (scope != group) clear(); }

        boolean unlock(Object group, Object protectedGroup, String expected, String input) {
            if (expected.isEmpty() || !expected.equals(input)) return false;
            scope = group;
            owner = protectedGroup;
            return true;
        }

        void clear() { scope = owner = null; }
    }
}
