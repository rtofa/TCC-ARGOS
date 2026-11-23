package br.com.argos.argos_api.identity.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeysTest {

    @Test
    void instancesSharingTheConfiguredKeysAcceptEachOthersTokens() throws Exception {
        KeyPair pair = rsa();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        SecurityConfig taskA = new SecurityConfig(privatePem, publicPem);
        SecurityConfig taskB = new SecurityConfig(privatePem.replace("\n", "\\n"), publicPem); // literal \n also works

        String token = issue(taskA);

        assertThat(taskB.jwtDecoder().decode(token).getSubject()).isEqualTo("user-1");
    }

    @Test
    void withoutConfiguredKeysEachInstanceHasItsOwnTemporaryPair() {
        SecurityConfig taskA = new SecurityConfig("", "");
        SecurityConfig taskB = new SecurityConfig("", "");

        String token = issue(taskA);

        assertThat(taskA.jwtDecoder().decode(token).getSubject()).isEqualTo("user-1");
        JwtDecoder other = taskB.jwtDecoder();
        assertThatThrownBy(() -> other.decode(token)).isInstanceOf(JwtException.class);
        assertThat(JwtKeys.load(null, null).ephemeral()).isTrue();
    }

    @Test
    void halfConfiguredOrMismatchedKeysFailAtStartup() throws Exception {
        KeyPair one = rsa();
        KeyPair other = rsa();

        assertThatThrownBy(() -> JwtKeys.load(pem("PRIVATE KEY", one.getPrivate().getEncoded()), ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtKeys.load(pem("PRIVATE KEY", one.getPrivate().getEncoded()),
                pem("PUBLIC KEY", other.getPublic().getEncoded())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não formam um par");
    }

    private static String issue(SecurityConfig config) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("user-1").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return config.jwtEncoder().encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
