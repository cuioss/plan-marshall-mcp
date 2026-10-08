/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


import de.planmarshall.mcp.server.runtime.PosixModes;

/**
 * The TLS material of the web listener in LAN mode: a self-signed ECDSA P-256 certificate whose subject
 * alternative names are the host name, {@code <hostname>.local}, the interface addresses at generation and
 * the loopback addresses (doc/specification/cli-and-security/03-web-server.adoc, Listener Exposure).
 * <p>
 * Key and certificate are kept as PEM in {@code <PM_MCP_BASE>/web/tls/} (directory {@code 0700}, files
 * {@code 0600}) and reused, so paired devices keep a stable fingerprint. The certificate is built with JDK
 * APIs and the {@link Der} encoder only.
 *
 * @param certificatePem the certificate, PEM
 * @param privateKeyPem  the PKCS#8 private key, PEM
 * @param certificate    the parsed certificate
 * @since 0.1
 */
public record WebTls(String certificatePem, String privateKeyPem, X509Certificate certificate) {

    static final String CERT_FILE = "cert.pem";
    static final String KEY_FILE = "key.pem";
    static final Duration VALIDITY = Duration.ofDays(825);
    private static final String LOCALHOST = "localhost";
    private static final int SAN_DNS = 0x82;
    private static final int SAN_IP = 0x87;
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /**
     * Loads the material from the directory, or generates and writes it on first use.
     *
     * @param dir the directory {@code <PM_MCP_BASE>/web/tls}
     * @return the material
     * @throws IOException              if a file cannot be read or written
     * @throws GeneralSecurityException if the key or certificate cannot be produced
     */
    public static WebTls loadOrCreate(Path dir) throws IOException, GeneralSecurityException {
        var cert = dir.resolve(CERT_FILE);
        var key = dir.resolve(KEY_FILE);
        if (Files.exists(cert, LinkOption.NOFOLLOW_LINKS) && Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
            var certPem = Files.readString(cert);
            return new WebTls(certPem, Files.readString(key), parse(certPem));
        }
        var generated = generate(hostName(), interfaceAddresses(), new SecureRandom(), Instant.now());
        Files.createDirectories(dir, PosixModes.directoryAttribute());
        write(key, generated.privateKeyPem());
        write(cert, generated.certificatePem());
        return generated;
    }

    /**
     * Generates a fresh key pair and certificate.
     *
     * @param hostName  the host name
     * @param addresses the interface addresses
     * @param random    the source of randomness
     * @param now       the start of the validity
     * @return the material
     * @throws GeneralSecurityException if the key or the signature cannot be produced
     */
    static WebTls generate(String hostName, List<InetAddress> addresses, SecureRandom random, Instant now)
            throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair pair = generator.generateKeyPair();
        var name = Der.sequence(Der.tlv(Der.TAG_SET, Der.sequence(Der.oid("2.5.4.3"), Der.utf8("pm-mcpd"))));
        var algorithm = Der.sequence(Der.oid("1.2.840.10045.4.3.2"));
        var tbs = Der.sequence(
                Der.tlv(0xA0, Der.integer(BigInteger.TWO)),
                Der.integer(new BigInteger(63, random).add(BigInteger.ONE)),
                algorithm,
                name,
                Der.sequence(Der.utcTime(UTC_TIME.format(now.minus(Duration.ofDays(1)))),
                        Der.utcTime(UTC_TIME.format(now.plus(VALIDITY)))),
                name,
                pair.getPublic().getEncoded(),
                Der.tlv(0xA3, Der.sequence(
                        extension("2.5.29.17", false, subjectAlternativeNames(hostName, addresses)),
                        extension("2.5.29.19", true, Der.sequence()),
                        extension("2.5.29.37", false, Der.sequence(Der.oid("1.3.6.1.5.5.7.3.1"))))));
        var signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate(), random);
        signer.update(tbs);
        var der = Der.sequence(tbs, algorithm, Der.bitString(signer.sign()));
        var certPem = pem("CERTIFICATE", der);
        return new WebTls(certPem, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), parse(certPem));
    }

    /**
     * @return the SHA-256 fingerprint of the DER certificate, upper-case hex pairs separated by {@code :}
     * @throws GeneralSecurityException if the certificate cannot be encoded
     */
    public String fingerprint() throws GeneralSecurityException {
        var digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    /**
     * @return the DNS names and IP addresses of the certificate, lower case
     * @throws GeneralSecurityException if the extension cannot be parsed
     */
    public Set<String> names() throws GeneralSecurityException {
        var names = new LinkedHashSet<String>();
        var alternatives = certificate.getSubjectAlternativeNames();
        if (alternatives != null) {
            for (var entry : alternatives) {
                names.add(String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical
                ? Der.sequence(Der.oid(oid), Der.tlv(Der.TAG_BOOLEAN, new byte[]{(byte) 0xFF}),
                Der.tlv(Der.TAG_OCTET_STRING, value))
                : Der.sequence(Der.oid(oid), Der.tlv(Der.TAG_OCTET_STRING, value));
    }

    private static byte[] subjectAlternativeNames(String hostName, List<InetAddress> addresses) {
        var dns = new LinkedHashSet<String>();
        dns.add(hostName);
        dns.add(hostName.endsWith(".local") ? hostName : hostName + ".local");
        dns.add(LOCALHOST);
        var ips = new LinkedHashSet<InetAddress>(addresses);
        ips.add(InetAddress.getLoopbackAddress());
        ips.add(loopbackV6());
        var entries = new ArrayList<byte[]>();
        dns.forEach(value -> entries.add(Der.tlv(SAN_DNS, value.getBytes(StandardCharsets.US_ASCII))));
        ips.forEach(value -> entries.add(Der.tlv(SAN_IP, value.getAddress())));
        return Der.sequence(entries.toArray(byte[][]::new));
    }

    private static InetAddress loopbackV6() {
        try {
            return InetAddress.getByAddress(LOCALHOST, new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException("a 16-byte address is always valid", e);
        }
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException _) {
            return LOCALHOST;
        }
    }

    static List<InetAddress> interfaceAddresses() {
        try {
            return NetworkInterface.networkInterfaces()
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> !address.isLinkLocalAddress())
                    .map(address -> {
                        try {
                            // drop the scope id: SAN entries carry the bare address
                            return InetAddress.getByAddress(address.getAddress());
                        } catch (UnknownHostException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        } catch (SocketException _) {
            return List.of();
        }
    }

    private static X509Certificate parse(String pem) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static void write(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        Files.writeString(Files.createFile(file, PosixModes.fileAttribute()), content);
    }
}
