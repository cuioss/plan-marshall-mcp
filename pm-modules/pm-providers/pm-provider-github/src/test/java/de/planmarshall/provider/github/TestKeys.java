/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.github;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/** A generated RSA key pair with its PKCS#8 and PKCS#1 PEM, and JWT verification (test helper). */
final class TestKeys {

    static final KeyPair PAIR = generate();
    static final String PKCS8_PEM = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(PAIR.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

    /**
     * The same key as GitHub delivers it: the {@code RSAPrivateKey} structure the JDK's PKCS#8 encoding of a
     * 2048-bit key carries as the content of its OCTET STRING, after a fixed prefix of {@value #PKCS8_PREFIX} bytes.
     */
    static final String PKCS1_PEM = "-----BEGIN RSA PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs1())
            + "\n-----END RSA PRIVATE KEY-----\n";

    /** SEQUENCE header (4), version (3), AlgorithmIdentifier (15), OCTET STRING header (4). */
    private static final int PKCS8_PREFIX = 26;

    private TestKeys() {
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] pkcs1() {
        byte[] pkcs8 = PAIR.getPrivate().getEncoded();
        if (pkcs8[PKCS8_PREFIX - 4] != 0x04 || (pkcs8[PKCS8_PREFIX - 3] & 0xff) != 0x82) {
            throw new IllegalStateException("unexpected PKCS#8 layout");
        }
        return Arrays.copyOfRange(pkcs8, PKCS8_PREFIX, pkcs8.length);
    }

    /** Verifies the RS256 signature and returns the decoded claims JSON. */
    static String verifiedClaims(String jwt, PublicKey key) throws GeneralSecurityException {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new GeneralSecurityException("not a compact JWT");
        }
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
            throw new GeneralSecurityException("bad signature");
        }
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    static String header(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
    }
}
