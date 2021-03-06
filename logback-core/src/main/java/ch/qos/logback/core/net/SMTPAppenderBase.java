/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2013, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.core.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.boolex.EvaluationException;
import ch.qos.logback.core.boolex.EventEvaluator;
import ch.qos.logback.core.helpers.CyclicBuffer;
import ch.qos.logback.core.pattern.PatternLayoutBase;
import ch.qos.logback.core.sift.DefaultDiscriminator;
import ch.qos.logback.core.sift.Discriminator;
import ch.qos.logback.core.spi.CyclicBufferTracker;
import ch.qos.logback.core.util.ContentTypeUtil;
import ch.qos.logback.core.util.OptionHelper;

// Contributors:
// Andrey Rybin charset encoding support http://jira.qos.ch/browse/LBCORE-69

/**
 * An abstract class that provides support for sending events to an email
 * address.
 * <p>
 * See http://logback.qos.ch/manual/appenders.html#SMTPAppender for further
 * documentation.
 *
 * @author Ceki G&uuml;lc&uuml;
 * @author S&eacute;bastien Pennec
 */
public abstract class SMTPAppenderBase<E> extends AppenderBase<E> {

  static InternetAddress[] EMPTY_IA_ARRAY = new InternetAddress[0];
  // ~ 14 days
  static final int MAX_DELAY_BETWEEN_STATUS_MESSAGES = 1228800 * CoreConstants.MILLIS_IN_ONE_SECOND;

  long lastTrackerStatusPrint = 0;
  int delayBetweenStatusMessages = 300 * CoreConstants.MILLIS_IN_ONE_SECOND;

  protected Layout<E> subjectLayout;
  protected Layout<E> layout;

  private List<PatternLayoutBase<E>> toPatternLayoutList = new ArrayList<PatternLayoutBase<E>>();
  private String from;
  private String subjectStr = null;
  private String smtpHost;
  private int smtpPort = 25;
  private boolean starttls = false;
  private boolean ssl = false;

  String username;
  String password;
  String localhost;

  boolean asynchronousSending = true;

  private String charsetEncoding = "UTF-8";

  protected MimeMessage mimeMsg;

  protected EventEvaluator<E> eventEvaluator;

  protected Discriminator<E> discriminator = new DefaultDiscriminator<E>();
  protected CyclicBufferTracker<E> cbTracker;

  private int errorCount = 0;

  /**
   * return a layout for the subject string as appropriate for the module. If the
   * subjectStr parameter is null, then a default value for subjectStr should be
   * used.
   *
   * @param subjectStr the desired subject
   * @return a layout as appropriate for the module
   */
  abstract protected Layout<E> makeSubjectLayout(String subjectStr);

  /**
   * Start the appender
   */
  public void start() {

    if (cbTracker == null) {
      cbTracker = new CyclicBufferTracker<E>();
    }

    Session session = buildSessionFromProperties();

    if (session == null) {
      addError("Failed to obtain javax.mail.Session. Cannot start.");
      return;
    }
    mimeMsg = new MimeMessage(session);

    try {
      if (from != null) {
        mimeMsg.setFrom(getAddress(from));
      } else {
        mimeMsg.setFrom();
      }

      subjectLayout = makeSubjectLayout(subjectStr);

      started = true;

    } catch (MessagingException e) {
      addError("Could not activate SMTPAppender options.", e);
    }
  }

  private Session buildSessionFromProperties() {
    Properties props = new Properties(OptionHelper.getSystemProperties());
    if (smtpHost != null) {
      props.put("mail.smtp.host", smtpHost);
    }
    props.put("mail.smtp.port", Integer.toString(smtpPort));

    if (localhost != null) {
      props.put("mail.smtp.localhost", localhost);
    }

    LoginAuthenticator loginAuthenticator = null;

    if (username != null) {
      loginAuthenticator = new LoginAuthenticator(username, password);
      props.put("mail.smtp.auth", "true");
    }

    if (isSTARTTLS() && isSSL()) {
      addError("Both SSL and StartTLS cannot be enabled simultaneously");
    } else {
      if (isSTARTTLS()) {
        // see also http://jira.qos.ch/browse/LBCORE-225
        props.put("mail.smtp.starttls.enable", "true");
      }
      if (isSSL()) {
        //String SSL_FACTORY = "javax.net.ssl.SSLSocketFactory";
        String SSL_FACTORY = "com.sun.mail.util.MailSSLSocketFactory";
        props.put("mail.smtp.socketFactory.port", Integer.toString(smtpPort));
        props.put("mail.smtp.socketFactory.class", SSL_FACTORY);
        props.put("mail.smtp.socketFactory.fallback", "true");
      }
    }

    // props.put("mail.debug", "true");

    return Session.getInstance(props, loginAuthenticator);
  }

