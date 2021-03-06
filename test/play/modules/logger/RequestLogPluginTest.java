package play.modules.logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import play.Play;
import play.data.parsing.UrlEncodedParser;
import play.mvc.Http;
import play.mvc.Scope;
import play.mvc.results.*;
import play.rebel.RenderView;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

public class RequestLogPluginTest {
  @SuppressWarnings("deprecation")
  Http.Request request = new Http.Request();

  @Before
  public void setUp() {
    Play.configuration.clear();
    Play.configuration.setProperty("request.log.maskParams", "password|cvv|card.cvv|card.number");
    new RequestLogPlugin().onConfigurationRead();
    Http.Request.current.set(request);
  }

  @After
  public void tearDown() {
    Play.configuration.clear();
    Http.Request.current.remove();
    Scope.Session.current.remove();
  }

  @Test
  public void passwordIsMasked() {
    setQueryString("username=anton&password=123&password2=456&newPassword=678&password=789&oldPassword=1693&age=12");
    String maskedParams = RequestLogPlugin.extractParams(request);

    assertTrue(maskedParams.contains("username=anton"));
    assertTrue(maskedParams.contains("age=12"));
    assertTrue(maskedParams.contains("password2=*"));
    assertTrue(maskedParams.contains("newPassword=*"));
    assertTrue(maskedParams.contains("oldPassword=*"));
    assertTrue(maskedParams.contains("password=*"));
    assertFalse(maskedParams.contains("123"));
    assertFalse(maskedParams.contains("456"));
    assertFalse(maskedParams.contains("67"));
    assertFalse(maskedParams.contains("789"));
    assertFalse(maskedParams.contains("1693"));
  }

  @Test
  public void cvvIsMasked() {
    setQueryString("card.holderName=Some+Body&card.number=6789690444552800&" +
        "card.validityMonth=07&card.validityYear=2015&card.cvv=907&cvv=600");
    String maskedParams = RequestLogPlugin.extractParams(request);

    assertTrue(maskedParams.contains("card.validityYear=2015"));
    assertTrue(maskedParams.contains("card.validityMonth=07"));
    assertTrue(maskedParams.contains("card.holderName=Some Body"));
    assertTrue(maskedParams.contains("card.cvv=*"));
    assertTrue(maskedParams.contains("cvv=*"));
    assertTrue(maskedParams.contains("card.number=*"));
    assertFalse(maskedParams.contains("6789690444552800"));
    assertFalse(maskedParams.contains("907"));
    assertFalse(maskedParams.contains("600"));
  }

  @Test
  public void cardNumberIsMasked() {
    setQueryString("card.number=6789 6904 4455 2800");
    assertEquals("card.number=*", RequestLogPlugin.extractParams(request));
  }

  @Test
  public void postParametersAreIncluded() {
    setQueryString("id=123");
    request.contentType = "application/x-www-form-urlencoded";
    request.body = new ByteArrayInputStream("password=456&x=y".getBytes());
    String maskedParams = RequestLogPlugin.extractParams(request);

    assertTrue(maskedParams.contains("id=123"));
    assertTrue(maskedParams.contains("password=*"));
    assertTrue(maskedParams.contains("x=y"));
    assertFalse(maskedParams.contains("456"));
  }

  @Test
  public void skipsPlaySpecificParameters() {
    setQueryString("authenticityToken=skip&action=skip&controller=skip&abc=value");
    assertEquals("abc=value", RequestLogPlugin.extractParams(request));
  }

  @Test
  public void paramsAreDecoded() {
    setQueryString("hello=A+B+%43");
    assertEquals("hello=A B C", RequestLogPlugin.extractParams(request));
  }

  @Test
  public void paramsAreSkipped_if_thereWasAnErrorWhenParsingRequestParams() {
    request.contentType = "application/json";
    request.encoding = "erroneous";

    assertEquals("", RequestLogPlugin.extractParams(request));
  }

  @Test
  public void customLogData() {
    assertEquals("", RequestLogPlugin.getRequestLogCustomData(request));

    request.args = new HashMap<>();
    request.args.put("requestLogCustomData", "xtra");

    assertEquals(" xtra", RequestLogPlugin.getRequestLogCustomData(request));
  }

  @Test
  public void setsCurrentThreadName_by_actionName() {
    request.action = "Payments.history";
    request.args.put("requestId", "xxx-yyy");
    request.remoteAddress = "111.22.3.444";
    Scope.Session.current.set(mockSession("session-002"));
    Thread.currentThread().setName("play-thread-666");

    new RequestLogPlugin().beforeActionInvocation(null);
    assertEquals("play-thread-666 Payments.history [xxx-yyy] (111.22.3.444 session-002)", Thread.currentThread().getName());
  }

