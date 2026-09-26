package fr.farmvivi.fluxcord.plugins.aiaudio;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The language files, against the keys the code actually looks up.
 *
 * <p>Three separate failures are guarded here, each of which has really happened in this repository: a
 * redundant top-level wrapper making every lookup miss, a key translated in one locale only (which falls
 * back silently), and the MessageFormat quoting rule — a single apostrophe swallows the placeholders of a
 * formatted string, while a doubled one shows up literally in a string that is never formatted.
 */
class LanguageFilesTest {

    /** Every key the plugin looks up. Kept by hand, and asserted against both locales. */
    private static final List<String> USED_KEYS = List.of(
            "commands.speak.description", "commands.speak.option.text", "commands.speak.option.voice",
            "commands.silence.description",
            "commands.transcribe.description", "commands.transcribe.option.action",
            "commands.transcribe.start", "commands.transcribe.stop",
            "commands.forget.description", "commands.forget.option.scope",
            "commands.forget.me", "commands.forget.channel", "commands.forget.server",
            "messages.speaking", "messages.silenced", "messages.already_silent",
            "messages.transcription_started", "messages.transcription_stopped",
            "messages.forgot_me", "messages.forgot_channel", "messages.forgot_server",
            "errors.guild_only", "errors.no_voice_channel", "errors.not_connected",
            "errors.nothing_to_say", "errors.text_too_long", "errors.api_key_missing",
            "errors.synthesis_failed", "errors.no_permission",
            "errors.already_transcribing", "errors.not_transcribing",
            "commands.persona.description", "commands.persona.show", "commands.persona.set",
            "commands.persona.reset", "commands.persona.option.action", "commands.persona.option.field",
            "commands.persona.option.value", "commands.persona.option.scope",
            "commands.persona.field.name", "commands.persona.field.traits", "commands.persona.field.tone",
            "commands.persona.field.language", "commands.persona.field.instructions",
            "commands.persona.field.mood",
            "commands.persona.scope.server", "commands.persona.scope.channel",
            "messages.persona_shown", "messages.persona_set", "messages.persona_reset",
            "messages.persona_nothing_to_reset", "messages.mood_reset",
            "messages.scope_server", "messages.scope_channel",
            "errors.persona_usage", "errors.persona_unknown_field",
            "mood.neutral", "mood.enthusiastic", "mood.subdued", "mood.tense", "mood.warm",
            "mood.distant",
            "commands.converse.description", "commands.converse.start", "commands.converse.stop",
            "commands.converse.option.action",
            "messages.converse_started", "messages.converse_started_wake_word", "messages.converse_stopped",
            "errors.conversation_disabled", "errors.already_conversing", "errors.not_conversing");

    /** The keys whose value goes through MessageFormat, because the code passes arguments. */
    private static final Set<String> FORMATTED_KEYS = Set.of(
            "messages.speaking", "messages.forgot_channel", "messages.forgot_server",
            "errors.text_too_long", "errors.synthesis_failed",
            "messages.persona_shown", "messages.persona_set", "messages.persona_reset",
            "messages.persona_nothing_to_reset", "errors.persona_unknown_field",
            "messages.converse_started_wake_word");

    private Map<String, String> load(String locale) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
            assertNotNull(in, "missing language file for " + locale);
            Map<String, Object> raw = new Yaml().load(in);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", raw, flat);
            return flat;
        } catch (Exception e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> node, Map<String, String> into) {
        node.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                flatten(path, (Map<String, Object>) child, into);
            } else {
                into.put(path, String.valueOf(value));
            }
        });
    }

    @Test
    void theFilesAreReadableAndNotWrappedInARedundantBlock() {
        Map<String, String> english = load("en-US");

        assertFalse(english.isEmpty());
        assertTrue(english.containsKey("commands.transcribe.description"),
                "a wrapper would make this 'aiaudio.commands.transcribe.description' and the lookup would miss");
    }

    @Test
    void everyKeyThePluginUsesExistsInEveryLocale() {
        for (String locale : List.of("en-US", "fr-FR")) {
            Map<String, String> translations = load(locale);
            for (String key : USED_KEYS) {
                assertTrue(translations.containsKey(key),
                        locale + " has no translation for '" + key + "' (a miss shows the key to the user)");
            }
        }
    }

    @Test
    void theTwoLocalesDeclareTheSameKeys() {
        Set<String> english = new TreeSet<>(load("en-US").keySet());
        Set<String> french = new TreeSet<>(load("fr-FR").keySet());

        assertEquals(english, french, "a key translated in one locale only falls back silently");
    }

    @Test
    void placeholdersMatchBetweenLocales() {
        Map<String, String> english = load("en-US");
        Map<String, String> french = load("fr-FR");

        english.forEach((key, value) -> assertEquals(placeholders(value), placeholders(french.get(key)),
                "'" + key + "' does not take the same arguments in both locales"));
    }

    @Test
    void onlyTheFormattedStringsDoubleTheirApostrophes() {
        // MessageFormat eats a single quote and everything that follows up to the next one, so a
        // formatted value must double its apostrophes -- and a value that is never formatted must not,
        // or the user reads "n''a pas".
        for (String locale : List.of("en-US", "fr-FR")) {
            load(locale).forEach((key, value) -> {
                if (FORMATTED_KEYS.contains(key)) {
                    assertFalse(value.replace("''", "").contains("'"),
                            locale + ": '" + key + "' is formatted, so its apostrophes must be doubled");
                } else {
                    assertFalse(value.contains("''"),
                            locale + ": '" + key + "' is never formatted, so a doubled apostrophe is shown as is");
                }
            });
        }
    }

    @Test
    void everyPlaceholderBearingKeyIsDeclaredAsFormatted() {
        // Keeps the list above honest: a new {0} without its entry would be a quoting bug waiting to happen.
        load("en-US").forEach((key, value) -> {
            if (!placeholders(value).isEmpty()) {
                assertTrue(FORMATTED_KEYS.contains(key), "'" + key + "' takes arguments but is not listed");
            }
        });
    }

    private Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\d+}").matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void everyKeyIsAStringAndNotSomethingYamlResolvedForUs() {
        // off/on/yes/no/true/false and bare numbers are not strings in YAML 1.1, which snakeyaml
        // implements: such a key is flattened through String.valueOf and stops matching what the code
        // asks for. It cost music-plugin a /loop choice labelled with a raw key.
        for (String locale : List.of("en-US", "fr-FR")) {
            assertNonStringKeys(locale, "", loadRaw(locale));
        }
    }

    private void assertNonStringKeys(String locale, String prefix, Map<?, ?> node) {
        node.forEach((key, value) -> {
            assertInstanceOf(String.class, key,
                    locale + ": the key '" + prefix + key + "' is a " + key.getClass().getSimpleName()
                            + ", not a string - quote it in the YAML");
            if (value instanceof Map<?, ?> child) {
                assertNonStringKeys(locale, prefix + key + ".", child);
            }
        });
    }

    /** The raw YAML tree, to inspect the key objects themselves rather than their string form. */
    private Map<String, Object> loadRaw(String locale) {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + locale + ".yml")) {
            return new Yaml().load(in);
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + locale, e);
        }
    }
}
