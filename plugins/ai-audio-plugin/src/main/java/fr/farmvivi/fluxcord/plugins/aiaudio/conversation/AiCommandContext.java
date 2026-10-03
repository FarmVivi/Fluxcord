package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.Event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A command invocation made on somebody's behalf, because they asked for it out loud.
 *
 * <p>This is the whole trick of letting the bot run commands, and it is deliberately not a trick: there is no
 * synthetic JDA event and no second execution path. The context carries the real asker, the real guild, the
 * real channel and the parsed options, and is handed to {@code CommandService.executeCommand} — the same
 * pipeline a slash command goes through. Permissions, {@code guildOnly}, cooldowns and the cancellable
 * {@code CommandExecuteEvent} therefore all apply, <strong>with the asker's rights and not the bot's</strong>.
 * A person who may not skip a track cannot get the bot to skip it for them.
 *
 * <p>Two consequences worth stating. {@link #getUser()} returns the asker and is never null: a null user is
 * what the core reads as "console, trusted", which would skip every check and is exactly the opposite of what
 * this is for. And {@link #getOriginalEvent()} returns null, because no event happened — which is why
 * {@link CommandContext#getMember()} exists and why commands read the caller from the context instead of
 * pattern-matching an event.
 *
 * <p>Replies are <strong>captured, not sent</strong>. The command believes it answered; what it wrote comes
 * back through {@link #replies()} and is handed to the model as the result of its call, so the bot can say
 * out loud what happened rather than leaving a wall of text in a channel nobody is reading. Embeds are
 * reduced to their title and description for the same reason: the model is going to speak this.
 */
public class AiCommandContext implements CommandContext {

    /** How much of one reply is kept. A spoken answer cannot carry a queue of forty tracks. */
    static final int MAX_REPLY_LENGTH = 500;

    private final Command command;
    private final Member member;
    private final MessageChannel channel;
    private final Locale locale;
    private final Map<String, Object> options;
    private final List<String> replies = new ArrayList<>();
    private boolean deferred;
    private boolean ephemeral;

    /**
     * @param command the command about to run, as the pipeline reports it in its events
     * @param member  who asked for it; their permissions are the ones that count
     * @param channel the text channel the conversation was started from, for commands that keep one
     * @param locale  the language to answer in
     * @param options the option values, already converted to the types the command's options declare
     */
    public AiCommandContext(Command command, Member member, MessageChannel channel, Locale locale,
                            Map<String, Object> options) {
        this.command = command;
        this.member = member;
        this.channel = channel;
        this.locale = locale;
        this.options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** @return everything the command said, in order; empty when it answered nothing */
    public List<String> replies() {
        return List.copyOf(replies);
    }

    /**
     * @return null: nothing happened on Discord, and a command that needs the caller asks
     *         {@link #getMember()} instead
     */
    @Override
    public Event getOriginalEvent() {
        return null;
    }

    @Override
    public Command getCommand() {
        return command;
    }

    @Override
    public User getUser() {
        return member.getUser();
    }

    @Override
    public Optional<Guild> getGuild() {
        return Optional.of(member.getGuild());
    }

    @Override
    public Optional<Member> getMember() {
        return Optional.of(member);
    }

    @Override
    public MessageChannel getChannel() {
        return channel;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> getOption(String name) {
        try {
            return Optional.ofNullable((T) options.get(name));
        } catch (ClassCastException e) {
            // The model wrote the value; a command asking for it as another type gets nothing rather
            // than an exception thrown from inside its own executor.
            return Optional.empty();
        }
    }

    @Override
    public <T> T getOption(String name, T defaultValue) {
        return this.<T>getOption(name).orElse(defaultValue);
    }

    @Override
    public <T> T getRequiredOption(String name) {
        return this.<T>getOption(name).orElseThrow(
                () -> new IllegalArgumentException("Required option not provided: " + name));
    }

    @Override
    public <T> Optional<CommandOption<T>> getOptionDefinition(String name) {
        if (command == null) {
            return Optional.empty();
        }
        return command.getOptions().stream()
                .filter(option -> option.getName().equals(name))
                .findFirst()
                .map(option -> {
                    @SuppressWarnings("unchecked")
                    CommandOption<T> typed = (CommandOption<T>) option;
                    return typed;
                });
    }

    @Override
    public boolean hasOption(String name) {
        return options.containsKey(name);
    }

    @Override
    public void reply(String message) {
        capture(message);
    }

    @Override
    public void reply(String message, Collection<MessageTopLevelComponent> components) {
        // The components are dropped on purpose: a spoken answer has no buttons.
        capture(message);
    }

    @Override
    public void replyEmbed(EmbedBuilder embed) {
        capture(flatten(embed));
    }

    @Override
    public void replyEmbed(EmbedBuilder embed, Collection<MessageTopLevelComponent> components) {
        capture(flatten(embed));
    }

    @Override
    public void replySuccess(String message) {
        capture(message);
    }

    @Override
    public void replyInfo(String message) {
        capture(message);
    }

    @Override
    public void replyWarning(String message) {
        capture(message);
    }

    @Override
    public void replyError(String message) {
        capture(message);
    }

    @Override
    public void deferReply() {
        deferred = true;
    }

    @Override
    public void deferReply(boolean ephemeral) {
        this.deferred = true;
        this.ephemeral = ephemeral;
    }

    @Override
    public boolean isDeferred() {
        return deferred;
    }

    @Override
    public boolean hasReplied() {
        return !replies.isEmpty();
    }

    @Override
    public boolean isEphemeral() {
        return ephemeral;
    }

    @Override
    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }

    @Override
    public JDA getJDA() {
        return member.getJDA();
    }

    private void capture(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String trimmed = message.strip();
        replies.add(trimmed.length() > MAX_REPLY_LENGTH
                ? trimmed.substring(0, MAX_REPLY_LENGTH) + "..."
                : trimmed);
    }

    /** An embed as a sentence: its title and its description, which is all a voice can carry. */
    private static String flatten(EmbedBuilder embed) {
        if (embed == null) {
            return "";
        }
        var built = embed.isEmpty() ? null : embed.build();
        if (built == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (built.getTitle() != null) {
            out.append(built.getTitle());
        }
        if (built.getDescription() != null) {
            if (!out.isEmpty()) {
                out.append(" — ");
            }
            out.append(built.getDescription());
        }
        return out.toString();
    }
}