  @Test
  public void ignoresPreviouslySetThreadName_if_itWasNotResetForWhateverReason() {
    request.action = "Payments.history";
    request.args.put("requestId", "yyy-zzz");
    request.remoteAddress = "127.0.0.1";
    Scope.Session.current.set(mockSession("session-003"));
    Thread.currentThread().setName("play-thread-1 Bank.statement");

    new RequestLogPlugin().beforeActionInvocation(null);
    assertEquals("play-thread-1 Payments.history [yyy-zzz] (127.0.0.1 session-003)", Thread.currentThread().getName());
  }

  @Test
  public void resetsCurrentThreadName_after_actionInvocation() {
    Thread.currentThread().setName("play-thread-007 Payments.history");
    new RequestLogPlugin().onActionInvocationFinally();
    assertEquals("play-thread-007", Thread.currentThread().getName());
  }

  @Test
  public void noResultMeansRenderingError() {
    assertEquals("RenderError", RequestLogPlugin.result(null));
  }

  @Test
  public void logsRedirectUrl() {
    Redirect result = new Redirect("/foo");
    assertEquals("Redirect /foo", RequestLogPlugin.result(result));
  }

  @Test
  public void logsTemplateRenderingTime() {
    RenderTemplate result = mock(RenderTemplate.class);
    when(result.getRenderTime()).thenReturn(101L);
    when(result.getName()).thenReturn("Employees/registry.html");
    assertEquals("RenderTemplate Employees/registry.html 101 ms", RequestLogPlugin.result(result));
  }

  @Test
  public void logsViewRenderingTime() {
    RenderView result = mock(RenderView.class);
    when(result.getRenderTime()).thenReturn(101L);
    when(result.getName()).thenReturn("Employees/registry.html");
    assertEquals("RenderView Employees/registry.html 101 ms", RequestLogPlugin.result(result));
  }

  @Test
  public void logsFileName_forRenderBinary() {
    RenderBinary result = new RenderBinary((InputStream) null, "statement.dbf");
    assertEquals("RenderBinary statement.dbf", RequestLogPlugin.result(result));
  }

  @Test
  public void logsFileNameAndContentType_forRenderBinary() {
    RenderBinary result = new RenderBinary(null, "cert.pem", "application/pkix-cert", false);
    assertEquals("RenderBinary cert.pem application/pkix-cert", RequestLogPlugin.result(result));
  }
  
  @Test
  public void logsReason_forError() {
    Forbidden forbidden = new Forbidden("User signature not valid!");
    assertEquals("Forbidden \"User signature not valid!\"", RequestLogPlugin.result(forbidden));
    BadRequest badRequest = new BadRequest("input error!");
    assertEquals("BadRequest \"input error!\"", RequestLogPlugin.result(badRequest));
    EvilRequest evilRequest=new EvilRequest("evil request");
    assertEquals("EvilRequest \"evil request\"", RequestLogPlugin.result(evilRequest));
  }

  private void setQueryString(String params) {
    try {
      request.params.data.putAll(UrlEncodedParser.parseQueryString(new ByteArrayInputStream(params.getBytes("UTF-8"))));
    }
    catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void logRequestInfo() {
    request.action = "Bank.overview";
    request.method = "GET";
    request.remoteAddress = "111.222.333.444";
    request.args.put("startTime", 1000000000L);
    request.args.put("requestLogCustomData", "c-corporate");
    request.params.put("paymentId", "12345");
    Scope.Session session = mockSession("session-id-001");
    RequestLogPlugin.logger = mock(Logger.class);

    RequestLogPlugin.logRequestInfo(request, session, new Redirect("/foo"));

    verify(RequestLogPlugin.logger).info(startsWith("Bank.overview 111.222.333.444 session-id-001 c-corporate GET paymentId=12345 -> Redirect /foo"));
  }

  private Scope.Session mockSession(String sessionId) {
    Scope.Session session = mock(Scope.Session.class);
    when(session.getId()).thenReturn(sessionId);
    return session;
  }

  @Test
  public void logRequestInfo_whenSessionIsNull() {
    request.action = "Bank.overview";
    request.method = "GET";
    request.remoteAddress = "111.222.333.444";
    request.args.put("startTime", 1000000000L);
    request.args.put("requestLogCustomData", "c-corporate");
    request.params.put("paymentId", "12345");
    RequestLogPlugin.logger = mock(Logger.class);

    RequestLogPlugin.logRequestInfo(request, null, new Redirect("/foo"));

    verify(RequestLogPlugin.logger).info(startsWith("Bank.overview 111.222.333.444 no-session c-corporate GET paymentId=12345 -> Redirect /foo"));
  }
}
