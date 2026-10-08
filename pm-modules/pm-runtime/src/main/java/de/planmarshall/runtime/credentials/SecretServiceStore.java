/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.credentials;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import de.planmarshall.runtime.credentials.dbus.DbusConnection;
import de.planmarshall.runtime.credentials.dbus.DbusException;
import de.planmarshall.runtime.credentials.dbus.Variant;

/**
 * The Linux Secret Service backend (PM-CRED-2): items of the default collection (alias
 * {@code default}) with the attributes {@code service=<service name>}, {@code credential_key=<key>}
 * and, for a project entry, {@code project=<project>}, over the session bus with a {@code plain}
 * session (the bus is a same-user Unix socket).
 * <p>
 * A locked collection or item is reported as {@link SecretStoreException.Reason#LOCKED}; the store
 * never calls {@code Unlock}, which may need an interactive prompt, so no operation waits for a
 * user. Every bus exchange is bounded by the timeout. A global entry is an item without a
 * {@code project} attribute: the attribute search matches subsets, so project items are filtered out.
 */
public final class SecretServiceStore implements SecretStore {

    static final String SECRETS = "org.freedesktop.secrets";
    static final String SERVICE_PATH = "/org/freedesktop/secrets";
    static final String SERVICE = "org.freedesktop.Secret.Service";
    static final String COLLECTION = "org.freedesktop.Secret.Collection";
    static final String ITEM = "org.freedesktop.Secret.Item";
    static final String PROPERTIES = "org.freedesktop.DBus.Properties";
    static final String NO_PROMPT = "/";

    private final Path busSocket;
    private final long uid;
    private final String serviceName;
    private final Duration timeout;

    /**
     * @param busSocket the session bus socket
     * @param uid       the uid of this process
     * @param service   the service name ({@link ServiceName})
     * @param timeout   the bound of every bus exchange
     */
    public SecretServiceStore(Path busSocket, long uid, String service, Duration timeout) {
        this.busSocket = busSocket;
        this.uid = uid;
        this.serviceName = service;
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return SECRET_SERVICE;
    }

    /**
     * Probes the backend: the bus answers, the Secret Service opens a session, and a default
     * collection exists and is unlocked.
     *
     * @return whether the backend is usable now
     */
    public boolean available() {
        try (var session = new Session()) {
            return !session.locked(session.defaultCollection());
        } catch (DbusException | SecretStoreException _) {
            return false;
        }
    }

    @Override
    public void put(CredentialAccount account, String secret) {
        try (var session = new Session()) {
            var secretStruct = List.of(session.path, new byte[0], secret.getBytes(StandardCharsets.UTF_8),
                    "text/plain; charset=utf8");
            var existing = session.exactItems(account);
            if (!existing.isEmpty()) {
                session.call(existing.getFirst(), ITEM, "SetSecret", "(oayays)", secretStruct);
                return;
            }
            var collection = session.defaultCollection();
            if (session.locked(collection)) {
                throw locked("collection " + collection + " is locked");
            }
            var properties = new LinkedHashMap<String, Variant>();
            properties.put(ITEM + ".Label", new Variant("s", serviceName + " " + account.account()));
            properties.put(ITEM + ".Attributes", new Variant("a{ss}", attributes(account)));
            var reply = session.call(collection, COLLECTION, "CreateItem", "a{sv}(oayays)b", properties, secretStruct,
                    false);
            if (!NO_PROMPT.equals(reply.get(1))) {
                throw locked("CreateItem needs a prompt");
            }
        } catch (DbusException e) {
            throw failed("put", e);
        }
    }

    @Override
    public Optional<String> get(CredentialAccount account) {
        try (var session = new Session()) {
            var items = session.exactItems(account);
            if (items.isEmpty()) {
                return Optional.empty();
            }
            var item = items.getFirst();
            var reply = session.call(SERVICE_PATH, SERVICE, "GetSecrets", "aoo", List.of(item), session.path);
            var secrets = (Map<?, ?>) reply.getFirst();
            var secret = (List<?>) secrets.get(item);
            if (secret == null) {
                throw locked("item " + item + " is locked");
            }
            return Optional.of(new String((byte[]) secret.get(2), StandardCharsets.UTF_8));
        } catch (DbusException e) {
            throw failed("get", e);
        }
    }

    @Override
    public boolean delete(CredentialAccount account) {
        try (var session = new Session()) {
            var items = session.exactItems(account);
            for (var item : items) {
                var prompt = session.call(item, ITEM, "Delete", "").getFirst();
                if (!NO_PROMPT.equals(prompt)) {
                    throw locked("Delete needs a prompt");
                }
            }
            return !items.isEmpty();
        } catch (DbusException e) {
            throw failed("delete", e);
        }
    }

    /**
     * @param account the account
     * @return the lookup attributes of the account
     */
    Map<String, String> attributes(CredentialAccount account) {
        var attributes = new LinkedHashMap<String, String>();
        attributes.put("service", serviceName);
        attributes.put("credential_key", account.key());
        account.project().ifPresent(p -> attributes.put("project", p));
        return attributes;
    }

    private static SecretStoreException locked(String message) {
        return new SecretStoreException(SecretStoreException.Reason.LOCKED, message);
    }

    private static SecretStoreException failed(String operation, DbusException e) {
        return new SecretStoreException(SecretStoreException.Reason.FAILED,
                "Secret Service " + operation + " failed: " + e.errorName() + " " + e.getMessage(), e);
    }

    /** One bus connection with an open {@code plain} session. */
    private final class Session implements AutoCloseable {

        private final DbusConnection connection;
        private final String path;

        Session() {
            connection = DbusConnection.open(busSocket, uid, timeout);
            try {
                path = (String) call(SERVICE_PATH, SERVICE, "OpenSession", "sv", "plain", new Variant("s", ""))
                        .get(1);
            } catch (DbusException e) {
                connection.close();
                throw e;
            }
        }

        List<Object> call(String objectPath, String iface, String member, String signature, Object... args) {
            return connection.call(SECRETS, objectPath, iface, member, signature, List.of(args));
        }

        String defaultCollection() {
            var collection = (String) call(SERVICE_PATH, SERVICE, "ReadAlias", "s", "default").getFirst();
            if (NO_PROMPT.equals(collection)) {
                throw new SecretStoreException(SecretStoreException.Reason.FAILED, "no default collection");
            }
            return collection;
        }

        boolean locked(String collection) {
            var value = (Variant) call(collection, PROPERTIES, "Get", "ss", COLLECTION, "Locked").getFirst();
            return Boolean.TRUE.equals(value.value());
        }

        List<String> exactItems(CredentialAccount account) {
            var reply = call(SERVICE_PATH, SERVICE, "SearchItems", "a{ss}", attributes(account));
            var unlocked = strings(reply.get(0));
            var locked = strings(reply.get(1));
            var exact = account.project().isPresent() ? unlocked
                    : unlocked.stream().filter(item -> !itemAttributes(item).containsKey("project")).toList();
            if (exact.isEmpty() && !locked.isEmpty()) {
                throw SecretServiceStore.locked("matching items are locked");
            }
            return exact;
        }

        private Map<?, ?> itemAttributes(String item) {
            var value = (Variant) call(item, PROPERTIES, "Get", "ss", ITEM, "Attributes").getFirst();
            return (Map<?, ?>) value.value();
        }

        private static List<String> strings(Object list) {
            return ((List<?>) list).stream().map(String.class::cast).toList();
        }

        @Override
        public void close() {
            connection.close();
        }
    }
}
