package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;

import java.util.Map;

/**
 * A tool's parameters as JSON Schema, which is the one thing every provider spells the same way.
 *
 * <p>Shared by both dialects: they disagree about where the schema goes — on the entry itself, or under a
 * {@code functionDeclarations} list — but not about what it looks like inside. Writing it twice is how the two
 * would quietly drift apart.
 */
final class JsonSchema {

    private JsonSchema() {
    }

    /**
     * @param tool the tool to describe
     * @return its parameters as an {@code object} schema, with the required names listed
     */
    static JsonObject of(ChatModel.Tool tool) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Map.Entry<String, ChatModel.Tool.Parameter> parameter : tool.parameters().entrySet()) {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", parameter.getValue().type());
            schema.addProperty("description", parameter.getValue().description());
            properties.add(parameter.getKey(), schema);
            if (parameter.getValue().required()) {
                required.add(parameter.getKey());
            }
        }
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);
        return parameters;
    }
}
