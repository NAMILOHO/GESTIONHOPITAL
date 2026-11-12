package com.call;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * POST /iceConfig : serveurs STUN/TURN pour WebRTC, reserve aux patients et medecins connectes.
 *
 * Configuration (propriete JVM, par exemple -Dturn.secret=..., ou variable d'environnement TURN_SECRET) :
 * - ice.stun.urls : serveurs STUN, separes par des virgules (defaut : stun.l.google.com:19302)
 * - turn.urls     : serveurs TURN (turn:hote:3478?transport=udp,turns:hote:5349?transport=tcp)
 * - turn.secret   : secret partage avec coturn (static-auth-secret) ; JAMAIS dans le code source
 * - turn.ttl.sec  : validite des identifiants TURN (defaut 3600)
 *
 * Les identifiants TURN sont temporaires (methode "REST API" de coturn) : nom d'utilisateur =
 * "<expiration>:<role>-<id>", mot de passe = base64(HMAC-SHA1(secret, nom d'utilisateur)). Le secret
 * ne quitte jamais le serveur, et un identifiant vole expire en quelques minutes.
 */
@WebServlet("/iceConfig")
public class IceConfigServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(IceConfigServlet.class.getName());
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String DEFAULT_STUN = "stun:stun.l.google.com:19302";

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        resp.setHeader("Cache-Control", "no-store");
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        if (!"XMLHttpRequest".equals(req.getHeader("X-Requested-With"))) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        SessionIdentity.Result who = SessionIdentity.resolve(req.getSession(false));
        if (!who.ok()) {
            resp.sendError(who.status);
            return;
        }
        resp.getWriter().write(build(who.identity, System.currentTimeMillis() / 1000L));
    }

    /** Construit la reponse JSON (separee de doPost pour pouvoir etre testee). */
    static String build(Identity who, long nowSeconds) {
        List<String> servers = new ArrayList<String>();

        List<String> stun = urls(setting("ice.stun.urls"), DEFAULT_STUN);
        if (!stun.isEmpty()) {
            servers.add("{\"urls\":" + array(stun) + "}");
        }

        List<String> turn = urls(setting("turn.urls"), null);
        String secret = setting("turn.secret");
        long ttl = 3600;
        try {
            String t = setting("turn.ttl.sec");
            if (t != null) {
                ttl = Math.max(60, Math.min(86400, Long.parseLong(t.trim())));
            }
        } catch (NumberFormatException e) {
            ttl = 3600;
        }
        if (!turn.isEmpty()) {
            if (secret == null || secret.length() == 0) {
                LOG.warning("turn.urls est defini mais turn.secret est absent : TURN desactive");
            } else {
                String username = (nowSeconds + ttl) + ":" + who.getRole().name().toLowerCase() + "-" + who.getId();
                servers.add("{\"urls\":" + array(turn) + ",\"username\":" + Json.quote(username) + ",\"credential\":"
                        + Json.quote(hmacSha1Base64(secret, username)) + "}");
            }
        }

        StringBuilder sb = new StringBuilder("{\"iceServers\":[");
        for (int i = 0; i < servers.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(servers.get(i));
        }
        return sb.append("],\"ttl\":").append(ttl).append('}').toString();
    }

    static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(UTF8), "HmacSHA1"));
            return base64(mac.doFinal(data.getBytes(UTF8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 indisponible", e);
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Base64 standard (RFC 4648) : ecrit ici pour ne dependre d'aucune version precise de Java. */
    static String base64(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xFF;
            int b1 = (i + 1 < in.length) ? in[i + 1] & 0xFF : 0;
            int b2 = (i + 2 < in.length) ? in[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append((i + 1 < in.length) ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append((i + 2 < in.length) ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }

    private static List<String> urls(String csv, String def) {
        List<String> out = new ArrayList<String>();
        String src = (csv == null || csv.trim().length() == 0) ? def : csv;
        if (src == null) {
            return out;
        }
        for (String u : src.split(",")) {
            String t = u.trim();
            // Seuls les schemas STUN/TURN sont acceptes : une valeur mal configuree ne doit pas atteindre le navigateur.
            if (t.matches("^(stun|turn|turns):[A-Za-z0-9._\\-]+(:[0-9]{1,5})?(\\?transport=(udp|tcp))?$")) {
                out.add(t);
            } else if (t.length() > 0) {
                LOG.warning("URL ICE ignoree (format invalide)");
            }
        }
        return out;
    }

    private static String array(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.quote(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String setting(String key) {
        String v = System.getProperty(key);
        if (v == null || v.length() == 0) {
            v = System.getenv(key.toUpperCase().replace('.', '_'));
        }
        return (v == null || v.length() == 0) ? null : v;
    }
}