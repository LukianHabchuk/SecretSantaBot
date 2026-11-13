package com.secretsanta;

final class BotLogMessages {
    static final String ORGANIZER_CLAIM_PERSIST_FAILED = "Could not persist organizer claim.";
    static final String ORGANIZER_STATE_READ_FAILED = "Could not read organizer state.";
    static final String REGISTRATION_PERSIST_FAILED = "Could not persist participant registration.";
    static final String JOIN_NOTIFICATION_FAILED = "Could not notify organizer of participant registration.";
    static final String ACTION_ATTEMPTED = "action_attempted ";
    static final String ASSIGNMENT_DELIVERY_COMPLETED = "assignment_delivery_completed";
    static final String PENDING_DRAW_CHECK_FAILED =
            "Could not check pending draw before pairing.";
    static final String ONBOARDING_STATE_READ_FAILED =
            "Could not read organizer state for onboarding.";
    static final String ONBOARDING_REGISTRATION_OPEN_FAILED =
            "Could not open registration from onboarding.";
    static final String ONBOARDING_RESET_FAILED =
            "Could not reset registration from onboarding.";
    static final String ONBOARDING_AUTHORIZATION_CHECK_FAILED =
            "Could not read organizer state for onboarding action.";
    static final String PAIRING_PERSIST_FAILED = "Could not persist participant pairing.";
    static final String PAIR_REQUEST_PERSIST_FAILED =
            "Could not persist or resolve participant pair request.";
    static final String DRAW_RESUME_FAILED =
            "Could not safely persist or resume the pending draw.";
    static final String REPLACEMENT_DRAW_FAILED =
            "Could not safely persist or deliver the replacement draw.";
    static final String EXCHANGE_CLEANUP_FAILED =
            "Could not clean up the completed exchange.";
    static final String TELEGRAM_RESPONSE_SEND_FAILED = "Failed to send Telegram response.";
    static final String TELEGRAM_CALLBACK_ANSWER_FAILED = "Failed to answer Telegram callback.";
    static final String DATA_DIRECTORY_LOCK_FAILED =
            "Could not lock the Secret Santa data directory.";
    static final String USER_HOME_UNAVAILABLE =
            "The user home directory is not configured.";
    static final String INSTANCE_LOCK_UNAVAILABLE =
            "Another bot may already be using this data directory.";
    static final String BOT_STARTED =
            "🤖 Бот Таємного Санти успішно запущений і слухає повідомлення!";

    private BotLogMessages() {}
}
