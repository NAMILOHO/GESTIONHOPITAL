package com.security;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hachage et verification des mots de passe (PBKDF2 + sel aleatoire).
 *
 * Format stocke (auto-descriptif, tient dans varchar(225)) :
 * pbkdf2_sha256$120000$<sel hex 32>$<hash hex 64>
 *
 * Les anciens mots de passe en clair restent verifiables (matchesLegacy) afin de
 * migrer chaque compte a sa prochaine connexion, sans casser les comptes existants.
 */
public final class PasswordUtil {

	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final SecureRandom RANDOM = new SecureRandom();

	private static final int ITERATIONS = 120000;
	private static final int SALT_BYTES = 16;

	private static final Pattern HASH_FORMAT = Pattern
			.compile("^pbkdf2_(sha256|sha1)\\$[0-9]{1,7}\\$[0-9a-f]{32}\\$[0-9a-f]{40,64}$");

	private PasswordUtil() {
	}

	/**
	 * Outil en ligne de commande : affiche le hash d'un mot de passe saisi (pour creer le premier compte admin).
	 * Dans Eclipse : clic droit sur ce fichier > Run As > Java Application, puis saisir le mot de passe dans la Console.
	 */
	public static void main(String[] args) {
		String pwd;
		if (System.console() != null) {
			pwd = new String(System.console().readPassword("Mot de passe a hacher : "));
		} else {
			System.out.print("Mot de passe a hacher : ");
			pwd = new java.util.Scanner(System.in).nextLine();
		}
		if (pwd.length() < 12) {
			System.err.println("Attention : un compte administrateur merite au moins 12 caracteres.");
		}
		System.out.println(hash(pwd));
	}

	/** Produit le hash a stocker pour un mot de passe en clair. */
	public static String hash(String plain) {
		if (plain == null || plain.length() == 0) {
			throw new IllegalArgumentException("Mot de passe vide");
		}
		byte[] salt = new byte[SALT_BYTES];
		RANDOM.nextBytes(salt);

		String algo = "sha256";
		int keyBits = 256;
		byte[] dk;
		try {
			dk = pbkdf2("PBKDF2WithHmacSHA256", plain, salt, ITERATIONS, keyBits);
		} catch (NoSuchAlgorithmException e) {
			// JRE ancien (< Java 8) : repli sur SHA-1
			algo = "sha1";
			keyBits = 160;
			try {
				dk = pbkdf2("PBKDF2WithHmacSHA1", plain, salt, ITERATIONS, keyBits);
			} catch (Exception e2) {
				throw new IllegalStateException("PBKDF2 indisponible", e2);
			}
		} catch (InvalidKeySpecException e) {
			throw new IllegalStateException("PBKDF2 indisponible", e);
		}
		return "pbkdf2_" + algo + "$" + ITERATIONS + "$" + toHex(salt) + "$" + toHex(dk);
	}

	/** Vrai si la valeur stockee est au format hash (et non un ancien mot de passe en clair). */
	public static boolean isHashed(String stored) {
		return stored != null && HASH_FORMAT.matcher(stored).matches();
	}

	/** Verifie un mot de passe contre un hash au format ci-dessus. */
	public static boolean verify(String plain, String stored) {
		if (plain == null || !isHashed(stored)) {
			return false;
		}
		String[] p = stored.split("\\$");
		String algo = p[0].equals("pbkdf2_sha256") ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
		int iterations = Integer.parseInt(p[1]);
		byte[] salt = fromHex(p[2]);
		byte[] expected = fromHex(p[3]);
		try {
			byte[] actual = pbkdf2(algo, plain, salt, iterations, expected.length * 8);
			return MessageDigest.isEqual(expected, actual);
		} catch (Exception e) {
			return false;
		}
	}

	/** Comparaison a temps constant avec un ancien mot de passe stocke en clair (migration). */
	public static boolean matchesLegacy(String plain, String stored) {
		if (plain == null || stored == null || isHashed(stored)) {
			return false;
		}
		return MessageDigest.isEqual(plain.getBytes(UTF8), stored.getBytes(UTF8));
	}

	private static byte[] pbkdf2(String algo, String plain, byte[] salt, int iterations, int keyBits)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, keyBits);
		try {
			return SecretKeyFactory.getInstance(algo).generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	private static String toHex(byte[] b) {
		char[] digits = "0123456789abcdef".toCharArray();
		char[] out = new char[b.length * 2];
		for (int i = 0; i < b.length; i++) {
			out[i * 2] = digits[(b[i] >> 4) & 0xF];
			out[i * 2 + 1] = digits[b[i] & 0xF];
		}
		return new String(out);
	}

	private static byte[] fromHex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}
}
