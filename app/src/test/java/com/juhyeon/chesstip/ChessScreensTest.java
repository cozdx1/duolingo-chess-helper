package com.juhyeon.chesstip;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChessScreensTest {
    private static final String DUO = "com.duolingo";
    private static final String OSCAR = "com.duolingo.chess.match.ChessMatchActivity";
    private static final String PLAYERS = "com.duolingo.chess.pvp.ChessPvpActivity";

    @Test public void requiresTheExactOscarActivityInTheFocusedDuolingoWindow() {
        var oscar = ChessScreens.activityWindow(DUO, OSCAR, 7);
        assertEquals(ChessScreens.Foreground.OSCAR_MATCH, ChessScreens.foreground(oscar, DUO, 7, -1));
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(oscar, DUO, 9, -1));
        assertEquals(ChessScreens.Foreground.OTHER, ChessScreens.foreground(oscar, "another.app", 7, -1));
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(oscar, null, -1, -1));
        assertNull(ChessScreens.activityWindow("another.app", OSCAR, 7));
        assertNull(ChessScreens.activityWindow(DUO, OSCAR, -1));
    }

    @Test public void aNewPlayerWindowIsBlockedBeforeAndAfterItsActivityEvent() {
        var oscar = ChessScreens.activityWindow(DUO, OSCAR, 7);
        var players = ChessScreens.activityWindow(DUO, PLAYERS, 8);
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(oscar, DUO, 8, -1));
        assertEquals(ChessScreens.Foreground.PLAYER_MATCH, ChessScreens.foreground(players, DUO, 8, -1));
        assertEquals(ChessScreens.Foreground.PLAYER_MATCH, ChessScreens.foreground(players, DUO, 9, 8));
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(players, DUO, 7, -1));
    }

    @Test public void invitationAnalysisAndUnrecognizedActivitiesCannotBeUsedForAssistance() {
        for (String name : new String[]{"com.duolingo.feature.chess.matchtab.inappinvite.ChessFriendPvpInAppInviteActivity",
                "com.duolingo.chess.gamereview.ChessGameReviewActivity", "com.duolingo.chess.match.OtherChessMatchActivity"}) {
            var activity = ChessScreens.activityWindow(DUO, name, 5);
            assertEquals(ChessScreens.Foreground.OTHER, ChessScreens.foreground(activity, DUO, 5, -1));
        }
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(null, DUO, 5, -1));
    }

    @Test public void dialogsDoNotQualifyAsTheOscarBoardEvenWhenOwnedByIt() {
        var oscar = ChessScreens.activityWindow(DUO, OSCAR, 7);
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(oscar, DUO, 9, 7));
        assertEquals(ChessScreens.Foreground.UNKNOWN, ChessScreens.foreground(oscar, DUO, 9, 8));
        assertEquals(ChessScreens.Foreground.OTHER, ChessScreens.foreground(oscar, "another.app", 9, 7));
    }
}
