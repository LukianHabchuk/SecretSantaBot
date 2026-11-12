package com.secretsanta;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

final class BotParticipantPairController {
    private static final Logger LOGGER =
            Logger.getLogger(BotParticipantPairController.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ExchangeService exchangeService;
    private final BotResponder responder;
    private final Map<Long, Map<String, PairCallbackAction>> pendingCallbacks = new HashMap<>();
    private final Map<String, Consumer<CallbackQuery>> menuActions;
    private final Map<String, BiConsumer<CallbackQuery, PairCallbackAction>> callbackActions;

    BotParticipantPairController(ExchangeService exchangeService, BotResponder responder) {
        this.exchangeService = exchangeService;
        this.responder = responder;
        menuActions = Map.of(
                BotButtons.PAIR_REQUEST_CHOOSE, this::startPairRequest,
                BotButtons.PAIR_REQUEST_STATUS, this::showPairStatus);
        callbackActions = Map.of(
                "target", this::submitPairRequest,
                "accept", this::acceptPairRequest,
                "decline", this::declinePairRequest,
                "cancel", this::cancelPairRequest);
    }

    void handleCallbackQuery(CallbackQuery callback) {
        String data = callback.getData();
        Consumer<CallbackQuery> menuAction = menuActions.get(data);
        if (menuAction != null) {
            menuAction.accept(callback);
            return;
        }

        String[] parts = data.split(":", -1);
        boolean targetAction = parts.length == 4 && parts[1].equals("target");
        if ((!targetAction && parts.length != 3) || parts.length < 3) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }

        long userId = callback.getFrom().getId();
        PairCallbackAction action = pendingCallbacks
                .getOrDefault(userId, Map.of())
                .get(parts[2]);
        BiConsumer<CallbackQuery, PairCallbackAction> handler =
                callbackActions.get(parts[1]);
        if (action == null
                || handler == null
                || !action.type().equals(parts[1])) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }
        if (!action.observedState().equals(exchangeService.state())) {
            invalidateCallbacksFor(action, userId);
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }
        handler.accept(callback, action);
    }

    private void startPairRequest(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        ExchangeState state = exchangeService.state();
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_CLOSED);
            responder.sendText(userId, BotMessages.PAIR_REQUEST_CLOSED);
            return;
        }
        ExchangeState.Participant requester = findParticipant(state, userId);
        if (requester == null) {
            responder.answerCallback(callback.getId(), BotMessages.ACTION_UNAVAILABLE);
            responder.sendText(userId, BotMessages.PAIR_REQUEST_NOT_REGISTERED);
            return;
        }
        if (requester.partnerId() != null) {
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_ALREADY_PAIRED);
            responder.sendText(userId, BotMessages.PAIR_REQUEST_ALREADY_PAIRED);
            return;
        }
        if (hasOpenPairRequest(state, userId)) {
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_RECEIVED_CALLBACK);
            showPairStatus(userId);
            return;
        }

        Set<Long> participantsWithRequests = state.pairRequests().stream()
                .flatMap(request -> Stream.of(
                        request.requesterId(), request.requestedPartnerId()))
                .collect(Collectors.toSet());
        List<SecretSantaBot.Participant> candidates = exchangeService.participants().stream()
                .filter(participant -> participant.chatId() != userId
                        && participant.partnerId() == null
                        && !participantsWithRequests.contains(participant.chatId()))
                .toList();
        if (candidates.isEmpty()) {
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_RECEIVED_CALLBACK);
            responder.sendText(userId, BotMessages.PAIR_REQUEST_NO_CANDIDATES);
            return;
        }

        invalidateCallbacks(userId);
        String token = newToken();
        pendingCallbacks.computeIfAbsent(userId, ignored -> new HashMap<>())
                .put(token, new PairCallbackAction(
                        "target",
                        state,
                        null,
                        candidates.stream().map(SecretSantaBot.Participant::chatId)
                                .collect(Collectors.toSet())));
        responder.sendWithKeyboard(
                userId,
                BotMessages.PAIR_REQUEST_CHOOSE_PROMPT,
                BotButtons.pairing(
                        candidates,
                        BotButtons.PAIR_REQUEST_TARGET_PREFIX + token + ":",
                        userId));
        responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_RECEIVED_CALLBACK);
    }

    private void submitPairRequest(CallbackQuery callback, PairCallbackAction action) {
        long userId = callback.getFrom().getId();
        long targetId;
        try {
            targetId = Long.parseLong(callback.getData().split(":", -1)[3]);
        } catch (NumberFormatException exception) {
            responder.answerCallback(callback.getId(), BotMessages.INVALID_PARTICIPANT_SELECTION);
            return;
        }
        if (!action.eligibleParticipantIds().contains(targetId)) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }
        try {
            exchangeService.requestPair(userId, targetId);
            invalidateCallbacks(userId, targetId);
            issuePairRequestActions(new ExchangeState.PairRequest(userId, targetId));
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_SENT_CALLBACK);
        } catch (IllegalArgumentException exception) {
            invalidateCallbacks(userId, targetId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_STALE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PAIR_REQUEST_PERSIST_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_FAILED);
            responder.sendText(userId, BotMessages.PAIR_REQUEST_FAILED);
        }
    }

    private void showPairStatus(CallbackQuery callback) {
        showPairStatus(callback.getFrom().getId());
        responder.answerCallback(callback.getId(), BotMessages.PAIR_STATUS_CALLBACK);
    }

    private void showPairStatus(long userId) {
        ExchangeState state = exchangeService.state();
        ExchangeState.Participant participant = findParticipant(state, userId);
        if (participant == null) {
            responder.sendText(userId, BotMessages.PAIR_REQUEST_NOT_REGISTERED);
            return;
        }
        if (participant.partnerId() != null) {
            ExchangeState.Participant partner = findParticipant(state, participant.partnerId());
            responder.sendText(
                    userId,
                    BotMessages.pairStatusPaired(BotMessages.escapeMarkdown(partner.name())));
            return;
        }
        ExchangeState.PairRequest request = state.pairRequests().stream()
                .filter(pending -> pending.requesterId() == userId
                        || pending.requestedPartnerId() == userId)
                .findFirst()
                .orElse(null);
        if (request == null) {
            responder.sendWithKeyboard(
                    userId, BotMessages.PAIR_REQUEST_NONE, BotButtons.participantPairMenu());
            return;
        }
        issuePairRequestActions(request);
    }

    private void issuePairRequestActions(ExchangeState.PairRequest request) {
        ExchangeState state = exchangeService.state();
        ExchangeState.Participant requester = findParticipant(state, request.requesterId());
        ExchangeState.Participant recipient = findParticipant(state, request.requestedPartnerId());
        invalidateCallbacks(request.requesterId(), request.requestedPartnerId());

        String cancelToken = registerCallback(
                request.requesterId(), "cancel", state, request, Set.of());
        String acceptToken = registerCallback(
                request.requestedPartnerId(), "accept", state, request, Set.of());
        String declineToken = registerCallback(
                request.requestedPartnerId(), "decline", state, request, Set.of());

        responder.sendWithKeyboard(
                request.requesterId(),
                BotMessages.pairRequestStatus(
                        BotMessages.escapeMarkdown(recipient.name()), true),
                BotButtons.pairRequestCancellation(cancelToken));
        responder.sendWithKeyboard(
                request.requestedPartnerId(),
                BotMessages.pairRequestReceived(BotMessages.escapeMarkdown(requester.name())),
                BotButtons.pairRequestResponse(acceptToken, declineToken));
    }

    private void acceptPairRequest(CallbackQuery callback, PairCallbackAction action) {
        long recipientId = callback.getFrom().getId();
        try {
            exchangeService.acceptPairRequest(recipientId, action.request().requesterId());
            invalidateCallbacks(action.request().requesterId(), recipientId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_ACCEPTED_CALLBACK);
            responder.sendText(
                    recipientId,
                    BotMessages.pairAccepted(BotMessages.escapeMarkdown(
                            exchangeService.participant(action.request().requesterId())
                                    .orElseThrow().name())));
            responder.sendText(
                    action.request().requesterId(),
                    BotMessages.pairAccepted(BotMessages.escapeMarkdown(
                            exchangeService.participant(recipientId).orElseThrow().name())));
        } catch (IllegalArgumentException exception) {
            invalidateCallbacks(action.request().requesterId(), recipientId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_STALE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PAIR_REQUEST_PERSIST_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_FAILED);
            responder.sendText(recipientId, BotMessages.PAIR_REQUEST_FAILED);
        }
    }

    private void declinePairRequest(CallbackQuery callback, PairCallbackAction action) {
        long recipientId = callback.getFrom().getId();
        try {
            exchangeService.declinePairRequest(recipientId, action.request().requesterId());
            invalidateCallbacks(action.request().requesterId(), recipientId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_DECLINED_CALLBACK);
            responder.sendText(recipientId, BotMessages.PAIR_REQUEST_DECLINED);
            responder.sendText(
                    action.request().requesterId(), BotMessages.PAIR_REQUEST_DECLINED);
        } catch (IllegalArgumentException exception) {
            invalidateCallbacks(action.request().requesterId(), recipientId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_STALE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PAIR_REQUEST_PERSIST_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_FAILED);
            responder.sendText(recipientId, BotMessages.PAIR_REQUEST_FAILED);
        }
    }

    private void cancelPairRequest(CallbackQuery callback, PairCallbackAction action) {
        long requesterId = callback.getFrom().getId();
        long recipientId = action.request().requestedPartnerId();
        try {
            exchangeService.cancelPairRequest(requesterId, recipientId);
            invalidateCallbacks(requesterId, recipientId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_CANCELED_CALLBACK);
            responder.sendText(requesterId, BotMessages.PAIR_REQUEST_CANCELED);
            responder.sendText(recipientId, BotMessages.PAIR_REQUEST_CANCELED);
        } catch (IllegalArgumentException exception) {
            invalidateCallbacks(requesterId, recipientId);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_STALE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PAIR_REQUEST_PERSIST_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_REQUEST_FAILED);
            responder.sendText(requesterId, BotMessages.PAIR_REQUEST_FAILED);
        }
    }

    private String registerCallback(
            long actorId,
            String type,
            ExchangeState state,
            ExchangeState.PairRequest request,
            Set<Long> eligibleParticipantIds) {
        String token = newToken();
        pendingCallbacks.computeIfAbsent(actorId, ignored -> new HashMap<>())
                .put(token, new PairCallbackAction(type, state, request, eligibleParticipantIds));
        return token;
    }

    private void invalidateCallbacksFor(PairCallbackAction action, long userId) {
        if (action.request() == null) {
            invalidateCallbacks(userId);
        } else {
            invalidateCallbacks(
                    action.request().requesterId(), action.request().requestedPartnerId());
        }
    }

    private void invalidateCallbacks(long... userIds) {
        for (long userId : userIds) {
            pendingCallbacks.remove(userId);
        }
    }

    private static boolean hasOpenPairRequest(ExchangeState state, long userId) {
        return state.pairRequests().stream().anyMatch(request ->
                request.requesterId() == userId || request.requestedPartnerId() == userId);
    }

    private static ExchangeState.Participant findParticipant(ExchangeState state, long userId) {
        return state.participants().stream()
                .filter(participant -> participant.userId() == userId)
                .findFirst()
                .orElse(null);
    }

    private static String newToken() {
        byte[] tokenBytes = new byte[6];
        RANDOM.nextBytes(tokenBytes);
        return TOKEN_ENCODER.encodeToString(tokenBytes);
    }

    private record PairCallbackAction(
            String type,
            ExchangeState observedState,
            ExchangeState.PairRequest request,
            Set<Long> eligibleParticipantIds) {
        private PairCallbackAction {
            eligibleParticipantIds = Set.copyOf(eligibleParticipantIds);
        }
    }
}
