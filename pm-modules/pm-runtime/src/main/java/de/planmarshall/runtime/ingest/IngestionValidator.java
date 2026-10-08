/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.ingest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;

/**
 * Stage 2 (Markdown AST) of the level-1 ingestion validator in refusing mode
 * (doc/specification/job-runtime/03-parsers-and-gates.adoc, Ingestion Validator): the text is parsed with
 * {@code commonmark}; raw HTML blocks, inline HTML and images are findings, and every link destination must be
 * an {@code https} URL (or a relative link) whose path and query pass the {@code cui-http} URL validation
 * pipelines. A text with any finding is refused; nothing is changed silently.
 * <p>
 * The other stages (Unicode fixpoint, bounds, rule table, spotlighting) are not part of this slice.
 *
 * @since 0.1
 */
public class IngestionValidator {

    /**
     * One finding of the change report.
     *
     * @param kind   the finding kind: {@code html_block}, {@code html_inline}, {@code image}, {@code link_scheme},
     *               {@code link_malformed} or {@code link_rejected}
     * @param detail what was found, for the operator
     */
    public record Finding(String kind, String detail) {
    }

    /**
     * The change report of one text.
     *
     * @param findings every finding, in document order
     */
    public record Report(List<Finding> findings) {

        /**
         * @param findings every finding, in document order
         */
        public Report {
            findings = List.copyOf(findings);
        }

        /** @return {@code true} if the text is refused */
        public boolean refused() {
            return !findings.isEmpty();
        }
    }

    private final Parser parser = Parser.builder().build();
    private final HttpSecurityValidator pathPipeline;
    private final HttpSecurityValidator parameterPipeline;

    /** Creates a validator with the default {@code cui-http} security configuration. */
    public IngestionValidator() {
        var configuration = SecurityConfiguration.defaults();
        var counter = new SecurityEventCounter();
        pathPipeline = PipelineFactory.createUrlPathPipeline(configuration, counter);
        parameterPipeline = PipelineFactory.createUrlParameterPipeline(configuration, counter);
    }

    /**
     * @param markdown the external text
     * @return the change report; a non-empty report refuses the text
     */
    public Report validate(String markdown) {
        var findings = new ArrayList<Finding>();
        parser.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(HtmlBlock htmlBlock) {
                findings.add(new Finding("html_block", htmlBlock.getLiteral().strip()));
            }

            @Override
            public void visit(HtmlInline htmlInline) {
                findings.add(new Finding("html_inline", htmlInline.getLiteral()));
            }

            @Override
            public void visit(Image image) {
                findings.add(new Finding("image", image.getDestination()));
                visitChildren(image);
            }

            @Override
            public void visit(Link link) {
                checkLink(link.getDestination(), findings);
                visitChildren(link);
            }
        });
        return new Report(findings);
    }

    private void checkLink(String destination, List<Finding> findings) {
        URI uri;
        try {
            uri = new URI(destination);
        } catch (URISyntaxException _) {
            findings.add(new Finding("link_malformed", destination));
            return;
        }
        var scheme = uri.getScheme();
        if (scheme != null && !"https".equals(scheme.toLowerCase(Locale.ROOT))) {
            findings.add(new Finding("link_scheme", destination));
            return;
        }
        if (scheme != null && (uri.getRawUserInfo() != null || uri.getHost() == null)) {
            findings.add(new Finding("link_rejected", destination));
            return;
        }
        try {
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                pathPipeline.validate(uri.getRawPath());
            }
            if (uri.getRawQuery() != null) {
                for (var parameter : uri.getRawQuery().split("&")) {
                    var separator = parameter.indexOf('=');
                    parameterPipeline.validate(separator < 0 ? parameter : parameter.substring(separator + 1));
                }
            }
        } catch (UrlSecurityException e) {
            findings.add(new Finding("link_rejected", destination + " (" + e.getFailureType() + ")"));
        }
    }
}
