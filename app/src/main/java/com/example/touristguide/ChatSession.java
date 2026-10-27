package com.example.touristguide;

import java.util.ArrayList;
import java.util.List;

final class ChatSession {
    static final List<ChatAdapter.Msg> MESSAGES = new ArrayList<>();
    static String lastQuestion = "";
    static boolean waiting = false;

    private ChatSession() {
    }
}
