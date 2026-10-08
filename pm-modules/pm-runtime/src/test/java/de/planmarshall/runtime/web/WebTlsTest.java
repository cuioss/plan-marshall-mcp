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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;


import de.planmarshall.runtime.test.TestBases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Self-signed certificate of the web listener")
class WebTlsTest {

    @Test
    @DisplayName("generates a verifiable ECDSA P-256 certificate with host, interface and loopback names")
    void shouldGenerateCertificate() throws Exception {
        var tls = WebTls.generate("studio", List.of(InetAddress.getByName("192.168.1.20")), new SecureRandom(),
                Instant.now());

        var certificate = tls.certificate();
        certificate.verify(certificate.getPublicKey());
        certificate.checkValidity();
        assertEquals("SHA256withECDSA", certificate.getSigAlgName());
        assertEquals("CN=pm-mcpd", certificate.getSubjectX500Principal().getName());
        assertEquals(-1, certificate.getBasicConstraints());
        assertTrue(certificate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        var names = tls.names();
        for (var expected : List.of("studio", "studio.local", "localhost", "192.168.1.20", "127.0.0.1")) {
            assertTrue(names.contains(expected), names.toString());
        }
        assertEquals(32 * 3 - 1, tls.fingerprint().length());
        var der = Base64.getMimeDecoder().decode(tls.privateKeyPem().replaceAll("-----[A-Z ]+-----", ""));
        assertEquals("EC", KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der))
                .getAlgorithm());
    }

    @Test
    @DisplayName("writes key and certificate privately once and reuses them")
    void shouldPersistAndReuse() throws Exception {
        var base = TestBases.create("pmt");
        try {
            var dir = base.resolve("web").resolve("tls");
            var first = WebTls.loadOrCreate(dir);
            var second = WebTls.loadOrCreate(dir);

            assertEquals(first.fingerprint(), second.fingerprint());
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
            assertEquals("rw-------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(WebTls.KEY_FILE))));
            assertTrue(first.names().contains("localhost"));
        } finally {
            TestBases.delete(base);
        }
    }

    @Test
    @DisplayName("encodes DER lengths and object identifiers")
    void shouldEncodeDer() {
        assertEquals("0603551d11", HexFormat.of().formatHex(Der.oid("2.5.29.17")));
        assertEquals("06072a8648ce3d0201", HexFormat.of().formatHex(Der.oid("1.2.840.10045.2.1")));
        var longValue = Der.tlv(Der.TAG_OCTET_STRING, new byte[300]);
        assertEquals("0482012c", HexFormat.of().formatHex(longValue, 0, 4));
        assertEquals("020100", HexFormat.of().formatHex(Der.integer(BigInteger.ZERO)));
    }

    @Test
    @DisplayName("finds a host name and interface addresses")
    void shouldInspectHost() {
        assertFalse(WebTls.hostName().isBlank());
        assertTrue(WebTls.interfaceAddresses().stream().noneMatch(InetAddress::isLinkLocalAddress));
    }
}
