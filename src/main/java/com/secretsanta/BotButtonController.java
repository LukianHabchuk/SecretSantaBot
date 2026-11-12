package com.secretsanta;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

final class BotButtonController {
    private static final Logger LOGGER = Logger.getLogger(BotButtonController.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final OrganizerService organizerService;
    private final ExchangeService exchangeService;
    private final PendingDrawService pendingDrawService;
    private final BotResponder responder;
    private final Map<Long, PendingInput> pendingInputs = new HashMap<>();
    private final Map<Long, PendingJoin> pendingJoins = new HashMap<>();
    private final Map<Long, String> pendingResetTokens = new HashMap<>();
    private final Map<String, Consumer<CallbackQuery>> onboardingActions;
    private final EnumMap<PendingInput, BiConsumer<InputContext, String>> inputHandlers =
            new EnumMap<>(PendingInput.class);
    private Long pendingPairParticipantId;
    private String activePairingSessionToken;

    BotButtonController(
            OrganizerService organizerService,
            ExchangeService exchangeService,
            PendingDrawService pendingDrawService,
            BotResponder responder) {
        this.organizerService = organizerService;
        this.exchangeService = exchangeService;
        this.pendingDrawService = pendingDrawService;
        this.responder = responder;
        onboardingActions = Map.of(
                BotButtons.CLAIM, this::requestOrganizerClaim,
                BotButtons.JOIN, this::requestJoin,
                BotButtons.OPEN, this::openRegistrationFromButton,
                BotButtons.RESET, this::requestResetConfirmation);
        inputHandlers.put(PendingInput.SETUP_CODE, this::claimOrganizer);
        inputHandlers.put(PendingInput.INVITE_CODE, this::promptJoinName);
        inputHandlers.put(PendingInput.JOIN_NAME, this::updateJoinName);
    }

    boolean isAwaitingInput(long userId) {
        return pendingInputs.containsKey(userId);
    }

    synchronized void showStartMenu(long chatId, long userId, boolean privateChat) {
        if (!privateChat) {
            responder.sendText(chatId, BotMessages.PRIVATE_START_ONLY);
            return;
        }
        pendingInputs.remove(userId);
        pendingJoins.remove(userId);
        pendingResetTokens.remove(userId);
        try {
            boolean organizer = organizerService.isOrganizer(userId);
            responder.sendWithKeyboard(
                    chatId,
                    organizer ? BotMessages.ORGANIZER_START : BotMessages.PARTICIPANT_START,
                    organizer
                            ? BotButtons.organizerMenu(isRegistrationOpen())
                            : BotButtons.participantStartMenu());
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.ONBOARDING_STATE_READ_FAILED, exception);
            responder.sendText(chatId, BotMessages.MENU_LOAD_FAILED);
        }
    }

    void handlePendingInput(long userId, String firstName, String input) {
        PendingInput pendingInput = pendingInputs.remove(userId);
        if (pendingInput == null) {
            return;
        }
        inputHandlers.get(pendingInput).accept(new InputContext(userId, firstName), input);
    }

    synchronized String openRegistration(long organizerId, String organizerName)
            throws IOException {
        String inviteCode = pendingDrawService.openRegistration(organizerId, organizerName);
        cancelPairing();
        return inviteCode;
    }

    synchronized void cancelPairing() {
        pendingPairParticipantId = null;
        activePairingSessionToken = null;
    }

    synchronized void startPairing(long organizerId) {
        List<SecretSantaBot.Participant> roster = exchangeService.participants();
        if (roster.size() < 2) {
            cancelPairing();
            responder.sendText(organizerId, BotMessages.TOO_FEW_FOR_PAIRING);
            return;
        }
        pendingPairParticipantId = null;
        activePairingSessionToken = newToken();
        responder.sendWithKeyboard(
                organizerId,
                BotMessages.FIRST_PARTICIPANT_PROMPT,
                BotButtons.pairing(
                        roster,
                        BotButtons.PAIR_PREFIX + "first:" + activePairingSessionToken + ":",
                        null));
    }

