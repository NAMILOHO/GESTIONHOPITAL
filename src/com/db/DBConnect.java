package com.db;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Fabrique de connexions MySQL.
 *
 * - Chaque appel renvoie une NOUVELLE connexion (plus de champ static partage entre threads).
 * - Parametres lus, dans l'ordre : propriete JVM (-Ddb.url=...), puis variable d'environnement
 *   (DB_URL, DB_USER, DB_PASSWORD, DB_DRIVER), puis valeur par defaut de DEVELOPPEMENT ci-dessous.
 *   En production : definir DB_USER / DB_PASSWORD (utilisateur MySQL dedie, pas root) et activer SSL.
 */
public class DBConnect {

	// Valeurs par defaut : developpement local uniquement.
	private static final String DEFAULT_DRIVER = "com.mysql.cj.jdbc.Driver";
	private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/hospital_2"
			+ "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
	private static final String DEFAULT_USER = "root";
	private static final String DEFAULT_PASSWORD = "";

	private static String setting(String key, String defaultValue) {
		String v = System.getProperty(key);
		if (v == null || v.length() == 0) {
			v = System.getenv(key.toUpperCase().replace('.', '_'));
		}
		return (v == null || v.length() == 0) ? defaultValue : v;
	}

	public static Connection getConn() {
		try {
			Class.forName(setting("db.driver", DEFAULT_DRIVER));
			return DriverManager.getConnection(setting("db.url", DEFAULT_URL), setting("db.user", DEFAULT_USER),
					setting("db.password", DEFAULT_PASSWORD));
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

}
