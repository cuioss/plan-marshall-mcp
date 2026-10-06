/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.spike.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;


import de.cuioss.pm.provider.ci.Json;

/**
 * The operator's GitHub App fixture ({@code -Dspike.fixture=<app.json>}; the private key is {@code app.pem} next to
 * it). The key is read per mint and never logged or written.
 *
 * @param clientId       the App's client id
 * @param appId          the App id
 * @param slug           the App slug
 * @param installationId the installation id
 * @param owner          the repository owner
 * @param repository     the repository name
 * @param keyFile        the PEM key file
 */
record FixtureSettings(String clientId, String appId, String slug, long installationId, String owner,
String repository, Path keyFile) {

    /** The opt-in system property naming the fixture file. */
    static final String PROPERTY = "spike.fixture";

    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String PKCS1_END = "-----END RSA PRIVATE KEY-----";
    /** AlgorithmIdentifier rsaEncryption with NULL parameters. */
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};

    static FixtureSettings load() throws IOException {
        Path file = expand(System.getProperty(PROPERTY));
        Object json = Json.parse(Files.readString(file));
        String[] fullName = Json.string(json, "repository").orElseThrow().split("/", 2);
        return new FixtureSettings(Json.string(json, "client_id").orElseThrow(), Json.string(json, "id").orElseThrow(),
                Json.string(json, "slug").orElse(""), Long.parseLong(Json.string(json, "installation_id").orElseThrow()),
                fullName[0], fullName[1], file.resolveSibling("app.pem"));
    }

    static Path expand(String path) {
        if (path.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), path.substring(2));
        }
        return Path.of(path);
    }

    /**
     * @return {@code pkcs8} or {@code pkcs1}, the format of the key file as GitHub delivered or the operator stored it
     */
    String keyFormat() {
        try {
            return Files.readString(keyFile).strip().startsWith(PKCS1_BEGIN) ? "pkcs1" : "pkcs8";
        } catch (IOException e) {
            return "unreadable";
        }
    }

    /**
     * @return the key as PKCS#8 PEM, the only form {@code GitHubAppJwt} accepts; a PKCS#1 key (GitHub's download
     *         format) is wrapped in a PKCS#8 envelope in memory
     */
    String pkcs8Pem() {
        String pem;
        try {
            pem = Files.readString(keyFile, StandardCharsets.US_ASCII).strip();
        } catch (IOException e) {
            throw new IllegalStateException("key file unreadable", e);
        }
        if (pem.startsWith(PKCS8_BEGIN)) {
            return pem;
        }
        if (!pem.startsWith(PKCS1_BEGIN) || !pem.endsWith(PKCS1_END)) {
            throw new IllegalStateException("unsupported key format");
        }
        byte[] pkcs1 = Base64.getMimeDecoder()
                .decode(pem.substring(PKCS1_BEGIN.length(), pem.length() - PKCS1_END.length()));
        var content = new ByteArrayOutputStream();
        content.writeBytes(new byte[]{0x02, 0x01, 0x00});
        content.writeBytes(RSA_ALGORITHM);
        content.writeBytes(tlv(0x04, pkcs1));
        byte[] der = tlv(0x30, content.toByteArray());
        return PKCS8_BEGIN + "\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der) + "\n" + PKCS8_END;
    }

    private static byte[] tlv(int tag, byte[] value) {
        var out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
