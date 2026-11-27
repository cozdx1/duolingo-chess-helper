package com.juhyeon.chesstip;

final class ChessScreens {
    static final String DUOLINGO = "com.duolingo";
    private static final String OSCAR = "com.duolingo.chess.match.ChessMatchActivity";
    private static final String PLAYER_MATCH = "com.duolingo.chess.pvp.ChessPvpActivity";

    enum Foreground { OSCAR_MATCH, PLAYER_MATCH, OTHER, UNKNOWN }
    record ActivityWindow(String name, int id) {}

    static ActivityWindow activityWindow(CharSequence pkg, CharSequence className, int windowId) {
        if (pkg == null || !DUOLINGO.contentEquals(pkg) || className == null || windowId < 0) return null;
        String name = className.toString();
        return name.startsWith(DUOLINGO + ".") && name.endsWith("Activity") ? new ActivityWindow(name, windowId) : null;
    }

    static Foreground foreground(ActivityWindow activity, CharSequence pkg, int windowId, int parentId) {
        if (pkg == null || windowId < 0) return Foreground.UNKNOWN;
        if (!DUOLINGO.contentEquals(pkg)) return Foreground.OTHER;
        if (activity == null) return Foreground.UNKNOWN;
        if (windowId == activity.id()) {
            if (OSCAR.equals(activity.name())) return Foreground.OSCAR_MATCH;
            if (PLAYER_MATCH.equals(activity.name())) return Foreground.PLAYER_MATCH;
            return Foreground.OTHER;
        }
        if (parentId == activity.id() && PLAYER_MATCH.equals(activity.name())) return Foreground.PLAYER_MATCH;
        return Foreground.UNKNOWN;
    }
}
