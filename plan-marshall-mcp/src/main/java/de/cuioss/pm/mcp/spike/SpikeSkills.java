/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;


import de.cuioss.pm.mcp.spike.SpikeQueue.Caller;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The skill catalogue of a run and the record of what each worker generation received.
 * <p>
 * The catalogue is a directory: every {@code SKILL.md} below it is a skill, the other files of its directory
 * are its supporting files. A file {@code role/triage/SKILL.md} has the URI
 * {@code skill://pm/role/triage/SKILL.md}; its digest is the SHA-256 of its content.
 * <p>
 * A required skill is delivered in-band once per worker generation. The record of a generation is empty at
 * its start, is cleared when the driver reports a compaction, and no longer matches once the digest of a
 * skill changed, so the skill is delivered again in all three cases.
 */
final class SpikeSkills {

    /** Prefix of every skill URI. */
    static final String SCHEME = "skill://pm/";

    private static final String SKILL_FILE = "SKILL.md";
    private static final String URI = "uri";
    private static final String DIGEST = "digest";

    /**
     * One file of the catalogue.
     *
     * @param uri     the URI
     * @param content the text
     * @param digest  {@code sha256:} and the hexadecimal SHA-256 of the content
     * @param size    the size in bytes
     */
    record File(String uri, String content, String digest, int size) {

        static File of(String uri, String content) {
            var bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                var hash = MessageDigest.getInstance("SHA-256").digest(bytes);
                return new File(uri, content, "sha256:" + HexFormat.of().formatHex(hash), bytes.length);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * The skills of one offer.
     *
     * @param skills the entries of the offer: URI and digest, with the content when delivered in-band
     * @param events one entry per delivery or missing skill, for the event log
     */
    record Delivery(JsonArray skills, List<JsonObject> events) {
    }

    private final Map<String, File> files = new TreeMap<>();
    private final Map<String, Map<String, String>> delivered = new HashMap<>();

    /**
     * Reads a catalogue directory.
     *
     * @param directory the directory
     * @return the catalogue
     * @throws IOException when the directory cannot be read
     */
    static SpikeSkills load(Path directory) throws IOException {
        var skills = new SpikeSkills();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var relative = directory.relativize(path).toString().replace('\\', '/');
                skills.put(SCHEME + relative, Files.readString(path));
            }
        }
        return skills;
    }

    /**
     * Adds or replaces a file; a changed digest makes every record of the file outdated.
     *
     * @param uri     the URI
     * @param content the text
     * @return the file
     */
    synchronized File put(String uri, String content) {
        var file = File.of(uri, content);
        files.put(uri, file);
        return file;
    }

    synchronized Optional<File> file(String uri) {
        return Optional.ofNullable(files.get(uri));
    }

    synchronized List<File> files() {
        return List.copyOf(files.values());
    }

    /**
     * @return one manifest per skill: its URI, name and description, and every file with digest and size
     */
    synchronized JsonArray manifests() {
        var manifests = new JsonArray();
        for (var file : files.values()) {
            if (!file.uri().endsWith("/" + SKILL_FILE)) {
                continue;
            }
            var directory = file.uri().substring(0, file.uri().length() - SKILL_FILE.length());
            var entries = new JsonArray();
            files.values().stream().filter(other -> other.uri().startsWith(directory))
                    .forEach(other -> entries.add(new JsonObject().put(URI, other.uri())
                            .put(DIGEST, other.digest()).put("size", other.size())));
            manifests.add(new JsonObject().put(URI, file.uri()).put("name", frontmatter(file.content(), "name"))
                    .put("description", frontmatter(file.content(), "description")).put("files", entries));
        }
        return manifests;
    }

    /**
     * Composes the skills of an offer and records what is delivered.
     *
     * @param caller the worker generation the offer goes to
     * @param uris   the skills the task requires
     * @param byUri  whether the connection activates skills itself, so that the offer names them only
     * @return the entries of the offer and the events to log
     */
    synchronized Delivery deliver(Caller caller, List<String> uris, boolean byUri) {
        var received = delivered.computeIfAbsent(key(caller), _ -> new HashMap<>());
        var skills = new JsonArray();
        var events = new ArrayList<JsonObject>();
        for (var uri : uris) {
            var file = files.get(uri);
            if (file == null) {
                skills.add(new JsonObject().put(URI, uri).put("missing", true));
                events.add(new JsonObject().put("event", "skill_missing").put(URI, uri));
                continue;
            }
            var entry = new JsonObject().put(URI, uri).put(DIGEST, file.digest());
            if (!file.digest().equals(received.get(uri))) {
                received.put(uri, file.digest());
                if (!byUri) {
                    entry.put("content", file.content());
                }
                events.add(new JsonObject().put("event", "skill_delivered").put(URI, uri)
                        .put(DIGEST, file.digest()).put("form", byUri ? URI : "inband"));
            }
            skills.add(entry);
        }
        return new Delivery(skills, events);
    }

    /**
     * Records that a worker generation read a file itself.
     *
     * @param caller the reader
     * @param file   the file
     */
    synchronized void read(Caller caller, File file) {
        delivered.computeIfAbsent(key(caller), _ -> new HashMap<>()).put(file.uri(), file.digest());
    }

    /**
     * Clears the record of a worker whose context was compacted.
     *
     * @param worker the worker
     */
    synchronized void compacted(String worker) {
        delivered.keySet().removeIf(key -> key.startsWith(worker + "#"));
    }

    private static String key(Caller caller) {
        return caller.worker() + "#" + caller.generation();
    }

    private static String frontmatter(String content, String field) {
        var lines = content.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) {
            return null;
        }
        for (var line : lines.subList(1, lines.size())) {
            if ("---".equals(line.strip())) {
                break;
            }
            if (line.startsWith(field + ":")) {
                return line.substring(field.length() + 1).strip();
            }
        }
        return null;
    }
}
