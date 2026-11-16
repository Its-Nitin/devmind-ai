package com.devmind.ingestion.internal.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SEC-3 rules for remote repository URLs: {@code https://<allowed host>/<owner>/<repo>[.git]} and nothing else. No
 * user-info, port, query, fragment, percent-encoding or IP literal, and the host must match an allow-list entry
 * exactly (no subdomains).
 *
 * <p>Validation is lexical only. Redirects, which could lead elsewhere after validation, are refused by the cloner.
 */
public final class RemoteUrlPolicy {

    /** Longer URLs are rejected before parsing. */
    public static final int MAX_LENGTH = 300;

    private static final Pattern REPOSITORY_PATH = Pattern.compile("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern IPV4_LIKE = Pattern.compile("[0-9.]+|0x.*");
    private static final Pattern HOST_NAME =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private final Set<String> allowedHosts;

    /**
     * @param allowedHosts lowercase DNS host names, e.g. {@code github.com}
     * @throws IllegalArgumentException if an entry isn't a lowercase DNS name (IP literals included)
     */
    public RemoteUrlPolicy(Collection<String> allowedHosts) {
        for (String host : allowedHosts) {
            if (!isHostName(host)) {
                throw new IllegalArgumentException("allowed host must be a lowercase DNS name: " + host);
            }
        }
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    /** Returns the URL normalized to {@code https://<host>/<owner>/<repo>[.git]}, or empty if it isn't allowed. */
    public Optional<URI> validate(String url) {
        if (url.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (uri.isOpaque()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || host == null
                || path == null) {
            return Optional.empty();
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(normalizedHost) || !isHostName(normalizedHost) || !isRepositoryPath(path)) {
            return Optional.empty();
        }
        return Optional.of(URI.create("https://" + normalizedHost + path));
    }

    private static boolean isHostName(String host) {
        return HOST_NAME.matcher(host).matches() && !IPV4_LIKE.matcher(host).matches();
    }

    private static boolean isRepositoryPath(String path) {
        if (!REPOSITORY_PATH.matcher(path).matches()) {
            return false;
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
