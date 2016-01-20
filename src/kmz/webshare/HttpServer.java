package kmz.webshare;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.tastefuljava.sceyefi.multipart.Multipart;
import org.tastefuljava.sceyefi.multipart.Part;
import org.tastefuljava.sceyefi.multipart.ValueParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

public abstract class HttpServer implements HttpHandler {

	protected class Request {
		public final String path;
		public final String method;
		private final HttpExchange context;
		private final Map<String, Object> extras;

		public Request(HttpExchange context) {
			this.context = context;
			this.method = context.getRequestMethod();
			this.path = context.getRequestURI().getPath();
			this.extras = new HashMap<String, Object>();
		}

		public File getLocalPath() {
			return HttpServer.this.getLocalPath(this.path);
		}

		public Object getExtra(String key) {
			return this.extras.get(key);
		}

		public void putExtra(String key, Object value) {
			this.extras.put(key, value);
		}

		public Headers getHeaders() {
			return this.context.getRequestHeaders();
		}

		public String getFirstHeader(String key) {
			return this.context.getRequestHeaders().getFirst(key);
		}

		public String getQuery() {
			return this.context.getRequestURI().getQuery();
		}

		public InputStream getBody() {
			return this.context.getRequestBody();
		}

		public InetSocketAddress getRemoteAddress() {
			return this.context.getRemoteAddress();
		}
	}

	protected class Response {
		private final HttpExchange context;
		private final Request request;

		private String attachment = null;
		private String contentType = null;
		private int responseCode = HttpURLConnection.HTTP_OK;
		//public int reasonPhrase;

		public Response(HttpExchange context, Request request) {
			this.context = context;
			this.request = request;
		}

		public File getLocalPath() {
			return this.request.getLocalPath();
		}

		public Object getExtra(String key) {
			return this.request.extras.get(key);
		}

		public void putExtra(String key, Object value) {
			this.request.extras.put(key, value);
		}

		public void setResponseCode(int responseCode) {
			this.responseCode = responseCode;
		}

		public void setContentType(String contentType) {
			this.contentType = contentType;
		}

		public void setAttachment(String attachment) {
			this.attachment = attachment;
		}

		public Headers getHeaders() {
			return this.context.getResponseHeaders();
		}

		public List<String> getHeader(String key) {
			return this.context.getResponseHeaders().get(key);
		}

		public String getFirstHeader(String key) {
			return this.context.getResponseHeaders().getFirst(key);
		}

		public void putHeader(String key, String... values) {
			this.context.getResponseHeaders().put(key, Arrays.asList(values));
		}

		public void write(String string) throws IOException {
			byte[] response = string.getBytes();
			this.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_HTML_CHARSET);
			this.context.sendResponseHeaders(this.responseCode, response.length);
			this.context.getResponseBody().write(response);
		}

		public void write(File file) throws IOException {
			InputStream in = null;
			if (this.contentType == null) {
				this.contentType = HttpServer.this.getContentType(file);
			}
			try {
				if (this.attachment != null) {
					request.context.getResponseHeaders().add(CONTENT_DISPOSITION, "attachment; filename=" + this.attachment);
				}
				request.context.getResponseHeaders().add(CONTENT_TYPE, this.contentType);
				request.context.sendResponseHeaders(this.responseCode, file.length());

				OutputStream out = request.context.getResponseBody();
				in = new FileInputStream(file);
				Utils.copyStream(out, in);
			}
			finally {
				Utils.close(in);
			}
		}

		public void writeZip(File... files) throws IOException {
			if (this.attachment != null) {
				request.context.getResponseHeaders().add(CONTENT_DISPOSITION, "attachment; filename=" + this.attachment);
			}
			request.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_ARCHIVE_ZIP);
			request.context.sendResponseHeaders(this.responseCode, 0);

