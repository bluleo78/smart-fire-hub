package com.smartfirehub.pipeline.service.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;

@Service
public class SsrfProtectionService {

    private static final Logger log = LoggerFactory.getLogger(SsrfProtectionService.class);

    /**
     * Validates a URL string for SSRF safety.
     * Checks scheme, then resolves all DNS addresses for the hostname and validates each.
     *
     * @param url the URL string to validate
     * @throws SsrfException if the URL is unsafe
     */
    public void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new SsrfException("URL must not be null or blank");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new SsrfException("Invalid URL: " + url, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SsrfException("URL scheme not allowed: " + scheme + ". Only http and https are permitted.");
        }

        String hostname = uri.getHost();
        if (hostname == null || hostname.isBlank()) {
            throw new SsrfException("URL has no valid hostname: " + url);
        }

        // Resolve all IPs for the hostname to prevent DNS rebinding attacks
        InetAddress[] resolvedAddresses;
        try {
            resolvedAddresses = InetAddress.getAllByName(hostname);
        } catch (UnknownHostException e) {
            throw new SsrfException("Could not resolve hostname: " + hostname, e);
        }

        for (InetAddress address : resolvedAddresses) {
            validateResolvedAddress(address);
        }

        log.debug("URL passed SSRF validation: {}", url);
    }

    /**
     * Validates a resolved IP address against private/internal network ranges.
     * Call this for every hop in a redirect chain.
     *
     * @param address the resolved InetAddress to validate
     * @throws SsrfException if the address is private, loopback, link-local, multicast, or unspecified
     */
    public void validateResolvedAddress(InetAddress address) {
        if (address.isLoopbackAddress()) {
            throw new SsrfException("Requests to loopback addresses are not allowed: " + address.getHostAddress());
        }

        if (address.isSiteLocalAddress()) {
            // Covers RFC 1918: 10.x.x.x, 172.16-31.x.x, 192.168.x.x
            throw new SsrfException("Requests to private/site-local addresses are not allowed: " + address.getHostAddress());
        }

        if (address.isLinkLocalAddress()) {
            // Covers 169.254.x.x (IPv4) and fe80::/10 (IPv6)
            throw new SsrfException("Requests to link-local addresses are not allowed: " + address.getHostAddress());
        }

        if (address.isMulticastAddress()) {
            throw new SsrfException("Requests to multicast addresses are not allowed: " + address.getHostAddress());
        }

        if (address.isAnyLocalAddress()) {
            // Covers 0.0.0.0 and ::
            throw new SsrfException("Requests to wildcard addresses are not allowed: " + address.getHostAddress());
        }

        log.debug("Address passed SSRF validation: {}", address.getHostAddress());
    }
}
