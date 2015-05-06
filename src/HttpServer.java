import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.tastefuljava.sceyefi.multipart.Multipart;
import org.tastefuljava.sceyefi.multipart.Part;
import org.tastefuljava.sceyefi.multipart.ValueParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

public abstract class HttpServer implements HttpHandler {

	protected static class Request {
		public final HttpExchange context;
		public final String method;
		public final String path;
		private final Map<String, Object> extras;

		public Request(HttpExchange context, String path, String method) {
			this.context = context;
			this.path = path;
			this.method = method;
			this.extras = new HashMap<String, Object>();
		}

		public File getFile(File root) {
			return new File(root, this.path);
		}

		public Object get(String key) {
			return this.extras.get(key);
		}

		public void put(String key, Object value) {
			this.extras.put(key, value);
		}
	}

	protected static class Error extends Exception {
		public final int responseCode;
		public final Map<String, String> responseHeaders;

		public Error(String message, Throwable cause, int responseCode, Map<String, String> responseHeaders) {
			super(message, cause);
			this.responseCode = responseCode;
			this.responseHeaders = responseHeaders;
		}

		public Error(int responseCode, String message, Map<String, String> responseHeaders) {
			this(message, null, responseCode, responseHeaders);
		}

		public Error(Exception cause) {
			this(cause.getMessage(), cause, 500, Collections.<String, String>emptyMap());
		}

		public Error(String message) {
			this(message, null, 500, Collections.<String, String>emptyMap());
		}

	}

	public static final Properties mimeTypes = new Properties() {{
		String file = "mime.map";
		try {
			load(new FileInputStream(file));
		} catch (IOException e) {
			log(e, "Error loading mime mapping: `%s`", file);
		}
	}};

	private static File logFile = new File("log.txt");

	public static void log(Throwable error, String message, Object... args) {
		if (message != null) {
			System.out.printf(message, args).println();
		}
		if (error != null) {
			error.printStackTrace(System.out);
		}

		PrintWriter log = null;
		if (logFile != null) {
			try {
				log = new PrintWriter(new FileOutputStream(logFile, true));
				if (message != null) {
					log.printf(message, args).println();
				}
				if (error != null) {
					error.printStackTrace(log);
				}
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				if (log != null) {
					log.close();
				}
			}
		}
	}

	public static void log(String message, Object... args) {
		log(null, message, args);
	}

	public static void log(Throwable error) {
		log(error, null);
	}

	// handle a new request, return the executor to execute on.
	abstract void beginRequest(Request request) throws Error;

	// process the request get and post params.
	abstract void processParam(Request request, String name, InputStream value, Map<String, String> params) throws Error;

	// handle write response to client.
	abstract void writeResponse(Request request, Exception error) throws Error, IOException;


