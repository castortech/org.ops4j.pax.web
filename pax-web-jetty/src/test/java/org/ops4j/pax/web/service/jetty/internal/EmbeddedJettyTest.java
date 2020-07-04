/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.jetty.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.junit.Test;
import org.ops4j.pax.web.service.jetty.internal.web.JettyResourceServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EmbeddedJettyTest {

	public static final Logger LOG = LoggerFactory.getLogger(EmbeddedJettyTest.class);

	@Test
	public void embeddedServerWithTrivialHandler() throws Exception {
		QueuedThreadPool qtp = new QueuedThreadPool(10);
		qtp.setName("jetty-qtp");

		// main class for a "server" in Jetty. It:
		// - contains connectors (http receivers)
		// - contains request handlers (being a handler itself)
		// - contains a thread pool used by connectors to run request handlers
		Server server = new Server(qtp);

		// "connector" accepts remote connections and data. ServerConnector is the main, NIO based connector
		// that can handle HTTP, HTTP/2, SSL and websocket connections
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		LOG.info("Local port before start: {}", connector.getLocalPort());

		// initially server doesn't have connectors, so we have to set them
		server.setConnectors(new Connector[] { connector });

		// this is done implicitly anyway
		server.setErrorHandler(new ErrorHandler());

		// "handler" is invoked by connector when request is received. Jetty comes with this nice
		// hierarchy of handlers and Pax Web itself adds few more:
		//
		// Handler (org.eclipse.jetty.server)
		//   AbstractHandler (org.eclipse.jetty.server.handler)
		//     AbstractHandlerContainer (org.eclipse.jetty.server.handler)
		//       HandlerCollection (org.eclipse.jetty.server.handler)
		//         ContextHandlerCollection (org.eclipse.jetty.server.handler)
		//         HandlerList (org.eclipse.jetty.server.handler)
		//         JettyServerHandlerCollection (org.ops4j.pax.web.service.jetty.internal)
		//       HandlerWrapper (org.eclipse.jetty.server.handler)
		//         AsyncDelayHandler (org.eclipse.jetty.server.handler)
		//         BufferedResponseHandler (org.eclipse.jetty.server.handler)
		//         DebugHandler (org.eclipse.jetty.server.handler)
		//         GzipHandler (org.eclipse.jetty.server.handler.gzip)
		//         IdleTimeoutHandler (org.eclipse.jetty.server.handler)
		//         InetAccessHandler (org.eclipse.jetty.server.handler)
		//         IPAccessHandler (org.eclipse.jetty.server.handler)
		//         RequestLogHandler (org.eclipse.jetty.server.handler)
		//         ResourceHandler (org.eclipse.jetty.server.handler)
		//         ScopedHandler (org.eclipse.jetty.server.handler)
		//           ContextHandler (org.eclipse.jetty.server.handler)
		//             MovedContextHandler (org.eclipse.jetty.server.handler)
		//             ServletContextHandler (org.eclipse.jetty.servlet)
		//               HttpServiceContext (org.ops4j.pax.web.service.jetty.internal)
		//               WebAppContext (org.eclipse.jetty.webapp)
		//           ServletHandler (org.eclipse.jetty.servlet)
		//             HttpServiceServletHandler (org.ops4j.pax.web.service.jetty.internal)
		//           SessionHandler (org.eclipse.jetty.server.session)
		//         SecurityHandler (org.eclipse.jetty.security)
		//           ConstraintSecurityHandler (org.eclipse.jetty.security)
		//         Server (org.eclipse.jetty.server)
		//           JettyServerWrapper (org.ops4j.pax.web.service.jetty.internal)
		//         ShutdownHandler (org.eclipse.jetty.server.handler)
		//         StatisticsHandler (org.eclipse.jetty.server.handler)
		//         ThreadLimitHandler (org.eclipse.jetty.server.handler)
		//       HotSwapHandler (org.eclipse.jetty.server.handler)
		//   DefaultHandler (org.eclipse.jetty.server.handler)
		//   ErrorDispatchHandler in AbstractHandler (org.eclipse.jetty.server.handler)
		//   ErrorHandler (org.eclipse.jetty.server.handler)
		//     ErrorPageErrorHandler (org.eclipse.jetty.servlet)
		//   Redirector in MovedContextHandler (org.eclipse.jetty.server.handler)
		//   SecuredRedirectHandler (org.eclipse.jetty.server.handler)
		server.setHandler(new AbstractHandler() {
			@Override
			protected void doStart() throws Exception {
				LOG.info("Starting custom handler during server startup");
			}

			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("OK\n");
				response.getWriter().close();
			}
		});

		// starting Jetty server performs these tasks:
		// - ensuring that org.eclipse.jetty.server.handler.ErrorHandler is available
		// - all beans () are started. With the above setup, these are the beans:
		//   _beans = {java.util.concurrent.CopyOnWriteArrayList@2151}  size = 4
		//     0 = "{QueuedThreadPool[jetty-qtp]@45efd90f{STOPPED,8<=0<=10,i=0,r=-1,q=0}[NO_TRY],AUTO}"
		//     1 = "{ServerConnector@398dada8{HTTP/1.1,[http/1.1]}{0.0.0.0:0},AUTO}"
		//       _beans: java.util.List  = {java.util.concurrent.CopyOnWriteArrayList@2260}  size = 6
		//         0 = "{Server@2812b107{STARTING}[9.4.26.v20200117],UNMANAGED}"
		//           _beans: java.util.List  = {java.util.concurrent.CopyOnWriteArrayList@2207}  size = 1
		//             0 = {org.eclipse.jetty.util.component.ContainerLifeCycle$Bean@2739} "{ReservedThreadExecutor@352c1b98{s=0/1,p=0},AUTO}"
		//         1 = "{QueuedThreadPool[jetty-qtp]@45efd90f{STOPPED,8<=0<=10,i=0,r=-1,q=0}[NO_TRY],UNMANAGED}"
		//         2 = "{ScheduledExecutorScheduler@6ee4d9ab{STOPPED},AUTO}"
		//         3 = "{org.eclipse.jetty.io.ArrayByteBufferPool@5a5338df,POJO}"
		//         4 = "{HttpConnectionFactory@61eaec38[HTTP/1.1],AUTO}"
		//           _beans: java.util.List  = {java.util.concurrent.CopyOnWriteArrayList@2489}  size = 1
		//             0 = "{HttpConfiguration@332729ad{32768/8192,8192/8192,https://:0,[]},POJO}"
		//         5 = "{SelectorManager@ServerConnector@398dada8{HTTP/1.1,[http/1.1]}{0.0.0.0:0},MANAGED}"
		//     2 = "{ErrorHandler@df6620a{STOPPED},AUTO}"
		//     3 = "{AbstractHandler@4416d64f{STOPPED},MANAGED}"
		// - in the above lifecycle, lifecycle objects with org.eclipse.jetty.server.Connector interface are delayed,
		//   they'll be started last
		// - after all org.eclipse.jetty.util.component.ContainerLifeCycle._beans are started, Server starts
		//   all org.eclipse.jetty.server.Server._connectors
		// - local port of the connector is set in org.eclipse.jetty.server.ServerConnector.open()
		server.start();

		LOG.info("Local port after start: {}", connector.getLocalPort());

		Socket s = new Socket();
		s.connect(new InetSocketAddress("127.0.0.1", connector.getLocalPort()));

		s.getOutputStream().write((
				"GET / HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithServletHandler() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		// empty ServletHandler has empty org.eclipse.jetty.servlet.ServletHandler._servletMappings
		// so (if org.eclipse.jetty.servlet.ServletHandler._ensureDefaultServlet == true),
		// org.eclipse.jetty.servlet.ServletHandler.Default404Servlet is mapped to "/"
		ServletHandler handler = new ServletHandler();
		handler.setEnsureDefaultServlet(false);

		// this method just adds servlet to org.eclipse.jetty.servlet.ServletHandler._servlets, it's not enough
		// servlet needs a name (in ServletHolder) to allow its mapping
		handler.addServlet(new ServletHolder("default-servlet", new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK\n");
				resp.getWriter().close();
			}
		}));

		// adding a mapping ensures proper entry in org.eclipse.jetty.servlet.ServletHandler._servletMappings
		// when ServletHandler starts, org.eclipse.jetty.servlet.ServletHandler._servletNameMap is also updated
		ServletMapping mapping = new ServletMapping();
		mapping.setServletName("default-servlet");
		mapping.setPathSpec("/");
		handler.addServletMapping(mapping);

		server.setHandler(handler);
		server.start();

		int port = connector.getLocalPort();

		Socket s = new Socket();
		s.connect(new InetSocketAddress("127.0.0.1", port));

		s.getOutputStream().write((
				"GET / HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithContextHandler() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		// ServletHandler requires ServletContextHandler, so we need more primitive handlers
		Handler plainHandler1 = new AbstractHandler() {
			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("OK1\n");
				response.getWriter().close();
			}
		};

		Handler plainHandler2 = new AbstractHandler() {
			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
				response.setContentType("text/plain");
				response.setCharacterEncoding("UTF-8");
				response.getWriter().write("OK2\n");
				response.getWriter().close();
			}
		};

		// context handler sets "context" for the request. "Context" consists of class loader, context path, ...
		ContextHandler handler1 = new ContextHandler("/c1");
		handler1.setHandler(plainHandler1);
		// without it, we'll need "GET /c1/ HTTP/1.1" requests
		// or just follow `HTTP/1.1 302 Found` redirect from /c1 to /c1/
		handler1.setAllowNullPathInfo(true);
		ContextHandler handler2 = new ContextHandler("/c2");
		handler2.setHandler(plainHandler2);
		// without it, we'll need "GET /c2/ HTTP/1.1" requests
		handler2.setAllowNullPathInfo(true);

		ContextHandlerCollection chc = new ContextHandlerCollection(handler1, handler2);

		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		Socket s1 = new Socket();
		s1.connect(new InetSocketAddress("127.0.0.1", port));

		s1.getOutputStream().write((
				"GET /c1 HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s1.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s1.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK1\n"));

		Socket s2 = new Socket();
		s2.connect(new InetSocketAddress("127.0.0.1", port));

		s2.getOutputStream().write((
				"GET /c2 HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		buf = new byte[64];
		read = -1;
		sw = new StringWriter();
		while ((read = s2.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s2.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK2\n"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithServletContextHandler() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		// passing chc to ServletContextHandler is a bit confusing, because it's not kept as field there. It's
		// only used to call chc.addHandler()
		// fortunately null can be passed as 1st argument in ServletContextHandler constructor
		ContextHandlerCollection chc = new ContextHandlerCollection();

		// servlet context handler extends ContextHandler for easier ContextHandler with _handler = ServletHandler
		// created ServletContextHandler will already have session, security handlers (depending on options) and
		// ServletHandler and we can add servlets/filters through ServletContextHandler
		ServletContextHandler handler1 = new ServletContextHandler(null, "/c1", ServletContextHandler.NO_SESSIONS);
		handler1.setAllowNullPathInfo(true);
		// this single method adds both ServletHolder and ServletMapping
		// calling org.eclipse.jetty.servlet.ServletHandler.addServletWithMapping()
		handler1.addServlet(new ServletHolder("default-servlet", new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK1\n");
				resp.getWriter().close();
			}
		}), "/");

		ServletContextHandler handler2 = new ServletContextHandler(null, "/c2", ServletContextHandler.NO_SESSIONS);
		handler2.setAllowNullPathInfo(true);
		handler2.addServlet(new ServletHolder("default-servlet", new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK2\n");
				resp.getWriter().close();
			}
		}), "/");

		chc.addHandler(handler1);
		chc.addHandler(handler2);

		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		Socket s1 = new Socket();
		s1.connect(new InetSocketAddress("127.0.0.1", port));

		s1.getOutputStream().write((
				"GET /c1 HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s1.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s1.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK1\n"));

		Socket s2 = new Socket();
		s2.connect(new InetSocketAddress("127.0.0.1", port));

		s2.getOutputStream().write((
				"GET /c2 HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		buf = new byte[64];
		read = -1;
		sw = new StringWriter();
		while ((read = s2.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s2.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK2\n"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithJettyResourceServlet() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();
		ServletContextHandler handler1 = new ServletContextHandler(null, "/", ServletContextHandler.NO_SESSIONS);
		handler1.setAllowNullPathInfo(true);
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "dirAllowed", "false");
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "etags", "true");
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "resourceBase", new File("target").getAbsolutePath());
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "maxCachedFiles", "1000");

		handler1.addServlet(new ServletHolder("default", new DefaultServlet()), "/");

		chc.addHandler(handler1);
		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		// Jetty generates ETag using org.eclipse.jetty.util.resource.Resource.getWeakETag(java.lang.String)
		// which is 'W/"' + base64(hash of name ^ lastModified) + base64(hash of name ^ length) + '"'

		String response = send(port, "/test-classes/log4j2-test.properties");
		Map<String, String> headers = extractHeaders(response);
		assertTrue(response.contains("ETag: W/"));
		assertTrue(response.contains("rootLogger.appenderRef.file.ref = file"));

		response = send(port, "/test-classes/log4j2-test.properties",
				"If-None-Match: " + headers.get("ETag"),
				"If-Modified-Since: " + headers.get("Date"));
		assertTrue(response.contains("HTTP/1.1 304"));
		assertFalse(response.contains("rootLogger.appenderRef.file.ref = file"));

		server.stop();
		server.join();
	}

	@Test
	public void twoResourceServletsWithDifferentBases() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();
		ServletContextHandler handler1 = new ServletContextHandler(null, "/", ServletContextHandler.NO_SESSIONS);
		handler1.setAllowNullPathInfo(true);
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "dirAllowed", "false");
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "etags", "true");
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "resourceBase", new File("target").getAbsolutePath());
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "maxCachedFiles", "1000");
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "pathInfoOnly", "true");

		// in Jetty, DefaultServlet implements org.eclipse.jetty.util.resource.ResourceFactory used by the
		// cache to populate in-memory storage. So org.eclipse.jetty.util.resource.ResourceFactory is actually
		// the interface to load actual org.eclipse.jetty.util.resource.Resource when needed. Returned Resource
		// should have metadata (lastModified) allowing it to be cached (and evicted) safely

		File b1 = new File("target/b1");
		FileUtils.deleteDirectory(b1);
		b1.mkdirs();
		FileWriter fw1 = new FileWriter(new File(b1, "hello.txt"));
		IOUtils.write("b1", fw1);
		fw1.close();

		File b2 = new File("target/b2");
		FileUtils.deleteDirectory(b2);
		b2.mkdirs();
		FileWriter fw2 = new FileWriter(new File(b2, "hello.txt"));
		IOUtils.write("b2", fw2);
		fw2.close();

		final PathResource p1 = new PathResource(b1);
		final PathResource p2 = new PathResource(b2);

		handler1.addServlet(new ServletHolder("default1", new JettyResourceServlet(p1, null)), "/d1/*");
		handler1.addServlet(new ServletHolder("default2", new JettyResourceServlet(p2, null)), "/d2/*");

		chc.addHandler(handler1);
		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		String response = send(port, "/hello.txt");
		assertTrue(response.contains("HTTP/1.1 404"));

		response = send(port, "/d1/hello.txt");
		Map<String, String> headers = extractHeaders(response);
		assertTrue(response.contains("ETag: W/"));
		assertTrue(response.endsWith("b1"));

		response = send(port, "/d1/hello.txt",
				"If-None-Match: " + headers.get("ETag"),
				"If-Modified-Since: " + headers.get("Date"));
		assertTrue(response.contains("HTTP/1.1 304"));
		assertFalse(response.endsWith("b1"));

		response = send(port, "/d2/hello.txt");
		headers = extractHeaders(response);
		assertTrue(response.contains("ETag: W/"));
		assertTrue(response.endsWith("b2"));

		response = send(port, "/d2/hello.txt",
				"If-None-Match: " + headers.get("ETag"),
				"If-Modified-Since: " + headers.get("Date"));
		assertTrue(response.contains("HTTP/1.1 304"));
		assertFalse(response.endsWith("b2"));

		response = send(port, "/d1/../hello.txt");
		assertTrue(response.contains("HTTP/1.1 404"));
		response = send(port, "/d2/../../../../../../hello.txt");
		assertTrue(response.contains("HTTP/1.1 400"));

		response = send(port, "/d3/hello.txt");
		assertTrue(response.contains("HTTP/1.1 404"));
		response = send(port, "/d3/");
		assertTrue(response.contains("HTTP/1.1 404"));
		response = send(port, "/d3");
		assertTrue(response.contains("HTTP/1.1 404"));
		response = send(port, "/d2/");
		// directory access, 404 with bundle access, 403 when the resource is a file: directory
		assertTrue(response.contains("HTTP/1.1 403"));
		response = send(port, "/d2");
		assertTrue(response.contains("HTTP/1.1 302"));

		server.stop();
		server.join();
	}

	@Test
	public void resourceServletWithWelcomePages() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();
		ServletContextHandler handler1 = new ServletContextHandler(null, "/", ServletContextHandler.NO_SESSIONS);
		handler1.setAllowNullPathInfo(true);
		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "pathInfoOnly", "true");

		handler1.setWelcomeFiles(new String[] { "index.txt" });

		File b1 = new File("target/b1");
		FileUtils.deleteDirectory(b1);
		b1.mkdirs();
		new File(b1, "sub").mkdirs();
		try (FileWriter fw1 = new FileWriter(new File(b1, "hello.txt"))) {
			IOUtils.write("'hello.txt'", fw1);
		}
		try (FileWriter fw1 = new FileWriter(new File(b1, "sub/hello.txt"))) {
			IOUtils.write("'sub/hello.txt'", fw1);
		}
		try (FileWriter fw1 = new FileWriter(new File(b1, "index.txt"))) {
			IOUtils.write("'index.txt'", fw1);
		}
		try (FileWriter fw1 = new FileWriter(new File(b1, "sub/index.txt"))) {
			IOUtils.write("'sub/index.txt'", fw1);
		}

		final PathResource p1 = new PathResource(b1);

		handler1.addServlet(new ServletHolder("default1", new JettyResourceServlet(p1, null)), "/d1/*");

		chc.addHandler(handler1);
		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		String response = send(port, "/hello.txt");
		assertTrue(response.contains("HTTP/1.1 404"));

		response = send(port, "/d1/hello.txt");
		assertThat(response, endsWith("'hello.txt'"));
		response = send(port, "/d1/sub/hello.txt");
		assertThat(response, endsWith("'sub/hello.txt'"));

		response = send(port, "/d1/../hello.txt");
		assertTrue(response.contains("HTTP/1.1 404"));

		response = send(port, "/d1/");
		assertThat(response, endsWith("'index.txt'"));
		response = send(port, "/d1/sub/");
		assertThat(response, endsWith("'sub/index.txt'"));
		response = send(port, "/d1");
		assertThat(response, startsWith("HTTP/1.1 302 Found"));
		response = send(port, "/d1/sub");
		assertThat(response, startsWith("HTTP/1.1 302 Found"));

		server.stop();
		server.join();
	}

	@Test
	public void standardWelcomePages() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();
		ServletContextHandler handler1 = new ServletContextHandler(null, "/", ServletContextHandler.NO_SESSIONS);
		handler1.setAllowNullPathInfo(true);
		// a bug when "false"? When mapped to "/" it doesn't matter, but fails
		// when mapped to "/x" resources would have to be under /x....
		//		handler1.setInitParameter(DefaultServlet.CONTEXT_INIT + "pathInfoOnly", "true");

		handler1.setWelcomeFiles(new String[] { "index.x", "indexx" });

		File b1 = new File("target/b1");
		FileUtils.deleteDirectory(b1);
		b1.mkdirs();
		new File(b1, "sub").mkdirs();
		try (FileWriter fw1 = new FileWriter(new File(b1, "sub/index.x"))) {
			IOUtils.write("'sub/index'", fw1);
		}

		ServletHolder defaultServlet = new ServletHolder("default", new DefaultServlet());
		defaultServlet.setInitParameter("welcomeServlets", "true");
		// a bug with "/"? We can't set it to "true", but this may mean we can't map the default servlet to something
		// different than "/"
		defaultServlet.setInitParameter("pathInfoOnly", "false");
		defaultServlet.setInitParameter("resourceBase", b1.getAbsolutePath());
		handler1.addServlet(defaultServlet, "/");

		ServletHolder indexxServlet = new ServletHolder("indexx", new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.getWriter().print("'indexx'");
			}
		});
		handler1.addServlet(indexxServlet, "/indexx/*");

		chc.addHandler(handler1);
		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		String response = send(port, "/");
		assertThat(response, endsWith("'indexx'"));
		response = send(port, "/sub/");
		assertThat(response, endsWith("'sub/index'"));
		response = send(port, "/sub");
		assertThat(response, startsWith("HTTP/1.1 302"));

		server.stop();
		server.join();
	}

	@Test
	public void paxWebWelcomePages() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();
		ServletContextHandler handler = new ServletContextHandler(null, "/", ServletContextHandler.NO_SESSIONS);
		handler.setAllowNullPathInfo(true);
		// pathInfoOnly will be set at servlet level
