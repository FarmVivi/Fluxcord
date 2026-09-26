package fr.farmvivi.fluxcord.api.storage;

import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scope strings, pinned character by character.
 *
 * <p>These are not cosmetic: a scope string is how stored data is found again. The file backend turns
 * {@code guild:123} into the directory {@code guild/123}, and the database stores it in a column. Change
 * one separator and every bot's saved prefixes, permissions and playlists become unreachable — with no
 * error, since a missing key simply reads as absent.
 */
class StorageKeysTest {

    @Test
    void theFourScopeFormatsAreFixed() {
        assertEquals("global", StorageKey.globalScope());
        assertEquals("user:u1", StorageKey.userScope("u1"));
        assertEquals("guild:g1", StorageKey.guildScope("g1"));
        assertEquals("user:u1:guild:g1", StorageKey.userGuildScope("u1", "g1"));
    }

    @Test
    void theFactoriesBuildTheSameScopesAsTheHelpers() {
        assertEquals(new StorageKey("global", "k"), StorageKey.global("k"));
        assertEquals(new StorageKey("user:u1", "k"), StorageKey.user("u1", "k"));
        assertEquals(new StorageKey("guild:g1", "k"), StorageKey.guild("g1", "k"));
        assertEquals(new StorageKey("user:u1:guild:g1", "k"), StorageKey.userGuild("u1", "g1", "k"));
    }

    @Test
    void aKeyPrintsAsScopeThenName() {
        assertEquals("guild:g1:commands.prefix", StorageKey.guild("g1", "commands.prefix").toString());
    }

    @Test
    void theRecordAccessorsAndTheGettersAgree() {
        StorageKey key = StorageKey.user("u1", "theme");

        assertEquals(key.scope(), key.getScope());
        assertEquals(key.key(), key.getKey());
        assertEquals("user:u1", key.getScope());
        assertEquals("theme", key.getKey());
    }

    @Test
    void twoKeysWithTheSameScopeAndNameAreTheSameKey() {
        // The backends use keys in maps, so value equality is load-bearing.
        assertEquals(StorageKey.guild("g1", "k"), StorageKey.guild("g1", "k"));
        assertEquals(StorageKey.guild("g1", "k").hashCode(), StorageKey.guild("g1", "k").hashCode());
        assertNotEquals(StorageKey.guild("g1", "k"), StorageKey.guild("g2", "k"));
        assertNotEquals(StorageKey.guild("g1", "k"), StorageKey.user("g1", "k"));
    }

    @Test
    void binaryKeysUseTheSameScopesWithAPath() {
        assertEquals("global", BinaryStorageKey.global("a.png").scope());
        assertEquals("user:u1", BinaryStorageKey.user("u1", "a.png").scope());
        assertEquals("guild:g1", BinaryStorageKey.guild("g1", "a.png").scope());
        assertEquals("user:u1:guild:g1", BinaryStorageKey.userGuild("u1", "g1", "a.png").scope());
    }

    @Test
    void aBinaryPathIsNormalisedSoWindowsAndUnixAgree() {
        // A plugin building a path with File.separator would otherwise store under a different key on
        // Windows than on Linux, and the file would be missing after a migration.
        assertEquals("covers/a.png", BinaryStorageKey.global("covers\\a.png").path());
        assertEquals("covers/a.png", BinaryStorageKey.global("/covers/a.png").path(),
                "a leading slash would make the path absolute for the file backend");
        assertEquals("covers/a.png", BinaryStorageKey.global("\\covers\\a.png").path());
        assertEquals("", BinaryStorageKey.global(null).path(), "null is a missing path, not a crash");
    }

    @Test
    void aBinaryKeyPrintsWithAColonButResolvesWithASlash() {
        BinaryStorageKey key = BinaryStorageKey.guild("g1", "covers/a.png");

        assertEquals("guild:g1:covers/a.png", key.toString());
        assertEquals("guild:g1/covers/a.png", key.getFullPath(), "what the file backend walks");
    }
}
