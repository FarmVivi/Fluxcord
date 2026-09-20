package fr.farmvivi.fluxcord.api.command.option;



/**
 * Types of command options supported by the command system.
 * These types correspond to the option types supported by Discord slash commands.
 */
public enum OptionType {
    /**
     * String option type (text input).
     */
    STRING(net.dv8tion.jda.api.interactions.commands.OptionType.STRING),

    /**
     * Integer option type (whole numbers).
     */
    INTEGER(net.dv8tion.jda.api.interactions.commands.OptionType.INTEGER),

    /**
     * Boolean option type (true/false).
     */
    BOOLEAN(net.dv8tion.jda.api.interactions.commands.OptionType.BOOLEAN),

    /**
     * User option type (Discord user).
     */
    USER(net.dv8tion.jda.api.interactions.commands.OptionType.USER),

    /**
     * Channel option type (Discord channel).
     */
    CHANNEL(net.dv8tion.jda.api.interactions.commands.OptionType.CHANNEL),

    /**
     * Role option type (Discord role).
     */
    ROLE(net.dv8tion.jda.api.interactions.commands.OptionType.ROLE),

    /**
     * Mentionable option type (user or role).
     */
    MENTIONABLE(net.dv8tion.jda.api.interactions.commands.OptionType.MENTIONABLE),

    /**
     * Decimal number option type.
     */
    NUMBER(net.dv8tion.jda.api.interactions.commands.OptionType.NUMBER),

    /**
     * File attachment option type.
     */
    ATTACHMENT(net.dv8tion.jda.api.interactions.commands.OptionType.ATTACHMENT);

    private final net.dv8tion.jda.api.interactions.commands.OptionType jdaType;

    OptionType(net.dv8tion.jda.api.interactions.commands.OptionType jdaType) {
        this.jdaType = jdaType;
    }

    /**
     * Converts a JDA option type to our option type.
     *
     * @param jdaType the JDA option type
     * @return the corresponding option type
     */
    public static OptionType fromJdaType(net.dv8tion.jda.api.interactions.commands.OptionType jdaType) {
        for (OptionType type : values()) {
            if (type.getJdaType() == jdaType) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported JDA option type: " + jdaType);
    }

    /**
     * Gets the corresponding JDA option type.
     *
     * @return the JDA option type
     */
    public net.dv8tion.jda.api.interactions.commands.OptionType getJdaType() {
        return jdaType;
    }
}
