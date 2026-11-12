package com.entity;

/**
 * Etats d'un appel et transitions autorisees :
 *
 * RINGING  -> ACCEPTED | REJECTED | CANCELLED | MISSED
 * ACCEPTED -> ENDED | DROPPED
 * les autres etats sont terminaux.
 *
 * RINGING = appel en attente (sortant pour l'appelant, entrant pour l'appele) ;
 * CANCELLED = l'appelant raccroche avant la reponse (affiche "manque" cote appele) ;
 * MISSED = pas de reponse, appele hors ligne ou occupe ;
 * DROPPED = connexion interrompue pendant l'appel.
 */
public enum CallStatus {
	RINGING, ACCEPTED, REJECTED, CANCELLED, MISSED, ENDED, DROPPED;

	public boolean isTerminal() {
		return this != RINGING && this != ACCEPTED;
	}

	public boolean canTransitionTo(CallStatus next) {
		if (this == RINGING) {
			return next == ACCEPTED || next == REJECTED || next == CANCELLED || next == MISSED;
		}
		if (this == ACCEPTED) {
			return next == ENDED || next == DROPPED;
		}
		return false;
	}
}
