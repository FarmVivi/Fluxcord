package fr.farmvivi.fluxcord.plugins.music.playlist;

import java.util.Locale;

/**
 * Who owns a saved playlist: a single user (available to them on every server) or a whole guild
 * (shared by everyone on that server).
 */
public enum PlaylistScope {
    /** Personal playlist, stored in the user scope of the data storage. */
    USER("personal"),
    /** Server playlist, stored in the guild scope of the data storage. */
    GUILD("server");

    private final String commandValue;

    PlaylistScope(String commandValue) {
        this.commandValue = commandValue;
    }

    /**
     * Parses the value of the {@code scope} command option.
     *
     * @param value        the user-supplied value, may be {@code null}
     * @param defaultScope the scope to use when the value is absent or unknown
     * @return the matching scope
     */
    public static PlaylistScope parse(String value, PlaylistScope defaultScope) {
        if (value == null) {
            return defaultScope;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "personal", "user", "me", "perso" -> USER;
            case "server", "guild" -> GUILD;
            default -> defaultScope;
        };
    }

    /** @return the value used by the {@code scope} command option */
    public String getCommandValue() {
        return commandValue;
    }
}
