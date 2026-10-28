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
import com.google.android.material.chip.Chip;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Travel assistant. Questions go to the backend with a small context (area, loaded places, trip,
 * budget). If the backend is unreachable, an offline suggestion is built from places already in
 * the app and clearly labelled as such.
 */
public class ChatFragment extends Fragment implements AiClient.Listener, ChatAdapter.Callbacks {

    /** The Chat screen currently on display, so replies that arrive after a tab switch still show up. */
    private static ChatFragment active;
    /** Application context for building reply text even if this screen was closed meanwhile. */
    private static android.content.Context appContext;

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
        active = this;
        appContext = requireContext().getApplicationContext();
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        if (msgs.isEmpty()) msgs.add(new ChatAdapter.Msg(ChatAdapter.BOT_AI, getString(R.string.chat_greet_body)));
        adapter = new ChatAdapter(msgs, this);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext());
        layout.setStackFromEnd(true);
        binding.messages.setLayoutManager(layout);
        binding.messages.setAdapter(adapter);
        binding.send.setOnClickListener(v -> send());
        binding.send.setEnabled(!ChatSession.waiting);
        binding.clearChat.setOnClickListener(v -> clearChat());
        buildSuggestions();
        updateContextLine();
        checkServer();

        if (ChatSession.pendingPrompt != null) {
            binding.input.setText(ChatSession.pendingPrompt);
            binding.input.setSelection(binding.input.length());
            binding.input.requestFocus();
            ChatSession.pendingPrompt = null;
        }
        scroll();
    }

    @Override
    public void onDestroyView() {
        if (active == this) active = null;
        binding = null;
        super.onDestroyView();
    }

    private void buildSuggestions() {
        binding.suggestions.removeAllViews();
        List<String> prompts = new ArrayList<>();
        for (String s : getResources().getStringArray(R.array.chat_suggestions)) prompts.add(s);
        Place selected = PlaceRepository.lastViewed();
        if (selected != null && !selected.food) {
            prompts.add(0, getString(R.string.chat_suggest_near_fmt, selected.name));
        }
        for (String prompt : prompts) {
            Chip chip = new Chip(requireContext());
            chip.setText(prompt);
            chip.setOnClickListener(v -> {
                binding.input.setText(prompt);
                send();
            });
            binding.suggestions.addView(chip);
        }
    }

    /** Tells the user exactly what context the assistant will receive. */
    private void updateContextLine() {
        LocationRepository.Selected loc = LocationRepository.selected();
        int places = Math.min(AiClient.MAX_CONTEXT_PLACES, contextPlaces().size());
        int stops = PlanRepository.get().size();
        BudgetCalculator.Inputs budget = BudgetRepository.inputs();
        String area = loc.kind == LocationRepository.Kind.DEFAULT ? getString(R.string.chat_context_no_area) : loc.label;
        binding.contextLine.setText(budget == null
                ? getString(R.string.chat_context_fmt, area, places, stops)
                : getString(R.string.chat_context_budget_fmt, area, places, stops, Money.inr(budget.total)));
    }

    private void checkServer() {
        binding.serverState.setText(R.string.chat_checking);
        BackendClient.health(new BackendClient.Callback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject value) {
                if (binding == null) return;
                boolean ai = value.optBoolean("ai_configured", false);
                binding.serverState.setText(ai
                        ? getString(R.string.chat_state_online_fmt, value.optString("model", "Gemini"))
                        : getString(R.string.chat_state_no_ai));
            }

            @Override
            public void onError(BackendClient.ApiError error) {
                if (binding == null) return;
                binding.serverState.setText(R.string.chat_state_offline);
            }
        });
    }

    private List<Place> contextPlaces() {
        List<Place> places = PlaceRepository.current();
        if (places.isEmpty()) places = new ArrayList<>(Catalog.PLACES);
        return places;
    }

    private void send() {
        if (ChatSession.waiting || binding == null) return;
        String text = binding.input.getText() == null ? "" : binding.input.getText().toString().trim();
        if (text.isEmpty()) {
            binding.input.setError(getString(R.string.chat_input_empty));
            return;
        }
        List<AiClient.Turn> history = history();
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        msgs.add(new ChatAdapter.Msg(ChatAdapter.USER, text));
        binding.input.setText("");
        binding.input.setError(null);
        adapter.notifyItemInserted(msgs.size() - 1);
        ask(text, history);
    }

    private void ask(String text, List<AiClient.Turn> history) {
        ChatSession.lastQuestion = text;
        ChatSession.waiting = true;
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        msgs.add(new ChatAdapter.Msg(ChatAdapter.PENDING, getString(R.string.chat_pending)));
        adapter.notifyItemInserted(msgs.size() - 1);
        binding.send.setEnabled(false);
        scroll();

        JSONObject request = AiClient.buildRequest(text, LocationRepository.selected(), contextPlaces(),
                PlaceRepository.lastViewed(), PlanRepository.get(), BudgetRepository.inputs(), history);
        AiClient.chat(request, this);
    }

    /** Previous user/assistant turns (offline and pending bubbles are not sent). */
    private List<AiClient.Turn> history() {
        List<AiClient.Turn> turns = new ArrayList<>();
        for (ChatAdapter.Msg m : ChatSession.MESSAGES) {
            if (m.kind == ChatAdapter.USER) turns.add(new AiClient.Turn(true, m.text));
            else if (m.kind == ChatAdapter.BOT_AI) turns.add(new AiClient.Turn(false, m.text));
        }
        // The greeting is UI chrome, not part of the conversation.
        if (!turns.isEmpty() && !turns.get(0).user) turns.remove(0);
        return turns;
    }

    @Override
    public void onRetry() {
        if (ChatSession.waiting || ChatSession.lastQuestion.isEmpty() || binding == null) return;
        List<AiClient.Turn> turns = history();
        // The question being retried is already the last user turn.
        if (!turns.isEmpty() && turns.get(turns.size() - 1).user) turns.remove(turns.size() - 1);
        ask(ChatSession.lastQuestion, turns);
    }

    @Override
    public void onReply(AiClient.Reply reply) {
        settle(new ChatAdapter.Msg(ChatAdapter.BOT_AI,
                reply.text.isEmpty() ? appContext.getString(R.string.chat_empty_reply) : reply.text, null, reply.places));
    }

    @Override
    public void onError(BackendClient.ApiError error) {
        android.content.Context c = appContext;
        LocationRepository.Selected loc = LocationRepository.selected();
        OfflineAssistant.Suggestion s = OfflineAssistant.suggest(ChatSession.lastQuestion, contextPlaces(), loc.lat, loc.lng);
        StringBuilder text = new StringBuilder(c.getString(R.string.chat_fallback_title));
        if (s.intent == OfflineAssistant.Intent.BUDGET) {
            text.append("\n\n").append(budgetAnswer(c));
        } else if (s.intent == OfflineAssistant.Intent.MY_PLAN) {
            text.append("\n\n").append(planAnswer(c));
        } else if (s.places.isEmpty()) {
            text.append("\n\n").append(c.getString(s.wantedOpenNow ? R.string.chat_fallback_open_unknown
                    : R.string.chat_fallback_none));
        } else {
            text.append("\n\n").append(c.getString(s.intent == OfflineAssistant.Intent.DAY_PLAN
                    ? R.string.chat_fallback_plan_intro : R.string.chat_fallback_intro, loc.label));
        }
        String reason = c.getString(R.string.chat_fallback_verify) + "\n"
                + c.getString(R.string.chat_offline_reason_fmt, Formats.error(c, error));
        settle(new ChatAdapter.Msg(ChatAdapter.BOT_OFFLINE, text.toString(), reason, s.places));
    }

    /** Offline answer from the user's own budget numbers (nothing is estimated). */
    private static String budgetAnswer(android.content.Context c) {
        BudgetCalculator.Inputs in = BudgetRepository.inputs(); // null until total and days are valid
        if (in == null) return c.getString(R.string.chat_offline_budget_none);
        BudgetCalculator.Summary sum = BudgetCalculator.calculate(in);
        String days = c.getResources().getQuantityString(R.plurals.chat_offline_days, in.days, in.days);
        String text = c.getString(R.string.chat_offline_budget_fmt, Money.inr(in.total), days, Money.inr(sum.planned));
        return text + " " + (sum.overBudget
                ? c.getString(R.string.chat_offline_budget_over_fmt, Money.inr(-sum.remaining))
                : c.getString(R.string.chat_offline_budget_left_fmt, Money.inr(sum.remaining),
                Money.inr(sum.remainingPerDay)));
    }

    /** Offline answer listing the user's plan by day. */
    private static String planAnswer(android.content.Context c) {
        Itinerary plan = PlanRepository.get();
        if (plan.isEmpty()) return c.getString(R.string.chat_offline_plan_empty);
        StringBuilder out = new StringBuilder(c.getString(R.string.chat_offline_plan_intro));
        for (int day = 1; day <= plan.days(); day++) {
            List<String> names = new ArrayList<>();
            for (Itinerary.Stop stop : plan.stopsForDay(day)) names.add(stop.place.name);
            out.append("\n").append(names.isEmpty()
                    ? c.getString(R.string.chat_offline_plan_day_empty_fmt, day)
                    : c.getString(R.string.chat_offline_plan_day_fmt, day, android.text.TextUtils.join(", ", names)));
        }
        return out.toString();
    }

    private static void settle(ChatAdapter.Msg reply) {
        dropPending();
        ChatSession.MESSAGES.add(reply);
        ChatSession.waiting = false;
        ChatFragment screen = active;
        if (screen != null && screen.binding != null) {
            screen.adapter.notifyDataSetChanged();
            screen.binding.send.setEnabled(true);
            screen.scroll();
        }
    }

    private static void dropPending() {
        List<ChatAdapter.Msg> msgs = ChatSession.MESSAGES;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).kind == ChatAdapter.PENDING) {
                msgs.remove(i);
                return;
            }
        }
    }

    private void clearChat() {
        if (ChatSession.waiting) return;
        ChatSession.MESSAGES.clear();
        ChatSession.MESSAGES.add(new ChatAdapter.Msg(ChatAdapter.BOT_AI, getString(R.string.chat_greet_body)));
        ChatSession.lastQuestion = "";
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onOpenPlace(Place place) {
        ((MainActivity) requireActivity()).openDetails(place);
    }

    @Override
    public void onAddPlace(Place place) {
        ((MainActivity) requireActivity()).addToPlan(place, () -> {
            if (adapter != null) adapter.notifyDataSetChanged();
            if (binding != null) updateContextLine();
        });
    }

    private void scroll() {
        if (binding != null && adapter.getItemCount() > 0) {
            binding.messages.post(() -> {
                if (binding != null) binding.messages.smoothScrollToPosition(adapter.getItemCount() - 1);
            });
        }
    }
}
