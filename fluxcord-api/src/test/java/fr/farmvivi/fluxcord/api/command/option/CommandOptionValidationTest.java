package fr.farmvivi.fluxcord.api.command.option;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommandOption#isValid} — the only real logic in the option model, and the last gate before a
 * user's value reaches a plugin.
 *
 * <p>Its order of checks is what the tests pin: the validator runs before the choices, and the choices
 * short-circuit the numeric and length bounds entirely. That matters — an option with both choices and a
 * max length does not apply the length, and a reader who assumed otherwise would write a rule that never
 * runs.
 */
class CommandOptionValidationTest {

    /** A minimal option whose constraints the test sets one at a time. */
    private static final class Option<T> implements CommandOption<T> {
        private boolean required;
        private List<OptionChoice<T>> choices = List.of();
        private Predicate<T> validator;
        private Number min;
        private Number max;
        private Integer minLength;
        private Integer maxLength;

        @Override
        public String getName() {
            return "opt";
        }

        @Override
        public String getDescription() {
            return "an option";
        }

        @Override
        public OptionType getType() {
            return OptionType.STRING;
        }

        @Override
        public boolean isRequired() {
            return required;
        }

        @Override
        public List<OptionChoice<T>> getChoices() {
            return choices;
        }

        @Override
        public Predicate<T> getValidator() {
            return validator;
        }

        @Override
        public AutocompleteProvider<T> getAutocompleteProvider() {
            return null;
        }

        @Override
        public Number getMinValue() {
            return min;
        }

        @Override
        public Number getMaxValue() {
            return max;
        }

        @Override
        public Integer getMinLength() {
            return minLength;
        }

        @Override
        public Integer getMaxLength() {
            return maxLength;
        }
    }

    @Test
    void aMissingValueIsOnlyValidWhenTheOptionIsOptional() {
        Option<String> option = new Option<>();

        assertTrue(option.isValid(null));

        option.required = true;
        assertFalse(option.isValid(null));
    }

    @Test
    void anUnconstrainedOptionAcceptsAnything() {
        assertTrue(new Option<String>().isValid("whatever"));
    }

    @Test
    void theValidatorCanRefuseAValue() {
        Option<String> option = new Option<>();
        option.validator = value -> value.startsWith("https://");

        assertTrue(option.isValid("https://example.com"));
        assertFalse(option.isValid("javascript:alert(1)"));
    }

    @Test
    void theValidatorRunsBeforeTheChoices() {
        // A value that is a declared choice is still refused by the validator.
        Option<String> option = new Option<>();
        option.choices = List.of(OptionChoice.of("Off", "off"));
        option.validator = value -> false;

        assertFalse(option.isValid("off"));
    }

    @Test
    void whenChoicesExistTheValueMustBeOneOfThem() {
        Option<String> option = new Option<>();
        option.choices = List.of(OptionChoice.of("Off", "off"), OptionChoice.of("Track", "track"));

        assertTrue(option.isValid("off"));
        assertTrue(option.isValid("track"));
        assertFalse(option.isValid("queue"), "not offered, so not accepted");
        assertFalse(option.isValid("Off"), "the value is matched, not the label");
    }

    @Test
    void choicesShortCircuitTheLengthAndRangeChecks() {
        // Worth knowing: a constraint set next to choices never runs.
        Option<String> option = new Option<>();
        option.choices = List.of(OptionChoice.of("Long", "a-very-long-value"));
        option.maxLength = 3;

        assertTrue(option.isValid("a-very-long-value"));
    }

    @Test
    void numericBoundsAreInclusiveAtBothEnds() {
        Option<Integer> option = new Option<>();
        option.min = 0;
        option.max = 100;

        assertTrue(option.isValid(0));
        assertTrue(option.isValid(100));
        assertTrue(option.isValid(50));
        assertFalse(option.isValid(-1));
        assertFalse(option.isValid(101));
    }

    @Test
    void aBoundOnOneSideOnlyLeavesTheOtherOpen() {
        Option<Integer> lower = new Option<>();
        lower.min = 10;
        assertFalse(lower.isValid(9));
        assertTrue(lower.isValid(Integer.MAX_VALUE));

        Option<Integer> upper = new Option<>();
        upper.max = 10;
        assertTrue(upper.isValid(Integer.MIN_VALUE));
        assertFalse(upper.isValid(11));
    }

    @Test
    void boundsAreComparedAsDoublesSoMixedNumberTypesWork() {
        // The builder may hand an Integer bound to a NUMBER option, or the reverse.
        Option<Double> option = new Option<>();
        option.min = 1;
        option.max = 2;

        assertTrue(option.isValid(1.5));
        assertFalse(option.isValid(0.999));
        assertFalse(option.isValid(2.001));
    }

    @Test
    void stringLengthsAreInclusiveAtBothEnds() {
        Option<String> option = new Option<>();
        option.minLength = 2;
        option.maxLength = 4;

        assertTrue(option.isValid("ab"));
        assertTrue(option.isValid("abcd"));
        assertFalse(option.isValid("a"));
        assertFalse(option.isValid("abcde"));
    }

    @Test
    void lengthBoundsDoNotApplyToNumbersAndRangeBoundsDoNotApplyToStrings() {
        Option<Integer> numeric = new Option<>();
        numeric.maxLength = 1;
        assertTrue(numeric.isValid(123456), "a number has no length");

        Option<String> text = new Option<>();
        text.max = 5;
        assertTrue(text.isValid("123456"), "a string has no numeric range");
    }

    @Test
    void anOptionShipsNoFileTypesUnlessItOverridesThem() {
        assertEquals(List.of(), new Option<String>().getFileTypes());
    }

    @Test
    void everyOptionTypeMapsToAJdaTypeAndBack() {
        // The conversion is used on every command sync, in both directions.
        for (OptionType type : OptionType.values()) {
            assertNotNull(type.getJdaType(), type + " has no JDA type");
            assertEquals(type, OptionType.fromJdaType(type.getJdaType()));
        }
    }

    @Test
    void aJdaTypeWithNoEquivalentIsRefusedLoudly() {
        // SUB_COMMAND is not an option in this model; silently mapping it to STRING would build a
        // command Discord rejects, with no clue why.
        assertThrows(IllegalArgumentException.class, () -> OptionType.fromJdaType(
                net.dv8tion.jda.api.interactions.commands.OptionType.SUB_COMMAND));
        assertThrows(IllegalArgumentException.class, () -> OptionType.fromJdaType(
                net.dv8tion.jda.api.interactions.commands.OptionType.UNKNOWN));
    }

    @Test
    void theChoiceFactoriesKeepTheirLabelAndValue() {
        assertEquals("off", OptionChoice.of("Off", "off").value());
        assertEquals("Off", OptionChoice.of("Off", "off").name());
        assertEquals(3, OptionChoice.of("Three", 3).value());
        assertEquals(1.5, OptionChoice.of("Half", 1.5).value());
    }
}
