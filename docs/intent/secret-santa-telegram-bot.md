# Intent: Secret Santa Telegram Bot

## Outcome
Build a button-driven Telegram Secret Santa bot for one friend group's exchange,
using private chats for setup, participation, and assignment delivery.

## Users
The organizer opens one exchange and is automatically registered as a participant.
Friends start the bot, enter the exchange's invitation code, and confirm or edit
the first name the bot receives from their Telegram profile. Telegram identity is
available only after someone presses Start or sends the bot a message.

## Why now
Christmas is next month, and building a tool tailored to the group would be fun.

## Success
The organizer shares a private invitation code, which remains valid until the
draw starts. Participants can propose a partner; that person must accept before
the pair is active. The organizer can review and directly change pairings.

When ready, the organizer starts the assignment from a button. The bot closes
registration and privately messages each participant, including the organizer,
only their own recipient. Every participant gives to and receives from exactly
one person; nobody draws themselves or their partner.

The bot must not show or log the full giver-to-recipient mapping to the organizer.
The organizer trusts the running application and its host; this is not protection
against someone who can inspect the bot process or its secrets.

An open exchange, its pairs, pending requests, current assignments, delivery
progress, and participant confirmations survive restarts. Before assignment,
the organizer can reset an exchange after an explicit confirmation. After a draw
is delivered, the organizer can request a complete replacement draw before
cleanup; everyone receives the new assignment and prior results are superseded.
Each participant confirms receipt with a private button. The organizer deletes
the retained exchange only after every participant confirms and the organizer
confirms deletion.

## Constraint
Avoid sharing participant details with a separate Secret Santa website. Telegram is
acceptable. Keep the first version focused on our group and easy to extend later.
Use private chats; the bot cannot identify someone who only opens its profile.

## Out of scope for the first version
- Email delivery
- A public multi-group service
- Multiple concurrent exchanges
- Group-chat workflow
- Arbitrary non-partner exclusions
