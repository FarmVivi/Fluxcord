package fr.farmvivi.fluxcord.api.command.option;

import java.util.List;

import java.util.function.Predicate;

/**
 * Represents a command option with typed value.
 * Options are used for both slash commands and text commands.
 *
 * @param <T> the type of the option value
 */
public interface CommandOption<T> {

    /**
     * Gets the name of the option.
     *
     * @return the option name
     */
    String getName();

    /**
     * Gets the description of the option.
     *
     * @return the option description
     */
    String getDescription();

    /**
     * Gets the type of the option.
     *
     * @return the option type
     */
    OptionType2 getType();

    /**
     * Checks if the option is required.
     *
     * @return true if the option is required
     */
    boolean isRequired();

    /**
     * Gets the available choices for this option.
     * These are used for autocompletion and validation.
     *
     * @return the list of choices, or an empty list if no choices are available
     */
    List<OptionChoice<T>> getChoices();

    /**
     * Gets the validator for this option.
     * The validator is used to check if a value is valid for this option.
     *
     * @return the validator, or null if no validation is needed
     */
    Predicate<T> getValidator();

    /**
     * Gets the autocomplete provider for this option.
     * This function is called when the user is typing an option value and
     * returns a list of choices based on the partial input.
     *
     * @return the autocomplete provider, or null if autocomplete is not supported
     */
    AutocompleteProvider<T> getAutocompleteProvider();

    /**
     * Gets the minimum value for number-based options.
     *
     * @return the minimum value, or null if not applicable
     */
    Number getMinValue();

    /**
     * Gets the maximum value for number-based options.
     *
     * @return the maximum value, or null if not applicable
     */
    Number getMaxValue();

    /**
     * Gets the minimum length for string options.
     *
     * @return the minimum length, or null if not applicable
     */
    Integer getMinLength();

    /**
     * Gets the maximum length for string options.
     *
     * @return the maximum length, or null if not applicable
     */
    Integer getMaxLength();

    /**
     * Gets the accepted file types for attachment options.
     * <p>
     * Each entry is either one of the generic categories {@code image}, {@code video}, {@code audio},
     * or a file extension without the leading dot (e.g. {@code svg}, {@code tar.gz}).
     * Discord enforces the filter client-side when the user picks a file (max 10 entries).
     *
     * @return the accepted file types, or an empty list to accept any file
     */
    default List<String> getFileTypes() {
        return List.of();
    }

    /**
     * Checks if the value is valid for this option.
     *
     * @param value the value to check
     * @return true if the value is valid
     */
    default boolean isValid(T value) {
        if (value == null) {
            return !isRequired();
        }

        Predicate<T> validator = getValidator();
        if (validator != null && !validator.test(value)) {
            return false;
        }

        // If choices are provided, the value must be one of them
        List<OptionChoice<T>> choices = getChoices();
        if (!choices.isEmpty()) {
            return choices.stream()
                    .anyMatch(choice -> choice.value().equals(value));
        }

        // Type-specific validation
        if (value instanceof Number number) {
            Number min = getMinValue();
            Number max = getMaxValue();

            if (min != null && number.doubleValue() < min.doubleValue()) {
                return false;
            }

            if (max != null && number.doubleValue() > max.doubleValue()) {
                return false;
            }
        } else if (value instanceof String string) {
            Integer minLength = getMinLength();
            Integer maxLength = getMaxLength();

            if (minLength != null && string.length() < minLength) {
                return false;
            }

            if (maxLength != null && string.length() > maxLength) {
                return false;
            }
        }

        return true;
    }
}