  /**
   * Perform SMTPAppender specific appending actions, delegating some of them to
   * a subclass and checking if the event triggers an e-mail to be sent.
   */
  protected void append(E eventObject) {

    if (!checkEntryConditions()) {
      return;
    }

    String key = discriminator.getDiscriminatingValue(eventObject);
    long now = System.currentTimeMillis();
    final CyclicBuffer<E> cb = cbTracker.getOrCreate(key, now);
    subAppend(cb, eventObject);

    try {
      if (eventEvaluator.evaluate(eventObject)) {
        // clone the CyclicBuffer before sending out asynchronously
        CyclicBuffer<E> cbClone = new CyclicBuffer<E>(cb);
        // see http://jira.qos.ch/browse/LBCLASSIC-221
        cb.clear();

        if (asynchronousSending) {
          // perform actual sending asynchronously
          SenderRunnable senderRunnable = new SenderRunnable(cbClone, eventObject);
          context.getExecutorService().execute(senderRunnable);
        } else {
          // synchronous sending
          sendBuffer(cbClone, eventObject);
        }
      }
    } catch (EvaluationException ex) {
      errorCount++;
      if (errorCount < CoreConstants.MAX_ERROR_COUNT) {
        addError("SMTPAppender's EventEvaluator threw an Exception-", ex);
      }
    }

    // immediately remove the buffer if asked by the user
    if (eventMarksEndOfLife(eventObject)) {
      cbTracker.endOfLife(key);
    }

    cbTracker.removeStaleComponents(now);

    if (lastTrackerStatusPrint + delayBetweenStatusMessages < now) {
      addInfo("SMTPAppender [" + name + "] is tracking [" + cbTracker.getComponentCount() + "] buffers");
      lastTrackerStatusPrint = now;
      // quadruple 'delay' assuming less than max delay
      if (delayBetweenStatusMessages < MAX_DELAY_BETWEEN_STATUS_MESSAGES) {
        delayBetweenStatusMessages *= 4;
      }
    }
  }

  abstract protected boolean eventMarksEndOfLife(E eventObject);

  abstract protected void subAppend(CyclicBuffer<E> cb, E eventObject);

  /**
   * This method determines if there is a sense in attempting to append.
   * <p>
   * It checks whether there is a set output target and also if there is a set
   * layout. If these checks fail, then the boolean value <code>false</code> is
   * returned.
   * @return true if appender is setup properly
   */
  public boolean checkEntryConditions() {
    if (!this.started) {
      addError("Attempting to append to a non-started appender: "
              + this.getName());
      return false;
    }

    if (this.mimeMsg == null) {
      addError("Message object not configured.");
      return false;
    }

    if (this.eventEvaluator == null) {
      addError("No EventEvaluator is set for appender [" + name + "].");
      return false;
    }

    if (this.layout == null) {
      addError("No layout set for appender named ["
              + name
              + "]. For more information, please visit http://logback.qos.ch/codes.html#smtp_no_layout");
      return false;
    }
    return true;
  }

  synchronized public void stop() {
    this.started = false;
  }

  InternetAddress getAddress(String addressStr) {
    try {
      return new InternetAddress(addressStr);
    } catch (AddressException e) {
      addError("Could not parse address [" + addressStr + "].", e);
      return null;
    }
  }

  private List<InternetAddress> parseAddress(E event) {
    int len = toPatternLayoutList.size();

    List<InternetAddress> iaList = new ArrayList<InternetAddress>();

    for (int i = 0; i < len; i++) {
      try {
        PatternLayoutBase<E> emailPL = toPatternLayoutList.get(i);
        String emailAdrr = emailPL.doLayout(event);
        if (emailAdrr == null || emailAdrr.length() == 0) {
          continue;
        }
        InternetAddress[] tmp = InternetAddress.parse(emailAdrr, true);
        iaList.addAll(Arrays.asList(tmp));
      } catch (AddressException e) {
        addError("Could not parse email address for [" + toPatternLayoutList.get(i) + "] for event [" + event + "]", e);
        return iaList;
      }
    }

    return iaList;
  }

  /**
   * Returns value of the <b>toList</b> option.
   * @return list of pattern layout
   */
  public List<PatternLayoutBase<E>> getToList() {
    return toPatternLayoutList;
  }

