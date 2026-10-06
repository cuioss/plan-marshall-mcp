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

import java.io.ByteArrayOutputStream;
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
 * A JWT is signed per mint and never reused. The App's private key is accepted in both PEM forms: PKCS#8
 * ({@code BEGIN PRIVATE KEY}) and PKCS#1 ({@code BEGIN RSA PRIVATE KEY}, the form GitHub delivers); a PKCS#1 key
 * is wrapped in a PKCS#8 {@code PrivateKeyInfo} structure in memory.
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
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS1_END = "-----END RSA PRIVATE KEY-----";
    /** {@code version 0} of a PKCS#8 {@code PrivateKeyInfo}. */
    private static final byte[] VERSION_0 = {0x02, 0x01, 0x00};
    /** {@code AlgorithmIdentifier} rsaEncryption (1.2.840.113549.1.1.1) with NULL parameters. */
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * @param clientId      the App's client id ({@code iss})
     * @param privateKeyPem the App's private key, PEM-encoded PKCS#8 ({@code BEGIN PRIVATE KEY}) or PKCS#1
     *                      ({@code BEGIN RSA PRIVATE KEY})
     * @param now           the signing instant
     * @return the compact JWT
     * @throws JwtSigningException if the key is no PEM-encoded RSA key or signing fails
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
        byte[] pkcs8;
        if (trimmed.startsWith(PKCS8_BEGIN) && trimmed.endsWith(PKCS8_END)) {
            pkcs8 = base64(trimmed, PKCS8_BEGIN, PKCS8_END);
        } else if (trimmed.startsWith(PKCS1_BEGIN) && trimmed.endsWith(PKCS1_END)) {
            pkcs8 = wrapPkcs1(base64(trimmed, PKCS1_BEGIN, PKCS1_END));
        } else {
            throw new JwtSigningException(
                    "the private key must be PEM-encoded PKCS#8 (BEGIN PRIVATE KEY) or PKCS#1 (BEGIN RSA PRIVATE KEY)",
                    null);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static byte[] base64(String pem, String begin, String end) {
        String base64 = pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new JwtSigningException("the private key is not valid base64", e);
        }
    }

    /**
     * Wraps a PKCS#1 {@code RSAPrivateKey} in a PKCS#8 {@code PrivateKeyInfo}:
     * {@code SEQUENCE { version 0, rsaEncryption, OCTET STRING rsaPrivateKey }}.
     */
    static byte[] wrapPkcs1(byte[] pkcs1) {
        var content = new ByteArrayOutputStream();
        content.writeBytes(VERSION_0);
        content.writeBytes(RSA_ALGORITHM);
        content.writeBytes(tlv(TAG_OCTET_STRING, pkcs1));
        return tlv(TAG_SEQUENCE, content.toByteArray());
    }

    /** One DER tag-length-value with a definite length in short or long form. */
    static byte[] tlv(int tag, byte[] value) {
        var out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int octets = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | octets);
            for (int shift = (octets - 1) * 8; shift >= 0; shift -= 8) {
                out.write((length >> shift) & 0xff);
            }
        }
        out.writeBytes(value);
        return out.toByteArray();
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
