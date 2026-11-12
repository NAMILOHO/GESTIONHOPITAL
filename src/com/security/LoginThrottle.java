package com.security;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Limitation des tentatives de connexion (anti force brute), en memoire.
 *
 * - 5 echecs en 15 min pour un meme (role, email, adresse IP) -> blocage 15 min.
 * - 30 echecs en 15 min depuis une meme adresse IP, tous comptes confondus -> blocage 15 min.
 * La cle contient l'IP : un attaquant ne peut pas verrouiller le compte d'une victime depuis une autre machine.
 * Limite connue : l'etat est perdu au redemarrage de Tomcat et n'est pas partage entre plusieurs serveurs.
 */
public final class LoginThrottle {

	private static final Logger LOG = Logger.getLogger(LoginThrottle.class.getName());

	private static final int MAX_FAILURES = 5;
	private static final int MAX_FAILURES_PER_IP = 30;
	private static final long WINDOW_MS = 15L * 60 * 1000;
	private static final long LOCK_MS = 15L * 60 * 1000;
	private static final int MAX_ENTRIES = 10000;

	private static final class Entry {
		int failures;
		long windowStart;
		long lockedUntil;
	}

	private static final Map<String, Entry> ENTRIES = new HashMap<String, Entry>();

	private LoginThrottle() {
	}

	public static synchronized boolean isBlocked(String role, String email, String ip) {
		long now = System.currentTimeMillis();
		return locked(accountKey(role, email, ip), now) || locked(ipKey(ip), now);
	}

	public static synchronized void recordFailure(String role, String email, String ip) {
		long now = System.currentTimeMillis();
		purgeIfNeeded(now);
		if (fail(accountKey(role, email, ip), MAX_FAILURES, now)) {
			LOG.warning("Connexion bloquee 15 min (trop d'echecs) role=" + role + " ip=" + ip);
		}
		if (fail(ipKey(ip), MAX_FAILURES_PER_IP, now)) {
			LOG.warning("Adresse bloquee 15 min (trop d'echecs) ip=" + ip);
		}
	}

	public static synchronized void recordSuccess(String role, String email, String ip) {
		ENTRIES.remove(accountKey(role, email, ip));
	}

	private static boolean locked(String key, long now) {
		Entry e = ENTRIES.get(key);
		return e != null && e.lockedUntil > now;
	}

	/** Enregistre un echec ; renvoie true si cet echec declenche un blocage. */
	private static boolean fail(String key, int max, long now) {
		Entry e = ENTRIES.get(key);
		if (e == null) {
			e = new Entry();
			e.windowStart = now;
			ENTRIES.put(key, e);
		}
		if (e.lockedUntil <= now && now - e.windowStart > WINDOW_MS) {
			e.failures = 0;
			e.windowStart = now;
		}
		e.failures++;
		if (e.failures >= max) {
			e.lockedUntil = now + LOCK_MS;
			e.failures = 0;
			e.windowStart = now;
			return true;
		}
		return false;
	}

	private static void purgeIfNeeded(long now) {
		if (ENTRIES.size() < MAX_ENTRIES) {
			return;
		}
		for (Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext();) {
			Entry e = it.next().getValue();
			if (e.lockedUntil <= now && now - e.windowStart > WINDOW_MS) {
				it.remove();
			}
		}
		if (ENTRIES.size() >= MAX_ENTRIES) {
			// attaque par emails aleatoires : on garde seulement les blocages actifs
			for (Iterator<Map.Entry<String, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext();) {
				if (it.next().getValue().lockedUntil <= now) {
					it.remove();
				}
			}
		}
	}

	private static String accountKey(String role, String email, String ip) {
		String e = (email == null) ? "" : email.trim().toLowerCase(Locale.ROOT);
		if (e.length() > 100) {
			e = e.substring(0, 100);
		}
		return role + "|" + e + "|" + ip;
	}

	private static String ipKey(String ip) {
		return "ip|" + ip;
	}
}