  /**
   * Send the contents of the cyclic buffer as an e-mail message.
   * @param cb the cyclic buffer
   * @param lastEventObject the log event
   */
  protected void sendBuffer(CyclicBuffer<E> cb, E lastEventObject) {

    // Note: this code already owns the monitor for this
    // appender. This frees us from needing to synchronize on 'cb'.
    try {
      MimeBodyPart part = new MimeBodyPart();

      StringBuffer sbuf = new StringBuffer();

      String header = layout.getFileHeader();
      if (header != null) {
        sbuf.append(header);
      }
      String presentationHeader = layout.getPresentationHeader();
      if (presentationHeader != null) {
        sbuf.append(presentationHeader);
      }
      fillBuffer(cb, sbuf);
      String presentationFooter = layout.getPresentationFooter();
      if (presentationFooter != null) {
        sbuf.append(presentationFooter);
      }
      String footer = layout.getFileFooter();
      if (footer != null) {
        sbuf.append(footer);
      }

      String subjectStr = "Undefined subject";
      if (subjectLayout != null) {
        subjectStr = subjectLayout.doLayout(lastEventObject);

        // The subject must not contain new-line characters, which cause
        // an SMTP error (LOGBACK-865). Truncate the string at the first
        // new-line character.
        int newLinePos = (subjectStr != null) ? subjectStr.indexOf('\n') : -1;
        if (newLinePos > -1) {
          subjectStr = subjectStr.substring(0, newLinePos);
        }
      }
      mimeMsg.setSubject(subjectStr, charsetEncoding);

      List<InternetAddress> destinationAddresses = parseAddress(lastEventObject);
      if (destinationAddresses.isEmpty()) {
        addInfo("Empty destination address. Aborting email transmission");
        return;
      }

      InternetAddress[] toAddressArray = destinationAddresses.toArray(EMPTY_IA_ARRAY);
      mimeMsg.setRecipients(Message.RecipientType.TO, toAddressArray);

      String contentType = layout.getContentType();

      if (ContentTypeUtil.isTextual(contentType)) {
        part.setText(sbuf.toString(), charsetEncoding, ContentTypeUtil
                .getSubType(contentType));
      } else {
        part.setContent(sbuf.toString(), layout.getContentType());
      }

      Multipart mp = new MimeMultipart();
      mp.addBodyPart(part);
      mimeMsg.setContent(mp);

      mimeMsg.setSentDate(new Date());
      addInfo("About to send out SMTP message \"" + subjectStr + "\" to " + Arrays.toString(toAddressArray));
      Transport.send(mimeMsg);
    } catch (Exception e) {
      addError("Error occurred while sending e-mail notification.", e);
    }
  }

  abstract protected void fillBuffer(CyclicBuffer<E> cb, StringBuffer sbuf);

  /**
   * Returns value of the <b>From</b> option.
   * @return the value of "from"
   */
  public String getFrom() {
    return from;
  }

  /**
   * Returns value of the <b>Subject</b> option.
   * @return the subject text
   */
  public String getSubject() {
    return subjectStr;
  }

  /**
   * The <b>From</b> option takes a string value which should be a e-mail
   * address of the sender.
   * @param from desired value of "from" field
   */
  public void setFrom(String from) {
    this.from = from;
  }

  /**
   * The <b>Subject</b> option takes a string value which should be a the
   * subject of the e-mail message.
   * @param subject desired subject text
   */
  public void setSubject(String subject) {
    this.subjectStr = subject;
  }

  /**
   * Alias for smtpHost
   *
   * @param smtpHost desired alias for SMTP host
   */
  public void setSMTPHost(String smtpHost) {
    setSmtpHost(smtpHost);
  }

  /**
   * The <b>smtpHost</b> option takes a string value which should be a the host
   * name of the SMTP server that will send the e-mail message.
   * @param smtpHost desired SMTP hostname
   */
  public void setSmtpHost(String smtpHost) {
    this.smtpHost = smtpHost;
  }

  /**
   * Alias for getSmtpHost().
   * @return the SMTP hostname
   */
  public String getSMTPHost() {
    return getSmtpHost();
  }

  /**
   * Returns value of the <b>SMTPHost</b> option.
   * @return the SMTP hostname
   */
  public String getSmtpHost() {
    return smtpHost;
  }

  /**
   * Alias for {@link #setSmtpPort}.
   *
   * @param port desired SMTP port
   */
  public void setSMTPPort(int port) {
    setSmtpPort(port);
  }

