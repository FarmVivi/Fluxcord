package fr.farmvivi.fluxcord.core.command.reply;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.io.PrintStream;

/**
 * Console commands: the message is rendered as {@code [CONSOLE] } prefixed lines — content first, then each
 * embed as {@code === title ===}, its description and {@code name: value} fields; ephemeral and deferral mean
 * nothing here. An empty message prints nothing.
 */
public final class ConsoleReplyTarget implements ReplyTarget {
    private static final String PREFIX = "[CONSOLE] ";

    private final PrintStream out;

    public ConsoleReplyTarget(PrintStream out) {
        this.out = out;
    }

    @Override
    public void send(MessageCreateData message, boolean ephemeral, boolean deferred) {
        if (InteractionReplyTarget.isEmpty(message)) {
            return;
        }
        out.println(render(message));
    }

    /** The plain-text rendering, one {@code [CONSOLE]} line per text line, embeds separated by a blank line. */
    public static String render(MessageCreateData message) {
        StringBuilder output = new StringBuilder();
        if (!message.getContent().isBlank()) {
            appendBlock(output, message.getContent());
        }
        for (MessageEmbed embed : message.getEmbeds()) {
            if (!output.isEmpty()) {
                output.append('\n');
            }
            if (embed.getTitle() != null) {
                appendLine(output, "=== " + embed.getTitle() + " ===");
            }
            if (embed.getDescription() != null) {
                appendBlock(output, embed.getDescription());
            }
            for (MessageEmbed.Field field : embed.getFields()) {
                String name = field.getName() != null ? field.getName() + ": " : "";
                String[] lines = field.getValue() == null ? new String[]{""} : field.getValue().split("\\R", -1);
                appendLine(output, name + lines[0]);
                for (int i = 1; i < lines.length; i++) {
                    appendLine(output, lines[i]);
                }
            }
        }
        return output.toString();
    }

    private static void appendBlock(StringBuilder output, String text) {
        for (String line : text.split("\\R", -1)) {
            appendLine(output, line);
        }
    }

    private static void appendLine(StringBuilder output, String line) {
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append(PREFIX).append(line);
    }
}
