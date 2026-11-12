package com.call;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tickets de connexion WebSocket : jetons aleatoires (128 bits), a usage unique, valables 30 secondes,
 * delivres uniquement a une session HTTP authentifiee (servlet /wsTicket). Le WebSocket n'utilise donc
 * ni cookie ni mot de passe : un site tiers ne peut pas ouvrir de connexion au nom de l'utilisateur.
 */
public final class TicketStore {

	private static final long TTL_MS = 30000L;
	private static final int MAX_TICKETS = 10000;
	private static final int MAX_PER_IDENTITY = 20;

	private static final class Ticket {
		final Identity identity;
		final long expires;

		Ticket(Identity identity, long expires) {
			this.identity = identity;
			this.expires = expires;
		}
	}

	private static final TicketStore INSTANCE = new TicketStore();

	public static TicketStore instance() {
		return INSTANCE;
	}

	private final Map<String, Ticket> tickets = new HashMap<String, Ticket>();
	private final SecureRandom random = new SecureRandom();

	/** Emet un ticket, ou null si trop de tickets sont deja en attente (protection contre l'abus). */
	public synchronized String issue(Identity identity) {
		long now = System.currentTimeMillis();
		int mine = 0;
		for (Iterator<Map.Entry<String, Ticket>> it = tickets.entrySet().iterator(); it.hasNext();) {
			Ticket t = it.next().getValue();
			if (t.expires <= now) {
				it.remove();
			} else if (t.identity.equals(identity)) {
				mine++;
			}
		}
		if (mine >= MAX_PER_IDENTITY || tickets.size() >= MAX_TICKETS) {
			return null;
		}
		byte[] b = new byte[16];
		random.nextBytes(b);
		StringBuilder sb = new StringBuilder(32);
		for (int k = 0; k < b.length; k++) {
			sb.append(Character.forDigit((b[k] >> 4) & 0xF, 16)).append(Character.forDigit(b[k] & 0xF, 16));
		}
		String ticket = sb.toString();
		tickets.put(ticket, new Ticket(identity, now + TTL_MS));
		return ticket;
	}

	/** Consomme un ticket (usage unique). Renvoie l'identite, ou null si inconnu, deja utilise ou expire. */
	public synchronized Identity redeem(String ticket) {
		if (ticket == null) {
			return null;
		}
		Ticket t = tickets.remove(ticket);
		if (t == null || t.expires <= System.currentTimeMillis()) {
			return null;
		}
		return t.identity;
	}
}
