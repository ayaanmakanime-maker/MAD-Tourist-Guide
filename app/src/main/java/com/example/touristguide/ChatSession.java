package com.example.touristguide;

import java.util.ArrayList;
import java.util.List;

/** Chat history for the current app session (survives tab switches, not app restarts). */
final class ChatSession {
    static final List<ChatAdapter.Msg> MESSAGES = new ArrayList<>();
    static String lastQuestion = "";
    static boolean waiting = false;
    /** A question prepared by another screen (e.g. "Ask about this place"). */
    static String pendingPrompt;

    private ChatSession() {
    }
}
