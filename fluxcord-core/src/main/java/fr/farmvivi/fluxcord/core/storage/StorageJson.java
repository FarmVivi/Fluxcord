package fr.farmvivi.fluxcord.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.function.Function;

/**
 * The one Gson configuration shared by every data-storage backend, so a value written through the FILE backend
 * reads back identically through the DB backend (and vice versa after a migration).
 * <p>
 * Supported value types: JSON primitives and their boxes, {@code String}, enums (stored by name), records and
 * plain beans (public or private fields, no-arg constructor not required for records), {@code List}/{@code Map}
 * of the above, and {@code java.time} {@link Instant}, {@link LocalDate}, {@link LocalDateTime},
 * {@link ZonedDateTime}, {@link Duration} (stored as ISO-8601 strings). Untyped reads ({@code getAll()},
 * {@code get(key, Object.class)}) give integral numbers back as {@code Long} and decimals as {@code Double}.
 * {@code null} is not a storable value.
 */
public final class StorageJson {
    private static final GsonBuilder BASE = new GsonBuilder()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .registerTypeAdapter(Instant.class, iso(Instant::parse))
            .registerTypeAdapter(LocalDate.class, iso(LocalDate::parse))
            .registerTypeAdapter(LocalDateTime.class, iso(LocalDateTime::parse))
            .registerTypeAdapter(ZonedDateTime.class, iso(ZonedDateTime::parse))
            .registerTypeAdapter(Duration.class, iso(Duration::parse));

    private static final Gson COMPACT = BASE.create();
    private static final Gson PRETTY = BASE.setPrettyPrinting().create();

    private StorageJson() {
    }

    /** Compact output, for values stored in a database column. */
    public static Gson compact() {
        return COMPACT;
    }

    /** Pretty-printed output, for files a human may open. Same data model as {@link #compact()}. */
    public static Gson pretty() {
        return PRETTY;
    }

    /** Converts any value to the requested type through its JSON form (how the FILE backend re-types cached data). */
    public static <T> T convert(Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return COMPACT.fromJson(COMPACT.toJson(value), type);
    }

    private static <T> TypeAdapter<T> iso(Function<String, T> parser) {
        TypeAdapter<T> adapter = new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter out, T value) throws IOException {
                out.value(value.toString());
            }

            @Override
            public T read(JsonReader in) throws IOException {
                return parser.apply(in.nextString());
            }
        };
        return adapter.nullSafe();
    }
}
