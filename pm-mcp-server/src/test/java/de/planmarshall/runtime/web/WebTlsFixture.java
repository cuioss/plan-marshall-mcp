/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.web;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Writes the TLS material of a web listener whose certificate names chosen addresses, where the product names
 * the addresses of the machine's interfaces. It lives in the package of {@link WebTls} to reach its generator.
 */
public final class WebTlsFixture {

    private WebTlsFixture() {
    }

    /**
     * @param dir       the directory {@code <PM_MCP_BASE>/web/tls}
     * @param addresses the addresses the certificate names beside the host and loopback names
     * @return the written material
     * @throws IOException              if the files cannot be written
     * @throws GeneralSecurityException if the material cannot be generated
     */
    public static WebTls write(Path dir, List<InetAddress> addresses) throws IOException, GeneralSecurityException {
        var tls = WebTls.generate("fixture", addresses, new SecureRandom(), Instant.now());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(WebTls.KEY_FILE), tls.privateKeyPem());
        Files.writeString(dir.resolve(WebTls.CERT_FILE), tls.certificatePem());
        return tls;
    }
}
