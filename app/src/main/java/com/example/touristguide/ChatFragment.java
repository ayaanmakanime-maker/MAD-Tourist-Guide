package com.example.touristguide;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.touristguide.databinding.FragmentTabChatBinding;

import java.util.List;

public class ChatFragment extends Fragment implements AiClient.Listener {

    private FragmentTabChatBinding binding;
    private ChatAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTabChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        if (msgs.isEmpty()) {
            msgs.add(new ChatAdapter.Msg(ChatAdapter.BOT_AI, getString(R.string.chat_greet_body)));
        }
        adapter = new ChatAdapter(msgs, this::retry);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        layout.setStackFromEnd(true);
        binding.messages.setLayoutManager(layout);
        binding.messages.setAdapter(adapter);
        refreshAiState();
        binding.send.setOnClickListener(v -> send());
        scroll();
    }

    private void refreshAiState() {
        String key = BuildConfig.GEMINI_API_KEY == null ? "" : BuildConfig.GEMINI_API_KEY.trim();
        binding.serverState.setText(key.isEmpty()
                ? getString(R.string.chat_gemini_missing)
                : getString(R.string.chat_gemini_state, BuildConfig.GEMINI_MODEL));
        binding.serverState.setTextColor(
                androidx.core.content.ContextCompat.getColor(requireContext(),
                        ChatSession.waiting ? R.color.gold : R.color.text_secondary));
    }

    private void send() {
        if (ChatSession.waiting) return;
        String text = binding.input.getText() == null ? "" : binding.input.getText().toString().trim();
        if (text.isEmpty()) {
            binding.input.setError(getString(R.string.chat_input_hint));
            return;
        }
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        ChatSession.lastQuestion = text;
        ChatSession.waiting = true;
        msgs.add(new ChatAdapter.Msg(ChatAdapter.USER, text));
        msgs.add(new ChatAdapter.Msg(ChatAdapter.PENDING, getString(R.string.chat_pending)));
        binding.input.setText("");
        binding.input.setError(null);
        adapter.notifyItemRangeInserted(msgs.size() - 2, 2);
        scroll();
        AiClient.chat(text, this);
    }

    private void retry() {
        if (ChatSession.waiting || ChatSession.lastQuestion.isEmpty()) return;
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        ChatSession.waiting = true;
        msgs.add(new ChatAdapter.Msg(ChatAdapter.PENDING, getString(R.string.chat_pending)));
        adapter.notifyItemInserted(msgs.size() - 1);
        scroll();
        AiClient.chat(ChatSession.lastQuestion, this);
    }

    @Override
    public void onReply(String reply, String mode) {
        settle(new ChatAdapter.Msg(ChatAdapter.BOT_AI, reply));
    }

    @Override
    public void onError(String message) {
        settle(new ChatAdapter.Msg(ChatAdapter.BOT_OFFLINE,
                getString(R.string.chat_fallback_title) + "\n\n"
                        + getString(R.string.chat_fallback_body),
                message));
    }

    private void settle(ChatAdapter.Msg reply) {
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        dropPending();
        msgs.add(reply);
        ChatSession.waiting = false;
        if (isAdded() && binding != null) {
            adapter.notifyDataSetChanged();
            scroll();
            refreshAiState();
        }
    }

    private void dropPending() {
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).kind == ChatAdapter.PENDING) {
                msgs.remove(i);
                return;
            }
        }
    }

    private void scroll() {
        if (adapter.getItemCount() > 0) {
            binding.messages.post(() ->
                    binding.messages.smoothScrollToPosition(adapter.getItemCount() - 1));
        }
    }

}
