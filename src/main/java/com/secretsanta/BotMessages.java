package com.secretsanta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BotMessages {
    static final String PRIVATE_START_ONLY =
            "Відкрий бота в особистому чаті, щоб приєднатися або створити обмін.";
    static final String ORGANIZER_START =
            "🎅 **Таємний Санта**\n\nОбери дію для свого обміну.";
    static final String PARTICIPANT_START =
            "🎅 **Таємний Санта**\n\nСтвори обмін або приєднайся за запрошенням.";
    static final String PARTICIPANT_JOIN_START =
            "🎅 **Таємний Санта**\n\nПриєднайся до обміну за запрошенням.";
    static final String ORGANIZER_CLAIMED = "✅ Права організатора підтверджено.";
    static final String CLAIM_FAILED =
            "❌ Не вдалося підтвердити права організатора. Перевір код або звернись до власника.";
    static final String CLAIM_PERSIST_FAILED =
            "❌ Не вдалося зберегти права організатора. Спробуй пізніше.";
    static final String JOIN_REQUEST_STALE = "Запит на приєднання застарів. Почни з `/start`.";
    static final String INVITE_CLOSED =
            "❌ Реєстрація закрита. Перевір код або звернись до організатора.";
    static final String INVITE_INVALID = "❌ Код запрошення недійсний. Спробуй ще раз.";
    static final String JOIN_NAME_INVALID = "❌ Введи ім'я від 1 до 64 символів.";
    static final String JOIN_NAME_PROMPT = "Введи ім'я, яке побачать учасники.";
    static final String JOIN_INVITE_PROMPT = "Введи запрошувальний код обміну.";
    static final String SETUP_CODE_PROMPT =
            "Введи код налаштування, щоб підтвердити права організатора.";
    static final String PRIVATE_INPUT_CALLBACK = "Введи код у цьому особистому чаті.";
    static final String JOIN_CALLBACK = "Ти приєднався до обміну.";
    static final String ALREADY_REGISTERED_CALLBACK = "Ти вже зареєстрований.";
    static final String INVALID_INVITE_CALLBACK = "Код запрошення недійсний.";
    static final String REGISTRATION_CLOSED_CALLBACK = "Реєстрація закрита.";
    static final String NAME_INVALID_CALLBACK = "Ім'я некоректне.";
    static final String REGISTRATION_FAILED_CALLBACK = "Не вдалося зберегти реєстрацію.";
    static final String JOINED = "✅ Ти приєднався до обміну.";
    static final String ALREADY_REGISTERED = "ℹ️ Ти вже зареєстрований у цьому обміні.";
    static final String INVITE_INVALID_FINAL = "❌ Код запрошення недійсний.";
    static final String REGISTRATION_CLOSED_FINAL = "❌ Реєстрація зараз закрита.";
    static final String REGISTRATION_FAILED = "❌ Не вдалося зберегти реєстрацію. Спробуй пізніше.";
    static final String REGISTRATION_OPEN_CALLBACK = "Реєстрацію відкрито.";
    static final String PENDING_DRAW_CALLBACK = "Є активний обмін.";
    static final String PENDING_DRAW_RETRY =
            "❌ Є активний обмін. Нову реєстрацію відкрити не можна.";
    static final String PROFILE_NAME_CALLBACK = "Не вдалося використати ім'я профілю.";
    static final String PROFILE_NAME_INVALID = "❌ Не вдалося використати ім'я з профілю Telegram.";
    static final String ORGANIZER_ONLY_CALLBACK = "Дія доступна лише організатору.";
    static final String REGISTRATION_OPEN_FAILED_CALLBACK = "Не вдалося відкрити реєстрацію.";
    static final String REGISTRATION_OPEN_FAILED = "❌ Не вдалося відкрити реєстрацію. Спробуй пізніше.";
    static final String RESET_PHASE_CALLBACK = "Скидання доступне лише до початку жеребкування.";
    static final String RESET_PHASE_MESSAGE =
            "❌ Скинути можна лише відкриту реєстрацію до початку жеребкування.";
    static final String RESET_CONFIRMATION =
            "Скинути обмін і видалити поточний список учасників?";
    static final String RESET_CONFIRMATION_CALLBACK = "Підтверди або скасуй скидання.";
    static final String STALE_BUTTON = "Ця кнопка більше неактивна.";
    static final String RESET_CANCELLED_CALLBACK = "Скидання скасовано.";
    static final String RESET_CANCELLED = "Скидання скасовано.";
    static final String ACTION_UNAVAILABLE = "Дія недоступна.";
    static final String EXCHANGE_RESET_CALLBACK = "Обмін скинуто.";
    static final String EXCHANGE_RESET = "✅ Обмін скинуто. Ти залишаєшся організатором.";
    static final String RESET_UNAVAILABLE_CALLBACK =
            "Скидання недоступне після початку жеребкування.";
    static final String RESET_UNAVAILABLE = "❌ Обмін уже закрито; скидання недоступне.";
    static final String RESET_FAILED_CALLBACK = "Не вдалося скинути обмін.";
    static final String RESET_FAILED = "❌ Не вдалося скинути обмін. Спробуй пізніше.";
    static final String ORGANIZER_ONLY_ACTION = "Ця дія доступна лише організатору.";
    static final String ORGANIZER_CHECK_FAILED = "Не вдалося перевірити права організатора.";
    static final String CALLBACK_PRIVATE_ONLY = "Використовуй кнопки лише в особистому чаті.";
    static final String PARTICIPANT_LIST_CHANGED =
            "Список змінився. Почни вибір заново командою `/pair`.";
    static final String PARTICIPANT_LIST_CHANGED_SECOND =
            "Список змінився. Обери партнера з останнього повідомлення.";
    static final String PARTICIPANTS_CHANGED = "Список учасників змінився. Почни з `/pair`.";
    static final String INVALID_PARTICIPANT_SELECTION = "Некоректний вибір учасника.";
    static final String SECOND_PARTICIPANT_PROMPT = "Обери другого учасника.";
    static final String PAIR_UNCHANGED = "ℹ️ Ці учасники вже є парою.";
    static final String PAIR_UNCHANGED_CALLBACK = "Пара не змінилася.";
    static final String PAIR_SAVED_CALLBACK = "Пару збережено.";
    static final String PAIRING_FAILED_CALLBACK = "Не вдалося створити пару.";
    static final String PAIRING_CLOSED = "Не можна змінювати пари після закриття реєстрації.";
    static final String PAIRING_CLOSED_CALLBACK = "Реєстрацію закрито.";
    static final String PAIR_PERSIST_FAILED = "❌ Не вдалося зберегти пару. Спробуй пізніше.";
    static final String PAIR_PERSIST_FAILED_CALLBACK = "Не вдалося зберегти пару.";
    static final String PAIR_REQUEST_CLOSED = "❌ Запити на пару доступні лише під час реєстрації.";
    static final String PAIR_REQUEST_NOT_REGISTERED =
            "❌ Спочатку приєднайся до обміну, щоб надіслати запит на пару.";
    static final String PAIR_REQUEST_ALREADY_PAIRED = "ℹ️ У тебе вже є пара.";
    static final String PAIR_REQUEST_NO_CANDIDATES =
            "Наразі немає вільних учасників, яким можна надіслати запит.";
    static final String PAIR_REQUEST_CHOOSE_PROMPT = "Обери учасника, якому хочеш надіслати запит:";
    static final String PAIR_REQUEST_SENT_CALLBACK = "Запит на пару надіслано.";
    static final String PAIR_REQUEST_RECEIVED_CALLBACK = "Запит на пару.";
    static final String PAIR_REQUEST_STALE = "Цей запит більше неактивний.";
    static final String PAIR_REQUEST_FAILED = "❌ Не вдалося зберегти запит на пару.";
    static final String PAIR_REQUEST_ACCEPTED_CALLBACK = "Пару підтверджено.";
    static final String PAIR_REQUEST_ACCEPTED = "✅ Пару підтверджено: ";
    static final String PAIR_REQUEST_DECLINED_CALLBACK = "Запит відхилено.";
    static final String PAIR_REQUEST_DECLINED = "Запит на пару відхилено.";
    static final String PAIR_REQUEST_CANCELED_CALLBACK = "Запит скасовано.";
    static final String PAIR_REQUEST_CANCELED = "Запит на пару скасовано.";
    static final String PAIR_REQUEST_CANCELED_BY_ORGANIZER =
            "Організатор змінив пари, тому твій запит на пару скасовано.";
    static final String PAIR_REQUEST_NONE = "Немає відкритого запиту на пару.";
    static final String PAIR_STATUS_CALLBACK = "Статус пари оновлено.";
    static final String PAIR_CHANGED_UNPAIRED =
            "Організатор змінив пари. Наразі ти без пари.";
    static final String COMMAND_PRIVATE_ONLY = "❌ Команду можна виконати лише в особистому чаті.";
    static final String CLAIM_USAGE = "❌ Вкажи код налаштування: `/claim <код>`.";
    static final String CLAIM_SUCCESS = "✅ Тепер ти організатор.";
    static final String CLAIM_COMMAND_FAILED =
            "❌ Не вдалося отримати права організатора. Перевір код або звернись до власника.";
    static final String OPEN_USAGE = "❌ Використання: `/open`.";
    static final String OPEN_PENDING_DRAW =
            "❌ Є активний обмін. Нову реєстрацію відкрити не можна.";
    static final String OPEN_ORGANIZER_ONLY = "❌ Лише організатор може відкрити реєстрацію.";
    static final String JOIN_PRIVATE_ONLY = "❌ Приєднатися можна лише в особистому чаті.";
    static final String JOIN_USAGE = "❌ Використання: `/join <код> <ім'я>`.";
    static final String JOIN_NAME_RULES =
            "❌ Ім'я має містити від 1 до 64 символів без керівних символів.";
    static final String REGISTER_HELP =
            "ℹ️ Щоб приєднатися, скористайся `/join <код> <ім'я>`.";
    static final String EMPTY_ROSTER = "Поки що ніхто не зареєструвався.";
    static final String ROSTER_HEADER = "📋 **Список учасників:**\n";
    static final String NO_COMMAND = "Невідома команда. Напиши /start для інструкції.";
    static final String ORGANIZER_COMMAND_PRIVATE_ONLY =
            "❌ Команду організатора можна виконати лише в особистому чаті.";
    static final String ORGANIZER_COMMAND_ONLY = "❌ Ця команда доступна лише організатору.";
    static final String ORGANIZER_CHECK_COMMAND_FAILED =
            "❌ Не вдалося перевірити права організатора. Спробуй пізніше.";
    static final String PAIR_USAGE = "❌ Використання: `/pair`.";
    static final String PAIR_PENDING_DRAW =
            "❌ Після початку розподілу змінювати пари не можна.";
    static final String PAIR_PENDING_DRAW_CHECK_FAILED =
            "❌ Не вдалося перевірити стан розподілу. Спробуй пізніше.";
    static final String RUN_TOO_FEW = "❌ Замало учасників для розподілу (потрібно мінімум 2).";
    static final String RUN_COMPLETED =
            "✅ Розподіл успішно завершено! Усі учасники отримали свої цілі в особисті чати.";
    static final String RUN_NO_ASSIGNMENT =
            "❌ Не вдалося знайти комбінацію. Перевір пари: для цієї групи немає допустимого розподілу.";
    static final String RUN_FAILED =
            "❌ Не вдалося безпечно продовжити розподіл. Не відкривай нову реєстрацію; "
                    + "повтори `/run`, щоб відновити поточний розподіл.";
    static final String DRAW_RECEIPT_RECORDED = "Отримання підтверджено.";
    static final String DRAW_RECEIPT_ALREADY_RECORDED = "Отримання вже підтверджено.";
    static final String DRAW_RECEIPT_STALE = "Це завдання більше неактивне.";
    static final String DRAW_RECEIPT_UNAVAILABLE =
            "Підтвердження доступне лише учасникам поточного розподілу.";
    static final String DRAW_RECEIPT_FAILED = "Не вдалося зберегти підтвердження.";
    static final String DRAW_STATUS_CALLBACK = "Статус обміну.";
    static final String REPLACEMENT_CONFIRMATION =
            "Провести нове жеребкування? Поточні завдання буде замінено для всіх учасників.";
    static final String REPLACEMENT_CONFIRMATION_CALLBACK =
            "Підтверди або скасуй нове жеребкування.";
    static final String REPLACEMENT_CANCELLED_CALLBACK = "Нове жеребкування скасовано.";
    static final String REPLACEMENT_CANCELLED = "Нове жеребкування скасовано.";
    static final String REPLACEMENT_UNAVAILABLE_CALLBACK =
            "Нове жеребкування доступне лише після повної доставки поточного.";
    static final String REPLACEMENT_UNAVAILABLE =
            "❌ Спочатку потрібно доставити поточні завдання всім учасникам.";
    static final String REPLACEMENT_COMPLETED =
            "✅ Новий розподіл збережено й доставлено всім учасникам.";
    static final String REPLACEMENT_COMPLETED_CALLBACK = "Нове жеребкування завершено.";
    static final String REPLACEMENT_PENDING_CALLBACK = "Нове жеребкування очікує доставки.";
    static final String REPLACEMENT_TOO_FEW_CALLBACK = "Недостатньо учасників.";
    static final String REPLACEMENT_FAILED =
            "❌ Не вдалося безпечно зберегти або доставити новий розподіл. Якщо новий "
                    + "розподіл уже почався, повтори `/run`; інакше спробуй підтвердити заміну ще раз.";
    static final String CLEANUP_CONFIRMATION =
            "Усі учасники підтвердили отримання. Видалити дані поточного обміну?";
    static final String CLEANUP_CONFIRMATION_CALLBACK = "Підтверди або скасуй очищення.";
    static final String CLEANUP_CANCELLED_CALLBACK = "Очищення скасовано.";
    static final String CLEANUP_CANCELLED = "Очищення скасовано. Дані обміну збережено.";
    static final String CLEANUP_UNAVAILABLE_CALLBACK =
            "Очищення доступне після підтвердження всіма учасниками.";
    static final String CLEANUP_UNAVAILABLE =
            "❌ Обмін можна видалити лише після того, як усі учасники підтвердять отримання.";
    static final String EXCHANGE_CLEANED_CALLBACK = "Обмін видалено.";
    static final String EXCHANGE_CLEANED =
            "✅ Дані обміну видалено. Ти залишаєшся організатором.";
    static final String CLEANUP_FAILED_CALLBACK = "Не вдалося видалити обмін.";
    static final String CLEANUP_FAILED = "❌ Не вдалося видалити обмін. Спробуй пізніше.";
    static final String MENU_LOAD_FAILED = "❌ Не вдалося завантажити меню. Спробуй пізніше.";
    static final String TOO_FEW_FOR_PAIRING =
            "❌ Для створення пари потрібно щонайменше двоє учасників.";
    static final String FIRST_PARTICIPANT_PROMPT = "Обери першого учасника:";
    static final String CHAT_NAME_INPUT = "Введи ім'я в чаті.";
    static final String FALLBACK_PARTICIPANT_NAME = "Учасник";
    static final String PAIR_CREATED_PREFIX = "✅ Створено пару: **";
    static final String PAIR_CREATED_MIDDLE = "** та **";
    static final String PAIR_CREATED_SUFFIX = "**.";
    static final String REPLACED_PAIRS_PREFIX = "\nПопередні пари видалено: ";
    static final String REPLACED_PAIRS_SUFFIX =
            ". Звільнені учасники більше не мають пари.";

    private BotMessages() {}

    static String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    static String joinConfirmation(String escapedName) {
        return "Приєднатися до обміну як **" + escapedName + "**?";
    }

    static String participantJoined(String name) {
        return "🎉 **" + escapeMarkdown(name) + "** приєднався до обміну.";
    }

    static String registrationOpened(String inviteCode) {
        return "✅ Реєстрацію відкрито.\nЗапрошувальний код: `" + inviteCode + "`";
    }

    static String partnerSelection(String escapedName) {
        return "Обери партнера для " + escapedName + ":";
    }

    static String openPendingDeliveries(int count) {
        return "⚠️ Не вдалося доставити результати " + count
                + " учасникам. Розподіл збережено; повтори `/run`, "
                + "щоб повторити доставку без нового жеребкування.";
    }

    static String drawResult(PendingDrawService.Result result) {
        return switch (result.status()) {
            case TOO_FEW_PARTICIPANTS -> RUN_TOO_FEW;
            case PENDING -> openPendingDeliveries(result.failedDeliveries());
            case COMPLETED -> RUN_COMPLETED;
        };
    }

    static String replacementDrawResult(PendingDrawService.Result result) {
        return switch (result.status()) {
            case TOO_FEW_PARTICIPANTS -> RUN_TOO_FEW;
            case PENDING -> openPendingDeliveries(result.failedDeliveries());
            case COMPLETED -> REPLACEMENT_COMPLETED;
        };
    }

    static String replacementDrawCallback(PendingDrawService.Result result) {
        return switch (result.status()) {
            case TOO_FEW_PARTICIPANTS -> REPLACEMENT_TOO_FEW_CALLBACK;
            case PENDING -> REPLACEMENT_PENDING_CALLBACK;
            case COMPLETED -> REPLACEMENT_COMPLETED_CALLBACK;
        };
    }

    static String drawStatus(ExchangeState state) {
        int participantCount = state.participants().size();
        StringBuilder message = new StringBuilder("📊 **Статус обміну**\n")
                .append("Доставлено: ")
                .append(state.deliveredUserIds().size())
                .append("/")
                .append(participantCount)
                .append("\nПідтверджено: ")
                .append(state.confirmedUserIds().size())
                .append("/")
                .append(participantCount)
                .append("\n");
        state.participants().forEach(participant -> {
            String status = state.confirmedUserIds().contains(participant.userId())
                    ? "підтвердив отримання"
                    : state.deliveredUserIds().contains(participant.userId())
                            ? "очікує підтвердження"
                            : "очікує доставку";
            message.append("• ")
                    .append(escapeMarkdown(participant.name()))
                    .append(" — ")
                    .append(status)
                    .append("\n");
        });
        return message.toString();
    }

    static String pairRequestReceived(String escapedRequesterName) {
        return "Тобі надійшов запит на пару від **" + escapedRequesterName + "**.";
    }

    static String pairRequestStatus(String escapedOtherName, boolean outgoing) {
        return (outgoing ? "Твій запит очікує на відповідь від **"
                : "Запит від **") + escapedOtherName + "** очікує на твою відповідь.";
    }

    static String pairAccepted(String escapedPartnerName) {
        return PAIR_REQUEST_ACCEPTED + "**" + escapedPartnerName + "**.";
    }

    static String pairStatusPaired(String escapedPartnerName) {
        return "Твоя пара: **" + escapedPartnerName + "**.";
    }

    static String pairChanged(String escapedPartnerName) {
        return "Організатор змінив пари. Твоя поточна пара: **"
                + escapedPartnerName + "**.";
    }

    static String participantRoster(
            List<SecretSantaBot.Participant> participants,
            List<ExchangeState.PairRequest> pairRequests) {
        Map<Long, SecretSantaBot.Participant> participantsById = new HashMap<>();
        participants.forEach(participant ->
                participantsById.put(participant.chatId(), participant));
        Map<Long, ExchangeState.PairRequest> requestsByParticipant = new HashMap<>();
        pairRequests.forEach(request -> {
            requestsByParticipant.put(request.requesterId(), request);
            requestsByParticipant.put(request.requestedPartnerId(), request);
        });

        StringBuilder message = new StringBuilder(ROSTER_HEADER);
        participants.forEach(participant -> {
            String status;
            if (participant.partnerId() != null) {
                status = "пара: `" + escapeMarkdown(
                        participantsById.get(participant.partnerId()).name()) + "`";
            } else {
                ExchangeState.PairRequest request =
                        requestsByParticipant.get(participant.chatId());
                if (request == null) {
                    status = "без пари";
                } else {
                    long otherId = request.requesterId() == participant.chatId()
                            ? request.requestedPartnerId()
                            : request.requesterId();
                    String direction = request.requesterId() == participant.chatId()
                            ? "запит до "
                            : "запит від ";
                    status = direction + "`"
                            + escapeMarkdown(participantsById.get(otherId).name()) + "`";
                }
            }
            message.append(participantRosterRow(
                    escapeMarkdown(participant.name()), status));
        });
        return message.toString();
    }

    static String participantRosterRow(String escapedName, String status) {
        return "• " + escapedName + " (" + status + ")\n";
    }

    static String pairingChange(String escapedFirstName, String escapedSecondName,
            java.util.List<String> escapedReplacedPairs) {
        StringBuilder message = new StringBuilder(PAIR_CREATED_PREFIX)
                .append(escapedFirstName)
                .append(PAIR_CREATED_MIDDLE)
                .append(escapedSecondName)
                .append(PAIR_CREATED_SUFFIX);
        if (!escapedReplacedPairs.isEmpty()) {
            message.append(REPLACED_PAIRS_PREFIX)
                    .append(String.join("; ", escapedReplacedPairs))
                    .append(REPLACED_PAIRS_SUFFIX);
        }
        return message.toString();
    }
}
