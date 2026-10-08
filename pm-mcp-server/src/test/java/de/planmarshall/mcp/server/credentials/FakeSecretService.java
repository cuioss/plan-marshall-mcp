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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.planmarshall.mcp.server.credentials.dbus.DbusMessage;
import de.planmarshall.mcp.server.credentials.dbus.FakeBus;
import de.planmarshall.mcp.server.credentials.dbus.Variant;

/**
 * An in-memory {@code org.freedesktop.secrets} with one default collection, for {@link FakeBus}.
 */
class FakeSecretService implements FakeBus.Handler {

    static final String COLLECTION = "/org/freedesktop/secrets/collection/login";

    /** A stored item. */
    static final class Item {
        final Map<String, String> attributes;
        final String label;
        byte[] secret;

        Item(Map<String, String> attributes, String label, byte[] secret) {
            this.attributes = attributes;
            this.label = label;
            this.secret = secret;
        }
    }

    final Map<String, Item> items = new LinkedHashMap<>();
    final List<String> members = new ArrayList<>();
    boolean collectionLocked;
    boolean itemsLocked;
    boolean promptOnCreate;
    boolean noDefaultCollection;
    boolean serviceUnknown;
    private int next = 1;

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(DbusMessage call) {
        members.add(call.member());
        if (serviceUnknown) {
            return new FakeBus.ErrorReply("org.freedesktop.DBus.Error.ServiceUnknown", "no secrets service");
        }
        var args = call.body();
        return switch (call.member()) {
            case "OpenSession" -> new FakeBus.Reply("vo",
                    List.of(new Variant("s", ""), "/org/freedesktop/secrets/session/1"));
            case "ReadAlias" -> new FakeBus.Reply("o", List.of(noDefaultCollection ? "/" : COLLECTION));
            case "Get" -> "Locked".equals(args.get(1))
                    ? new FakeBus.Reply("v", List.of(new Variant("b", collectionLocked)))
                    : new FakeBus.Reply("v", List.of(new Variant("a{ss}", items.get(call.path()).attributes)));
            case "SearchItems" -> {
                var wanted = (Map<String, String>) args.getFirst();
                var matches = items.entrySet().stream()
                        .filter(e -> e.getValue().attributes.entrySet().containsAll(wanted.entrySet()))
                        .map(Map.Entry::getKey).toList();
                yield new FakeBus.Reply("aoao", itemsLocked ? List.of(List.of(), matches) : List.of(matches, List.of()));
            }
            case "CreateItem" -> {
                if (promptOnCreate) {
                    yield new FakeBus.Reply("oo", List.of("/", "/org/freedesktop/secrets/prompt/1"));
                }
                var properties = (Map<String, Variant>) args.getFirst();
                var attributes = (Map<String, String>) properties.get("org.freedesktop.Secret.Item.Attributes").value();
                var label = (String) properties.get("org.freedesktop.Secret.Item.Label").value();
                var secret = (byte[]) ((List<Object>) args.get(1)).get(2);
                var path = COLLECTION + "/" + next++;
                items.put(path, new Item(new LinkedHashMap<>(attributes), label, secret));
                yield new FakeBus.Reply("oo", List.of(path, "/"));
            }
            case "SetSecret" -> {
                items.get(call.path()).secret = (byte[]) ((List<Object>) args.getFirst()).get(2);
                yield new FakeBus.Reply("", List.of());
            }
            case "GetSecrets" -> {
                var secrets = new LinkedHashMap<String, List<Object>>();
                for (var item : (List<String>) args.getFirst()) {
                    secrets.put(item, List.of(args.get(1), new byte[0], items.get(item).secret, "text/plain"));
                }
                yield new FakeBus.Reply("a{o(oayays)}", List.of(secrets));
            }
            case "Delete" -> {
                items.remove(call.path());
                yield new FakeBus.Reply("o", List.of("/"));
            }
            default -> new FakeBus.ErrorReply("org.freedesktop.DBus.Error.UnknownMethod", call.member());
        };
    }
}
