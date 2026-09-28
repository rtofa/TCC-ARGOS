package br.com.argos.argos_api.apikey.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTest {

    @Test
    void generatesUrlSafeKeysWithArgosPrefix() {
        String key = ApiKeyGenerator.generate();

        assertThat(key).startsWith("argos_").hasSize(49).matches("argos_[A-Za-z0-9_-]+");
    }

    @Test
    void generatesDifferentKeysEachTime() {
        assertThat(ApiKeyGenerator.generate()).isNotEqualTo(ApiKeyGenerator.generate());
    }

    @Test
    void prefixIsTheFirstTwelveCharacters() {
        String key = ApiKeyGenerator.generate();

        assertThat(ApiKeyGenerator.prefixOf(key)).hasSize(12).isEqualTo(key.substring(0, 12));
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        String key = ApiKeyGenerator.generate();

        String hash = ApiKeyGenerator.hash(key);

        assertThat(hash).hasSize(64).matches("[0-9a-f]+").isNotEqualTo(key);
        assertThat(ApiKeyGenerator.hash(key)).isEqualTo(hash);
        assertThat(ApiKeyGenerator.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
