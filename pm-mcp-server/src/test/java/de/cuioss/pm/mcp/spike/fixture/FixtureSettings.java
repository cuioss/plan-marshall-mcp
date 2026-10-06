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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


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

    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";

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
     * @return the key file's PEM as stored; {@code GitHubAppJwt} accepts PKCS#8 and PKCS#1 (GitHub's download format)
     */
    String privateKeyPem() {
        try {
            return Files.readString(keyFile, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("key file unreadable", e);
        }
    }
}
