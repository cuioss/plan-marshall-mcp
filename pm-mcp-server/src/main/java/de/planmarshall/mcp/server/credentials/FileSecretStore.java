/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;

/**
 * The permission-guarded fallback file store (PM-CRED-3): {@code <PM_MCP_BASE>/credentials/<key>.json}
 * and {@code <PM_MCP_BASE>/credentials/<project>/<key>.json}, directories {@code 0700}, files
 * {@code 0600}, written atomically. A file that is not owned by the runtime user or is accessible
 * to anyone else is refused ({@code credentials_file_insecure}); a file in another format is
 * refused ({@code credentials_file_invalid}).
 * <p>
 * The file format of this slice is {@code {"format_version":1,"value":"<secret>"}}: the secret of
 * the local API's credential resource; the full credential schema (url, auth type, expiry) of
 * PM-CRED-3 extends it with the operator verbs.
 */
public final class FileSecretStore implements SecretStore {

    /** The current file format version. */
    static final int FORMAT_VERSION = 1;

    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final JsonFactory JSON = new JsonFactory();

    private final Path directory;

    /**
     * @param directory {@code <PM_MCP_BASE>/credentials}
     */
    public FileSecretStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return FILE;
    }

    @Override
    public synchronized void put(CredentialAccount account, String secret) {
        var target = path(account);
        try {
            createPrivateDirectory(directory);
            createPrivateDirectory(target.getParent());
            var temp = Files.createTempFile(target.getParent(), ".tmp-", ".json",
                    PosixFilePermissions.asFileAttribute(FILE_MODE));
            try {
                Files.writeString(temp, json(secret), StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            requirePrivate(target, FILE_MODE);
        } catch (IOException e) {
            throw new SecretStoreException(SecretStoreException.Reason.FAILED,
                    "cannot write credential file " + target, e);
        }
    }

    @Override
    public synchronized Optional<String> get(CredentialAccount account) {
        var file = path(account);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            requirePrivate(directory, DIRECTORY_MODE);
            requirePrivate(file.getParent(), DIRECTORY_MODE);
            requirePrivate(file, FILE_MODE);
            return Optional.of(parse(file, Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new SecretStoreException(SecretStoreException.Reason.FAILED, "cannot read credential file " + file, e);
        }
    }

    @Override
    public synchronized boolean delete(CredentialAccount account) {
        var file = path(account);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new SecretStoreException(SecretStoreException.Reason.FAILED,
                    "cannot delete credential file " + file, e);
        }
    }

    /**
     * @param account the account
     * @return its file, canonicalized below the store directory
     */
    Path path(CredentialAccount account) {
        var file = directory.resolve(account.account() + ".json").normalize();
        if (!file.startsWith(directory)) {
            throw new InvalidCredentialNameException(account.account());
        }
        return file;
    }

    private static void createPrivateDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            Files.setPosixFilePermissions(dir, DIRECTORY_MODE);
        }
        requirePrivate(dir, DIRECTORY_MODE);
    }

    private static void requirePrivate(Path path, Set<PosixFilePermission> mode) throws IOException {
        var owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        var user = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || !owner.equals(user) || !permissions.equals(mode)) {
            throw new SecretStoreException(SecretStoreException.Reason.INSECURE,
                    path + " must be owned by " + user.getName() + " with mode "
                            + PosixFilePermissions.toString(mode) + ", is " + owner.getName() + " "
                            + PosixFilePermissions.toString(permissions));
        }
    }

    private static String json(String secret) throws IOException {
        var writer = new StringWriter();
        try (var generator = JSON.createGenerator(writer)) {
            generator.writeStartObject();
            generator.writeNumberField("format_version", FORMAT_VERSION);
            generator.writeStringField("value", secret);
            generator.writeEndObject();
        }
        return writer.toString();
    }

    private static String parse(Path file, String content) throws IOException {
        Integer version = null;
        String value = null;
        try (JsonParser parser = JSON.createParser(content)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid(file, "not a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var field = parser.currentName();
                var token = parser.nextToken();
                switch (field) {
                    case "format_version" -> version = token == JsonToken.VALUE_NUMBER_INT ? parser.getIntValue() : -1;
                    case "value" -> value = token == JsonToken.VALUE_STRING ? parser.getText() : null;
                    default -> throw invalid(file, "undeclared field '" + field + "'");
                }
            }
        } catch (JsonProcessingException e) {
            throw invalid(file, "malformed JSON");
        }
        if (version == null || version != FORMAT_VERSION) {
            throw invalid(file, "field 'format_version' must be " + FORMAT_VERSION);
        }
        if (value == null) {
            throw invalid(file, "field 'value' must be a string");
        }
        return value;
    }

    private static SecretStoreException invalid(Path file, String reason) {
        return new SecretStoreException(SecretStoreException.Reason.INVALID, file + ": " + reason);
    }
}
