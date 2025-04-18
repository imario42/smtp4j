[![Maven](https://img.shields.io/maven-central/v/ch.astorm/smtp4j.svg)](https://search.maven.org/search?q=g:ch.astorm%20AND%20a:smtp4j)
[![Build](https://app.travis-ci.com/imario42/smtp4j.svg?branch=master)](https://app.travis-ci.com/github/imario42/smtp4j/branches)
[![javadoc](https://javadoc.io/badge2/ch.astorm/smtp4j/javadoc.svg)](https://javadoc.io/doc/ch.astorm/smtp4j)

# smtp4j

Simple API to fake an SMTP server for Unit testing (and more).

## About this project

This API is inspired from
[dumbster](https://github.com/kirviq/dumbster)
and forked (28.3.2025) from [ctabin/smtp4j](https://github.com/ctabin/smtp4j)
with the following improvements:

- Java 22
- Use ExecutorService instead of ThreadFactory
    - This gives us Virtual Threads support
- Use ExecutorService for handling new connections
    - ```SmtpServerBuilder.withExecutorService```
- Message size limitation
    - ```SmtpServerBuilder.withMaxMessageSize```
- Remote IP restriction (via SmtpFirewall)
    - ```SmtpServerBuilder.withFirewall```
- Authentication (CRAM-MD5, LOGIN, PLAIN)
    - ```SmtpServerBuilder.withAuth```
- STARTTLS
    - ```SmtpServerBuilder.withSecure```

Here is the compatibility map of this API:

| Version  | JDK                | Package   |   
|----------|--------------------|-----------|
| >= 1.0.0 | JDK 22 and upwards | `jakarta` |

## Installation (maven)

Use the following dependency in your `pom.xml`:

```xml

<dependency>
    <groupId>at.datenwort.commons</groupId>
    <artifactId>smtp4j</artifactId>
    <version>LATEST</version>
</dependency>
```

## Quick Start Guide

Here is a quick example of this API that shows an oversight on
how it can be used:

```java
void example() {
    /* SMTP server is started on port 1025 */
    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (
            SmtpServer server = builder.withPort(1025).start()) {

        /* create and send an SMTP message to smtp4j */
        MimeMessageBuilder messageBuilder = new MimeMessageBuilder(server);
        messageBuilder
                .from("source@smtp4j.local")
                .to("target1@smtp4j.local", "John Doe <john@smtp4j.local>")
                .cc("target3@smtp4j.local")
                .subject("Hello, world !")
                .body("Hello\r\nGreetings from smtp4j !\r\n\r\nBye.").

                attachment("data.txt", new File("someAttachment.txt"));

        messageBuilder.

                send(); //uses Transport.send(...)

        /* retrieve the sent message in smtp4j */
        List<SmtpMessage> messages = server.readReceivedMessages();

        assertEquals(1, messages.size());

        /* analyze the content of the message */
        SmtpMessage receivedMessage = messages.get(0);
        String from = receivedMessage.getFrom();
        String subject = receivedMessage.getSubject();
        String body = receivedMessage.getBody();
        Date sentDate = receivedMessage.getSentDate();
        List<String> recipientsTo = receivedMessage.getRecipients(RecipientType.TO);
        List<SmtpAttachment> attachments = receivedMessage.getAttachments();
    }
}

```

## Usage

Here are some usages about specific parts of the API. For more examples,
look in the [tests](src/test/java/ch/astorm/smtp4j).

Basically, it is recommended to always use
the [SmtpServerBuilder](src/main/java/ch/astorm/smtp4j/SmtpServerBuilder.java)
class to instanciate a new `SmtpServer` instance.

### SMTP server port

The `SmtpServer` can be configured either to listen to a specific port or to find
dynamically a free port to listen to.

A static port can simply be specified like this:

```java
void example() {
    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (SmtpServer server = builder.withPort(1025)
            .start()) {
        //server is listening on port 1025
    }
}
```

On the other hand, if no port is defined, the `SmtpServer` will find a free port
to listen to when it is started:

```java
void example() {
    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (SmtpServer server = builder.start()) {
        int port = server.getPort(); //port listen by the server
    }
}
```

When the port is not specified, the `SmtpServer` will try to open a server socket on
the default SMTP port (25). If the latter fails (most probably), it will look up for
a free port starting from 1024.

Note that generally ports under 1024 can only be used with root privileges.

### Session

The `SmtpServer` provides some utilities that let you create a new `Session`
for message creation and sending. The latter will be automatically connected
to the running server (on localhost):

```java
void example() {
    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (SmtpServer server = builder.start()) {
        Session session = server.createSession();
//use the session to create a MimeMessage
    }
}
```

### Received messages

The received messages can be accessed directly through the `SmtpServer`:

```java
List<SmtpMessage> receivedMessages = smtpServer.readReceivedMessages();
```

This method will clear the server's storage cache, hence another invocation of
the same method will yield an empty list until a new message has been received.

**WARNING:** Do not use this method concurrently `SmtpServer.receivedMessageReader()`
because of race conditions.

Since smtp4j is multithreaded, it may happen that there is not enough time to process the message before
reading it. This can be easily circumvented by defining a delay to wait when there is no message yet received.

```
List<SmtpMessage> receivedMessages = smtpServer.readReceivedMessages(2, TimeUnit.SECONDS);
```

#### Waiting for messages

A simple API is provided to wait and loop over the received messages:

```java
void example() {
    try (SmtpMessageReader reader = smtpServer.receivedMessageReader()) {
        SmtpMessage smtpMessage = reader.readMessage(); //blocks until the first message is available
        while (smtpMessage != null) {
            /* ... */

//blocks until the next message is available
            smtpMessage = reader
                    .readMessage();
        }
    }
}
```

When the `SmtpServer` is closed, the reader will yield `null`.

**WARNING:** Creating multiple instances of `SmtpMessageReader` will cause a race condition between
them and hence, a message will be received only by one of the readers. For the same reasons, do not use
`SmtpServer.readReceivedMessages()` when using a reader.

#### SMTP messages

The API of `SmtpMessage` provides easy access to all the basic fields:

```java
String from = smtpMessage.getFrom();
String subject = smtpMessage.getSubject();
String body = smtpMessage.getBody();
Date sentDate = smtpMessage.getSentDate();
List<String> recipientsTo = smtpMessage.getRecipients(RecipientType.TO);
List<SmtpAttachment> attachments = smtpMessage.getAttachments();
```

It is also possible to retrieve some data directly issued from the underlying SMTP exchanges
between the server and the client. Those data might differ (even be missing) from the resulting
`MimeMessage`:

```java
String sourceFrom = smtpMessage.getSourceFrom();
List<String> sourceRecipients = smtpMessage.getSourceRecipients();
```

Typically, the `BCC` recipients will be absent from the `MimeMessage` but will
be available through the `getSourceRecipients()` method.

If more specific data has to be accessed, it is possible to retrieve the raw
data with the following methods:

```java
MimeMessage mimeMessage = smtpMessage.getMimeMessage();
String mimeMessageStr = smtpMessage.getRawMimeContent();
```

#### Low level SMTP exchanges

One can access direclty the exchanges between the sender and smtp4j.

```java
List<SmtpExchange> exchanges = smtpMessage.getSmtpExchanges();
List<String> receivedData = exchanges.get(0).getReceivedData();
String repliedData = exchanges.get(0).getRepliedData();
```

#### Attachments

Multipart messages might contain many attachments that are accessibles with the `getAttachments()`
method of the `SmtpMessage`. Here is an insight of the `SmtpAttachment` API:

```java
String filename = attachment.getFilename(); // myFile.pdf
String contentType = attachment.getContentType(); // application/pdf; charset=us-ascii; name=myFile.pdf
```

The content of an attachment can be read with the following piece of code:

```java
void example() {
    try (InputStream is = attachment.openStream()) {
        //...
    }
}
```

#### Client-side messages

The API includes a utility class to build SMTP messages from the client side
that can easily be sent to smtp4j. The [MimeMessageBuilder](src/main/java/ch/astorm/smtp4j/util/MimeMessageBuilder.java)
class provides easy-to-use methods to create a Multipart MIME message:

```java
void example() {
    /* SMTP server is started on port 1025 */
    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (SmtpServer server = builder.withPort(1025).start()) {

        /* create and send an SMTP message */
        MimeMessageBuilder messageBuilder = new MimeMessageBuilder(server)
                .from("source@smtp4j.local")
                //use multiple arguments ...
                .to("to1@smtp4j.local", "Igôr <to2@smtp4.local>")
                // ... or a comma-separated list
                .to("to3@smtp4j.local, My Friend <to4@smtp4j.local>")
                //or call the method multiple times
                .cc("cc1@smtp4j.local")
                .cc("cc2@smtp4j.local")
                .bcc("bcc@smtp4j.local")
                .at("31.12.2020 23:59:59")
                .subject("Hello, world !")
                .body("Hello\r\nGreetings from smtp4j !\r\n\r\nBye.")
                .attachment(new File("file.pdf"));

//build the message and send it to smtp4j
        messageBuilder.send();
//process the received message
//...
    }
}
```

It is also possible to use this builder in a production application by using the
dedicated `Session` constructor:

```java
MimeMessageBuilder messageBuilder = new MimeMessageBuilder(session);
```

#### Server events

It is possible to listen to `SmtpServer` events by implementing a
[SmtpServerListener](src/main/java/ch/astorm/smtp4j/core/SmtpServerListener.java).

```java
void example() {
    SmtpServerListener myListener = new SmtpServerListener() {
        public void notifyStart(SmtpServer server) {
            System.out.println("Server has been started");
        }

        public void notifyClose(SmtpServer server) {
            System.out.println("Server has been closed");
        }

        public void notifyMessage(SmtpServer server, SmtpMessage message) {
            System.out.println("Message has been received");
        }
    };

    mySmtpServer.addListener(myListener);
}
```

#### Refuse a message

It is possible to trigger message refusal through the API. The exception message will
be received on the SMTP client side.

```java
void example() {
    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (SmtpServer server = builder.start()) {
        server.addListener((srv, msg) -> {
            throw new IllegalStateException("Message refused");
        });

        try {
            new MimeMessageBuilder(server)
                    .to("test@astorm.ch")
                    .subject("Test")
                    .body("Hello!")
                    .send();
        } catch (MessagingException e) {
            String message = e.getMessage(); //554 Message refused
        }
    }
}
```

#### Message storage

By default, once a `SmtpMessage` has been received, it will be stored in a default
[DefaultSmtpMessageHandler](src/main/java/ch/astorm/smtp4j/core/DefaultSmtpMessageHandler.java) instance,
which can be directly accessed like this:

```java
SmtpMessageHandler messageHandler = smtpServer.getMessageHandler();
DefaultSmtpMessageHandler defaultMessageHandler = (DefaultSmtpMessageHandler) messageHandler;
```

It is possible to override this default behavior with your custom handler with the
following piece of code:

```java
void example() {
    SmtpMessageHandler myCustomHandler = new CustomSmtpMessageHandler();

    SmtpServerBuilder builder = new SmtpServerBuilder();
    try (SmtpServer server = builder.withMessageHandler(myCustomHandler)
            .start()) {
        //... your code ...
    }
}
```
