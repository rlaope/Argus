package io.argus.core.net;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Validates a host (or full URL) against a conservative deny list of
 * addresses that an aggregator or webhook sender must never reach out to,
 * to avoid SSRF / IMDS / loopback abuse.
 *
 * <p>The allowlist is intentionally simple — string-shape checks only, no DNS
 * resolution. DNS resolution at validation time would expose us to TOCTOU
 * (an attacker-controlled name resolving to a private IP between our check
 * and the HTTP client's resolution).
 *
 * <p>Denied:
 * <ul>
 *   <li>IPv4 loopback {@code 127.0.0.0/8}</li>
 *   <li>IPv4 link-local + cloud IMDS {@code 169.254.0.0/16}</li>
 *   <li>IPv4 "any" {@code 0.0.0.0}</li>
 *   <li>IPv6 loopback {@code ::1}</li>
 *   <li>IPv6 unique-local {@code fc00::/7}</li>
 * </ul>
 *
 * <p>Allowed (positive shape match):
 * <ul>
 *   <li>K8s cluster DNS: hostname ending in {@code .svc.cluster.local} or {@code .svc} or {@code .local}</li>
 *   <li>Simple hostnames matching {@code [a-z0-9-]+(\.[a-z0-9-]+)*} (RFC 1123-ish labels)</li>
 *   <li>RFC 1918 IPv4 private ranges ({@code 10.0.0.0/8}, {@code 172.16.0.0/12}, {@code 192.168.0.0/16})</li>
 * </ul>
 */
public final class HostAllowlist {

    private static final Pattern HOSTNAME = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$"
    );
    private static final Pattern IPV4 = Pattern.compile(
            "^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$"
    );

    private HostAllowlist() {}

    /**
     * Returns the rejection reason as a human-readable string, or {@code null}
     * if the host is allowed.
     */
    public static String rejectionReason(String host) {
        if (host == null || host.isBlank()) {
            return "host is blank";
        }
        String lower = host.toLowerCase().trim();
        // Strip IPv6 brackets if present
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1);
        }

        // IPv6 explicit denials
        if (lower.equals("::1") || lower.equals("::") || lower.equals("0:0:0:0:0:0:0:1")) {
            return "loopback IPv6 address forbidden";
        }
        if (lower.startsWith("fc") || lower.startsWith("fd")) {
            // fc00::/7 = fc and fd prefix
            if (lower.contains(":")) {
                return "unique-local IPv6 address forbidden";
            }
        }
        if (lower.startsWith("fe80:") || lower.startsWith("fe8") || lower.startsWith("fe9")
                || lower.startsWith("fea") || lower.startsWith("feb")) {
            if (lower.contains(":")) {
                return "link-local IPv6 address forbidden";
            }
        }

        java.util.regex.Matcher v4 = IPV4.matcher(lower);
        if (v4.matches()) {
            int a, b, c, d;
            try {
                a = Integer.parseInt(v4.group(1));
                b = Integer.parseInt(v4.group(2));
                c = Integer.parseInt(v4.group(3));
                d = Integer.parseInt(v4.group(4));
            } catch (NumberFormatException e) {
                return "invalid IPv4 address";
            }
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) {
                return "invalid IPv4 octet";
            }
            // 0.0.0.0
            if (a == 0 && b == 0 && c == 0 && d == 0) {
                return "0.0.0.0 forbidden";
            }
            // 127.0.0.0/8
            if (a == 127) {
                return "loopback IPv4 forbidden";
            }
            // 169.254.0.0/16 (link-local + cloud IMDS)
            if (a == 169 && b == 254) {
                return "link-local IPv4 (incl. cloud IMDS) forbidden";
            }
            // RFC 1918 / standard private — allowed
            return null;
        }

        // Hostname shape
        if (!HOSTNAME.matcher(lower).matches()) {
            return "host does not look like a valid DNS name or IP";
        }
        if (lower.length() > 253) {
            return "host length exceeds 253 chars";
        }
        // Block names that obviously target cloud metadata endpoints by name
        if (lower.equals("metadata.google.internal")
                || lower.equals("metadata.azure.com")
                || lower.equals("metadata")
                || lower.equals("localhost")) {
            return "cloud metadata / localhost host forbidden";
        }
        return null;
    }

    /** Convenience: throw-style check. */
    public static boolean isAllowed(String host) {
        return rejectionReason(host) == null;
    }

    /**
     * Validates that the URI uses an allowed scheme and an allowed host.
     * Returns the rejection reason or {@code null} when allowed.
     */
    public static String rejectionReasonForUri(URI uri) {
        if (uri == null) return "uri is null";
        String scheme = uri.getScheme();
        if (scheme == null) return "scheme is null";
        String s = scheme.toLowerCase();
        if (!s.equals("http") && !s.equals("https")) {
            return "scheme '" + scheme + "' not in {http, https}";
        }
        return rejectionReason(uri.getHost());
    }
}
