/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;


import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drift test of the GraalVM reachability metadata for LSP4J.
 * <p>
 * LSP4J serializes its protocol classes reflectively with Gson, creates the remote
 * {@link LanguageServer} as a dynamic proxy, and dispatches incoming requests reflectively to the
 * methods of {@link PoolLanguageClient}. The expected metadata is computed from the jars on the classpath;
 * run with {@code -Dpm.native-metadata.write=true} to rewrite the committed files after a version change.
 */
@DisplayName("Native reachability metadata for LSP4J")
class Lsp4jNativeMetadataTest {

    static final String DIRECTORY = "META-INF/native-image/de.planmarshall/pm-runtime-lsp4j/";
    private static final Pattern ANONYMOUS = Pattern.compile("\\$\\d");
    private static final String ALL_MEMBERS = "\"allDeclaredConstructors\":true,\"allPublicConstructors\":true,"
            + "\"allDeclaredMethods\":true,\"allPublicMethods\":true,\"allDeclaredFields\":true,\"allPublicFields\":true";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"reflect-config.json", "proxy-config.json"})
    @DisplayName("the committed metadata matches the jars on the classpath")
    void upToDate(String file) throws Exception {
        String expected = "reflect-config.json".equals(file) ? reflectConfig() : proxyConfig();
        if (Boolean.getBoolean("pm.native-metadata.write")) {
            Path target = Path.of("src/main/resources").resolve(DIRECTORY + file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, expected, StandardCharsets.UTF_8);
        }

        assertEquals(expected, committed(file));
    }

    @Test
    @DisplayName("covers the protocol classes, the JSON-RPC messages and the language client")
    void coversKnownTypes() throws Exception {
        String reflect = committed("reflect-config.json");

        for (String type : List.of("org.eclipse.lsp4j.InitializeParams", "org.eclipse.lsp4j.ServerCapabilities",
                "org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage", "org.eclipse.lsp4j.services.TextDocumentService",
                PoolLanguageClient.class.getName())) {
            assertTrue(reflect.contains("\"name\":\"" + type + "\""), type);
        }
    }

    private static String committed(String file) throws IOException {
        try (InputStream in = Lsp4jNativeMetadataTest.class.getClassLoader().getResourceAsStream(DIRECTORY + file)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String reflectConfig() throws IOException, URISyntaxException {
        var entries = new TreeSet<String>();
        for (String name : classNames(LanguageServer.class)) {
            entries.add("{\"name\":\"" + name + "\"," + ALL_MEMBERS + "}");
        }
        for (String name : classNames(Launcher.class)) {
            entries.add("{\"name\":\"" + name + "\"," + ALL_MEMBERS + "}");
        }
        entries.add("{\"name\":\"" + PoolLanguageClient.class.getName() + "\",\"allDeclaredMethods\":true,"
                + "\"allPublicMethods\":true,\"allDeclaredFields\":true}");
        return "[\n  " + String.join(",\n  ", entries) + "\n]\n";
    }

    /**
     * LSP4J creates every remote service, the {@code @JsonDelegate} services included, as a proxy of
     * the service interface and {@link Endpoint}.
     */
    private static String proxyConfig() throws IOException, URISyntaxException {
        var entries = new TreeSet<String>();
        ClassLoader loader = Lsp4jNativeMetadataTest.class.getClassLoader();
        for (String name : classNames(LanguageServer.class)) {
            if (name.startsWith("org.eclipse.lsp4j.services.") && isInterface(name, loader)) {
                entries.add("{\"interfaces\":[\"" + name + "\",\"" + Endpoint.class.getName() + "\"]}");
            }
        }
        return "[\n  " + String.join(",\n  ", entries) + "\n]\n";
    }

    private static boolean isInterface(String name, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(name, false, loader);
            return type.isInterface() && !type.isAnnotation();
        } catch (ClassNotFoundException | LinkageError _) {
            return false;
        }
    }

    /** All named (non-anonymous) classes of the jar holding {@code anchor}. */
    private static List<String> classNames(Class<?> anchor) throws IOException, URISyntaxException {
        Path jar = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        var names = new ArrayList<String>();
        try (var file = new JarFile(jar.toFile())) {
            for (JarEntry entry : file.stream().toList()) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("-info") && !name.startsWith("META-INF")
                        && !ANONYMOUS.matcher(name).find()) {
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        }
        return names;
    }
}
