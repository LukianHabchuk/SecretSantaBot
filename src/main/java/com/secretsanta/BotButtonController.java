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
import java.util.OptionalLong;
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
    private final Map<Long, String> pendingCleanupTokens = new HashMap<>();
    private final Map<Long, String> pendingReplacementTokens = new HashMap<>();
    private final Map<String, Consumer<CallbackQuery>> onboardingActions;
    private final Map<String, Consumer<CallbackQuery>> organizerPairActions;
    private final BotParticipantPairController participantPairController;
    private final Map<ExchangeService.RegistrationResult, Consumer<JoinAttempt>>
            registrationActions;
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
        participantPairController =
                new BotParticipantPairController(exchangeService, responder);
        onboardingActions = Map.of(
                BotButtons.CLAIM, this::requestOrganizerClaim,
                BotButtons.JOIN, this::requestJoin,
                BotButtons.OPEN, this::openRegistrationFromButton,
                BotButtons.RESET, this::requestResetConfirmation);
        organizerPairActions = Map.of(
                BotButtons.ORGANIZER_PAIR, this::startPairingFromButton,
                BotButtons.ORGANIZER_ROSTER, this::showOrganizerRoster,
                BotButtons.ORGANIZER_RUN_DRAW, this::runDrawFromButton,
                BotButtons.ORGANIZER_DRAW_STATUS, this::showDrawStatus,
                BotButtons.ORGANIZER_REPLACE_DRAW, this::requestReplacementConfirmation,
                BotButtons.ORGANIZER_CLEANUP, this::requestCleanupConfirmation);
        registrationActions = Map.of(
                ExchangeService.RegistrationResult.REGISTERED,
                attempt -> finishJoinAttempt(
                        attempt.userId(),
                        attempt.callbackId(),
                        BotMessages.JOIN_CALLBACK,
                        BotMessages.JOINED),
                ExchangeService.RegistrationResult.ALREADY_REGISTERED,
                attempt -> finishJoinAttempt(
                        attempt.userId(),
                        attempt.callbackId(),
                        BotMessages.ALREADY_REGISTERED_CALLBACK,
                        BotMessages.ALREADY_REGISTERED),
                ExchangeService.RegistrationResult.INVALID_INVITE,
                attempt -> finishJoinAttempt(
                        attempt.userId(),
                        attempt.callbackId(),
                        BotMessages.INVALID_INVITE_CALLBACK,
                        BotMessages.INVITE_INVALID_FINAL),
                ExchangeService.RegistrationResult.REGISTRATION_CLOSED,
                attempt -> finishJoinAttempt(
                        attempt.userId(),
                        attempt.callbackId(),
                        BotMessages.REGISTRATION_CLOSED_CALLBACK,
                        BotMessages.REGISTRATION_CLOSED_FINAL),
                ExchangeService.RegistrationResult.INVALID_NAME,
                attempt -> {
                    pendingInputs.put(attempt.userId(), PendingInput.JOIN_NAME);
                    responder.answerCallback(
                            attempt.callbackId(), BotMessages.NAME_INVALID_CALLBACK);
                    responder.sendText(attempt.userId(), BotMessages.JOIN_NAME_INVALID);
                });
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
        pendingCleanupTokens.remove(userId);
        pendingReplacementTokens.remove(userId);
        try {
            OptionalLong organizerId = organizerService.organizerId();
            boolean organizer = organizerId.isPresent() && organizerId.getAsLong() == userId;
            boolean registeredParticipant = exchangeService.participant(userId).isPresent();
            ExchangeState state = exchangeService.state();
            responder.sendWithKeyboard(
                    chatId,
                    organizer
                            ? BotMessages.ORGANIZER_START
                            : organizerId.isPresent()
                                    ? BotMessages.PARTICIPANT_JOIN_START
                                    : BotMessages.PARTICIPANT_START,
                    organizer
                            ? BotButtons.organizerMenu(state, registeredParticipant)
                            : state.phase() == ExchangeState.Phase.REGISTRATION_OPEN
                                            && registeredParticipant
                                    ? BotButtons.participantPairMenu()
                                    : BotButtons.participantStartMenu(organizerId.isEmpty()));
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
        LOGGER.info(BotLogMessages.ACTION_ATTEMPTED + switch (pendingInput) {
            case SETUP_CODE -> "input_setup_code";
            case INVITE_CODE -> "input_invite_code";
            case JOIN_NAME -> "input_join_name";
        });
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

    synchronized boolean startPairing(long organizerId) {
        List<SecretSantaBot.Participant> roster = exchangeService.participants();
        if (roster.size() < 2) {
            cancelPairing();
            responder.sendText(organizerId, BotMessages.TOO_FEW_FOR_PAIRING);
            return false;
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
        return true;
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
        String action = callbackAction(data);
        if (action != null) {
            LOGGER.info(BotLogMessages.ACTION_ATTEMPTED + action);
        }
        if (data != null && data.startsWith(BotButtons.ONBOARDING_PREFIX)) {
            handleOnboardingCallback(callback);
            return;
        }
        if (data != null && data.startsWith(BotButtons.PAIR_REQUEST_PREFIX)) {
            participantPairController.handleCallbackQuery(callback);
            return;
        }
        if (data != null && data.startsWith(BotButtons.DRAW_RECEIVED_PREFIX)) {
            confirmDrawReceipt(callback);
            return;
        }
        if (!isOrganizerForCallback(userId, callback.getId())) {
            return;
        }
        Consumer<CallbackQuery> organizerPairAction =
                data == null ? null : organizerPairActions.get(data);
        if (organizerPairAction != null) {
            organizerPairAction.accept(callback);
            return;
        }
        if (data != null
                && (data.startsWith(BotButtons.CLEANUP_CONFIRM_PREFIX)
                        || data.startsWith(BotButtons.CLEANUP_CANCEL_PREFIX))) {
            confirmCleanup(callback);
            return;
        }
        if (data != null
                && (data.startsWith(BotButtons.REPLACE_DRAW_CONFIRM_PREFIX)
                        || data.startsWith(BotButtons.REPLACE_DRAW_CANCEL_PREFIX))) {
            confirmReplacementDraw(callback);
            return;
        }
        if (data != null && data.startsWith(BotButtons.PAIR_PREFIX)) {
            handlePairingCallback(callback);
            return;
        }
        responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
    }

    private static String callbackAction(String data) {
        if (data == null) {
            return null;
        }
        String fixedAction = switch (data) {
            case BotButtons.CLAIM -> "button_claim";
            case BotButtons.JOIN -> "button_join";
            case BotButtons.OPEN -> "button_open";
            case BotButtons.RESET -> "button_reset";
            case BotButtons.ORGANIZER_PAIR -> "button_pair";
            case BotButtons.ORGANIZER_ROSTER -> "button_roster";
            case BotButtons.ORGANIZER_CLEANUP -> "button_cleanup";
            case BotButtons.PAIR_REQUEST_CHOOSE -> "button_pair_request";
            case BotButtons.PAIR_REQUEST_STATUS -> "button_pair_status";
            default -> null;
        };
        if (fixedAction != null) {
            return fixedAction;
        }
        if (data.startsWith(BotButtons.JOIN_CONFIRM_PREFIX)) return "button_join_confirm";
        if (data.startsWith(BotButtons.JOIN_EDIT_PREFIX)) return "button_join_edit";
        if (data.startsWith(BotButtons.RESET_CONFIRM_PREFIX)) return "button_reset_confirm";
        if (data.startsWith(BotButtons.RESET_CANCEL_PREFIX)) return "button_reset_cancel";
        if (data.startsWith(BotButtons.CLEANUP_CONFIRM_PREFIX)) return "button_cleanup_confirm";
        if (data.startsWith(BotButtons.CLEANUP_CANCEL_PREFIX)) return "button_cleanup_cancel";
        if (data.startsWith(BotButtons.PAIR_REQUEST_TARGET_PREFIX)) return "button_pair_target";
        if (data.startsWith(BotButtons.PAIR_REQUEST_ACCEPT_PREFIX)) return "button_pair_accept";
        if (data.startsWith(BotButtons.PAIR_REQUEST_DECLINE_PREFIX)) return "button_pair_decline";
        if (data.startsWith(BotButtons.PAIR_REQUEST_CANCEL_PREFIX)) return "button_pair_cancel";
        if (data.startsWith(BotButtons.PAIR_PREFIX)) return "button_pair_selection";
        return null;
    }

    private void confirmDrawReceipt(CallbackQuery callback) {
        String generationValue =
                callback.getData().substring(BotButtons.DRAW_RECEIVED_PREFIX.length());
        long generation;
        try {
            generation = Long.parseLong(generationValue);
        } catch (NumberFormatException exception) {
            responder.answerCallback(callback.getId(), BotMessages.DRAW_RECEIPT_STALE);
            return;
        }

        try {
            boolean newlyConfirmed =
                    exchangeService.confirmReceipt(callback.getFrom().getId(), generation);
            responder.answerCallback(
                    callback.getId(),
                    newlyConfirmed
                            ? BotMessages.DRAW_RECEIPT_RECORDED
                            : BotMessages.DRAW_RECEIPT_ALREADY_RECORDED);
        } catch (IllegalArgumentException exception) {
            responder.answerCallback(callback.getId(), BotMessages.DRAW_RECEIPT_UNAVAILABLE);
        } catch (IllegalStateException exception) {
            responder.answerCallback(callback.getId(), BotMessages.DRAW_RECEIPT_STALE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.DRAW_RESUME_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.DRAW_RECEIPT_FAILED);
        }
    }

    private void requestOrganizerClaim(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        pendingInputs.put(userId, PendingInput.SETUP_CODE);
        responder.sendText(userId, BotMessages.SETUP_CODE_PROMPT);
        responder.answerCallback(callback.getId(), BotMessages.PRIVATE_INPUT_CALLBACK);
    }

    private void startPairingFromButton(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        try {
            if (pendingDrawService.hasPendingDraw()) {
                responder.answerCallback(callback.getId(), BotMessages.PAIR_PENDING_DRAW);
                responder.sendText(userId, BotMessages.PAIR_PENDING_DRAW);
                return;
            }
            boolean started = startPairing(userId);
            responder.answerCallback(
                    callback.getId(),
                    started
                            ? BotMessages.FIRST_PARTICIPANT_PROMPT
                            : BotMessages.TOO_FEW_FOR_PAIRING);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PENDING_DRAW_CHECK_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_PENDING_DRAW_CHECK_FAILED);
            responder.sendText(userId, BotMessages.PAIR_PENDING_DRAW_CHECK_FAILED);
        }
    }

    private void showOrganizerRoster(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        List<SecretSantaBot.Participant> participants = exchangeService.participants();
        responder.sendText(
                userId,
                participants.isEmpty()
                        ? BotMessages.EMPTY_ROSTER
                        : BotMessages.participantRoster(
                                participants,
                                exchangeService.state().pairRequests()));
        responder.answerCallback(callback.getId(), BotMessages.PAIR_STATUS_CALLBACK);
    }

    private void runDrawFromButton(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        cancelPairing();
        try {
            PendingDrawService.Result result = pendingDrawService.run();
            responder.answerCallback(callback.getId(), BotMessages.drawResult(result));
            responder.sendText(userId, BotMessages.drawResult(result));
        } catch (IllegalStateException exception) {
            responder.answerCallback(callback.getId(), BotMessages.RUN_NO_ASSIGNMENT);
            responder.sendText(userId, BotMessages.RUN_NO_ASSIGNMENT);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.DRAW_RESUME_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.RUN_FAILED);
            responder.sendText(userId, BotMessages.RUN_FAILED);
        }
    }

    private void showDrawStatus(CallbackQuery callback) {
        responder.sendText(
                callback.getFrom().getId(),
                BotMessages.drawStatus(exchangeService.state()));
        responder.answerCallback(callback.getId(), BotMessages.DRAW_STATUS_CALLBACK);
    }

    private void requestReplacementConfirmation(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        if (!exchangeService.canReplaceDraw()) {
            responder.answerCallback(
                    callback.getId(), BotMessages.REPLACEMENT_UNAVAILABLE_CALLBACK);
            responder.sendText(userId, BotMessages.REPLACEMENT_UNAVAILABLE);
            return;
        }
        String token = newToken();
        pendingReplacementTokens.put(userId, token);
        responder.sendWithKeyboard(
                userId,
                BotMessages.REPLACEMENT_CONFIRMATION,
                BotButtons.replacementDrawConfirmation(token));
        responder.answerCallback(
                callback.getId(), BotMessages.REPLACEMENT_CONFIRMATION_CALLBACK);
    }

    private void confirmReplacementDraw(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        String data = callback.getData();
        boolean cancel = data.startsWith(BotButtons.REPLACE_DRAW_CANCEL_PREFIX);
        String prefix = cancel
                ? BotButtons.REPLACE_DRAW_CANCEL_PREFIX
                : BotButtons.REPLACE_DRAW_CONFIRM_PREFIX;
        String token = data.substring(prefix.length());
        if (token.isBlank() || !Objects.equals(pendingReplacementTokens.get(userId), token)) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }
        pendingReplacementTokens.remove(userId);
        if (cancel) {
            responder.answerCallback(
                    callback.getId(), BotMessages.REPLACEMENT_CANCELLED_CALLBACK);
            responder.sendText(userId, BotMessages.REPLACEMENT_CANCELLED);
            return;
        }
        try {
            PendingDrawService.Result result = pendingDrawService.replaceDraw();
            responder.answerCallback(
                    callback.getId(), BotMessages.replacementDrawCallback(result));
            responder.sendText(userId, BotMessages.replacementDrawResult(result));
            showStartMenu(userId, userId, true);
        } catch (IllegalStateException exception) {
            responder.answerCallback(
                    callback.getId(), BotMessages.REPLACEMENT_UNAVAILABLE_CALLBACK);
            responder.sendText(userId, BotMessages.REPLACEMENT_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.REPLACEMENT_DRAW_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.REPLACEMENT_FAILED);
            responder.sendText(userId, BotMessages.REPLACEMENT_FAILED);
        }
    }

    private void requestCleanupConfirmation(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        if (!exchangeService.canCleanupExchange()) {
            responder.answerCallback(callback.getId(), BotMessages.CLEANUP_UNAVAILABLE_CALLBACK);
            responder.sendText(userId, BotMessages.CLEANUP_UNAVAILABLE);
            return;
        }
        String token = newToken();
        pendingCleanupTokens.put(userId, token);
        responder.sendWithKeyboard(
                userId,
                BotMessages.CLEANUP_CONFIRMATION,
                BotButtons.cleanupConfirmation(token));
        responder.answerCallback(callback.getId(), BotMessages.CLEANUP_CONFIRMATION_CALLBACK);
    }

    private void confirmCleanup(CallbackQuery callback) {
        long userId = callback.getFrom().getId();
        String data = callback.getData();
        boolean cancel = data.startsWith(BotButtons.CLEANUP_CANCEL_PREFIX);
        String prefix = cancel
                ? BotButtons.CLEANUP_CANCEL_PREFIX
                : BotButtons.CLEANUP_CONFIRM_PREFIX;
        String token = data.substring(prefix.length());
        if (token.isBlank() || !Objects.equals(pendingCleanupTokens.get(userId), token)) {
            responder.answerCallback(callback.getId(), BotMessages.STALE_BUTTON);
            return;
        }
        pendingCleanupTokens.remove(userId);
        if (cancel) {
            responder.answerCallback(callback.getId(), BotMessages.CLEANUP_CANCELLED_CALLBACK);
            responder.sendText(userId, BotMessages.CLEANUP_CANCELLED);
            return;
        }
        try {
            exchangeService.cleanupExchange(userId, true);
            responder.answerCallback(callback.getId(), BotMessages.EXCHANGE_CLEANED_CALLBACK);
            responder.sendText(userId, BotMessages.EXCHANGE_CLEANED);
            showStartMenu(userId, userId, true);
        } catch (SecurityException exception) {
            responder.answerCallback(callback.getId(), BotMessages.ORGANIZER_ONLY_ACTION);
        } catch (IllegalStateException exception) {
            responder.answerCallback(callback.getId(), BotMessages.CLEANUP_UNAVAILABLE_CALLBACK);
            responder.sendText(userId, BotMessages.CLEANUP_UNAVAILABLE);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.EXCHANGE_CLEANUP_FAILED, exception);
            responder.answerCallback(callback.getId(), BotMessages.CLEANUP_FAILED_CALLBACK);
            responder.sendText(userId, BotMessages.CLEANUP_FAILED);
        }
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
            if (result == ExchangeService.RegistrationResult.REGISTERED) {
                notifyOrganizerOfJoin(userId);
            }
            registrationActions.get(result).accept(new JoinAttempt(userId, callbackId));
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.REGISTRATION_PERSIST_FAILED, exception);
            responder.answerCallback(callbackId, BotMessages.REGISTRATION_FAILED_CALLBACK);
            responder.sendText(userId, BotMessages.REGISTRATION_FAILED);
        }
    }

    void notifyOrganizerOfJoin(long userId) {
        try {
            long organizerId = organizerService.organizerId()
                    .orElseThrow(() -> new IOException("Organizer identity is unavailable."));
            SecretSantaBot.Participant participant = exchangeService.participant(userId)
                    .orElseThrow(() -> new IOException("Registered participant is unavailable."));
            responder.sendText(organizerId, BotMessages.participantJoined(participant.name()));
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.JOIN_NOTIFICATION_FAILED, exception);
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
                    BotButtons.organizerMenu(exchangeService.state(), true));
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
        if (!isRegistrationOpen()) {
            cancelPairing();
            responder.answerCallback(callback.getId(), BotMessages.PAIRING_CLOSED_CALLBACK);
            return;
        }

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
            notifyPairingChange(change);
            responder.sendText(userId, pairingChangeMessage(change));
            responder.answerCallback(callback.getId(), BotMessages.PAIR_SAVED_CALLBACK);
        } catch (IllegalArgumentException exception) {
            responder.sendText(userId, BotMessages.PARTICIPANT_LIST_CHANGED);
            responder.answerCallback(callback.getId(), BotMessages.PAIRING_FAILED_CALLBACK);
        } catch (IllegalStateException exception) {
            responder.sendText(userId, BotMessages.PAIRING_CLOSED);
            responder.answerCallback(callback.getId(), BotMessages.PAIRING_CLOSED_CALLBACK);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PAIRING_PERSIST_FAILED, exception);
            responder.sendText(userId, BotMessages.PAIR_PERSIST_FAILED);
            responder.answerCallback(callback.getId(), BotMessages.PAIR_PERSIST_FAILED_CALLBACK);
        }
    }

    private void notifyPairingChange(ExchangeService.PairingChange change) {
        Map<Long, String> notifications = new HashMap<>();
        notifications.put(
                change.first().chatId(),
                BotMessages.pairChanged(BotMessages.escapeMarkdown(change.second().name())));
        notifications.put(
                change.second().chatId(),
                BotMessages.pairChanged(BotMessages.escapeMarkdown(change.first().name())));
        if (change.formerPartnerOfFirst() != null) {
            notifications.put(
                    change.formerPartnerOfFirst().chatId(), BotMessages.PAIR_CHANGED_UNPAIRED);
        }
        if (change.formerPartnerOfSecond() != null) {
            notifications.put(
                    change.formerPartnerOfSecond().chatId(), BotMessages.PAIR_CHANGED_UNPAIRED);
        }
        change.canceledRequests().forEach(request -> {
            notifications.merge(
                    request.requesterId(),
                    BotMessages.PAIR_REQUEST_CANCELED_BY_ORGANIZER,
                    BotButtonController::appendNotification);
            notifications.merge(
                    request.requestedPartnerId(),
                    BotMessages.PAIR_REQUEST_CANCELED_BY_ORGANIZER,
                    BotButtonController::appendNotification);
        });
        notifications.forEach(responder::sendText);
    }

    private static String appendNotification(String existing, String added) {
        return existing + "\n" + added;
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

    private record JoinAttempt(long userId, String callbackId) {}

}
