package io.argus.core.net;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class HostAllowlistTest {

    @Test
    void rejectsLoopbackIPv4() {
        assertNotNull(HostAllowlist.rejectionReason("127.0.0.1"));
        assertNotNull(HostAllowlist.rejectionReason("127.255.255.255"));
    }

    @Test
    void rejectsLinkLocalAndImds() {
        assertNotNull(HostAllowlist.rejectionReason("169.254.169.254"));
        assertNotNull(HostAllowlist.rejectionReason("169.254.0.1"));
    }

    @Test
    void rejectsZeroAddress() {
        assertNotNull(HostAllowlist.rejectionReason("0.0.0.0"));
    }

    @Test
    void rejectsIPv6Loopback() {
        assertNotNull(HostAllowlist.rejectionReason("::1"));
        assertNotNull(HostAllowlist.rejectionReason("[::1]"));
    }

    @Test
    void rejectsIPv6UniqueLocal() {
        assertNotNull(HostAllowlist.rejectionReason("fc00::1"));
        assertNotNull(HostAllowlist.rejectionReason("fd12:3456::1"));
    }

    @Test
    void rejectsLocalhostName() {
        assertNotNull(HostAllowlist.rejectionReason("localhost"));
    }

    @Test
    void rejectsCloudMetadataNames() {
        assertNotNull(HostAllowlist.rejectionReason("metadata.google.internal"));
        assertNotNull(HostAllowlist.rejectionReason("metadata.azure.com"));
        assertNotNull(HostAllowlist.rejectionReason("metadata"));
    }

    @Test
    void rejectsBlankHost() {
        assertNotNull(HostAllowlist.rejectionReason(""));
        assertNotNull(HostAllowlist.rejectionReason(null));
        assertNotNull(HostAllowlist.rejectionReason("   "));
    }

    @Test
    void allowsRfc1918Ipv4() {
        assertNull(HostAllowlist.rejectionReason("10.0.0.1"));
        assertNull(HostAllowlist.rejectionReason("172.16.0.5"));
        assertNull(HostAllowlist.rejectionReason("192.168.1.10"));
        // Even public IPv4 is allowed by shape — operator/SRE is trusted to register
        // pods with routable IPs; we only block obviously-dangerous targets.
        assertNull(HostAllowlist.rejectionReason("203.0.113.5"));
    }

    @Test
    void allowsClusterDnsNames() {
        assertNull(HostAllowlist.rejectionReason("payment.prod.svc.cluster.local"));
        assertNull(HostAllowlist.rejectionReason("argus-agent-0.headless.default.svc"));
        assertNull(HostAllowlist.rejectionReason("pod-abc-123"));
    }

    @Test
    void rejectsMalformedHost() {
        assertNotNull(HostAllowlist.rejectionReason("not a host"));
        assertNotNull(HostAllowlist.rejectionReason("host with spaces"));
        // We normalize input to lowercase before applying the shape rule, so
        // "UPPER.case" is treated as "upper.case" and accepted — K8s DNS is
        // case-insensitive. Reject only when shape itself is invalid.
        assertNotNull(HostAllowlist.rejectionReason("under_score.bad"));
    }

    @Test
    void rejectsInvalidIPv4Octet() {
        assertNotNull(HostAllowlist.rejectionReason("256.0.0.1"));
        assertNotNull(HostAllowlist.rejectionReason("999.999.999.999"));
    }

    @Test
    void uriValidationRejectsFileScheme() {
        assertNotNull(HostAllowlist.rejectionReasonForUri(URI.create("file:///etc/passwd")));
    }

    @Test
    void uriValidationRejectsLoopback() {
        assertNotNull(HostAllowlist.rejectionReasonForUri(URI.create("http://127.0.0.1:8080/hook")));
        assertNotNull(HostAllowlist.rejectionReasonForUri(URI.create("https://localhost/hook")));
    }

    @Test
    void uriValidationAllowsHttpsClusterTarget() {
        assertNull(HostAllowlist.rejectionReasonForUri(URI.create("https://hooks.slack.com/services/X/Y/Z")));
        assertNull(HostAllowlist.rejectionReasonForUri(URI.create("http://payment.prod.svc.cluster.local:7070/prometheus")));
    }

    @Test
    void uriValidationRejectsImds() {
        assertNotNull(HostAllowlist.rejectionReasonForUri(URI.create("http://169.254.169.254/latest/meta-data/")));
    }
}
