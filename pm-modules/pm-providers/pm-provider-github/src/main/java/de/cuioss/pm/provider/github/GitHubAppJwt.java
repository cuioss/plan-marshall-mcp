/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.provider.github;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import lombok.experimental.UtilityClass;

import de.cuioss.pm.provider.ci.Json;

/**
 * Signs the RS256 JSON Web Token a GitHub App authenticates with, using the JDK only.
 * <p>
 * The window follows the GitHub App JWT validity window of the timeouts specification: {@code iat}
 * back-dated {@value #IAT_BACKDATE_SECONDS} s against clock skew and {@code exp}
 * {@value #EXP_AFTER_SECONDS} s after signing (GitHub's maximum); {@code iss} is the App's client id.
 * A JWT is signed per mint and never reused.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitHubAppJwt {

    /** Seconds {@code iat} is back-dated. */
    public static final long IAT_BACKDATE_SECONDS = 60;
    /** Seconds after signing at which the JWT expires. */
    public static final long EXP_AFTER_SECONDS = 600;

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * @param clientId      the App's client id ({@code iss})
     * @param privateKeyPem the App's private key, PEM-encoded PKCS#8 ({@code BEGIN PRIVATE KEY})
     * @param now           the signing instant
     * @return the compact JWT
     * @throws JwtSigningException if the key is not a PKCS#8 RSA key or signing fails
     */
    public static String sign(String clientId, String privateKeyPem, Instant now) {
        var header = new LinkedHashMap<String, Object>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iat", now.getEpochSecond() - IAT_BACKDATE_SECONDS);
        claims.put("exp", now.getEpochSecond() + EXP_AFTER_SECONDS);
        claims.put("iss", clientId);
        String signingInput = encode(Json.write(header)) + "." + encode(Json.write(claims));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(privateKeyPem));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new JwtSigningException("signing failed: " + e.getClass().getSimpleName(), e);
        }
    }

    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String trimmed = pem.strip();
        if (!trimmed.startsWith(PKCS8_BEGIN) || !trimmed.endsWith(PKCS8_END)) {
            throw new JwtSigningException("the private key must be PEM-encoded PKCS#8 (BEGIN PRIVATE KEY)", null);
        }
        String base64 = trimmed.substring(PKCS8_BEGIN.length(), trimmed.length() - PKCS8_END.length())
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new JwtSigningException("the private key is not valid base64", e);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String encode(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Thrown when the JWT cannot be signed; never carries key material. */
    public static final class JwtSigningException extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * @param message the reason
         * @param cause   the cause, or {@code null}
         */
        public JwtSigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
