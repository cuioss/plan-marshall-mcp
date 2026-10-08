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

import static de.planmarshall.runtime.PmMcpLogMessages.INFO;
import static de.planmarshall.runtime.PmMcpLogMessages.WARN;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.GeneralClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.JsonRpcException;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * One language-server instance: a child process spoken to over {@code stdio} with LSP4J.
 * <p>
 * {@link #start} spawns the process, runs {@code initialize} (negotiating the position encoding,
 * preferring {@code utf-32}, then {@code utf-8}, falling back to {@code utf-16}) and sends
 * {@code initialized}. Every request is bounded by the instance's timeout. {@link #close()} runs
 * {@code shutdown} and {@code exit} and terminates the process if it does not end in time.
 *
 * @since 0.1
 */
public final class LspClient implements AutoCloseable {

    private static final CuiLogger LOGGER = new CuiLogger(LspClient.class);

    /** Position encodings offered, in order of preference. */
    static final List<String> POSITION_ENCODINGS = List.of("utf-32", "utf-8", "utf-16");

    private final Process process;
    private final LanguageServer server;
    private final PoolLanguageClient client;
    private final Future<Void> listening;
    private final Duration timeout;
    private final String positionEncoding;

    /**
     * A location answered by the server, 0-based as on the wire.
     *
     * @param uri            the document URI
     * @param startLine      the start line
     * @param startCharacter the start character in the negotiated encoding
     * @param endLine        the end line
     * @param endCharacter   the end character
     */
    public record LspLocation(String uri, int startLine, int startCharacter, int endLine, int endCharacter) {

        static LspLocation of(String uri, Range range) {
            return new LspLocation(uri, range.getStart().getLine(), range.getStart().getCharacter(),
                    range.getEnd().getLine(), range.getEnd().getCharacter());
        }
    }

    private LspClient(Process process, LanguageServer server, PoolLanguageClient client, Future<Void> listening,
            Duration timeout, String positionEncoding) {
        this.process = process;
        this.server = server;
        this.client = client;
        this.listening = listening;
        this.timeout = timeout;
        this.positionEncoding = positionEncoding;
    }

    /**
     * Starts a language server and initializes it for a workspace.
     *
     * @param command   the argument vector of the language server
     * @param workspace the workspace root (also the working directory)
     * @param timeout   the bound of every request, {@code initialize} included
     * @return the initialized client
     * @throws LspException if the process cannot start or {@code initialize} fails or times out
     */
    public static LspClient start(List<String> command, Path workspace, Duration timeout) throws LspException {
        Objects.requireNonNull(timeout, "timeout");
        Process process;
        try {
            process = new ProcessBuilder(command).directory(workspace.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException | IllegalArgumentException e) {
            throw new LspException(LspException.Kind.START_FAILED, "cannot start " + command, e);
        }
        var client = new PoolLanguageClient();
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(),
                process.getOutputStream());
        Future<Void> listening = launcher.startListening();
        LanguageServer server = launcher.getRemoteProxy();
        try {
            InitializeResult result = await(() -> server.initialize(initializeParams(workspace)), timeout);
            server.initialized(new InitializedParams());
            String encoding = Optional.ofNullable(result.getCapabilities().getPositionEncoding()).orElse("utf-16");
            LOGGER.info(INFO.LSP_STARTED, command.getFirst(), process.pid(), encoding);
            return new LspClient(process, server, client, listening, timeout, encoding);
        } catch (LspException | JsonRpcException e) {
            listening.cancel(true);
            process.destroyForcibly();
            throw new LspException(LspException.Kind.START_FAILED, "initialize failed: " + e.getMessage(), e);
        }
    }

    private static InitializeParams initializeParams(Path workspace) {
        var general = new GeneralClientCapabilities();
        general.setPositionEncodings(POSITION_ENCODINGS);
        var textDocument = new TextDocumentClientCapabilities();
        textDocument.setDefinition(new DefinitionCapabilities(Boolean.TRUE));
        var capabilities = new ClientCapabilities();
        capabilities.setGeneral(general);
        capabilities.setTextDocument(textDocument);
        var params = new InitializeParams();
        params.setProcessId((int) ProcessHandle.current().pid());
        params.setCapabilities(capabilities);
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(workspace.toUri().toString(),
                String.valueOf(workspace.getFileName()))));
        return params;
    }

    /**
     * @return the negotiated position encoding
     */
    public String positionEncoding() {
        return positionEncoding;
    }

    /**
     * Opens a document with its content read from disk.
     *
     * @param file       the file
     * @param languageId the LSP language id, e.g. {@code java}
     * @throws LspException if the file cannot be read
     */
    public void open(Path file, String languageId) throws LspException {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(file.toUri().toString(), languageId, 1, text)));
        } catch (IOException | JsonRpcException e) {
            throw new LspException(LspException.Kind.REQUEST_FAILED, "cannot read " + file, e);
        }
    }

    /**
     * {@code textDocument/definition}.
     *
     * @param file      the document
     * @param line      the 0-based line
     * @param character the 0-based character in the negotiated encoding
     * @return the definition locations, possibly empty
     * @throws LspException on timeout or a server error
     */
    public List<LspLocation> definition(Path file, int line, int character) throws LspException {
        var params = new DefinitionParams(new TextDocumentIdentifier(file.toUri().toString()), new Position(line, character));
        Either<List<? extends Location>, List<? extends LocationLink>> answer = await(
                () -> server.getTextDocumentService().definition(params), timeout);
        var locations = new ArrayList<LspLocation>();
        if (answer == null) {
            return locations;
        }
        if (answer.isLeft()) {
            answer.getLeft().forEach(l -> locations.add(LspLocation.of(l.getUri(), l.getRange())));
        } else {
            answer.getRight().forEach(l -> locations.add(LspLocation.of(l.getTargetUri(), l.getTargetSelectionRange())));
        }
        return locations;
    }

    /**
     * @param file the document
     * @return the diagnostics the server last published for it
     */
    public List<Diagnostic> publishedDiagnostics(Path file) {
        return client.publishedDiagnostics(file.toUri().toString());
    }

    /**
     * @return whether the process is alive
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Ends the instance: {@code shutdown}, {@code exit}, then termination after the timeout.
     */
    @Override
    public void close() {
        try {
            await(server::shutdown, timeout);
            server.exit();
        } catch (LspException | JsonRpcException e) {
            LOGGER.warn(WARN.LSP_SHUTDOWN_FAILED, process.pid(), e.getMessage());
        }
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                var reason = String.format("no exit within %s", timeout);
                LOGGER.warn(WARN.LSP_SHUTDOWN_FAILED, process.pid(), reason);
                process.destroy();
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            listening.cancel(true);
        }
    }

    private static <T> T await(Supplier<CompletableFuture<T>> request, Duration timeout) throws LspException {
        CompletableFuture<T> future;
        try {
            future = request.get();
        } catch (JsonRpcException e) {
            throw new LspException(LspException.Kind.REQUEST_FAILED, "cannot send: " + e.getMessage(), e);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LspException(LspException.Kind.TIMEOUT, "no answer within " + timeout, e);
        } catch (ExecutionException e) {
            throw new LspException(LspException.Kind.REQUEST_FAILED, String.valueOf(e.getCause()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LspException(LspException.Kind.INTERRUPTED, "interrupted", e);
        }
    }
}
