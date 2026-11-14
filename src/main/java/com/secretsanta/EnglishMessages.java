package com.secretsanta;

import java.util.HashMap;
import java.util.Map;

final class EnglishMessages {
    private static final Map<String, String> TRANSLATIONS = translations();

    private EnglishMessages() {}

    static String translate(String ukrainian) {
        return TRANSLATIONS.get(ukrainian);
    }

    private static Map<String, String> translations() {
        Map<String, String> m = new HashMap<>();
        m.put(BotMessages.PRIVATE_START_ONLY, "Open the bot in a private chat to join or create an exchange.");
        m.put(BotMessages.ORGANIZER_START, "🎅 **Secret Santa**\n\nChoose an action for your exchange.");
        m.put(BotMessages.PARTICIPANT_START, "🎅 **Secret Santa**\n\nCreate an exchange or join with an invitation.");
        m.put(BotMessages.PARTICIPANT_JOIN_START, "🎅 **Secret Santa**\n\nJoin the exchange with an invitation.");
        m.put(BotMessages.ORGANIZER_CLAIMED, "✅ Organizer access confirmed.");
        m.put(BotMessages.CLAIM_FAILED, "❌ Could not confirm organizer access. Check the code or contact the owner.");
        m.put(BotMessages.CLAIM_PERSIST_FAILED, "❌ Could not save organizer access. Please try again later.");
        m.put(BotMessages.JOIN_REQUEST_STALE, "This join request has expired. Start again with `/start`.");
        m.put(BotMessages.INVITE_CLOSED, "❌ Registration is closed. Check the code or contact the organizer.");
        m.put(BotMessages.INVITE_INVALID, "❌ Invalid invitation code. Please try again.");
        m.put(BotMessages.JOIN_NAME_INVALID, "❌ Enter a name between 1 and 64 characters.");
        m.put(BotMessages.JOIN_NAME_PROMPT, "Enter the name that participants will see.");
        m.put(BotMessages.NAME_EDIT_PROMPT, "Enter the new name that participants will see.");
        m.put(BotMessages.NAME_EDIT_PROMPT_CALLBACK, "Enter the name in this private chat.");
        m.put(BotMessages.NAME_EDIT_INVALID,
                "❌ The name must contain 1 to 64 characters and no control characters.");
        m.put(BotMessages.NAME_EDIT_SAVED, "✅ Name updated.");
        m.put(BotMessages.NAME_EDIT_CLOSED_CALLBACK,
                "Names can only be changed before the draw starts.");
        m.put(BotMessages.NAME_EDIT_CLOSED,
                "❌ A name can only be changed before the draw starts.");
        m.put(BotMessages.NAME_EDIT_UNAVAILABLE,
                "❌ Could not change the name. Check that the participant is registered.");
        m.put(BotMessages.NAME_EDIT_FAILED,
                "❌ Could not save the name. Please try again.");
        m.put(BotMessages.NAME_EDIT_TARGETS_PROMPT,
                "Choose the participant whose name you want to change:");
        m.put(BotMessages.NAME_EDIT_TARGETS_CALLBACK, "Choose a participant.");
        m.put(BotMessages.JOIN_INVITE_PROMPT, "Enter the exchange invitation code.");
        m.put(BotMessages.SETUP_CODE_PROMPT, "Enter the setup code to confirm organizer access.");
        m.put(BotMessages.PRIVATE_INPUT_CALLBACK, "Enter the code in this private chat.");
        m.put(BotMessages.JOIN_CALLBACK, "You joined the exchange.");
        m.put(BotMessages.ALREADY_REGISTERED_CALLBACK, "You are already registered.");
        m.put(BotMessages.INVALID_INVITE_CALLBACK, "Invalid invitation code.");
        m.put(BotMessages.REGISTRATION_CLOSED_CALLBACK, "Registration is closed.");
        m.put(BotMessages.NAME_INVALID_CALLBACK, "Invalid name.");
        m.put(BotMessages.REGISTRATION_FAILED_CALLBACK, "Could not save your registration.");
        m.put(BotMessages.JOINED, "✅ You joined the exchange.");
        m.put(BotMessages.ALREADY_REGISTERED, "ℹ️ You are already registered in this exchange.");
        m.put(BotMessages.INVITE_INVALID_FINAL, "❌ Invalid invitation code.");
        m.put(BotMessages.REGISTRATION_CLOSED_FINAL, "❌ Registration is currently closed.");
        m.put(BotMessages.REGISTRATION_FAILED, "❌ Could not save your registration. Please try again later.");
        m.put(BotMessages.REGISTRATION_OPEN_CALLBACK, "Registration is open.");
        m.put(BotMessages.PENDING_DRAW_CALLBACK, "An exchange is already active.");
        m.put(BotMessages.PENDING_DRAW_RETRY, "❌ An exchange is active. You cannot open a new registration.");
        m.put(BotMessages.PROFILE_NAME_CALLBACK, "Could not use the profile name.");
        m.put(BotMessages.PROFILE_NAME_INVALID, "❌ Could not use the name from your Telegram profile.");
        m.put(BotMessages.ORGANIZER_ONLY_CALLBACK, "Only the organizer can do this.");
        m.put(BotMessages.REGISTRATION_OPEN_FAILED_CALLBACK, "Could not open registration.");
        m.put(BotMessages.REGISTRATION_OPEN_FAILED, "❌ Could not open registration. Please try again later.");
        m.put(BotMessages.RESET_PHASE_CALLBACK, "Reset is available only before the draw.");
        m.put(BotMessages.RESET_PHASE_MESSAGE, "❌ Only open registration can be reset before the draw.");
        m.put(BotMessages.RESET_CONFIRMATION, "Reset the exchange and delete the current participant list?");
        m.put(BotMessages.RESET_CONFIRMATION_CALLBACK, "Confirm or cancel the reset.");
        m.put(BotMessages.STALE_BUTTON, "This button is no longer active.");
        m.put(BotMessages.RESET_CANCELLED_CALLBACK, "Reset canceled.");
        m.put(BotMessages.RESET_CANCELLED, "Reset canceled.");
        m.put(BotMessages.ACTION_UNAVAILABLE, "This action is unavailable.");
        m.put(BotMessages.EXCHANGE_RESET_CALLBACK, "Exchange reset.");
        m.put(BotMessages.EXCHANGE_RESET, "✅ Exchange reset. You remain the organizer.");
        m.put(BotMessages.RESET_UNAVAILABLE_CALLBACK, "Reset is unavailable after the draw starts.");
        m.put(BotMessages.RESET_UNAVAILABLE, "❌ The exchange is closed; reset is unavailable.");
        m.put(BotMessages.RESET_FAILED_CALLBACK, "Could not reset the exchange.");
        m.put(BotMessages.RESET_FAILED, "❌ Could not reset the exchange. Please try again later.");
        m.put(BotMessages.ORGANIZER_ONLY_ACTION, "This action is available only to the organizer.");
        m.put(BotMessages.ORGANIZER_CHECK_FAILED, "Could not check organizer access.");
        m.put(BotMessages.CALLBACK_PRIVATE_ONLY, "Use buttons only in a private chat.");
        m.put(BotMessages.PARTICIPANT_LIST_CHANGED, "The list changed. Start again with `/pair`.");
        m.put(BotMessages.PARTICIPANT_LIST_CHANGED_SECOND, "The list changed. Choose a partner from the latest message.");
        m.put(BotMessages.PARTICIPANTS_CHANGED, "The participant list changed. Start again with `/pair`.");
        m.put(BotMessages.INVALID_PARTICIPANT_SELECTION, "Invalid participant selection.");
        m.put(BotMessages.SECOND_PARTICIPANT_PROMPT, "Choose the second participant.");
        m.put(BotMessages.PAIR_UNCHANGED, "ℹ️ These participants are already paired.");
        m.put(BotMessages.PAIR_UNCHANGED_CALLBACK, "The pair was not changed.");
        m.put(BotMessages.PAIR_SAVED_CALLBACK, "Pair saved.");
        m.put(BotMessages.PAIRING_FAILED_CALLBACK, "Could not create the pair.");
        m.put(BotMessages.PAIRING_CLOSED, "Pairs cannot be changed after registration closes.");
        m.put(BotMessages.PAIRING_CLOSED_CALLBACK, "Registration is closed.");
        m.put(BotMessages.PAIR_PERSIST_FAILED, "❌ Could not save the pair. Please try again later.");
        m.put(BotMessages.PAIR_PERSIST_FAILED_CALLBACK, "Could not save the pair.");
        m.put(BotMessages.PAIR_REQUEST_CLOSED, "❌ Pair requests are available only during registration.");
        m.put(BotMessages.PAIR_REQUEST_NOT_REGISTERED, "❌ Join the exchange before sending a pair request.");
        m.put(BotMessages.PAIR_REQUEST_ALREADY_PAIRED, "ℹ️ You already have a partner.");
        m.put(BotMessages.PAIR_REQUEST_NO_CANDIDATES, "There are no available participants to request right now.");
        m.put(BotMessages.PAIR_REQUEST_CHOOSE_PROMPT, "Choose a participant to request as a partner:");
        m.put(BotMessages.PAIR_REQUEST_SENT_CALLBACK, "Pair request sent.");
        m.put(BotMessages.PAIR_REQUEST_RECEIVED_CALLBACK, "Pair request.");
        m.put(BotMessages.PAIR_REQUEST_STALE, "This request is no longer active.");
        m.put(BotMessages.PAIR_REQUEST_FAILED, "❌ Could not save the pair request.");
        m.put(BotMessages.PAIR_REQUEST_ACCEPTED_CALLBACK, "Pair confirmed.");
        m.put(BotMessages.PAIR_REQUEST_ACCEPTED, "✅ Pair confirmed: ");
        m.put(BotMessages.PAIR_EXPLANATION,
                "This pair only means neither of you will be assigned to the other in the draw.");
        m.put(BotMessages.PAIR_REQUEST_DECLINED_CALLBACK, "Request declined.");
        m.put(BotMessages.PAIR_REQUEST_DECLINED, "Pair request declined.");
        m.put(BotMessages.PAIR_REQUEST_CANCELED_CALLBACK, "Request canceled.");
        m.put(BotMessages.PAIR_REQUEST_CANCELED, "Pair request canceled.");
        m.put(BotMessages.PAIR_REQUEST_CANCELED_BY_ORGANIZER, "The organizer changed the pairs, so your request was canceled.");
        m.put(BotMessages.PAIR_REQUEST_NONE, "There is no open pair request.");
        m.put(BotMessages.PAIR_STATUS_CALLBACK, "Pair status updated.");
        m.put(BotMessages.PAIR_CHANGED_UNPAIRED, "The organizer changed the pairs. You currently have no partner.");
        m.put(BotMessages.COMMAND_PRIVATE_ONLY, "❌ This command can only be used in a private chat.");
        m.put(BotMessages.CLAIM_USAGE, "❌ Provide the setup code: `/claim <code>`.");
        m.put(BotMessages.CLAIM_SUCCESS, "✅ You are now the organizer.");
        m.put(BotMessages.CLAIM_COMMAND_FAILED, "❌ Could not claim organizer access. Check the code or contact the owner.");
        m.put(BotMessages.OPEN_USAGE, "❌ Usage: `/open`.");
        m.put(BotMessages.OPEN_PENDING_DRAW, "❌ An exchange is active. You cannot open a new registration.");
        m.put(BotMessages.OPEN_ORGANIZER_ONLY, "❌ Only the organizer can open registration.");
        m.put(BotMessages.JOIN_PRIVATE_ONLY, "❌ You can only join in a private chat.");
        m.put(BotMessages.JOIN_USAGE, "❌ Usage: `/join <code> <name>`.");
        m.put(BotMessages.JOIN_NAME_RULES, "❌ The name must contain 1 to 64 characters and no control characters.");
        m.put(BotMessages.REGISTER_HELP, "ℹ️ To join, use `/join <code> <name>`.");
        m.put(BotMessages.EMPTY_ROSTER, "Nobody has registered yet.");
        m.put(BotMessages.ROSTER_HEADER, "📋 **Participants:**\n");
        m.put(BotMessages.NO_COMMAND, "Unknown command. Send /start for instructions.");
        m.put(BotMessages.ORGANIZER_COMMAND_PRIVATE_ONLY, "❌ Organizer commands can only be used in a private chat.");
        m.put(BotMessages.ORGANIZER_COMMAND_ONLY, "❌ This command is available only to the organizer.");
        m.put(BotMessages.ORGANIZER_CHECK_COMMAND_FAILED, "❌ Could not check organizer access. Please try again later.");
        m.put(BotMessages.PAIR_USAGE, "❌ Usage: `/pair`.");
        m.put(BotMessages.PAIR_PENDING_DRAW, "❌ Pairs cannot be changed after the draw begins.");
        m.put(BotMessages.PAIR_PENDING_DRAW_CHECK_FAILED, "❌ Could not check draw status. Please try again later.");
        m.put(BotMessages.RUN_TOO_FEW, "❌ Not enough participants for the draw (at least 2 are needed).");
        m.put(BotMessages.RUN_COMPLETED, "✅ The draw is complete! All participants received their assignments privately.");
        m.put(BotMessages.RUN_NO_ASSIGNMENT, "❌ No valid assignment was found. Check the pairs for this group.");
        m.put(BotMessages.RUN_FAILED, "❌ Could not safely continue the draw. Do not open a new registration; retry `/run` to resume it.");
        m.put(BotMessages.DRAW_RECEIPT_RECORDED, "Receipt confirmed.");
        m.put(BotMessages.DRAW_RECEIPT_ALREADY_RECORDED, "Receipt was already confirmed.");
        m.put(BotMessages.DRAW_RECEIPT_STALE, "This assignment is no longer active.");
        m.put(BotMessages.DRAW_RECEIPT_UNAVAILABLE, "Only participants in the current draw can confirm receipt.");
        m.put(BotMessages.DRAW_RECEIPT_FAILED, "Could not save the confirmation.");
        m.put(BotMessages.DRAW_STATUS_CALLBACK, "Exchange status.");
        m.put(BotMessages.REPLACEMENT_CONFIRMATION, "Run a new draw? Current assignments will be replaced for everyone.");
        m.put(BotMessages.REPLACEMENT_CONFIRMATION_CALLBACK, "Confirm or cancel the new draw.");
        m.put(BotMessages.REPLACEMENT_CANCELLED_CALLBACK, "New draw canceled.");
        m.put(BotMessages.REPLACEMENT_CANCELLED, "New draw canceled.");
        m.put(BotMessages.REPLACEMENT_UNAVAILABLE_CALLBACK, "A new draw is available only after the current draw is fully delivered.");
        m.put(BotMessages.REPLACEMENT_UNAVAILABLE, "❌ Deliver the current assignments to everyone first.");
        m.put(BotMessages.REPLACEMENT_COMPLETED, "✅ The new draw was saved and delivered to everyone.");
        m.put(BotMessages.REPLACEMENT_COMPLETED_CALLBACK, "New draw completed.");
        m.put(BotMessages.REPLACEMENT_PENDING_CALLBACK, "The new draw is awaiting delivery.");
        m.put(BotMessages.REPLACEMENT_TOO_FEW_CALLBACK, "Not enough participants.");
        m.put(BotMessages.REPLACEMENT_FAILED, "❌ Could not safely save or deliver the new draw. If it has started, retry `/run`; otherwise confirm the replacement again.");
        m.put(BotMessages.CLEANUP_CONFIRMATION, "Everyone confirmed receipt. Delete the current exchange data?");
        m.put(BotMessages.CLEANUP_CONFIRMATION_CALLBACK, "Confirm or cancel cleanup.");
        m.put(BotMessages.CLEANUP_CANCELLED_CALLBACK, "Cleanup canceled.");
        m.put(BotMessages.CLEANUP_CANCELLED, "Cleanup canceled. Exchange data was retained.");
        m.put(BotMessages.CLEANUP_UNAVAILABLE_CALLBACK, "Cleanup is available after everyone confirms receipt.");
        m.put(BotMessages.CLEANUP_UNAVAILABLE, "❌ The exchange can be deleted only after everyone confirms receipt.");
        m.put(BotMessages.EXCHANGE_CLEANED_CALLBACK, "Exchange deleted.");
        m.put(BotMessages.EXCHANGE_CLEANED, "✅ Exchange data deleted. You remain the organizer.");
        m.put(BotMessages.CLEANUP_FAILED_CALLBACK, "Could not delete the exchange.");
        m.put(BotMessages.CLEANUP_FAILED, "❌ Could not delete the exchange. Please try again later.");
        m.put(BotMessages.MENU_LOAD_FAILED, "❌ Could not load the menu. Please try again later.");
        m.put(BotMessages.TOO_FEW_FOR_PAIRING, "❌ At least two participants are required to create a pair.");
        m.put(BotMessages.FIRST_PARTICIPANT_PROMPT, "Choose the first participant:");
        m.put(BotMessages.CHAT_NAME_INPUT, "Enter the name in the chat.");
        m.put(BotMessages.FALLBACK_PARTICIPANT_NAME, "Participant");
        m.put(BotMessages.PAIR_CREATED_PREFIX, "✅ Pair created: **");
        m.put(BotMessages.PAIR_CREATED_MIDDLE, "** and **");
        m.put(BotMessages.PAIR_CREATED_SUFFIX, "**.");
        m.put(BotMessages.REPLACED_PAIRS_PREFIX, "\nPrevious pairs removed: ");
        m.put(BotMessages.REPLACED_PAIRS_SUFFIX, ". Former partners are now unpaired.");
        return Map.copyOf(m);
    }
}
