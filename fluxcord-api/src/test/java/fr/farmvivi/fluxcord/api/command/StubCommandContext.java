package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.Event;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * The smallest context that exercises {@link CommandContext}'s default methods: four fields, everything
 * else refused.
 *
 * <p>Shared by the tests of those defaults, because each of them needs the whole interface implemented to
 * reach one method of it, and a second copy of twenty empty overrides is a second place to update.
 *
 * @param event   what {@link CommandContext#getOriginalEvent()} answers
 * @param user    the caller, or null for a console invocation
 * @param guild   the server, or null outside one
 * @param channel what {@link CommandContext#getChannel()} answers
 */
record StubCommandContext(Event event, User user, Guild guild, MessageChannel channel)
        implements CommandContext {

    @Override
    public Event getOriginalEvent() {
        return event;
    }

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public Optional<Guild> getGuild() {
        return Optional.ofNullable(guild);
    }

    @Override
    public MessageChannel getChannel() {
        return channel;
    }

    @Override
    public Command getCommand() {
        return null;
    }

    @Override
    public Locale getLocale() {
        return Locale.ROOT;
    }

    @Override
    public <T> Optional<T> getOption(String name) {
        return Optional.empty();
    }

    @Override
    public <T> T getOption(String name, T defaultValue) {
        return defaultValue;
    }

    @Override
    public <T> T getRequiredOption(String name) {
        throw new IllegalArgumentException(name);
    }

    @Override
    public <T> Optional<CommandOption<T>> getOptionDefinition(String name) {
        return Optional.empty();
    }

    @Override
    public boolean hasOption(String name) {
        return false;
    }

    @Override
    public void reply(String message) {
    }

    @Override
    public void reply(String message, Collection<MessageTopLevelComponent> components) {
    }

    @Override
    public void replyEmbed(EmbedBuilder embed) {
    }

    @Override
    public void replyEmbed(EmbedBuilder embed, Collection<MessageTopLevelComponent> components) {
    }

    @Override
    public void replySuccess(String message) {
    }

    @Override
    public void replyInfo(String message) {
    }

    @Override
    public void replyWarning(String message) {
    }

    @Override
    public void replyError(String message) {
    }

    @Override
    public void deferReply() {
    }

    @Override
    public void deferReply(boolean ephemeral) {
    }

    @Override
    public boolean isDeferred() {
        return false;
    }

    @Override
    public boolean isEphemeral() {
        return false;
    }

    @Override
    public void setEphemeral(boolean ephemeral) {
    }

    @Override
    public JDA getJDA() {
        return null;
    }
}
