/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.mcp.server.lsp;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


import com.google.gson.Gson;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * A language server for tests, built on LSP4J's server side and started as a separate JVM.
 * <p>
 * It answers {@code textDocument/definition} with the fixed {@link #RANGE} in the requested document
 * and publishes one diagnostic per opened document. The first argument selects a mode:
 * {@code location} (default), {@code links} (answers {@code LocationLink}s), {@code hang} (never
 * answers {@code definition}), {@code none} (answers {@code null}), {@code die} (exits before speaking) and {@code stubborn} (ignores
 * {@code exit}).
 */
public final class FixtureLanguageServer implements LanguageServer, LanguageClientAware {

    /** The fixed definition range. */
    static final Range RANGE = new Range(new Position(3, 4), new Position(3, 9));

    private final String mode;
    private final Documents documents = new Documents();
    private final Workspace workspace = new Workspace();
    private LanguageClient client;

    private FixtureLanguageServer(String mode) {
        this.mode = mode;
    }

    /**
     * @param args the mode
     * @throws InterruptedException if interrupted
     * @throws ExecutionException   if listening fails
     */
    public static void main(String[] args) throws InterruptedException, ExecutionException {
        String mode = args.length > 0 ? args[0] : "location";
        if ("die".equals(mode)) {
            Runtime.getRuntime().halt(3);
        }
        var server = new FixtureLanguageServer(mode);
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }

    /**
     * The command line starting this server in a separate JVM with the test classpath.
     *
     * @param mode the mode
     * @return the argument vector
     */
    public static List<String> command(String mode) {
        var classpath = new ArrayList<String>();
        for (Class<?> anchor : List.of(FixtureLanguageServer.class, LanguageServer.class, Launcher.class, Gson.class)) {
            try {
                classpath.add(Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", String.join(File.pathSeparator, classpath), FixtureLanguageServer.class.getName(),
                mode);
    }

    @Override
    public void connect(LanguageClient languageClient) {
        this.client = languageClient;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        var capabilities = new ServerCapabilities();
        capabilities.setDefinitionProvider(true);
        List<String> offered = params.getCapabilities().getGeneral().getPositionEncodings();
        capabilities.setPositionEncoding(offered == null || offered.isEmpty() ? "utf-16" : offered.getFirst());
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        if (!"stubborn".equals(mode)) {
            Runtime.getRuntime().halt(0);
        }
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return documents;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }

    /** The document service of the fixture. */
    public final class Documents implements TextDocumentService {

        @Override
        public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
                DefinitionParams params) {
            String uri = params.getTextDocument().getUri();
            return switch (mode) {
                case "hang" -> new CompletableFuture<>();
                case "none" -> CompletableFuture.completedFuture(null);
                case "links" -> CompletableFuture.completedFuture(Either.forRight(List.of(
                        new LocationLink(uri, RANGE, RANGE))));
                default -> CompletableFuture.completedFuture(Either.forLeft(List.of(new Location(uri, RANGE))));
            };
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            client.publishDiagnostics(new PublishDiagnosticsParams(params.getTextDocument().getUri(),
                    List.of(new Diagnostic(RANGE, "fixture diagnostic"))));
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            // not used by the tests
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
            // not used by the tests
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) {
            // not used by the tests
        }
    }

    /** The workspace service of the fixture. */
    public static final class Workspace implements WorkspaceService {

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
            // not used by the tests
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            // not used by the tests
        }
    }
}
