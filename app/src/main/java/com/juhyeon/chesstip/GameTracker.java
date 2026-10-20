package com.juhyeon.chesstip;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;

/** Updates turn/castling/en-passant state only when a unique legal path matches the observed placement. */
final class GameTracker {
    private Board board;
    private boolean whiteBottom;
    private String pending;
    private int stableCount;

    GameTracker() {}

    GameTracker(String savedFen, boolean savedWhiteBottom) {
        if (savedFen != null) {
            board = new Board();
            board.loadFromFen(savedFen);
            whiteBottom = savedWhiteBottom;
        }
    }

    boolean accept(BoardVision.Observation observation) {
        if (observation == null) { pending = null; stableCount = 0; return false; }
        String raw = new String(observation.cells());
        stableCount = raw.equals(pending) ? stableCount + 1 : 1;
        pending = raw;
        if (stableCount < 2) return false;
        if (board != null) {
            String observed = observation.placement(whiteBottom);
            if (placement(board).equals(observed)) return true;
            String matched = findSuccessor(board, observed);
            if (matched != null) { board.loadFromFen(matched); return true; }
        }
        // A new game may begin with either the initial board or Oscar's first white move.
        for (boolean direction : new boolean[]{true, false}) {
            String observed = observation.placement(direction);
            Board start = new Board();
            if (placement(start).equals(observed)) { board = start; whiteBottom = direction; return true; }
            for (Move move : start.legalMoves()) {
                start.doMove(move);
                if (placement(start).equals(observed)) { board = start; whiteBottom = direction; return true; }
                start.undoMove();
            }
        }
        return false;
    }

    private static String findSuccessor(Board current, String observed) {
        String match = null;
        for (Move first : current.legalMoves()) {
            current.doMove(first);
            if (placement(current).equals(observed)) {
                String fen = current.getFen();
                current.undoMove();
                return fen;
            }
            for (Move second : current.legalMoves()) {
                current.doMove(second);
                if (placement(current).equals(observed)) {
                    String fen = current.getFen();
                    if (match != null && !match.equals(fen)) {
                        current.undoMove(); current.undoMove(); return null;
                    }
                    match = fen;
                }
                current.undoMove();
            }
            current.undoMove();
        }
        return match;
    }

    String fen() { return board == null ? null : board.getFen(); }
    boolean terminal() { return board != null && board.legalMoves().isEmpty(); }
    boolean whiteBottom() { return whiteBottom; }
    boolean myTurn() { return board != null && board.getSideToMove() == (whiteBottom ? Side.WHITE : Side.BLACK); }
    String sideName() { return whiteBottom ? "백" : "흑"; }
    private static String placement(Board position) { return position.getFen().split(" ")[0]; }
}
