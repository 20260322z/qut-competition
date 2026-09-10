package com.ruoyi.zhcp.security;

import com.ruoyi.zhcp.common.LoginUser;
import com.ruoyi.zhcp.config.ZhcpProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class TokenService {
    private final ZhcpProperties props;

    public TokenService(ZhcpProperties props) {
        this.props = props;
    }

    private SecretKey key() {
        byte[] raw = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[32];
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, 32));
        return Keys.hmacShaKeyFor(key);
    }

    public String create(LoginUser user) {
        long ttl = props.getJwtTtlHours() * 3600_000L;
        return Jwts.builder()
                .subject(String.valueOf(user.userId))
                .claim("userName", user.userName)
                .claim("nickName", user.nickName)
                .claim("role", user.role)
                .claim("studentNo", user.studentNo)
                .claim("college", user.college)
                .claim("className", user.className)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttl))
                .signWith(key())
                .compact();
    }

    public LoginUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
        LoginUser u = new LoginUser();
        u.userId = Long.parseLong(c.getSubject());
        u.userName = c.get("userName", String.class);
        u.nickName = c.get("nickName", String.class);
        u.role = c.get("role", String.class);
        u.studentNo = c.get("studentNo", String.class);
        u.college = c.get("college", String.class);
        u.className = c.get("className", String.class);
        return u;
    }
}
