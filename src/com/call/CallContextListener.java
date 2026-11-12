package com.call;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/** Demarrage : cloture les appels restes ouverts apres un arret brutal. Arret : termine proprement les appels en cours. */
@WebListener
public class CallContextListener implements ServletContextListener {

	private static final Logger LOG = Logger.getLogger(CallContextListener.class.getName());

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		try {
			CallHub.instance().closeOrphans();
		} catch (Throwable t) {
			LOG.log(Level.WARNING, "Nettoyage des appels orphelins impossible (la table calls existe-t-elle ?)", t);
		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		CallHub.instance().shutdown();
	}
}