			ZipOutputStream out = null;
			try {
				out = new ZipOutputStream(request.context.getResponseBody());
				for (File toZip : files) {
					Utils.addToArchive(out, "", toZip);
				}
			}
			finally {
				Utils.close(out);
			}
		}

		public void write(HtmlTemplate template) throws IOException {
			request.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_HTML_CHARSET);
			request.context.sendResponseHeaders(this.responseCode, 0);
			Writer out = new OutputStreamWriter(request.context.getResponseBody());
			template.write(out);
			out.flush();
		}
	}

	protected static class Error extends Exception {
		private final int responseCode;

		public Error(int responseCode, String message, Throwable cause) {
			super(message, cause);
			this.responseCode = responseCode;
		}

		public Error(Exception cause) {
			this(HttpURLConnection.HTTP_INTERNAL_ERROR, cause.getMessage(), cause);
		}

		public Error(String message) {
			this(HttpURLConnection.HTTP_INTERNAL_ERROR, message, null);
		}
	}

	protected abstract String getContentType(File file);

	protected abstract File getLocalPath(String path);

	// Enforce to be authenticated.
	abstract boolean isAuthenticated(Request request);

	// starting a new request, return the file to be the returned, or null to process the request.
	abstract File beginRequest(Request request) throws Error;

	// process the request get and post params.
	abstract void processParam(Request request, String name, InputStream value, Map<String, String> params) throws Error;

	// handle write response to client.
	abstract void writeResponse(Response response, Exception error) throws IOException;

	public static final String REFERER = "Referer";

	//public static final String METHOD_GET = "GET";
	public static final String METHOD_POST = "POST";
	public static final String METHOD_CACHED = "FILE";
	public static final String DEFAULT_ENCODING = "UTF-8";

	protected static final String CONTENT_TYPE = "Content-type";
	protected static final String CONTENT_LENGTH = "Content-length";
	protected static final String CONTENT_DISPOSITION = "content-disposition";

	protected static final String CONTENT_TYPE_ARCHIVE_ZIP = "application/zip";
	protected static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
	protected static final String CONTENT_TYPE_URL_ENCODED_FORM = "application/x-www-form-urlencoded";

	protected static final String CONTENT_TYPE_TEXT_HTML_CHARSET = "text/html; charset=" + DEFAULT_ENCODING;
	protected static final String CONTENT_TYPE_TEXT_PLAIN_CHARSET = "text/plain; charset=" + DEFAULT_ENCODING;

	private static final InputStream emptyInputStream = new InputStream() {
		@Override
		public int read() throws IOException {
			return -1;
		}
	};

	private void processParam(Request request, String encodedParams) throws Exception {
		if (!Utils.isNullOrEmpty(encodedParams)) {
			for (String str : encodedParams.split("&")) {
				int eq = str.indexOf('=');
				if (eq >= 0) {
					String key = URLDecoder.decode(str.substring(0, eq), DEFAULT_ENCODING);
					String value = URLDecoder.decode(str.substring(eq + 1), DEFAULT_ENCODING);
					InputStream body = new ByteArrayInputStream(value.getBytes());
					HttpServer.this.processParam(request, key, body, null);
				}
				else {
					HttpServer.this.processParam(request, str, emptyInputStream, null);
				}
			}
		}
	}

	@Override
	public void handle(final HttpExchange context) {
		long requestStart = System.currentTimeMillis();
		long responseStart = requestStart;
		final Request request = new Request(context);
		final Response response = new Response(context, request);
		try {
			Exception error = null;

			if (!this.isAuthenticated(request)) {
				response.putHeader("WWW-Authenticate", "Basic realm=\"Home Server\"");
				response.setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED);
				response.write("401 Access denied");
				return;
			}

			// prepare request.
			File file = this.beginRequest(request);

			// if the local file exists return it.
			if (file != null && file.isFile()) {
				response.write(file);
				return;
			}

			try {
				// process query params first.
				this.processParam(request, context.getRequestURI().getQuery());

				// process post params.
				if (METHOD_POST.equals(context.getRequestMethod())) {
					String contentType = context.getRequestHeaders().getFirst(CONTENT_TYPE);

					InputStream input = context.getRequestBody();

					if (contentType != null && contentType.startsWith("multipart")) {
						Map<String, String> params = ValueParser.parse(contentType);

						String encoding = params.get("charset");
						if (encoding == null) {
							encoding = DEFAULT_ENCODING;
						}

						byte[] boundary = params.get("boundary").getBytes(encoding);
						Multipart mp = new Multipart(input, encoding, boundary);
						Part p = mp.nextPart();

						while (p != null) {
							InputStream body = p.getBody();
							try {
								String partType = p.getFirstValue(CONTENT_DISPOSITION);
								Map<String, String> param = ValueParser.parse(partType);
								String key = param.get("name");
								// process multipart params.
								HttpServer.this.processParam(request, key, body, param);
							}
							finally {
								Utils.close(body);
							}
							p = mp.nextPart();
						}
					}
					else if (CONTENT_TYPE_URL_ENCODED_FORM.equals(contentType)) {
						// process form data params.
						this.processParam(request, Utils.readStream(input));
					}
					else {
						throw new Exception("content type not known: " + contentType);
					}
				}
			}
			catch (Exception e) {
				error = e;
				WebShare.log(e);
			}

			responseStart = System.currentTimeMillis();
			this.writeResponse(response, error);
		}
		catch (Exception e) {
			// send only error if exception was not thrown during sending response
			if (responseStart == requestStart) {
				try {
					if (e instanceof Error) {
						response.setResponseCode(((Error) e).responseCode);
					}
					else {
						response.setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR);
					}
					response.write(e.getMessage());
				}
				catch (Exception e1) {
					WebShare.log(e1);
				}
			}
			WebShare.log(e);
		}
		finally {
			context.close();
			long now = System.currentTimeMillis();
			String requestTime = Utils.formatTime(now - requestStart);
			String responseTime = Utils.formatTime(now - responseStart);
			WebShare.log("response in: [%s / %s]: `%s`", requestTime, responseTime, request.path);
		}
	}
}
