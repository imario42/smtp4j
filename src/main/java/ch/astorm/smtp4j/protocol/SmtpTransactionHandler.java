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

package ch.astorm.smtp4j.protocol;

import ch.astorm.smtp4j.SmtpServer;
import ch.astorm.smtp4j.auth.SmtpAuth;
import ch.astorm.smtp4j.core.SmtpMessage;
import ch.astorm.smtp4j.firewall.SmtpFirewall;
import ch.astorm.smtp4j.protocol.SmtpCommand.Type;
import ch.astorm.smtp4j.secure.SmtpSecure;
import ch.astorm.smtp4j.util.ByteArrayUtils;
import ch.astorm.smtp4j.util.LineAwareBufferedInputStream;
import ch.astorm.smtp4j.util.StringUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Handles the SMTP protocol.
 */
public class SmtpTransactionHandler {
    private final static SecureRandom random;

    static {
        try {
            random = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private final SmtpServer smtpServer;
    private final Socket socket;
    private final boolean isSecure;
    private final LineAwareBufferedInputStream input;
    private final PrintWriter output;
    private final MessageReceiver messageReceiver;
    private final SmtpFirewall firewall;
    private final SmtpAuth auth;
    private final SmtpSecure secure;
    private final Long maxMessageSize;

    /**
     * Represents a message receiver within the SMTP transaction.
     */
    @FunctionalInterface
    public interface MessageReceiver {

        /**
         * Invoked when a message is received.
         * If this method throws an exception, the error will be sent back to the client
         * and the SMTP transaction will abort.
         *
         * @param message The received message.
         */
        void receiveMessage(SmtpMessage message);
    }

    private SmtpTransactionHandler(SmtpServer smtpServer, Socket socket, boolean isSecure, LineAwareBufferedInputStream input, PrintWriter output, SmtpFirewall firewall, SmtpAuth auth, SmtpSecure secure, Long maxMessageSize, MessageReceiver messageReceiver) {
        this.smtpServer = smtpServer;
        this.socket = socket;
        this.isSecure = isSecure;
        this.input = input;
        this.output = output;
        this.firewall = firewall;
        this.auth = auth;
        this.secure = secure;
        this.messageReceiver = messageReceiver;
        this.maxMessageSize = maxMessageSize;
    }


    /**
     * Handles an SMTP transaction by initializing and executing the SmtpTransactionHandler
     * with the provided parameters.
     *
     * @param smtpServer      the SMTP server instance managing the transaction
     * @param socket          the socket connection used for communication
     * @param isSecure        a boolean indicating whether the connection is secure
     * @param input           the input stream to read data from the client
     * @param output          the writer to send data to the client
     * @param firewall        the SMTP firewall for managing restrictions and rules
     * @param maxMessageSize  the maximum allowed size for the email message
     * @param auth            the SMTP authentication handler
     * @param secure          the SMTP secure connection handler
     * @param messageReceiver the object responsible for processing received emails
     * @throws IOException           if an I/O error occurs while handling the transaction
     * @throws SmtpProtocolException if there is an error in SMTP protocol handling
     */
    public static void handle(SmtpServer smtpServer, Socket socket, boolean isSecure, LineAwareBufferedInputStream input, PrintWriter output, SmtpFirewall firewall, Long maxMessageSize, SmtpAuth auth, SmtpSecure secure, MessageReceiver messageReceiver) throws IOException, SmtpProtocolException {
        SmtpTransactionHandler sth = new SmtpTransactionHandler(smtpServer, socket, isSecure, input, output, firewall, auth, secure, maxMessageSize, messageReceiver);
        sth.execute();
    }

    private void execute() throws SmtpProtocolException {
        // "server ready" message isn't required if we just started TLS
        if (!isSecure) {
            //inform the client about the SMTP server state
            reply(SmtpProtocolConstants.CODE_CONNECT, "localhost smtp4j server ready");
        }

        //extends the EHLO/HELO command to greet the client
        SmtpCommand ehlo = SmtpCommand.parse(nextLine());
        if (ehlo == null) {
            return;
        }

        if (ehlo.getType() == Type.EHLO) {
            String param = ehlo.getParameter();
            if (param == null || param.trim().isEmpty()) {
                param = "you";
            }
            reply(SmtpProtocolConstants.CODE_OK, Stream.of(
                            "smtp4j greets " + param,
                            Type.EIGHT_BIT_MIME.withArgs(null),
                            auth != null ? Type.AUTH.withArgs("PLAIN CRAM-MD5") : "",
                            (!isSecure && secure != null) ? Type.STARTTLS.withArgs(null) : "",
                            (!isSecure && secure != null) ? "REQUIRETLS" : "",
                            Type.SIZE.withArgs(maxMessageSize != null ? maxMessageSize.toString() : ""))
                    .filter(s -> !s.isEmpty())
                    .toList()
            );
        } else {
            reply(SmtpProtocolConstants.CODE_BAD_COMMAND_SEQUENCE, "Bad sequence of command (wrong command)");
            return;
        }

        //start reading the transaction data
        readTransaction();
    }

    private String mailFrom;
    private List<String> recipients;
    private ByteArrayOutputStream smtpMessageContent;

    private final List<byte[]> readData = new ArrayList<>(64);
    private final List<SmtpExchange> exchanges = new ArrayList<>(32);

    private void readTransaction() throws SmtpProtocolException {

        boolean inForbiddenState = false;
        boolean isAuthenticated = auth == null;
        boolean expectedSecurityLevelEstablished = isSecure || secure == null;

        int authTries = 0;
        String currentAuthOngoing = null;
        String currentAuthChallenge = null;

        while (true) {
            if (currentAuthOngoing != null) {
                try {
                    String[] credentials = StringUtils.decode(nextLine()).split(" ", 2);
                    if (credentials.length != 2) {
                        reply(SmtpProtocolConstants.CODE_AUTH_FAILED, "Authentication failed");
                        continue;
                    }

                    String user = credentials[0];
                    String pass = credentials[1];
                    if (Objects.equals(pass,
                            StringUtils.hashWithHMACMD5(
                                    currentAuthChallenge,
                                    auth.getPasswordForUser(user)))) {
                        isAuthenticated = true;
                        reply(SmtpProtocolConstants.CODE_AUTH_OK, "OK");
                    } else {
                        reply(SmtpProtocolConstants.CODE_AUTH_FAILED, "Authentication failed");
                    }
                    continue;
                } finally {
                    currentAuthOngoing = null;
                    currentAuthChallenge = null;
                }
            }

            SmtpCommand command = nextCommand();
            if (command == null) {
                return;
            }

            Type commandType = command.getType();

            if (inForbiddenState) {
                reply(SmtpProtocolConstants.CODE_FORBIDDEN, "Subsequent commands forbidden");
                continue;
            }

            if (commandType == Type.STARTTLS) {
                if (secure == null) {
                    reply(SmtpProtocolConstants.CODE_TRANSACTION_FAILED, "TLS not supported");
                    break;
                }

                // Upgrade to TLS
                SSLSocket sslSocket;
                try {
                    SSLContext sslContext = secure.getSSLContext();
                    SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
                    sslSocket = (SSLSocket) sslSocketFactory.createSocket(
                            socket,
                            socket.getInetAddress().getHostAddress(),
                            socket.getPort(),
                            true
                    );
                    sslSocket.setUseClientMode(false);
                } catch (Exception e) {
                    reply(SmtpProtocolConstants.CODE_TRANSACTION_FAILED, "TLS Upgrade failed");
                    throw new SmtpProtocolException("TLS Upgrade failed", e);
                }

                // notify client
                reply(SmtpProtocolConstants.CODE_CONNECT, "Ready to start TLS");

                smtpServer.handleConnection(sslSocket, true);
                // we are done here
                return;
            }

            if (!expectedSecurityLevelEstablished) {
                reply(SmtpProtocolConstants.CODE_AUTH_REQUIRED, Type.STARTTLS.withArgs(null) + " required");
                continue;
            }

            if (commandType == Type.AUTH) {
                authTries++;

                if (auth == null) {
                    reply(SmtpProtocolConstants.CODE_COMMAND_UNKNOWN, "Unknown command");
                    continue;
                }

                if (authTries > auth.getMaxTries()) {
                    reply(SmtpProtocolConstants.CODE_FORBIDDEN, "Too many authentication attempts.");
                    inForbiddenState = true;
                    continue;
                }

                String[] authTokens = StringUtils.split(command.getParameter(), " ", 2);
                if (authTokens.length < 1) {
                    reply(SmtpProtocolConstants.CODE_COMMAND_PARAMETERS_INVALID, "Invalid parameters");
                    continue;
                }
                String authType = StringUtils.toUpperCase(authTokens[0]);
                switch (authType) {
                    case "PLAIN":
                        if (authTokens.length != 2) {
                            reply(SmtpProtocolConstants.CODE_COMMAND_PARAMETERS_INVALID, "Invalid parameters");
                            break;
                        }
                        String login = authTokens[1];
                        String[] credentials = StringUtils.decode(login).split("\\x00", 3);
                        if (credentials.length != 3) {
                            reply(SmtpProtocolConstants.CODE_COMMAND_PARAMETERS_INVALID, "Invalid parameters");
                            break;
                        }
                        String user = credentials[1];
                        String pass = credentials[2];
                        if (ByteArrayUtils.equals(pass.getBytes(StandardCharsets.UTF_8), auth.getPasswordForUser(user))) {
                            isAuthenticated = true;
                            reply(SmtpProtocolConstants.CODE_AUTH_OK, "OK");
                        } else {
                            reply(SmtpProtocolConstants.CODE_AUTH_FAILED, "Authentication failed");
                        }
                        break;
                    case "CRAM-MD5":
                        currentAuthOngoing = authType;
                        currentAuthChallenge = String.format("<%d.%d@%s>",
                                random.nextLong(),
                                System.currentTimeMillis(),
                                "mydomain.com");
                        reply(SmtpProtocolConstants.CODE_SERVER_CHALLENGE, StringUtils.encode(currentAuthChallenge));
                        break;
                    default:
                        reply(SmtpProtocolConstants.CODE_COMMAND_PARAMETERS_INVALID, "Invalid parameters");
                        break;
                }

                continue;
            }

            if (!isAuthenticated) {
                if (commandType == Type.QUIT) {
                    reply(SmtpProtocolConstants.CODE_OK, "OK");
                    break;
                }

                reply(SmtpProtocolConstants.CODE_AUTH_REQUIRED, "Authentication required");
                continue;
            }

            if (mailFrom == null) {
                if (commandType == Type.MAIL_FROM) {
                    String enbraced = command.getParameter(); //enclosed: <mail_value>
                    mailFrom = enbraced.substring(1, enbraced.length() - 1);
                    if (!firewall.isAllowedFrom(mailFrom)) {
                        reply(SmtpProtocolConstants.CODE_FORBIDDEN, "Mail-From forbidden");
                        inForbiddenState = true;
                    } else {
                        reply(SmtpProtocolConstants.CODE_OK, "OK");
                    }
                } else if (commandType == Type.QUIT) {
                    reply(SmtpProtocolConstants.CODE_OK, "OK");
                    break;
                } else {
                    reply(SmtpProtocolConstants.CODE_BAD_COMMAND_SEQUENCE, "Bad sequence of command (wrong command)");
                }
                continue;
            }

            if (recipients == null) {
                if (commandType != Type.RECIPIENT) {
                    reply(SmtpProtocolConstants.CODE_BAD_COMMAND_SEQUENCE, "Bad sequence of command (wrong command)");
                    continue;
                }

                recipients = new ArrayList<>();
                while (commandType == Type.RECIPIENT) {
                    String enbraced = command.getParameter(); //enclosed: <mail_value>
                    String recipient = enbraced.substring(1, enbraced.length() - 1);
                    if (!firewall.isAllowedRecipient(recipient)) {
                        reply(SmtpProtocolConstants.CODE_FORBIDDEN, "Recipient forbidden");
                        inForbiddenState = true;
                    } else {
                        recipients.add(recipient);
                        reply(SmtpProtocolConstants.CODE_OK, "OK");
                    }

                    command = nextCommand();
                    commandType = command.getType();
                }
            }

            if (commandType == Type.DATA) {
                if (smtpMessageContent != null) {
                    reply(SmtpProtocolConstants.CODE_BAD_COMMAND_SEQUENCE, "Bad sequence of command (wrong command)");
                    continue;
                }

                smtpMessageContent = new ByteArrayOutputStream(8192);
                reply(SmtpProtocolConstants.CODE_INTERMEDIATE_REPLY, "Start mail input; end with <CRLF>.<CRLF>");

                boolean hasFailure = false;
                byte[] currentLine = nextLine();
                while (true) {
                    //DATA content must end with a dot on a single line
                    if (ByteArrayUtils.equals(currentLine, SmtpProtocolConstants.DOT_BYTES)) {
                        byte[] messageContent = ByteArrayUtils.copy(
                                smtpMessageContent.toByteArray(),
                                smtpMessageContent.size() - SmtpProtocolConstants.CRLF_BYTES.length);
                        if (!firewall.isAllowedMessage(messageContent)) {
                            reply(SmtpProtocolConstants.CODE_FORBIDDEN, "Message forbidden");
                            inForbiddenState = true;
                            hasFailure = true;
                            break;
                        }
                        SmtpMessage message = SmtpMessage.create(isSecure, mailFrom, recipients, messageContent, new ArrayList<>(exchanges));
                        try {
                            messageReceiver.receiveMessage(message);
                            resetState();
                        } catch (Exception e) {
                            reply(SmtpProtocolConstants.CODE_TRANSACTION_FAILED, e.getMessage());
                            hasFailure = true;
                        }

                        break;
                    } else {
                        //if DATA starts with a dot, a second one must be added
                        if (ByteArrayUtils.startsWith(currentLine, SmtpProtocolConstants.DOT_BYTES)) {
                            smtpMessageContent.write(currentLine, 1, currentLine.length - 1);
                        } else {
                            smtpMessageContent.writeBytes(currentLine);
                        }
                        smtpMessageContent.writeBytes(SmtpProtocolConstants.CRLF_BYTES);
                    }

                    currentLine = nextLine();
                }

                if (!hasFailure) {
                    reply(SmtpProtocolConstants.CODE_OK, "OK");
                }
                continue;
            }

            if (commandType == Type.QUIT) {
                reply(SmtpProtocolConstants.CODE_OK, "OK");
                break;
            } else {
                reply(SmtpProtocolConstants.CODE_BAD_COMMAND_SEQUENCE, "Bad sequence of command (wrong command)");
            }
        }
    }

    private void resetState() {
        this.mailFrom = null;
        this.recipients = null;
        this.smtpMessageContent = null;
    }

    private byte[] nextLine() throws SmtpProtocolException {
        try {
            byte[] line = input.readLine();
            if (line == null) {
                return null;
            }
            readData.add(line);
            return line;
        } catch (IOException ioe) {
            if (socket.isClosed()) {
                return null;
            }

            throw new SmtpProtocolException("I/O exception", ioe);
        }
    }

    private SmtpCommand nextCommand() throws SmtpProtocolException {
        SmtpCommand command = SmtpCommand.parse(nextLine());
        if (command == null) {
            return null;
        }

        while (command != null) {
            Type commandType = command.getType();
            if (commandType == Type.NOOP) {
                reply(SmtpProtocolConstants.CODE_OK, "OK");
            } else if (commandType == Type.EXPAND) {
                reply(SmtpProtocolConstants.CODE_NOT_SUPPORTED, "Not supported");
            } else if (commandType == Type.VERIFY) {
                reply(SmtpProtocolConstants.CODE_NOT_SUPPORTED, "Not supported");
            } else if (commandType == Type.HELP) {
                reply(SmtpProtocolConstants.CODE_NOT_SUPPORTED, "Not supported");
            } else if (commandType == Type.UNKNOWN) {
                reply(SmtpProtocolConstants.CODE_COMMAND_UNKNOWN, "Unknown command");
            } else if (commandType == Type.RESET) {
                resetState();
                reply(SmtpProtocolConstants.CODE_OK, "OK");
            } else {
                return command;
            }

            command = SmtpCommand.parse(nextLine());
        }

        throw new SmtpProtocolException("Unexpected end of exchange (no more command)");
    }

    private void reply(int code, List<String> messages) {
        StringBuilder builder = new StringBuilder(32);
        for (int i = 0; i < messages.size(); i++) {
            boolean isLast = i + 1 == messages.size();

            String message = messages.get(i);
            builder.append(code);
            if (message != null) {
                if (isLast) {
                    builder.append(SmtpProtocolConstants.SP);
                } else {
                    builder.append(SmtpProtocolConstants.MULTILINE);
                }
                builder.append(message);
            } else {
                if (!isLast) {
                    builder.append(SmtpProtocolConstants.MULTILINE);
                }
            }
            builder.append(SmtpProtocolConstants.CRLF);
        }

        send(builder);
    }

    private void send(StringBuilder builder) {
        SmtpExchange exchange = new SmtpExchange(
                readData.stream()
                        .map(b -> new String(b, StandardCharsets.ISO_8859_1))
                        .toList(),
                builder.toString());
        exchanges.add(exchange);
        readData.clear();

        output.print(builder);
        output.flush();
    }

    private void reply(int code, String message) {
        StringBuilder builder = new StringBuilder(32);
        builder.append(code);
        if (message != null) {
            builder.append(SmtpProtocolConstants.SP);
            builder.append(message);
        }
        builder.append(SmtpProtocolConstants.CRLF);

        send(builder);
    }
}
