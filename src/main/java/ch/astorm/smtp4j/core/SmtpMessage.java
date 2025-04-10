/*
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package ch.astorm.smtp4j.core;

import ch.astorm.smtp4j.protocol.SmtpCommand.Type;
import ch.astorm.smtp4j.protocol.SmtpExchange;
import ch.astorm.smtp4j.protocol.SmtpProtocolConstants;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Represents an SMTP message.
 */
public class SmtpMessage {
    private final boolean isSecure;
    private final String sourceFrom;
    private final List<String> sourceRecipients;
    private final MimeMessage mimeMessage;
    private final byte[] rawMimeContent;
    private final List<SmtpExchange> exchanges;

    /**
     * Simple {@code Session} used to create the {@code SmtpMessage} instances because depending on the underlying
     * library used (for instance Payara 6.2022.1), an NPE can be thrown while reading the content if there is no
     * session set.
     */
    private static final Session SESSION = Session.getInstance(new Properties());

    /**
     * Creates a new {@code SmtpMessage} with the specified parameters.
     *
     * @param isSecure       "true" if the message has been received using TLS.
     * @param from           The source {@code From} parameter value.
     * @param recipients     The source {@code Rcpt} parameter values.
     * @param mimeMessage    The parsed {@code MimeMessage}.
     * @param rawMimeContent The raw MIME content of {@code mimeMessage}.
     * @param exchanges      The raw SMTP exchanges.
     */
    public SmtpMessage(boolean isSecure, String from, List<String> recipients, MimeMessage mimeMessage, byte[] rawMimeContent, List<SmtpExchange> exchanges) {
        this.isSecure = isSecure;
        this.sourceFrom = from;
        this.sourceRecipients = recipients;
        this.mimeMessage = mimeMessage;
        this.rawMimeContent = rawMimeContent;
        this.exchanges = exchanges;
    }

    /**
     * Indicates whether the message was received using a secure TLS connection.
     *
     * @return true if the message was received using TLS, false otherwise.
     */
    public boolean isSecure() {
        return isSecure;
    }

    /**
     * Returns the {@code From} parameter specified during the protocol exchange.
     * This value will contain only the email (info@mydomain.com).
     *
     * @return The {@code MAIL FROM:} value.
     * @see Type#MAIL_FROM
     */
    public String getSourceFrom() {
        return sourceFrom;
    }

    /**
     * Returns the list of {@code To} parameters specified during the protocol exchange.
     * The values will contain only the email (info@mydomain.com).
     * <p>Note that all recipients (including {@link RecipientType#BCC}) will be present in this list.</p>
     *
     * @return The {@code RCPT TO:} values.
     * @see Type#RECIPIENT
     */
    public List<String> getSourceRecipients() {
        return sourceRecipients;
    }

    /**
     * Returns the raw SMTP exchanges to create this message.
     *
     * @return The raw SMTP exchanges.
     */
    public List<SmtpExchange> getSmtpExchanges() {
        return exchanges;
    }

    /**
     * Returns the {@code From} header of the MIME message.
     * This value can be composed, for instance: {@code Cédric <info@mydomain.com>}.
     *
     * @return The {@code From} header.
     */
    public String getFrom() {
        try {
            Address[] fromAddrs = mimeMessage.getFrom();
            if (fromAddrs == null || fromAddrs.length == 0) {
                return null;
            }
            return MimeUtility.decodeText(mimeMessage.getFrom()[0].toString());
        } catch (UnsupportedEncodingException | MessagingException e) {
            throw new RuntimeException("Unable to retrieve From header", e);
        }
    }

    /**
     * Returns all the recipients of the given {@code type}.
     * Those values can be composed, for instance: {@code Cédric <info@mydomain.com>}.
     * <p>The {@link RecipientType#BCC} will always yield an empty list.</p>
     *
     * @param type The type.
     * @return A list of recipients or an empty list if there is none.
     */
    public List<String> getRecipients(RecipientType type) {
        try {
            Address[] addrs = mimeMessage.getRecipients(type);
            if (addrs == null || addrs.length == 0) {
                return List.of();
            }

            List<String> addressStrs = new ArrayList<>(addrs.length);
            for (Address addr : addrs) {
                addressStrs.add(MimeUtility.decodeText(addr.toString()));
            }
            return addressStrs;
        } catch (UnsupportedEncodingException | MessagingException e) {
            throw new RuntimeException("Unable to retrieve Recipients " + type, e);
        }
    }