//		handler.setInitParameter(DefaultServlet.CONTEXT_INIT + "pathInfoOnly", "true");

		// In Jetty, welcome pages are handled by default servlet anyway. We can set these at context level,
		// because that's where DefaultServlet finds the welcome files
		// In Tomcat and Undertow, welcome files are handled by mapper, so we'll need special changes to
		// these default/resource servlets and each of them will have to get the welcome pages separately and
		// we'll have to explicitly configure NO welcome pages at request mapper level
		handler.setWelcomeFiles(new String[] { "index.y", "index.x" });

		File b1 = new File("target/b1");
		FileUtils.deleteDirectory(b1);
		b1.mkdirs();
		new File(b1, "sub").mkdirs();
		try (FileWriter fw1 = new FileWriter(new File(b1, "sub/index.x"))) {
			IOUtils.write("'sub/index-b1'", fw1);
		}
		File b2 = new File("target/b2");
		FileUtils.deleteDirectory(b2);
		b2.mkdirs();
		new File(b2, "sub").mkdirs();
		try (FileWriter fw2 = new FileWriter(new File(b2, "sub/index.x"))) {
			IOUtils.write("'sub/index-b2'", fw2);
		}
		File b3 = new File("target/b3");
		FileUtils.deleteDirectory(b3);
		b3.mkdirs();
		new File(b3, "sub").mkdirs();
		try (FileWriter fw3 = new FileWriter(new File(b3, "sub/index.x"))) {
			IOUtils.write("'sub/index-b3'", fw3);
		}

		final PathResource p1 = new PathResource(b1);
		final PathResource p2 = new PathResource(b2);
		final PathResource p3 = new PathResource(b3);

		// the "/" default & resource servlet
		ServletHolder defaultServlet = new ServletHolder("default", new JettyResourceServlet(p1, null));
		defaultServlet.setInitParameter("redirectWelcome", "false");
		defaultServlet.setInitParameter("welcomeServlets", "true");
		defaultServlet.setInitParameter("pathInfoOnly", "false"); // with "true" it leads to endless redirect...
		defaultServlet.setInitParameter("resourceBase", b1.getAbsolutePath());
		handler.addServlet(defaultServlet, "/");

		// the "/r" resource servlet
		ServletHolder resourceServlet = new ServletHolder("resource", new JettyResourceServlet(p2, null));
		resourceServlet.setInitParameter("redirectWelcome", "false");
		resourceServlet.setInitParameter("welcomeServlets", "true");
		resourceServlet.setInitParameter("pathInfoOnly", "true");
		resourceServlet.setInitParameter("resourceBase", b2.getAbsolutePath());
		handler.addServlet(resourceServlet, "/r/*");

		// the "/s" resource servlet - with redirected welcome files
		ServletHolder resource2Servlet = new ServletHolder("resource2", new JettyResourceServlet(p3, null));
		resource2Servlet.setInitParameter("redirectWelcome", "true");
		resource2Servlet.setInitParameter("welcomeServlets", "true");
		resource2Servlet.setInitParameter("pathInfoOnly", "true");
		resource2Servlet.setInitParameter("resourceBase", b3.getAbsolutePath());
		handler.addServlet(resource2Servlet, "/s/*");

		// the "/indexx/*" (and *.y and *.x) servlet which should be available through welcome files
		ServletHolder indexxServlet = new ServletHolder("indexx", new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.getWriter().println("'indexx servlet'");
				resp.getWriter().println("req.request_uri=\"" + req.getRequestURI() + "\"");
				resp.getWriter().println("req.context_path=\"" + req.getContextPath() + "\"");
				resp.getWriter().println("req.servlet_path=\"" + req.getServletPath() + "\"");
				resp.getWriter().println("req.path_info=\"" + req.getPathInfo() + "\"");
				resp.getWriter().println("req.query_string=\"" + req.getQueryString() + "\"");
				resp.getWriter().println("javax.servlet.forward.mapping=\"" + req.getAttribute("javax.servlet.forward.mapping") + "\"");
				resp.getWriter().println("javax.servlet.forward.request_uri=\"" + req.getAttribute("javax.servlet.forward.request_uri") + "\"");
				resp.getWriter().println("javax.servlet.forward.context_path=\"" + req.getAttribute("javax.servlet.forward.context_path") + "\"");
				resp.getWriter().println("javax.servlet.forward.servlet_path=\"" + req.getAttribute("javax.servlet.forward.servlet_path") + "\"");
				resp.getWriter().println("javax.servlet.forward.path_info=\"" + req.getAttribute("javax.servlet.forward.path_info") + "\"");
				resp.getWriter().println("javax.servlet.forward.query_string=\"" + req.getAttribute("javax.servlet.forward.query_string") + "\"");
				resp.getWriter().println("javax.servlet.include.mapping=\"" + req.getAttribute("javax.servlet.include.mapping") + "\"");
				resp.getWriter().println("javax.servlet.include.request_uri=\"" + req.getAttribute("javax.servlet.include.request_uri") + "\"");
				resp.getWriter().println("javax.servlet.include.context_path=\"" + req.getAttribute("javax.servlet.include.context_path") + "\"");
				resp.getWriter().println("javax.servlet.include.servlet_path=\"" + req.getAttribute("javax.servlet.include.servlet_path") + "\"");
				resp.getWriter().println("javax.servlet.include.path_info=\"" + req.getAttribute("javax.servlet.include.path_info") + "\"");
				resp.getWriter().println("javax.servlet.include.query_string=\"" + req.getAttribute("javax.servlet.include.query_string") + "\"");
			}
		});
		ServletMapping indexxMapping = new ServletMapping();
		indexxMapping.setServletName("indexx");
		indexxMapping.setPathSpecs(new String[] { "*.x", "*.y" });
		handler.getServletHandler().addServlet(indexxServlet);
		handler.getServletHandler().addServletMapping(indexxMapping);

		// the "/gateway/*" servlet through which we'll forward to/include other servlets
		ServletHolder gatewayServlet = new ServletHolder("gateway", new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				String what = req.getParameter("what");
				String where = req.getParameter("where");
				switch (what) {
					case "redirect":
						resp.sendRedirect(where);
						return;
					case "forward":
						// we can't send anything when forwarding
						req.getRequestDispatcher(where).forward(req, resp);
						return;
					case "include":
						resp.getWriter().print(">>>");
						req.getRequestDispatcher(where).include(req, resp);
						resp.getWriter().print("<<<");
						return;
					default:
				}
			}
		});
		handler.addServlet(gatewayServlet, "/gateway/*");

		chc.addHandler(handler);
		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		// --- resource access through "/" servlet

		// "/" - no "/index.x" or "/index.y" physical resource, but existing mapping for *.y to indexx servlet
		// forward is performed implicitly by Jetty's DefaultServlet
		String response = send(port, "/");
		assertTrue(response.contains("req.context_path=\"\""));
		assertTrue(response.contains("req.request_uri=\"/index.y\""));
		assertTrue(response.contains("javax.servlet.forward.request_uri=\"/\""));

		// Forward vs. Include:
		// in forward method:
		//  - original servletPath, pathInfo, requestURI are available ONLY through javax.servlet.forward.* attributes
		//  - values used to obtain the dispatcher are available through request object
		// in include method:
		//  - original servletPath, pathInfo, requestURI are available through request object
		//  - values used to obtain the dispatcher are available through javax.servlet.include.* attributes

		// "/" (but through gateway) - similar forward, but performed explicitly by gateway servlet
		// 9.4 The Forward Method:
		//     The path elements of the request object exposed to the target servlet must reflect the
		//     path used to obtain the RequestDispatcher.
		// so "gateway" forwards to "/", "/" is handled by "default" which forwards to "/index.y"
		response = send(port, "/gateway/x?what=forward&where=/");
		assertTrue(response.contains("req.context_path=\"\""));
		assertTrue(response.contains("req.request_uri=\"/index.y\""));
		assertTrue(response.contains("javax.servlet.forward.context_path=\"\""));
		assertTrue(response.contains("javax.servlet.forward.request_uri=\"/gateway/x\""));
		assertTrue(response.contains("javax.servlet.forward.servlet_path=\"/gateway\""));
		assertTrue(response.contains("javax.servlet.forward.path_info=\"/x\""));

		// "/", but included by gateway servlet
		// "gateway" includes "/" which includes "/index.y"
		// TOCHECK: with include, context path is "/"...
		response = send(port, "/gateway/x?what=include&where=/");
		assertTrue(response.contains("req.context_path=\"\""));
		assertTrue(response.contains("req.request_uri=\"/gateway/x\""));
		assertTrue(response.contains("javax.servlet.include.context_path=\"/\""));
		assertTrue(response.contains("javax.servlet.include.request_uri=\"/index.y\""));
		assertTrue(response.contains("javax.servlet.include.servlet_path=\"/index.y\""));
		assertTrue(response.contains("javax.servlet.include.path_info=\"null\""));

		response = send(port, "/sub");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		response = send(port, "/gateway/x?what=forward&where=/sub");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		// included servlet (here - "default") can't set Location header (org.eclipse.jetty.server.Response.isMutable() returns false)
		response = send(port, "/gateway/x?what=include&where=/sub");
		assertTrue(response.contains(">>><<<"));

		// "/sub/" + "index.x" welcome files is forwarded and mapped to indexx servlet
		// According to 10.10 "Welcome Files":
		//    The Web server must append each welcome file in the order specified in the deployment descriptor to the
		//    partial request and check whether a static resource in the WAR is mapped to that
		//    request URI. If no match is found, the Web server MUST again append each
		//    welcome file in the order specified in the deployment descriptor to the partial
		//    request and check if a servlet is mapped to that request URI.
		//    [...]
		//    The container may send the request to the welcome resource with a forward, a redirect, or a
		//    container specific mechanism that is indistinguishable from a direct request.
		// Jetty detects /sub/index.y (first welcome file) can be mapped to indexx servlet, but continues the
		// search for physical resource. /sub/index.x is actual physical resource, so forward is chosen, which
		// is eventually mapped to indexx again - with index.x, not index.y
		response = send(port, "/sub/");
		assertTrue(response.contains("req.context_path=\"\""));
		assertTrue(response.contains("req.request_uri=\"/sub/index.x\""));
		assertTrue(response.contains("javax.servlet.forward.context_path=\"\""));
		assertTrue(response.contains("javax.servlet.forward.request_uri=\"/sub/\""));
		assertTrue(response.contains("javax.servlet.forward.servlet_path=\"/sub/\"")); // TOCHECK: why not "/"?
		assertTrue(response.contains("javax.servlet.forward.path_info=\"null\""));

		response = send(port, "/gateway/x?what=forward&where=/sub/");
		assertTrue(response.contains("req.context_path=\"\""));
		assertTrue(response.contains("req.request_uri=\"/sub/index.x\""));
		assertTrue(response.contains("javax.servlet.forward.context_path=\"\""));
		assertTrue(response.contains("javax.servlet.forward.request_uri=\"/gateway/x\""));
		assertTrue(response.contains("javax.servlet.forward.servlet_path=\"/gateway\""));
		assertTrue(response.contains("javax.servlet.forward.path_info=\"/x\""));

		response = send(port, "/gateway/x?what=include&where=/sub/");
		assertTrue(response.contains("req.context_path=\"\""));
		assertTrue(response.contains("req.request_uri=\"/gateway/x\""));
		assertTrue(response.contains("javax.servlet.include.context_path=\"/\""));
		assertTrue(response.contains("javax.servlet.include.request_uri=\"/sub/index.x\""));
		assertTrue(response.contains("javax.servlet.include.servlet_path=\"/sub/index.x\""));
		assertTrue(response.contains("javax.servlet.include.path_info=\"null\""));

		// --- resource access through "/r" servlet

		response = send(port, "/r");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		response = send(port, "/gateway/x?what=forward&where=/r");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		// included servlet/resource can't be redirected
		response = send(port, "/gateway/x?what=include&where=/r");
		assertTrue(response.endsWith(">>><<<"));

		// "/r" - no "/index.x" or "/index.y" physical resource, but existing mapping for *.y to indexx servlet
		// forward is performed implicitly by Jetty's DefaultServlet (even if mapped to /r/*), forward URI is
		// "/r/index.y" (first welcome), but this time, "/r/*" is a mapping with higher priority than "*.y"
		// (with "/" servlet, "*.y" had higher priority than "/"), so "resource" servlet is called, this time
		// with full URI (no welcome files are checked). Such resource is not found, so we have 404
		response = send(port, "/r/");
		assertTrue(response.startsWith("HTTP/1.1 404"));

		response = send(port, "/gateway/x?what=forward&where=/r/");
		assertTrue(response.startsWith("HTTP/1.1 404"));
		// https://github.com/eclipse/jetty.project/issues/5025
