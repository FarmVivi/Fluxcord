package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import java.util.List;

/**
 * Looks something up on the web.
 *
 * <p>An interface for the same reason as {@link SpeechToText}: the APIs genuinely differ here. There is no
 * OpenAI-shaped standard for search the way there is for chat, so one implementation per backend is the honest
 * shape, selected by configuration exactly like {@code speech_to_text.api}.
 *
 * <p><strong>Everything that comes back through here is hostile input.</strong> A title and a snippet are
 * written by whoever owns the page, which is the open web; a result reading "ignore your previous instructions"
 * is the normal case to design for, not an edge case. Results therefore travel as delimited data in a
 * {@code tool} message that says so, and no implementation may ever put them anywhere else.
 */
public interface WebSearch {

    /**
     * Runs one query.
     *
     * @param query what to search for; must not be blank
     * @param limit the most results to return, at least one
     * @return the results, best first, possibly empty when nothing matched
     * @throws AiRequestException if the backend could not be reached or refused the query
     */
    List<Result> search(String query, int limit);

    /**
     * One result.
     *
     * @param title   the page's title, as the page claims it
     * @param url     where it is
     * @param snippet the extract the backend produced, which may be empty
     */
    record Result(String title, String url, String snippet) {

        public Result {
            title = title == null ? "" : title.strip();
            url = url == null ? "" : url.strip();
            snippet = snippet == null ? "" : snippet.strip();
        }

        /** @return true when there is nothing here worth showing the model */
        public boolean isEmpty() {
            return url.isEmpty() || (title.isEmpty() && snippet.isEmpty());
        }
    }
}