    /**
     * Returns the {@code Subject} header of the MIME message.
     *
     * @return The {@code Subject} header.
     */
    public String getSubject() {
        try {
            return mimeMessage.getSubject();
        } catch (MessagingException me) {
            throw new RuntimeException("Unable to retrieve Subject header", me);
        }
    }

    /**
     * Returns the content of the MIME message.
     * If the underlying {@code MimeMessage} is a {@code MimeMultipart}, then all the
     * parts without a filename will be concatenated together (separated by {@link SmtpProtocolConstants#CRLF})
     * and returned as the body. If there is none, then null will be returned.
     *
     * @return The content or null.
     */
    public String getBody() {
        try {
            Object content = mimeMessage.getContent();
            if (content == null) {
                return null;
            }

            if (content instanceof MimeMultipart multipart) {
                if (multipart.getCount() == 0) {
                    throw new IllegalStateException("At least one part expected");
                }

                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < multipart.getCount(); ++i) {
                    BodyPart body = multipart.getBodyPart(i);
                    if (body.getFileName() == null) {
                        if (!builder.isEmpty()) {
                            builder.append(SmtpProtocolConstants.CRLF);
                        }
                        builder.append(body.getContent().toString());
                    }
                }

                return !builder.isEmpty() ? builder.toString() : null;
            } else {
                return content.toString();
            }
        } catch (IOException | MessagingException e) {
            throw new RuntimeException("Unable to retrieve content", e);
        }
    }

    /**
     * Returns the attachments of the MIME message.
     * If the underlying {@code MimeMessage} is not {@code MimeMultipart} an empty
     * list will be returned.
     * <p>Note that only parts with a name will be considered as attachment.</p>
     *
     * @return A list of attachments.
     */
    public List<SmtpAttachment> getAttachments() {
        try {
            Object content = mimeMessage.getContent();
            if (content == null) {
                return null;
            }

            if (content instanceof MimeMultipart multipart) {
                int nbParts = multipart.getCount();

                if (nbParts == 0) {
                    throw new IllegalStateException("At least one part expected");
                }

                List<SmtpAttachment> attachments = new ArrayList<>(nbParts);
                for (int i = 0; i < nbParts; ++i) {
                    BodyPart part = multipart.getBodyPart(i);
                    String filename = part.getFileName();
                    if (filename != null) {
                        SmtpAttachment att = new SmtpAttachment(filename, part.getContentType(), part::getInputStream);
                        attachments.add(att);
                    }
                }
                return attachments;
            } else {
                return List.of();
            }
        } catch (IOException | MessagingException e) {
            throw new RuntimeException("Unable to retrieve content", e);
        }
    }

    /**
     * Returns the sent date.
     *
     * @return The sent date.
     */
    public Date getSentDate() {
        try {
            return mimeMessage.getSentDate();
        } catch (MessagingException e) {
            throw new RuntimeException("Unable to retrieve Sent date", e);
        }
    }

    /**
     * Returns the {@code MimeMessage} parsed from the content.
     *
     * @return the {@code MimeMessage}.
     */
    public MimeMessage getMimeMessage() {
        return mimeMessage;
    }

    /**
     * Returns the internal raw content received by the SMTP server to parse as {@code MimeMessage}.
     *
     * @return The raw content.
     */
    public byte[] getRawMimeContent() {
        return rawMimeContent;
    }

    /**
     * Creates a new {@code SmtpMessage} with the specified parameters.
     *
     * @param isSecure         "true" if the message has been received using TLS.
     * @param from             The source {@code From} parameter value.
     * @param recipients       The source {@code Rcpt} parameter values.
     * @param mimeMessageBytes The {@code MimeMessage} content.
     * @param exchanges        The raw SMTP exchanges of this message.
     * @return A new {@code SmtpMessage} instance.
     */
    public static SmtpMessage create(boolean isSecure, String from, List<String> recipients, byte[] mimeMessageBytes, List<SmtpExchange> exchanges) {
        MimeMessage mimeMessage;
        try (InputStream is = new ByteArrayInputStream(mimeMessageBytes)) {
            mimeMessage = new MimeMessage(SESSION, is);
        } catch (IOException | MessagingException e) {
            throw new RuntimeException("Unable to create MimeMessage from content", e);
        }
        return new SmtpMessage(isSecure, from, recipients, mimeMessage, mimeMessageBytes, exchanges);
    }
}