//		response = send(port, "/gateway/x?what=include&where=/r/");
//		assertTrue(response.startsWith("HTTP/1.1 404"));

		response = send(port, "/r/sub");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		response = send(port, "/gateway/x?what=forward&where=/r/sub");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		response = send(port, "/gateway/x?what=include&where=/r/sub");
		assertTrue(response.contains(">>><<<"));

		// this time, welcome file is /sub/index.x and even if it maps to existing servlet (*.x), physical
		// resource exists and is returned
		response = send(port, "/r/sub/");
		assertTrue(response.endsWith("'sub/index-b2'"));
		response = send(port, "/gateway/x?what=forward&where=/r/sub/");
		assertTrue(response.endsWith("'sub/index-b2'"));
		// https://github.com/eclipse/jetty.project/issues/5025
//		response = send(port, "/gateway/x?what=include&where=/r/sub/");
//		assertTrue(response.endsWith(">>>'sub/index-b2'<<<"));

		// --- resource access through "/s" servlet - welcome files with redirect

		response = send(port, "/s");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		response = send(port, "/gateway/x?what=forward&where=/s");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		// included servlet/resource can't be redirected
		response = send(port, "/gateway/x?what=include&where=/s");
		assertTrue(response.endsWith(">>><<<"));

		response = send(port, "/s/");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		// redirect to first welcome page with found *.y mapping, but another mapping will be found using /s/*
		assertTrue(extractHeaders(response).get("Location").endsWith("/s/index.y"));

		response = send(port, "/gateway/x?what=forward&where=/s/");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		assertTrue(extractHeaders(response).get("Location").endsWith("/s/index.y?what=forward&where=/s/"));
		response = send(port, "/gateway/x?what=include&where=/s/");
		assertTrue(response.contains(">>><<<"));

		response = send(port, "/s/sub");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		assertTrue(extractHeaders(response).get("Location").endsWith("/s/sub/"));
		response = send(port, "/gateway/x?what=forward&where=/s/sub");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		assertTrue(extractHeaders(response).get("Location").endsWith("/s/sub/?what=forward&where=/s/sub"));
		response = send(port, "/gateway/x?what=include&where=/s/sub");
		assertTrue(response.contains(">>><<<"));

		// this time, welcome file is /sub/index.x and even if it maps to existing servlet (*.x), physical
		// resource exists and is returned
		response = send(port, "/s/sub/");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		assertTrue(extractHeaders(response).get("Location").endsWith("/s/sub/index.x"));
		response = send(port, "/gateway/x?what=forward&where=/s/sub/");
		assertTrue(response.startsWith("HTTP/1.1 302"));
		assertTrue(extractHeaders(response).get("Location").endsWith("/s/sub/index.x?what=forward&where=/s/sub/"));
		response = send(port, "/gateway/x?what=include&where=/s/sub/");
		assertTrue(response.contains(">>><<<"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithServletContextHandlerAndOnlyFilter() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();

		ServletContextHandler handler1 = new ServletContextHandler(null, "/c1", ServletContextHandler.NO_SESSIONS);

		// without "default 404 servlet", jetty won't invoke a "pipeline" that has only a filter.
		handler1.getServletHandler().setEnsureDefaultServlet(true);

		handler1.setAllowNullPathInfo(true);
		handler1.addFilter(new FilterHolder(new Filter() {
			@Override
			public void init(FilterConfig filterConfig) throws ServletException {
			}

			@Override
			public void doFilter(ServletRequest request, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK\n");
				resp.getWriter().close();
			}
		}), "/*", EnumSet.of(DispatcherType.REQUEST));

		chc.addHandler(handler1);

		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		Socket s1 = new Socket();
		s1.connect(new InetSocketAddress("127.0.0.1", port));

		s1.getOutputStream().write((
				"GET /c1/anything HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s1.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s1.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.join();
	}

	@Test
	public void jettyUrlMapping() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();

		Servlet servlet = new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");

				String response = String.format("| %s | %s | %s |", req.getContextPath(), req.getServletPath(), req.getPathInfo());
				resp.getWriter().write(response);
				resp.getWriter().close();
			}
		};

		ServletContextHandler rootHandler = new ServletContextHandler(chc, "", ServletContextHandler.NO_SESSIONS);
		rootHandler.setAllowNullPathInfo(true);
		rootHandler.getServletHandler().addServlet(new ServletHolder("default-servlet", servlet));
		map(rootHandler, "default-servlet", new String[] {
				"/p/*",
				"*.action",
				"",
//				"/",
				"/x"
		});

		ServletContextHandler otherHandler = new ServletContextHandler(chc, "/c1", ServletContextHandler.NO_SESSIONS);
		otherHandler.setAllowNullPathInfo(true);
		otherHandler.getServletHandler().addServlet(new ServletHolder("default-servlet", servlet));
		map(otherHandler, "default-servlet", new String[] {
				"/p/*",
				"*.action",
				"",
//				"/",
				"/x"
		});

		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		// Jetty mapping is done in 3 stages:
		// - host finding:
		//    - in Jetty, vhost is checked at the level of each handler from the collection
		//    - org.eclipse.jetty.server.handler.ContextHandler.checkVirtualHost() returns a match based on
		//      javax.servlet.ServletRequest.getServerName() ("Host" HTTP header)
		// - context finding:
		//    - on each handler from the collection, org.eclipse.jetty.server.handler.ContextHandler.checkContextPath()
		//      is called
		//    - if there are many ContextHandlers in the collection, it's the collection that first matches
		//      request URI to a context. Virtual Host seems to be checked later...
		// - servlet finding:
		//    - org.eclipse.jetty.servlet.ServletHandler.getMappedServlet() where ServletHandler is a field of
		//      ServletContextHandler

		String response;

		// ROOT context
		response = send(connector.getLocalPort(), "/p/anything");
		assertTrue(response.endsWith("|  | /p | /anything |"));
		response = send(connector.getLocalPort(), "/anything.action");
		assertTrue(response.endsWith("|  | /anything.action | null |"));
		// just can't send `GET  HTTP/1.1` request
//		response = send(connector.getLocalPort(), "");
		response = send(connector.getLocalPort(), "/");
		// Jetty fixed https://github.com/eclipse-ee4j/servlet-api/issues/300
		// with https://github.com/eclipse/jetty.project/issues/4542
		assertTrue("Special, strange Servlet API 4 mapping rule", response.endsWith("|  |  | / |"));
		response = send(connector.getLocalPort(), "/x");
		assertTrue(response.endsWith("|  | /x | null |"));
		response = send(connector.getLocalPort(), "/y");
		assertTrue(response.contains("HTTP/1.1 404"));

		// /c1 context
		response = send(connector.getLocalPort(), "/c1/p/anything");
		assertTrue(response.endsWith("| /c1 | /p | /anything |"));
		response = send(connector.getLocalPort(), "/c1/anything.action");
		assertTrue(response.endsWith("| /c1 | /anything.action | null |"));
		response = send(connector.getLocalPort(), "/c1");
		// if org.eclipse.jetty.server.handler.ContextHandler.setAllowNullPathInfo(false):
//		assertTrue(response.contains("HTTP/1.1 302"));
		// still, treating as special "" mapping rule, it should be |  |  | / |
		// but IMO specification is wrong - context path should not be "", but should be ... context path
		assertTrue(response.endsWith("| /c1 |  | / |"));
		response = send(connector.getLocalPort(), "/c1/");
		// Jetty and Tomcat return (still incorrectly according to Servlet 4 spec) | /c1 |  | / | - but at least
		// consistently wrt findings from https://github.com/eclipse-ee4j/servlet-api/issues/300
		assertTrue("Special, strange Servlet API 4 mapping rule", response.endsWith("| /c1 |  | / |"));
		response = send(connector.getLocalPort(), "/c1/x");
		assertTrue(response.endsWith("| /c1 | /x | null |"));
		response = send(connector.getLocalPort(), "/c1/y");
		assertTrue(response.contains("HTTP/1.1 404"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithServletContextHandlerAddedAfterServerHasStarted() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();

		ServletContextHandler handler1 = new ServletContextHandler(chc, "/c1", ServletContextHandler.NO_SESSIONS);
		handler1.addServlet(new ServletHolder("s1", new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK1\n");
				resp.getWriter().close();
			}
		}), "/s1");

		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		String response;

		response = send(connector.getLocalPort(), "/c1/s1");
		assertTrue(response.endsWith("\r\n\r\nOK1\n"));

		response = send(connector.getLocalPort(), "/c1/s2");
		assertTrue(response.contains("HTTP/1.1 404"));

		response = send(connector.getLocalPort(), "/c2/s1");
		assertTrue(response.contains("HTTP/1.1 404"));

		// add new context

		ServletContextHandler handler2 = new ServletContextHandler(chc, "/c2", ServletContextHandler.NO_SESSIONS);
		handler2.setAllowNullPathInfo(true);
		handler2.addServlet(new ServletHolder("s1", new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK2\n");
				resp.getWriter().close();
			}
		}), "/s1");
		handler2.start();

		// add new servlet to existing context

		handler1.addServlet(new ServletHolder("s2", new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
				resp.setContentType("text/plain");
				resp.setCharacterEncoding("UTF-8");
				resp.getWriter().write("OK3\n");
				resp.getWriter().close();
			}
		}), "/s2");

		response = send(connector.getLocalPort(), "/c1/s1");
		assertTrue(response.endsWith("\r\n\r\nOK1\n"));

		response = send(connector.getLocalPort(), "/c1/s2");
		assertTrue(response.endsWith("\r\n\r\nOK3\n"));

		response = send(connector.getLocalPort(), "/c2/s1");
		assertTrue(response.endsWith("\r\n\r\nOK2\n"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithWebAppContext() throws Exception {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server, 1, 1, new HttpConnectionFactory());
		connector.setPort(0);
		server.setConnectors(new Connector[] { connector });

		ContextHandlerCollection chc = new ContextHandlerCollection();

		// and finally an extension of ServletContextHandler - WebAppContext, which is again a ServletContextHandler
		// but objects (filters, servlets, ...) are added by org.eclipse.jetty.webapp.Configuration and
		// org.eclipse.jetty.webapp.DescriptorProcessor processors
		WebAppContext wac1 = new WebAppContext();
		wac1.setContextPath("/app1");
		// by default, null path info is not allowed and redirect (with added "/") is sent when requesting just
		// the context URL
		wac1.setAllowNullPathInfo(false);
		// when we don't pass handler collection (or handler wrapper) in constructor, we have to add this
		// specialized context handler manually
		chc.addHandler(wac1);

		// org.eclipse.jetty.webapp.StandardDescriptorProcessor.end() just adds 4 component lists to WebAppContext's
		// org.eclipse.jetty.servlet.ServletContextHandler._servletHandler:
		// - servlets
		// - filters
		// - servlet mappings
		// - filter mappings
		//
		// when WebAppContext.doStart() calls org.eclipse.jetty.webapp.WebAppContext.preConfigure(), all
		// org.eclipse.jetty.webapp.WebAppContext._configurationClasses are turned into actual configurators
		// default configuration classes are org.eclipse.jetty.webapp.WebAppContext.DEFAULT_CONFIGURATION_CLASSES
		wac1.setConfigurationClasses(new String[] {
				"org.eclipse.jetty.webapp.WebXmlConfiguration"
		});

		// to impact WebXmlConfiguration, we need few settings
		wac1.setDefaultsDescriptor(null); // to override "org/eclipse/jetty/webapp/webdefault.xml"

		// prepare pure web.xml without any web app structure
		File webXml = new File("target/web-" + UUID.randomUUID().toString() + ".xml");
		webXml.delete();

		try (FileWriter writer = new FileWriter(webXml)) {
			writer.write("<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
					"    xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd\"\n" +
					"    version=\"4.0\">\n" +
					"\n" +
					"    <servlet>\n" +
					"        <servlet-name>test-servlet</servlet-name>\n" +
					"        <servlet-class>org.ops4j.pax.web.service.jetty.internal.EmbeddedJettyTest$TestServlet</servlet-class>\n" +
					"    </servlet>\n" +
					"\n" +
					"    <servlet-mapping>\n" +
					"        <servlet-name>test-servlet</servlet-name>\n" +
					"        <url-pattern>/ts</url-pattern>\n" +
					"    </servlet-mapping>\n" +
					"\n" +
					"</web-app>\n");
		}

		// all the metadata from different (webdefaults.xml, web.xml, ...) descriptors are kept in
		// org.eclipse.jetty.webapp.MetaData object inside org.eclipse.jetty.webapp.WebAppContext._metadata
		wac1.setDescriptor(webXml.toURI().toURL().toString());

		// when WebAppContext is started, registered descriptor processors process the descriptors
		// org.eclipse.jetty.webapp.StandardDescriptorProcessor.start():
		//  - populates org.eclipse.jetty.webapp.StandardDescriptorProcessor._filterHolderMap with existing filters
		//    from ServletContextHandler._servletHandler
		//  - populates org.eclipse.jetty.webapp.StandardDescriptorProcessor._filterHolders with existing filters
		//    from ServletContextHandler._servletHandler
		//  - populates org.eclipse.jetty.webapp.StandardDescriptorProcessor._filterMappings with existing filters
		//    from ServletContextHandler._servletHandler
		//  - populates org.eclipse.jetty.webapp.StandardDescriptorProcessor._servletHolderMap with existing servlets
		//    from ServletContextHandler._servletHandler
		//  - populates org.eclipse.jetty.webapp.StandardDescriptorProcessor._servletHolders with existing servlets
		//    from ServletContextHandler._servletHandler
		//  - populates org.eclipse.jetty.webapp.StandardDescriptorProcessor._servletMappings with existing servlets
		//    from ServletContextHandler._servletHandler
		//
		// org.eclipse.jetty.webapp.StandardDescriptorProcessor.visit() calls one of 19 visitors:
		// _visitors = {java.util.HashMap@2792}  size = 19
		//  - "servlet-mapping" -> "StandardDescriptorProcessor.visitServletMapping()"
		//  - "mime-mapping" -> "StandardDescriptorProcessor.visitMimeMapping()"
		//  - "distributable" -> "StandardDescriptorProcessor.visitDistributable()"
		//  - "locale-encoding-mapping-list" -> "StandardDescriptorProcessor.visitLocaleEncodingList()"
		//  - "servlet" -> "StandardDescriptorProcessor.visitServlet()"
		//  - "security-role" -> "StandardDescriptorProcessor.visitSecurityRole()"
		//  - "listener" -> "StandardDescriptorProcessor.visitListener()"
		//  - "jsp-config" -> "StandardDescriptorProcessor.visitJspConfig()"
		//  - "context-param" -> "StandardDescriptorProcessor.visitContextParam()"
		//  - "filter" -> "StandardDescriptorProcessor.visitFilter()"
		//  - "welcome-file-list" -> "StandardDescriptorProcessor.visitWelcomeFileList()"
		//  - "taglib" -> "StandardDescriptorProcessor.visitTagLib()"
		//  - "deny-uncovered-http-methods" -> "StandardDescriptorProcessor.visitDenyUncoveredHttpMethods()"
		//  - "login-config" -> "StandardDescriptorProcessor.visitLoginConfig() throws java.lang.Exception"
		//  - "display-name" -> "StandardDescriptorProcessor.visitDisplayName()"
		//  - "error-page" -> "StandardDescriptorProcessor.visitErrorPage()"
		//  - "session-config" -> "StandardDescriptorProcessor.visitSessionConfig()"
		//  - "security-constraint" -> "StandardDescriptorProcessor.visitSecurityConstraint()"
		//  - "filter-mapping" -> "StandardDescriptorProcessor.visitFilterMapping()"
		// org.eclipse.jetty.webapp.StandardDescriptorProcessor.end() calls
		// (on org.eclipse.jetty.servlet.ServletContextHandler._servletHandler):
		//  - org.eclipse.jetty.servlet.ServletHandler.setFilters()
		//  - org.eclipse.jetty.servlet.ServletHandler.setServlets()
		//  - org.eclipse.jetty.servlet.ServletHandler.setFilterMappings()
		//  - org.eclipse.jetty.servlet.ServletHandler.setServletMappings()
		//
		// visitServlet()        creates new org.eclipse.jetty.servlet.ServletHolder
		// visitServletMapping() creates new org.eclipse.jetty.servlet.ServletMapping

		server.setHandler(chc);
		server.start();

		int port = connector.getLocalPort();

		Socket s1 = new Socket();
		s1.connect(new InetSocketAddress("127.0.0.1", port));

		s1.getOutputStream().write((
				"GET /app1/ts HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector.getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s1.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s1.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.join();
	}

	@Test
	public void embeddedServerWithExternalConfiguration() throws Exception {
		// order is important
		String[] xmls = new String[] {
				"etc/jetty-threadpool.xml",
				"etc/jetty.xml",
				"etc/jetty-connectors.xml",
				"etc/jetty-handlercollection.xml",
				"etc/jetty-webapp.xml",
		};

		// prepare pure web.xml without any web app structure
		File webXml = new File("target/web.xml");
		webXml.delete();

		try (FileWriter writer = new FileWriter(webXml)) {
			writer.write("<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
					"    xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_4_0.xsd\"\n" +
					"    version=\"4.0\">\n" +
					"\n" +
					"    <servlet>\n" +
					"        <servlet-name>test-servlet</servlet-name>\n" +
					"        <servlet-class>org.ops4j.pax.web.service.jetty.internal.EmbeddedJettyTest$TestServlet</servlet-class>\n" +
					"    </servlet>\n" +
					"\n" +
					"    <servlet-mapping>\n" +
					"        <servlet-name>test-servlet</servlet-name>\n" +
					"        <url-pattern>/ts</url-pattern>\n" +
					"    </servlet-mapping>\n" +
					"\n" +
					"</web-app>\n");
		}

		// loop taken from org.eclipse.jetty.xml.XmlConfiguration.main()

		XmlConfiguration last = null;
		Map<String, Object> objects = new LinkedHashMap<>();

		for (String xml : xmls) {
			File f = new File("target/test-classes/" + xml);
			XmlConfiguration configuration = new XmlConfiguration(Resource.newResource(f));

			if (last != null) {
				configuration.getIdMap().putAll(last.getIdMap());
			}
			configuration.getProperties().put("thread.name.prefix", "jetty-qtp");

			configuration.configure();

			objects.putAll(configuration.getIdMap());

			last = configuration;
		}

		final ServerConnector[] connector = { null };
		objects.forEach((k, v) -> {
			LOG.info("Created {} -> {}", k, v);
			if (connector[0] == null && v instanceof ServerConnector) {
				connector[0] = (ServerConnector) v;
			}
		});

		Server server = (Server) objects.get("Server");
		assertThat(((QueuedThreadPool) server.getThreadPool()).getName(), equalTo("jetty-qtp"));

		server.start();

		int port = connector[0].getLocalPort();

		Socket s1 = new Socket();
		s1.connect(new InetSocketAddress("127.0.0.1", port));

		s1.getOutputStream().write((
				"GET /app1/ts HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + connector[0].getLocalPort() + "\r\n" +
				"Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s1.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s1.close();

		assertTrue(sw.toString().endsWith("\r\n\r\nOK\n"));

		server.stop();
		server.join();
	}

	@Test
	public void parseEmptyResource() throws Exception {
		XmlConfiguration configuration = new XmlConfiguration(Resource.newResource(getClass().getResource("/jetty-empty.xml")));
		assertThat(configuration.configure(), equalTo("OK"));
	}

	public static class TestServlet extends HttpServlet {

		@Override
		protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
			resp.setContentType("text/plain");
			resp.setCharacterEncoding("UTF-8");
			resp.getWriter().write("OK\n");
			resp.getWriter().close();
		}
	}

	private void map(ServletContextHandler h, String name, String[] uris) {
		ServletMapping mapping = new ServletMapping();
		mapping.setServletName(name);
		mapping.setPathSpecs(uris);
		h.getServletHandler().addServletMapping(mapping);
	}

	private String send(int port, String request, String ... headers) throws IOException {
		Socket s = new Socket();
		s.connect(new InetSocketAddress("127.0.0.1", port));

		s.getOutputStream().write((
				"GET " + request + " HTTP/1.1\r\n" +
				"Host: 127.0.0.1:" + port + "\r\n").getBytes());
		for (String header : headers) {
			s.getOutputStream().write((header + "\r\n").getBytes());
		}
		s.getOutputStream().write(("Connection: close\r\n\r\n").getBytes());

		byte[] buf = new byte[64];
		int read = -1;
		StringWriter sw = new StringWriter();
		while ((read = s.getInputStream().read(buf)) > 0) {
			sw.append(new String(buf, 0, read));
		}
		s.close();

		return sw.toString();
	}

	private Map<String, String> extractHeaders(String response) throws IOException {
		Map<String, String> headers = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(new StringReader(response))) {
			String line = null;
			while ((line = reader.readLine()) != null) {
				if (line.trim().equals("")) {
					break;
				}
				// I know, security when parsing headers is very important...
				String[] kv = line.split(": ");
				String header = kv[0];
				String value = String.join("", Arrays.asList(kv).subList(1, kv.length));
				headers.put(header, value);
			}
		}
		return headers;
	}

}