    synchronized void handleCallbackQuery(CallbackQuery callback) {
        if (callback == null
                || callback.getId() == null
                || callback.getFrom() == null
                || callback.getFrom().getId() == null
                || callback.getMessage() == null) {
            return;
        }

        long userId = callback.getFrom().getId();
        if (!Objects.equals(callback.getMessage().getChatId(), userId)) {
            responder.answerCallback(callback.getId(), BotMessages.CALLBACK_PRIVATE_ONLY);
            return;
        }

        String data = callback.getData();
        if (data != null && data.startsWith(BotButtons.ONBOARDING_PREFIX)) {
            handleOnboardingCallback(callback);
            return;
        }
        if (!isOrganizerForCallback(userId, callback.getId())) {
            return;
        }
        if (data != null && data.startsWith(BotButtons.PAIR_PREFIX)) {
            handlePairingCallback(callback);
            return;
        }
        responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
    }

    private void requestOrganizerClaim(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        pendingInputs.put(userId, PendingInput.SETUP_CODE);
        responder.sendText(userId, BotMessages.SETUP_CODE_PROMPT);
        responder.answerCallback(callback.getId(), BotMessages.PRIVATE_INPUT_CALLBACK);
    }

    private void requestJoin(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        pendingInputs.put(userId, PendingInput.INVITE_CODE);
        pendingJoins.remove(userId);
        responder.sendText(userId, BotMessages.JOIN_INVITE_PROMPT);
        responder.answerCallback(callback.getId(), BotMessages.PRIVATE_INPUT_CALLBACK);
    }