  /**
   * The port where the SMTP server is running. Default value is 25.
   *
   * @param port desired SMTP port
   */
  public void setSmtpPort(int port) {
    this.smtpPort = port;
  }

  /**
   * Alias for {@link #getSmtpPort}
   *
   * @return the SMTP port
   */
  public int getSMTPPort() {
    return getSmtpPort();
  }

  /**
   * See {@link #setSmtpPort}
   *
   * @return the SMTP port
   */
  public int getSmtpPort() {
    return smtpPort;
  }

  public String getLocalhost() {
    return localhost;
  }

  /**
   * Set the "mail.smtp.localhost" property to the value passed as parameter to
   * this method.
   * <p>
   * Useful in case the hostname for the client host is not fully qualified
   * and as a consequence the SMTP server rejects the clients HELO/EHLO command.
   * </p>
   *
   * @param localhost the localhost name
   */
  public void setLocalhost(String localhost) {
    this.localhost = localhost;
  }

  public CyclicBufferTracker<E> getCyclicBufferTracker() {
    return cbTracker;
  }

  public void setCyclicBufferTracker(CyclicBufferTracker<E> cbTracker) {
    this.cbTracker = cbTracker;
  }

  public Discriminator<E> getDiscriminator() {
    return discriminator;
  }

  public void setDiscriminator(Discriminator<E> discriminator) {
    this.discriminator = discriminator;
  }

  public boolean isAsynchronousSending() {
    return asynchronousSending;
  }

  /**
   * By default, SMTAppender transmits emails asynchronously. For synchronous email transmission set
   * asynchronousSending to 'false'.
   *
   * @param asynchronousSending determines whether sending is done asynchronously or not
   * @since 1.0.4
   */
  public void setAsynchronousSending(boolean asynchronousSending) {
    this.asynchronousSending = asynchronousSending;
  }

  public void addTo(String to) {
    if (to == null || to.length() == 0) {
      throw new IllegalArgumentException("Null or empty <to> property");
    }
    PatternLayoutBase plb = makeNewToPatternLayout(to.trim());
    plb.setContext(context);
    plb.start();
    this.toPatternLayoutList.add(plb);
  }

  abstract protected PatternLayoutBase<E> makeNewToPatternLayout(String toPattern);

  public List<String> getToAsListOfString() {
    List<String> toList = new ArrayList<String>();
    for (PatternLayoutBase plb : toPatternLayoutList) {
      toList.add(plb.getPattern());
    }
    return toList;
  }

  // for testing purpose only
  public Message getMessage() {
    return mimeMsg;
  }

  // for testing purpose only

  public void setMessage(MimeMessage msg) {
    this.mimeMsg = msg;
  }

  public boolean isSTARTTLS() {
    return starttls;
  }

  public void setSTARTTLS(boolean startTLS) {
    this.starttls = startTLS;
  }

  public boolean isSSL() {
    return ssl;
  }

  public void setSSL(boolean ssl) {
    this.ssl = ssl;
  }

  /**
   * The <b>EventEvaluator</b> option takes a string value representing the name
   * of the class implementing the {@link EventEvaluator} interface. A
   * corresponding object will be instantiated and assigned as the event
   * evaluator for the SMTPAppender.
   * @param eventEvaluator the desired event evaluator
   */
  public void setEvaluator(EventEvaluator<E> eventEvaluator) {
    this.eventEvaluator = eventEvaluator;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  /**
   * @return the charset encoding value
   * @see #setCharsetEncoding(String)
   */
  public String getCharsetEncoding() {
    return charsetEncoding;
  }

  /**
   * Set the character set encoding of the outgoing email messages. The default
   * encoding is "UTF-8" which usually works well for most purposes.
   *
   * @param charsetEncoding desired character encoding
   */
  public void setCharsetEncoding(String charsetEncoding) {
    this.charsetEncoding = charsetEncoding;
  }

  public Layout<E> getLayout() {
    return layout;
  }

  public void setLayout(Layout<E> layout) {
    this.layout = layout;
  }

  class SenderRunnable implements Runnable {

    final CyclicBuffer<E> cyclicBuffer;
    final E e;

    SenderRunnable(CyclicBuffer<E> cyclicBuffer, E e) {
      this.cyclicBuffer = cyclicBuffer;
      this.e = e;
    }

    public void run() {
      sendBuffer(cyclicBuffer, e);
    }
  }
}
