package br.com.argos.argos_api.identity.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * RSA key pair that signs and verifies the session tokens (JWT).
 *
 * <p>In production the pair comes from configuration ({@code ARGOS_JWT_PRIVATE_KEY} in PKCS#8 PEM and
 * {@code ARGOS_JWT_PUBLIC_KEY} in X.509 PEM, e.g. from AWS SSM Parameter Store): every instance then signs and
 * accepts the same tokens, so sessions survive deploys and work behind a load balancer with several tasks.
 *
 * <p>Without configuration (local development) a pair is generated in memory, and tokens die with the process.
 */
public record JwtKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey, boolean ephemeral) {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    public static JwtKeys load(String privateKeyPem, String publicKeyPem) {
        boolean hasPrivate = privateKeyPem != null && !privateKeyPem.isBlank();
        boolean hasPublic = publicKeyPem != null && !publicKeyPem.isBlank();
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("Configure as duas chaves do JWT (ARGOS_JWT_PRIVATE_KEY e ARGOS_JWT_PUBLIC_KEY) ou nenhuma");
        }
        if (!hasPrivate) {
            log.warn("Chaves do JWT não configuradas: usando um par temporário (as sessões caem a cada reinício). "
                    + "Em produção, defina ARGOS_JWT_PRIVATE_KEY e ARGOS_JWT_PUBLIC_KEY.");
            return generate();
        }
        RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(stream(privateKeyPem));
        RSAPublicKey publicKey = RsaKeyConverters.x509().convert(stream(publicKeyPem));
        if (privateKey == null || publicKey == null || !privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("ARGOS_JWT_PRIVATE_KEY e ARGOS_JWT_PUBLIC_KEY não formam um par RSA");
        }
        return new JwtKeys(publicKey, privateKey, false);
    }

    static JwtKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new JwtKeys((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(), true);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Accepts real line breaks or the literal "\n" some secret stores and .env files keep. */
    private static ByteArrayInputStream stream(String pem) {
        return new ByteArrayInputStream(pem.replace("\\n", "\n").strip().getBytes(StandardCharsets.UTF_8));
    }
}
