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

        // Reject any authority-grammar reserved character. These never belong
        // in a host and a concatenation of "http://" + host + ":" + port
        // would re-route URL parsing if they slipped through. Catches
        // userinfo injection (e.g. "a@169.254.169.254"), path/query
        // smuggling, control characters, and embedded whitespace.
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '@' || c == '/' || c == '\\' || c == '?' || c == '#'
                    || c == ' ' || c == '%' || c == '\t' || c == '\r' || c == '\n'
                    || c < 0x20 || c > 0x7e) {
                return "host contains reserved character";
            }
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
        // IPv4-mapped/embedded IPv6 (e.g. "::ffff:169.254.169.254") — pull the
        // trailing IPv4 portion and run it through the IPv4 deny checks.
        // Without this, an attacker can register a forbidden v4 address using
        // its v6 mapping and bypass the v4 octet check below.
        if (lower.contains(":") && lower.contains(".")) {
            int lastColon = lower.lastIndexOf(':');
            String tail = lower.substring(lastColon + 1);
            String tailReason = ipv4Reason(tail);
            if (tailReason != null) {
                return "IPv4-embedded IPv6 " + tailReason;
            }
        }

        String v4Reason = ipv4Reason(lower);
        if (v4Reason != null) return v4Reason;
        if (IPV4.matcher(lower).matches()) {
            // Standard dotted-quad form, already vetted by ipv4Reason → allowed.
            return null;
        }

        // Non-dotted-quad numeric forms that InetAddress would still
        // resolve as IPv4 — decimal integer ("2852039166"), hex
        // ("0xa9fea9fe"), or short forms ("169.4262", "127.1"). Reject so
        // attackers cannot smuggle 169.254.169.254 past the v4 octet check.
        if (looksLikeNumericIpv4(lower)) {
            return "numeric IPv4 literal forbidden (use dotted-quad form)";
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
     * Returns a rejection reason if {@code s} parses as a forbidden
     * dotted-quad IPv4 address. Returns {@code null} either when the input
     * is not a dotted-quad at all (caller should keep trying) or when the
     * address is a permitted private range.
     */
    private static String ipv4Reason(String s) {
        java.util.regex.Matcher v4 = IPV4.matcher(s);
        if (!v4.matches()) return null;
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
        if (a == 0 && b == 0 && c == 0 && d == 0) return "0.0.0.0 forbidden";
        if (a == 127) return "loopback IPv4 forbidden";
        if (a == 169 && b == 254) return "link-local IPv4 (incl. cloud IMDS) forbidden";
        return null;
    }

    /**
     * Detects numeric IPv4 forms that {@link java.net.InetAddress} would
     * interpret as an address but our dotted-quad regex would not catch.
     */
    private static boolean looksLikeNumericIpv4(String s) {
        if (s.isEmpty()) return false;
        // Pure decimal integer: e.g. "2852039166" -> 169.254.169.254
        if (s.chars().allMatch(Character::isDigit)) return true;
        // Hex integer: e.g. "0xa9fea9fe"
        if (s.startsWith("0x") && s.length() > 2
                && s.substring(2).chars().allMatch(HostAllowlist::isHexChar)) {
            return true;
        }
        // Short forms like "127.1" or "169.4262": fewer than 4 octets, every
        // part is purely numeric (decimal or 0x-hex).
        if (s.contains(".")) {
            String[] parts = s.split("\\.", -1);
            if (parts.length >= 1 && parts.length <= 3) {
                for (String p : parts) {
                    if (p.isEmpty()) return false;
                    if (p.startsWith("0x")) {
                        if (p.length() < 3) return false;
                        if (!p.substring(2).chars().allMatch(HostAllowlist::isHexChar)) {
                            return false;
                        }
                    } else if (!p.chars().allMatch(Character::isDigit)) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isHexChar(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
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
