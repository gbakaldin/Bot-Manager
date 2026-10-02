package com.vingame.bot.domain.botgroup.service;

/**
 * Published by {@link RegistrationWorker} when a group's registration completes against the
 * document's own {@code botCount} (BOT_PROVISIONING AD-12).
 * <p>
 * Carries only the id on purpose: the listener ({@code BotGroupBehaviorService}) re-reads the
 * group under its own lock, because by the time the event is handled a {@code /stop}, a lowered
 * {@code botCount} or a restart may already have landed. An event rather than a direct call so the
 * worker takes no new bean dependency on the lifecycle service.
 *
 * @param botGroupId the group whose registration just completed
 */
public record RegistrationCompletedEvent(String botGroupId) {
}
