package com.ruleup.ruleup_backend.auth.review;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Cloudflare 엣지 주소 대역 — ALB 에 붙은 주소가 Cloudflare 인지 가린다({@link ReviewLoginAttemptLimiter#clientIp}).
 *
 * <p>출처: https://www.cloudflare.com/ips-v4 · https://www.cloudflare.com/ips-v6 (2026-10-31 확인). Cloudflare 가 대역을
 * 늘리면 새 대역의 엣지는 「직접 접근」으로 보여 그 엣지 주소로 횟수를 센다 — 막히는 쪽으로만 틀리고 우회되지는 않는다.
 *
 * <p>헤더 값은 클라이언트가 보낼 수 있으므로 <b>IP 리터럴만</b> 받는다 — 호스트 이름을 넘기면 DNS 조회가 일어난다.
 */
final class CloudflareRanges {

    private static final List<Cidr> RANGES = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
            "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
            "162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
            "2a06:98c0::/29", "2c0f:f248::/32"
    ).stream().map(Cidr::parse).toList();

    private CloudflareRanges() {}

    static boolean contains(String address) {
        byte[] ip = literal(address);
        if (ip == null) return false;
        for (Cidr range : RANGES) {
            if (range.contains(ip)) return true;
        }
        return false;
    }

    /** IPv4 점 표기 또는 IPv6 리터럴만 바이트로 바꾼다. 그 밖(호스트 이름 등)은 null. */
    private static byte[] literal(String address) {
        if (address == null) return null;
        String a = address.trim();
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        boolean v4 = a.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = a.contains(":") && a.matches("[0-9A-Fa-f:.]+");
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(a).getAddress();   // 리터럴이라 DNS 조회가 없다
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefix) {
        static Cidr parse(String cidr) {
            String[] parts = cidr.split("/");
            byte[] network = literal(parts[0]);
            if (network == null) throw new IllegalArgumentException(cidr);
            return new Cidr(network, Integer.parseInt(parts[1]));
        }

        boolean contains(byte[] ip) {
            if (ip.length != network.length) return false;   // IPv4 ↔ IPv6
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (ip[i] != network[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (ip[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
