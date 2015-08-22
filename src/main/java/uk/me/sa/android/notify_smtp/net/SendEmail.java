/*
	notify-smtp-android - Android Notify to SMTP Service

	Copyright 2015  Simon Arlott

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package uk.me.sa.android.notify_smtp.net;

import java.io.IOException;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.apache.commons.net.smtp.AuthenticatingSMTPClient.AUTH_METHOD;
import org.apache.commons.net.smtp.SMTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.me.sa.android.notify_smtp.data.Message;
import uk.me.sa.android.notify_smtp.data.ValidatedPrefs;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class SendEmail implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(SendEmail.class);
	private static final int ATTEMPTS = 3;
	private static final int TIMEOUT_MS = (int)TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS);

	private ValidatedPrefs prefs;
	private String message;
	private Date ts;

	public SendEmail(ValidatedPrefs prefs, String message, Date ts) {
		this.prefs = prefs;
		this.message = message;
		this.ts = (Date)ts.clone();
	}

	@SuppressFBWarnings("SWL_SLEEP_WITH_LOCK_HELD")
	public void run() {
		try {
			synchronized (SendEmail.class) {
				for (int i = 0; i < ATTEMPTS; i++) {
					try {
						log.info("Sending email at {} for: {}", ts, message);

						if (send())
							break;
					} catch (Exception e) {
						log.error("Unable to send email", e);
					}

					if (i + 1 < ATTEMPTS)
						Thread.sleep((int)TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS));
				}
			}
		} catch (InterruptedException e) {
			log.warn("Interrupted while sleeping", e);
		}
	}

	private boolean send() throws NoSuchAlgorithmException, SocketException, IOException, InvalidKeyException, InvalidKeySpecException {
		AuthSMTPTLSClient client = new AuthSMTPTLSClient();
		client.setDefaultTimeout(TIMEOUT_MS);
		client.connect(prefs.node, prefs.port);
		client.setSoTimeout(TIMEOUT_MS);
		try {
			if (!SMTPReply.isPositiveCompletion(client.getReplyCode()))
				return false;

			if (!client.elogin() || !client.execTLS())
				return false;

			if (!client.elogin() || !client.auth(AUTH_METHOD.PLAIN, prefs.username, prefs.password))
				return false;

			if (!client.setSender(prefs.sender))
				return false;

			for (String recipient : prefs.recipients)
				if (!client.addRecipient(recipient))
					return false;

			if (!client.sendShortMessageData(new Message(message, ts, prefs.sender, prefs.recipients).toString()))
				return false;

			return client.logout();
		} finally {
			try {
				client.disconnect();
			} catch (IOException e) {
				log.error("Error disconnecting", e);
			}
		}
	}
}