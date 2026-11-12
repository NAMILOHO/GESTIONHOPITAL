package com.call;

import com.entity.CallRole;

/** Identite d'un participant : (role, id). Toujours deduite de la session HTTP, jamais d'un message client. */
public final class Identity {

	private final CallRole role;
	private final int id;
	private final String name;

	public Identity(CallRole role, int id, String name) {
		this.role = role;
		this.id = id;
		String n = (name == null) ? "" : name.trim();
		this.name = (n.length() > 100) ? n.substring(0, 100) : n;
	}

	public CallRole getRole() {
		return role;
	}

	public int getId() {
		return id;
	}

	/** Nom d'affichage : texte saisi par un utilisateur, a afficher cote client uniquement avec textContent. */
	public String getName() {
		return name;
	}

	public String key() {
		return role.name() + ":" + id;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Identity && ((Identity) o).role == role && ((Identity) o).id == id;
	}

	@Override
	public int hashCode() {
		return role.hashCode() * 31 + id;
	}
}