	protected static void writeResponse(Request request, int responseCode, String string) throws IOException {
		byte[] response = string.getBytes();
		request.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_HTML_CHARSET);
		request.context.sendResponseHeaders(responseCode, response.length);
		request.context.getResponseBody().write(response);
	}

	protected static void writeResponse(Request request, int responseCode, File file) throws IOException {
		InputStream in = null;
		try {
			request.context.getResponseHeaders().add(CONTENT_TYPE, getContentType(file));
			request.context.sendResponseHeaders(responseCode, file.length());

			OutputStream out = request.context.getResponseBody();
			in = new FileInputStream(file);
			Utils.copyStream(out, in);
		} finally {
			if (in != null) {
				in.close();
			}
		}
	}

	protected static void writeResponse(Request request, int responseCode, HtmlTemplate template) throws IOException {
		writeResponse(request, responseCode, template.toString());

		// TODO: make it simple.
		//context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_HTML_CHARSET);
		//context.sendResponseHeaders(responseCode, 0);
		//template.write(context.getResponseBody());
	}

	private static void writeResponse(HttpExchange context, Exception error) throws IOException {
		byte[] bytes = error.getMessage().getBytes();
		int responseCode = 500; //internal server error
		if (error instanceof Error) {
			Error httpServerError = (Error) error;
			for (String key : httpServerError.responseHeaders.keySet()) {
				context.getResponseHeaders().add(key, httpServerError.responseHeaders.get(key));
			}
			if (!httpServerError.responseHeaders.containsKey(CONTENT_TYPE)) {
				context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_PLAIN_CHARSET);
			}
			responseCode = httpServerError.responseCode;
		}
		context.sendResponseHeaders(responseCode, bytes.length);
		context.getResponseBody().write(bytes);
	}


	protected static String getContentType(File file) {

		String fileName = file.getName();
		String mimeType = null;

		int extPos = fileName.lastIndexOf('.');
		if (extPos > 1) {
			String ext = fileName.substring(extPos + 1).toLowerCase();
			mimeType = mimeTypes.getProperty(ext, null);
		}

		/*if (mimeType == null) {
			FileTypeMap fileTypeMap = MimetypesFileTypeMap.getDefaultFileTypeMap();
			mimeType = fileTypeMap.getContentType(fileName);
			if (mimeType == null) {
				mimeType = fileTypeMap.getContentType(file);
			}
		}*/
		if (mimeType == null) {
			mimeType = mimeTypes.getProperty("*", null);
			if (mimeType == null) {
				mimeType = CONTENT_TYPE_TEXT_PLAIN_CHARSET;
			}
		}
		return mimeType;
	}

	protected static final String METHOD_POST = "POST";
	protected static final String DEFAULT_ENCODING = "UTF-8";

	protected static final String CONTENT_TYPE = "Content-type";
	protected static final String CONTENT_LENGTH = "Content-length";
	protected static final String CONTENT_DISPOSITION = "content-disposition";

	protected static final String CONTENT_TYPE_ARCHIVE_ZIP = "application/zip";
	protected static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
	protected static final String CONTENT_TYPE_URL_ENCODED_FORM = "application/x-www-form-urlencoded";

	protected static final String CONTENT_TYPE_TEXT_HTML_CHARSET = "text/html; charset=" + DEFAULT_ENCODING;
	protected static final String CONTENT_TYPE_TEXT_PLAIN_CHARSET = "text/plain; charset=" + DEFAULT_ENCODING;

	private void processParam(Request request, String encodedParams) throws Exception {
		if (!Utils.isNullOrEmpty(encodedParams)) {
			for (String str : encodedParams.split("&")) {
				int eq = str.indexOf('=');
				if (eq >= 0) {
					String key = URLDecoder.decode(str.substring(0, eq), DEFAULT_ENCODING);
					String value = URLDecoder.decode(str.substring(eq + 1), DEFAULT_ENCODING);
					InputStream body = new ByteArrayInputStream(value.getBytes());
					HttpServer.this.processParam(request, key, body, null);
				} else {
					HttpServer.this.processParam(request, str, null, null);
				}
			}
		}
	}

	@Override
	public void handle(final HttpExchange context) {
		long requestStart = System.currentTimeMillis();
		long responseStart = requestStart;
		final String path = context.getRequestURI().getPath();
		final Request request = new Request(context, path, context.getRequestMethod());
		try {

			if (path.equals("/favicon.ico")) {
				return;
			}

			Exception error = null;

			// prepare request.
			this.beginRequest(request);

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
								body.close();
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
				log(e);
			}

			responseStart = System.currentTimeMillis();
			this.writeResponse(request, error);
		}
		catch (Exception e) {
			try {
				writeResponse(context, e);
			}
			catch (IOException e1) {
				log(e);
			}
			log(e);
		}
		finally {
			context.close();
			long now = System.currentTimeMillis();
			String requestTime = Utils.formatTime(now - requestStart);
			String responseTime = Utils.formatTime(now - responseStart);
			log("response in: [%s / %s]: `%s`", requestTime, responseTime, path);
		}
	}
}
