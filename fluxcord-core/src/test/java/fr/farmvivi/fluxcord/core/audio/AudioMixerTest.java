package fr.farmvivi.fluxcord.core.audio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour la classe AudioMixer.
 */
public class AudioMixerTest {
    private AudioMixer mixer;

    @BeforeEach
    public void setUp() {
        mixer = new AudioMixer();
    }

    @Test
    public void testEmptyMixer() {
        // Test avec un mixeur vide
        mixer.reset();
        ByteBuffer result = mixer.mix();
        assertNull(result, "Un mixeur vide devrait retourner null");
    }

    @Test
    public void testSingleSource() {
        // Crée un buffer avec des échantillons simples
        ByteBuffer source = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        source.putShort((short) 1000);
        source.putShort((short) 2000);
        source.flip();

        // Ajoute la source au mixeur
        mixer.reset();
        mixer.addSource(source, 1.0f);

        // Mixe et vérifie le résultat
        ByteBuffer result = mixer.mix();
        assertNotNull(result, "Le résultat ne devrait pas être null");
        result.order(ByteOrder.BIG_ENDIAN); // the mixer outputs JDA-ready big-endian PCM

        // Vérifie que les valeurs sont correctes
        assertEquals(1000, result.getShort(), "Le premier échantillon devrait être 1000");
        assertEquals(2000, result.getShort(), "Le deuxième échantillon devrait être 2000");
    }

    @Test
    public void testMultipleSources() {
        // Crée deux buffers avec des échantillons
        ByteBuffer source1 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        source1.putShort((short) 1000);
        source1.putShort((short) 2000);
        source1.flip();

        ByteBuffer source2 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        source2.putShort((short) 500);
        source2.putShort((short) 1000);
        source2.flip();

        // Ajoute les sources au mixeur
        mixer.reset();
        mixer.addSource(source1, 1.0f);
        mixer.addSource(source2, 1.0f);

        // Mixe et vérifie le résultat
        ByteBuffer result = mixer.mix();
        assertNotNull(result, "Le résultat ne devrait pas être null");
        result.order(ByteOrder.BIG_ENDIAN); // the mixer outputs JDA-ready big-endian PCM

        // Vérifie que les valeurs sont correctes (somme des échantillons)
        assertEquals(1500, result.getShort(), "Le premier échantillon devrait être 1500");
        assertEquals(3000, result.getShort(), "Le deuxième échantillon devrait être 3000");
    }

    @Test
    public void testVolumeAdjustment() {
        // Crée un buffer avec des échantillons
        ByteBuffer source = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        source.putShort((short) 1000);
        source.putShort((short) 2000);
        source.flip();

        // Ajoute la source au mixeur avec un volume de 50%
        mixer.reset();
        mixer.addSource(source, 0.5f);

        // Mixe et vérifie le résultat
        ByteBuffer result = mixer.mix();
        assertNotNull(result, "Le résultat ne devrait pas être null");
        result.order(ByteOrder.BIG_ENDIAN); // the mixer outputs JDA-ready big-endian PCM

        // Vérifie que les valeurs sont ajustées correctement
        assertEquals(500, result.getShort(), "Le premier échantillon devrait être 500");
        assertEquals(1000, result.getShort(), "Le deuxième échantillon devrait être 1000");
    }

    @Test
    public void testHardClipping() {
        // Crée deux buffers avec des échantillons qui dépasseront la limite lorsque mixés
        short maxValue = Short.MAX_VALUE;

        ByteBuffer source1 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        source1.putShort(maxValue);
        source1.flip();

        ByteBuffer source2 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        source2.putShort(maxValue);
        source2.flip();

        // Ajoute les sources au mixeur
        mixer.reset();
        mixer.addSource(source1, 1.0f);
        mixer.addSource(source2, 1.0f);

        // Mixe et vérifie le résultat
        ByteBuffer result = mixer.mix();
        assertNotNull(result, "Le résultat ne devrait pas être null");
        result.order(ByteOrder.BIG_ENDIAN); // the mixer outputs JDA-ready big-endian PCM

        // Vérifie que la valeur est limitée à la valeur maximale (hard clipping)
        assertEquals(maxValue, result.getShort(), "La valeur devrait être limitée à Short.MAX_VALUE");
    }

    @Test
    public void testNegativeHardClipping() {
        // Crée deux buffers avec des échantillons négatifs qui dépasseront la limite lorsque mixés
        short minValue = Short.MIN_VALUE;

        ByteBuffer source1 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        source1.putShort(minValue);
        source1.flip();

        ByteBuffer source2 = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        source2.putShort(minValue);
        source2.flip();

        // Ajoute les sources au mixeur
        mixer.reset();
        mixer.addSource(source1, 1.0f);
        mixer.addSource(source2, 1.0f);

        // Mixe et vérifie le résultat
        ByteBuffer result = mixer.mix();
        assertNotNull(result, "Le résultat ne devrait pas être null");
        result.order(ByteOrder.BIG_ENDIAN); // the mixer outputs JDA-ready big-endian PCM

        // Vérifie que la valeur est limitée à la valeur minimale (hard clipping)
        assertEquals(minValue, result.getShort(), "La valeur devrait être limitée à Short.MIN_VALUE");
    }
}