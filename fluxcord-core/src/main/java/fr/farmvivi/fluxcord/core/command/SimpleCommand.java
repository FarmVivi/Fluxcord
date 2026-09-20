package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Basic implementation of the Command interface.
 * This record provides all the required functionality for a command.
 */
public record SimpleCommand(
        String name,
        String description,
        String category,
        List<CommandOption<?>> options,
        List<Command> subcommands,
        String group,
        String permission,
        String translationKey,
        Set<String> aliases,
        boolean guildOnly,
        Set<String> guildIds,
        boolean isSubcommand,
        ParentLink parent,
        boolean enabled,
        int cooldown,
        BiFunction<CommandContext, Command, CommandResult> executor
) implements Command {

    /**
     * Creates a new SimpleCommand with the provided parameters.
     */
    public SimpleCommand {
        // Ensure immutable collections
        options = options != null ? List.copyOf(options) : List.of();
        subcommands = subcommands != null ? List.copyOf(subcommands) : List.of();
        aliases = aliases != null ? Set.copyOf(aliases) : Set.of();
        guildIds = guildIds != null ? Set.copyOf(guildIds) : Set.of();
        parent = parent != null ? parent : new ParentLink();

        // Default values for null parameters
        if (category == null) category = "General";
        if (translationKey == null) translationKey = "command." + name;

        // Validation
        Objects.requireNonNull(name, "Command name cannot be null");
        Objects.requireNonNull(description, "Command description cannot be null");

        // No executor is allowed for commands with subcommands
        if (!subcommands.isEmpty() && executor != null) {
            throw new IllegalArgumentException("Commands with subcommands cannot have an executor");
        }

        // Commands without subcommands must have an executor
        if (subcommands.isEmpty() && executor == null) {
            throw new IllegalArgumentException("Commands without subcommands must have an executor");
        }
    }

    /**
     * Copy of this command with another enabled flag (records are immutable).
     */
    public SimpleCommand withEnabled(boolean enabled) {
        if (enabled == this.enabled) {
            return this;
        }
        return new SimpleCommand(name, description, category, options, subcommands, group, permission,
                translationKey, aliases, guildOnly, guildIds, isSubcommand, parent, enabled, cooldown, executor);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public List<CommandOption<?>> getOptions() {
        return options;
    }

    @Override
    public List<Command> getSubcommands() {
        return subcommands;
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public String getPermission() {
        return permission;
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }

    @Override
    public Set<String> getAliases() {
        return aliases;
    }

    @Override
    public boolean isGuildOnly() {
        return guildOnly;
    }

    @Override
    public Set<String> getGuildIds() {
        return guildIds;
    }

    @Override
    public boolean isSubcommand() {
        return isSubcommand;
    }

    @Override
    public Command getParent() {
        return parent.get();
    }

    /**
     * Mutable link from a subcommand to its parent. A parent and its subcommands reference each other, which an
     * immutable record cannot express: the subcommands are built first with an empty link, then the builder points
     * the link at the parent once it exists. Copies made by {@link #withEnabled(boolean)} share the link.
     */
    public static final class ParentLink {
        private volatile Command command;

        public Command get() {
            return command;
        }

        void set(Command command) {
            this.command = command;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getCooldown() {
        return cooldown;
    }

    @Override
    public CommandResult execute(CommandContext context) {
        if (executor == null) {
            return CommandResult.error("This command cannot be executed directly");
        }
        return executor.apply(context, this);
    }

    /**
     * Builder for creating SimpleCommand instances.
     */
    public static class Builder {
        private final List<CommandOption<?>> options = new ArrayList<>();
        private final List<Command> subcommands = new ArrayList<>();
        private final Set<String> aliases = new HashSet<>();
        private final Set<String> guildIds = new HashSet<>();
        private String name;
        private String description;
        private String category = "General";
        private String group;
        private String permission;
        private String translationKey;
        private boolean guildOnly = false;
        private boolean isSubcommand = false;
        private ParentLink parent;
        private boolean enabled = true;
        private int cooldown = 0;
        private BiFunction<CommandContext, Command, CommandResult> executor;

        /**
         * Sets the name of the command.
         *
         * @param name the command name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the description of the command.
         *
         * @param description the command description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets the category of the command.
         *
         * @param category the command category
         * @return this builder
         */
        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /**
         * Adds an option to the command.
         *
         * @param option the option to add
         * @return this builder
         */
        public Builder option(CommandOption<?> option) {
            this.options.add(option);
            return this;
        }

        /**
         * Adds a subcommand to the command.
         *
         * @param subcommand the subcommand to add
         * @return this builder
         */
        public Builder subcommand(Command subcommand) {
            this.subcommands.add(subcommand);
            return this;
        }

        /**
         * Sets the group of the command.
         *
         * @param group the command group
         * @return this builder
         */
        public Builder group(String group) {
            this.group = group;
            return this;
        }

        /**
         * Sets the permission required to execute the command.
         *
         * @param permission the permission string
         * @return this builder
         */
        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /**
         * Sets the translation key prefix for this command.
         *
         * @param translationKey the translation key prefix
         * @return this builder
         */
        public Builder translationKey(String translationKey) {
            this.translationKey = translationKey;
            return this;
        }

        /**
         * Adds an alias for the command.
         *
         * @param alias the command alias
         * @return this builder
         */
        public Builder alias(String alias) {
            this.aliases.add(alias);
            return this;
        }

        /**
         * Adds multiple aliases for the command.
         *
         * @param aliases the command aliases
         * @return this builder
         */
        public Builder aliases(String... aliases) {
            Collections.addAll(this.aliases, aliases);
            return this;
        }

        /**
         * Sets whether the command is guild-only.
         *
         * @param guildOnly true if the command can only be executed in a guild
         * @return this builder
         */
        public Builder guildOnly(boolean guildOnly) {
            this.guildOnly = guildOnly;
            return this;
        }

        /**
         * Adds a guild ID where this command is available.
         *
         * @param guildId the guild ID
         * @return this builder
         */
        public Builder guildId(String guildId) {
            this.guildIds.add(guildId);
            return this;
        }

        /**
         * Specifies multiple guilds where this command is available.
         *
         * @param guildIds the guild IDs
         * @return this builder
         */
        public Builder guildIds(String... guildIds) {
            Collections.addAll(this.guildIds, guildIds);
            return this;
        }

        /**
         * Sets whether this command is a subcommand.
         *
         * @param isSubcommand true if this command is a subcommand
         * @return this builder
         */
        public Builder subcommand(boolean isSubcommand) {
            this.isSubcommand = isSubcommand;
            return this;
        }

        /**
         * Sets the parent command if this is a subcommand.
         *
         * @param parent the parent command
         * @return this builder
         */
        public Builder parent(Command parent) {
            this.parent = new ParentLink();
            this.parent.set(parent);
            this.isSubcommand = parent != null;
            return this;
        }

        /**
         * Sets whether this command is enabled by default.
         *
         * @param enabled true if the command is enabled by default
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the cooldown of the command in seconds.
         *
         * @param cooldown the cooldown in seconds
         * @return this builder
         */
        public Builder cooldown(int cooldown) {
            this.cooldown = cooldown;
            return this;
        }

        /**
         * Sets the command executor.
         *
         * @param executor the command executor
         * @return this builder
         */
        public Builder executor(BiFunction<CommandContext, Command, CommandResult> executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Builds the command.
         *
         * @return the built command
         */
        public SimpleCommand build() {
            if (name == null) {
                throw new IllegalArgumentException("Command name is required");
            }
            if (description == null) {
                throw new IllegalArgumentException("Command description is required");
            }

            if (translationKey == null) {
                translationKey = "command." + name;
            }

            return new SimpleCommand(
                    name, description, category, options, subcommands, group,
                    permission, translationKey, aliases, guildOnly, guildIds,
                    isSubcommand, parent, enabled, cooldown, executor
            );
        }
    }
}