    private void claimOrganizer(InputContext context, String code) {
        long userId = context.userId();
        try {
            OrganizerService.ClaimResult result = organizerService.claim(userId, code);
            if (result == OrganizerService.ClaimResult.CLAIMED
                    || (result == OrganizerService.ClaimResult.ALREADY_CLAIMED
                            && organizerService.isOrganizer(userId))) {
                responder.sendText(userId, BotMessages.ORGANIZER_CLAIMED);
                showStartMenu(userId, userId, true);
                return;
            }
            responder.sendText(userId, BotMessages.CLAIM_FAILED);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.ORGANIZER_CLAIM_PERSIST_FAILED, exception);
            responder.sendText(userId, BotMessages.CLAIM_PERSIST_FAILED);
        }
    }

    private void promptJoinName(InputContext context, String inviteCode) {
        long userId = context.userId();
        ExchangeService.InviteStatus status = exchangeService.inviteStatus(inviteCode);
        if (status != ExchangeService.InviteStatus.VALID) {
            pendingInputs.put(userId, PendingInput.INVITE_CODE);
            responder.sendText(
                    userId,
                    status == ExchangeService.InviteStatus.CLOSED
                            ? BotMessages.INVITE_CLOSED
                            : BotMessages.INVITE_INVALID);
            return;
        }
        String firstName = context.firstName();
        String suggestedName = firstName == null || firstName.isBlank()
                ? BotMessages.FALLBACK_PARTICIPANT_NAME
                : firstName.strip();
        showJoinConfirmation(userId, new PendingJoin(inviteCode, suggestedName, newToken()));
    }

    private void updateJoinName(InputContext context, String name) {
        long userId = context.userId();
        PendingJoin pendingJoin = pendingJoins.get(userId);
        if (pendingJoin == null) {
            responder.sendText(userId, BotMessages.JOIN_REQUEST_STALE);
            return;
        }
        showJoinConfirmation(
                userId,
                new PendingJoin(pendingJoin.inviteCode(), name, newToken()));
    }

    private void showJoinConfirmation(long userId, PendingJoin pendingJoin) {
        pendingJoins.put(userId, pendingJoin);
        responder.sendWithKeyboard(
                userId,
                BotMessages.joinConfirmation(
                        BotMessages.escapeMarkdown(pendingJoin.suggestedName())),
                BotButtons.joinConfirmation(pendingJoin.confirmationToken()));
    }

    private void confirmJoin(long userId, String callbackId, String token) {
        PendingJoin pendingJoin = pendingJoins.get(userId);
        if (pendingJoin == null || !pendingJoin.confirmationToken().equals(token)) {
            responder.answerCallback(callbackId, BotMessages.JOIN_REQUEST_STALE);
            return;
        }
        try {
            ExchangeService.RegistrationResult result = exchangeService.register(
                    userId, pendingJoin.inviteCode(), pendingJoin.suggestedName());
            switch (result) {
                case REGISTERED ->
                        finishJoinAttempt(
                                userId, callbackId, BotMessages.JOIN_CALLBACK, BotMessages.JOINED);
                case ALREADY_REGISTERED ->
                        finishJoinAttempt(
                                userId,
                                callbackId,
                                BotMessages.ALREADY_REGISTERED_CALLBACK,
                                BotMessages.ALREADY_REGISTERED);
                case INVALID_INVITE ->
                        finishJoinAttempt(
                                userId,
                                callbackId,
                                BotMessages.INVALID_INVITE_CALLBACK,
                                BotMessages.INVITE_INVALID_FINAL);
                case REGISTRATION_CLOSED ->
                        finishJoinAttempt(
                                userId,
                                callbackId,
                                BotMessages.REGISTRATION_CLOSED_CALLBACK,
                                BotMessages.REGISTRATION_CLOSED_FINAL);
                case INVALID_NAME -> {
                    pendingInputs.put(userId, PendingInput.JOIN_NAME);
                    responder.answerCallback(callbackId, BotMessages.NAME_INVALID_CALLBACK);
                    responder.sendText(userId, BotMessages.JOIN_NAME_INVALID);
                }
            }
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.REGISTRATION_PERSIST_FAILED, exception);
            responder.answerCallback(callbackId, BotMessages.REGISTRATION_FAILED_CALLBACK);
            responder.sendText(userId, BotMessages.REGISTRATION_FAILED);
        }
    }

    private void finishJoinAttempt(
            long userId, String callbackId, String callbackText, String message) {
        pendingJoins.remove(userId);
        responder.answerCallback(callbackId, callbackText);
        responder.sendText(userId, message);
    }

    private void handleOnboardingCallback(CallbackQuery callback) {
        String data = callback.getData();
        Consumer<CallbackQuery> action = onboardingActions.get(data);
        if (action != null) {
            action.accept(callback);
        } else if (data.startsWith(BotButtons.JOIN_CONFIRM_PREFIX)) {
            confirmJoin(
                    callback.getFrom().getId(),
                    callback.getId(),
                    data.substring(BotButtons.JOIN_CONFIRM_PREFIX.length()));
        } else if (data.startsWith(BotButtons.JOIN_EDIT_PREFIX)) {
            editJoinName(
                    callback.getFrom().getId(),
                    callback.getId(),
                    data.substring(BotButtons.JOIN_EDIT_PREFIX.length()));
        } else if (data.startsWith(BotButtons.RESET_CONFIRM_PREFIX)
                || data.startsWith(BotButtons.RESET_CANCEL_PREFIX)) {
            confirmReset(callback.getFrom().getId(), callback.getId(), data);
        } else {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
        }
    }

    private void editJoinName(long userId, String callbackId, String token) {
        PendingJoin pendingJoin = pendingJoins.get(userId);
        if (pendingJoin == null || !pendingJoin.confirmationToken().equals(token)) {
            responder.answerCallback(callbackId, BotMessages.JOIN_REQUEST_STALE);
            return;
        }
        pendingInputs.put(userId, PendingInput.JOIN_NAME);
        responder.sendText(userId, BotMessages.JOIN_NAME_PROMPT);
        responder.answerCallback(callbackId, BotMessages.CHAT_NAME_INPUT);
    }

    private void openRegistrationFromButton(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        try {
            String inviteCode = openRegistration(userId, callback.getFrom().getFirstName());
            responder.answerCallback(callback.getId(), BotMessages.REGISTRATION_OPEN_CALLBACK);
            responder.sendWithKeyboard(
                    userId,
                    BotMessages.registrationOpened(inviteCode),
                    BotButtons.organizerMenu(isRegistrationOpen()));
        } catch (PendingDrawService.PendingDrawInProgressException exception) {
            responder.answerCallback(callback.getId(), BotMessages.PENDING_DRAW_CALLBACK);
            responder.sendText(userId, BotMessages.PENDING_DRAW_RETRY);
        } catch (IllegalArgumentException exception) {
            responder.answerCallback(callback.getId(), BotMessages.PROFILE_NAME_CALLBACK);
            responder.sendText(userId, BotMessages.PROFILE_NAME_INVALID);
        } catch (SecurityException exception) {
            responder.answerCallback(callback.getId(), BotMessages.ORGANIZER_ONLY_CALLBACK);
        } catch (IOException exception) {
            LOGGER.log(
                    Level.SEVERE,
                    BotLogMessages.ONBOARDING_REGISTRATION_OPEN_FAILED,
                    exception);
            responder.answerCallback(callback.getId(), BotMessages.REGISTRATION_OPEN_FAILED_CALLBACK);
            responder.sendText(userId, BotMessages.REGISTRATION_OPEN_FAILED);
        }
    }

    private void requestResetConfirmation(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        String callbackId = callback.getId();
        if (!isOrganizerForCallback(userId, callbackId)) {
            return;
        }
        if (!isRegistrationOpen()) {
            responder.answerCallback(callbackId, BotMessages.RESET_PHASE_CALLBACK);
            responder.sendText(userId, BotMessages.RESET_PHASE_MESSAGE);
            return;
        }
        String token = newToken();
        pendingResetTokens.put(userId, token);
        responder.sendWithKeyboard(
                userId,
                BotMessages.RESET_CONFIRMATION,
                BotButtons.resetConfirmation(token));
        responder.answerCallback(callbackId, BotMessages.RESET_CONFIRMATION_CALLBACK);
    }

    private void confirmReset(long userId, String callbackId, String data) {
        String[] parts = data.split(":", -1);
        if (parts.length != 4
                || !Objects.equals(pendingResetTokens.get(userId), parts[3])) {
            responder.answerCallback(callbackId, BotMessages.STALE_BUTTON);
            return;
        }
        pendingResetTokens.remove(userId);
        if ("cancel".equals(parts[2])) {
            responder.answerCallback(callbackId, BotMessages.RESET_CANCELLED_CALLBACK);
            responder.sendText(userId, BotMessages.RESET_CANCELLED);
            return;
        }
        if (!"confirm".equals(parts[2]) || !isOrganizerForCallback(userId, callbackId)) {
            responder.answerCallback(callbackId, BotMessages.ACTION_UNAVAILABLE);
            return;
        }
        try {
            exchangeService.resetRegistration(userId, true);
            responder.answerCallback(callbackId, BotMessages.EXCHANGE_RESET_CALLBACK);
            responder.sendText(userId, BotMessages.EXCHANGE_RESET);
            showStartMenu(userId, userId, true);
        } catch (IllegalStateException exception) {
            responder.answerCallback(callbackId, BotMessages.RESET_UNAVAILABLE_CALLBACK);
            responder.sendText(userId, BotMessages.RESET_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.ONBOARDING_RESET_FAILED, exception);
            responder.answerCallback(callbackId, BotMessages.RESET_FAILED_CALLBACK);
            responder.sendText(userId, BotMessages.RESET_FAILED);
        }
    }

    private boolean isOrganizerForCallback(long userId, String callbackId) {
        try {
            if (organizerService.isOrganizer(userId)) {
                return true;
            }
            responder.answerCallback(callbackId, BotMessages.ORGANIZER_ONLY_ACTION);
        } catch (IOException exception) {
            LOGGER.log(
                    Level.SEVERE,
                    BotLogMessages.ONBOARDING_AUTHORIZATION_CHECK_FAILED,
                    exception);
            responder.answerCallback(callbackId, BotMessages.ORGANIZER_CHECK_FAILED);
        }
        return false;
    }

    private synchronized void handlePairingCallback(CallbackQuery callback) {
        String[] parts = callback.getData().split(":", -1);
        if (parts.length < 4 || parts.length > 5) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }

        boolean firstSelection = parts[1].equals("first") && parts.length == 4;
        boolean secondSelection = parts[1].equals("second") && parts.length == 5;
        if (!firstSelection && !secondSelection) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }
        if (activePairingSessionToken == null
                || !activePairingSessionToken.equals(parts[2])) {
            responder.answerCallback(callback.getId(), BotMessages.PARTICIPANT_LIST_CHANGED);
            return;
        }

        long firstId = -1;
        long selectedId;
        try {
            if (firstSelection) {
                selectedId = Long.parseLong(parts[3]);
            } else {
                firstId = Long.parseLong(parts[3]);
                selectedId = Long.parseLong(parts[4]);
            }
        } catch (NumberFormatException exception) {
            responder.answerCallback(callback.getId(), BotMessages.INVALID_PARTICIPANT_SELECTION);
            return;
        }

        if (secondSelection
                && (pendingPairParticipantId == null || pendingPairParticipantId != firstId)) {
            responder.answerCallback(callback.getId(), BotMessages.PARTICIPANT_LIST_CHANGED_SECOND);
            return;
        }

        long userId = callback.getFrom().getId();
        SecretSantaBot.Participant selected = exchangeService.participant(selectedId).orElse(null);
        if (selected == null) {
            cancelPairing();
            responder.answerCallback(callback.getId(), BotMessages.PARTICIPANTS_CHANGED);
            return;
        }
        if (firstSelection) {
            selectFirstParticipant(callback, userId, selected);
            return;
        }
        savePair(callback, userId, firstId, selectedId);
    }

    private void selectFirstParticipant(
            CallbackQuery callback, long userId, SecretSantaBot.Participant selected) {
        pendingPairParticipantId = selected.chatId();
        List<SecretSantaBot.Participant> roster = exchangeService.participants().stream()
                .filter(participant -> participant.chatId() != selected.chatId())
                .toList();
        responder.sendWithKeyboard(
                userId,
                BotMessages.partnerSelection(
                        BotMessages.escapeMarkdown(selected.name())),
                BotButtons.pairing(
                        roster,
                        BotButtons.PAIR_PREFIX + "second:" + activePairingSessionToken + ":"
                                + selected.chatId() + ":",
                        selected.chatId()));
        responder.answerCallback(callback.getId(), BotMessages.SECOND_PARTICIPANT_PROMPT);
    }

    private void savePair(CallbackQuery callback, long userId, long firstId, long selectedId) {
        cancelPairing();
        try {
            ExchangeService.PairingChange change = exchangeService.pair(firstId, selectedId);
            if (change.unchanged()) {
                responder.sendText(userId, BotMessages.PAIR_UNCHANGED);
                responder.answerCallback(callback.getId(), BotMessages.PAIR_UNCHANGED_CALLBACK);
                return;
            }
            responder.sendText(userId, pairingChangeMessage(change));
            responder.answerCallback(callback.getId(), BotMessages.PAIR_SAVED_CALLBACK);
        } catch (IllegalArgumentException exception) {
            responder.sendText(userId, BotMessages.PARTICIPANT_LIST_CHANGED);
            responder.answerCallback(callback.getId(), BotMessages.PAIRING_FAILED_CALLBACK);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PAIRING_PERSIST_FAILED, exception);
            responder.sendText(userId, BotMessages.PAIR_PERSIST_FAILED);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_PERSIST_FAILED_CALLBACK);
        }
    }

    private static String pairingChangeMessage(ExchangeService.PairingChange change) {
        List<String> replacedPairs = new ArrayList<>();
        if (change.formerPartnerOfFirst() != null) {
            replacedPairs.add(
                    BotMessages.escapeMarkdown(change.first().name()) + " + "
                            + BotMessages.escapeMarkdown(change.formerPartnerOfFirst().name()));
        }
        if (change.formerPartnerOfSecond() != null) {
            replacedPairs.add(
                    BotMessages.escapeMarkdown(change.second().name()) + " + "
                            + BotMessages.escapeMarkdown(change.formerPartnerOfSecond().name()));
        }
        return BotMessages.pairingChange(
                BotMessages.escapeMarkdown(change.first().name()),
                BotMessages.escapeMarkdown(change.second().name()),
                replacedPairs);
    }

    private boolean isRegistrationOpen() {
        return exchangeService.state().phase() == ExchangeState.Phase.REGISTRATION_OPEN;
    }

    private static String newToken() {
        byte[] tokenBytes = new byte[6];
        RANDOM.nextBytes(tokenBytes);
        return TOKEN_ENCODER.encodeToString(tokenBytes);
    }

    private enum PendingInput {
        SETUP_CODE,
        INVITE_CODE,
        JOIN_NAME
    }

    private record PendingJoin(String inviteCode, String suggestedName, String confirmationToken) {}

    private record InputContext(long userId, String firstName) {}
}
