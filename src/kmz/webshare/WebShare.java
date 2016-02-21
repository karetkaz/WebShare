package kmz.webshare;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

public class WebShare extends HttpServer {

	private static final String DOWNLOAD = "download";
	private static final String RECURSIVE = "recursive";

	private static final String POST_CMD_MK_DIR = "mkdir";
	private static final String POST_CMD_DELETE = "delete";

	private static final String POST_CMD_FILENAME = "filename";
	private static final String POST_CMD_FILEDATA = "filedata";

	private static final String FILE_MIME_MAP = "mime.map";
	private static final String FILE_TEMPLATE = "FileList.html";

	private static File logFile = null;

	public static void log(Throwable error, String message, Object... args) {
		if (message != null) {
			System.out.printf(message, args).println();
		}
		if (error != null) {
			error.printStackTrace(System.out);
		}

		//*TODO:
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

	public static void main(String[] args) throws IOException, ParseException {
		String host = "http://localhost";
		int port = 8090;
		String directory = ".";
		String auth = null;
		String repoUrl = null;
		boolean readOnly = true;

		int threads = 256;

		if (args.length > 0) {
			int arg;
			for (arg = 0; arg < args.length - 1; arg += 1) {
				if ("-repo".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						repoUrl = args[arg];
					}
				}
				else if ("-host".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						host = args[arg];
					}
				}
				else if ("-port".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						port = Integer.parseInt(args[arg]);
					}
				}
				else if ("-auth".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						auth = args[arg];
					}
				}
				else if ("-log".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						logFile = new File(args[arg]);
					}
				}
				else if ("-n".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						threads = Integer.parseInt(args[arg]);
					}
				}
				else if ("-write".equals(args[arg])) {
					readOnly = false;
				}
				else {
					log("invalid argument: `%s`", args[arg]);
					return;
				}
			}
			if (arg < args.length) {
				String path = args[arg].trim();
				if (!path.isEmpty()) {
					directory = path;
					if (directory.startsWith("~/")) {
						directory = System.getProperty("user.home") + directory.substring(1);
					}
				}
			}
			final File root = new File(directory).getCanonicalFile();

			com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
			if (repoUrl != null) {
				server.createContext("/", new HttpFileProxy(new WebShare(root, auth, readOnly), repoUrl));
			}
			else {
				server.createContext("/", new WebShare(root, auth, readOnly));
			}
			if (threads > 0) {
				server.setExecutor(Executors.newFixedThreadPool(threads));
			}
			server.start();
			log("Server started: %s:%s using %d threads in folder: `%s`", host, server.getAddress().getPort(), threads, root.getAbsolutePath());
		}
		else {
			new WebShareUi().setVisible(true);
		}
	}

	private final File root;
	private final String auth;
	public final boolean readOnly;
	protected final Properties mimeMap;
	protected final Properties headerMap;
	private final HtmlTemplate template;
	private Set<String> authenticatedUsers = new HashSet<String>();

	public WebShare(File root, String auth, boolean readOnly) throws ParseException {
		this.root = root;
		this.auth = auth;
		this.readOnly = readOnly;
		this.mimeMap = new Properties();
		this.headerMap = new Properties();
		this.template = new HtmlTemplate();

		// load html template
		try {
			// try to load first from the
			template.parse(new FileInputStream(FILE_TEMPLATE));
		}
		catch (IOException e) {
			template.parse(getClass().getResourceAsStream("/" + FILE_TEMPLATE));
		}

		// customize mime types
		try {
			// load first from resource
			this.mimeMap.load(getClass().getResourceAsStream("/" + FILE_MIME_MAP));
		}
		catch (Exception ignored) {}
		try {
			// update mime types with external file.
			this.mimeMap.load(new FileInputStream(FILE_MIME_MAP));
		}
		catch (Exception ignored) {}
	}

	@Override
	protected String getContentType(File file) {
		String mimeType = null;
		String fileName = file.getName();
		int extPos = fileName.lastIndexOf('.');
		if (extPos > 1) {
			String ext = fileName.substring(extPos + 1).toLowerCase();
			mimeType = mimeMap.getProperty(ext, null);
		}
		if (mimeType == null) {
			mimeType = mimeMap.getProperty("*", null);
			if (mimeType == null) {
				mimeType = CONTENT_TYPE_TEXT_PLAIN_CHARSET;
			}
		}
		return mimeType;
	}

	@Override
	protected File getLocalPath(String path) {
		if (this.root == null) {
			return null;
		}
		return new File(this.root, path);
	}

	@Override
	public boolean beginRequest(Request request) throws HttpServer.Error {
		// no favicon for this server.
		if (request.path.equals("/favicon.ico")) {
			return false;
		}

		File file = request.getLocalPath();
		if (file == null || !file.exists()) {
			throw new HttpServer.Error(404, "File not found", null);
		}

		log("request: %s[%s]: `%s`", request.method, getContentLength(request, file), file.getAbsolutePath());
		return true;
	}

	@Override
	public void processParam(Request request, String name, InputStream body, Map<String, String> params) throws HttpServer.Error {
		if (POST_CMD_MK_DIR.equals(name)) {
			if (readOnly) {
				throw new HttpServer.Error("Write support is not enabled");
			}
			String value = Utils.readStream(body);
			if (!Utils.isNullOrEmpty(value)) {
				File file = new File(request.getLocalPath(), value);

				if (file.exists()) {
					throw new HttpServer.Error("File already exists.");
				}

				log("created: `%s`", file.getAbsolutePath());
				if (!file.mkdirs()) {
					throw new HttpServer.Error("Can not create folder.");
				}
			}
		}

		else if (POST_CMD_DELETE.equals(name)) {
			if (readOnly) {
				throw new HttpServer.Error("Write support is not enabled");
			}
			String value = Utils.readStream(body);
			if (!Utils.isNullOrEmpty(value)) {
				for (String fileName : value.split("[\r\n]+")) {
					fileName = fileName.trim();
					if (fileName.isEmpty()) {
						continue;
					}

					File file = new File(request.getLocalPath(), fileName);
					if (!file.exists()) {
						throw new HttpServer.Error("File does not exists: " + fileName);
					}
					if (!file.delete()) {
						throw new HttpServer.Error("Can not delete file: " + fileName);
					}
					log("deleted: `%s`", file.getAbsolutePath());
				}
			}
		}

		else if (DOWNLOAD.equals(name)) {
			String value = Utils.readStream(body);
			if (!Utils.isNullOrEmpty(value)) {
				List<File> toZip = new ArrayList<File>();
				for (String fileName : value.split("[\r\n]+")) {
					fileName = fileName.trim();
					if (fileName.isEmpty()) {
						continue;
					}

					File file = new File(request.getLocalPath(), fileName);
					if (!file.exists()) {
						throw new java.lang.Error("File does not exists: " + fileName);
					}
					toZip.add(file);
				}
				request.putExtra(DOWNLOAD, toZip.toArray(new File[toZip.size()]));
			}
			else {
				request.putExtra(DOWNLOAD, new File[] { request.getLocalPath() });
			}
		}

		else if (RECURSIVE.equals(name)) {
			if (!request.getLocalPath().isDirectory()) {
				throw new HttpServer.Error("Must recurse directories.");
			}
			request.putExtra(RECURSIVE, true);
		}

		else if (POST_CMD_FILENAME.equals(name)) {
			request.putExtra(POST_CMD_FILENAME, Utils.readStream(body));
		}

		else if (POST_CMD_FILEDATA.equals(name)) {
			if (readOnly) {
				throw new HttpServer.Error("Write support is not enabled");
			}

			// params should never be null for upload.
			String fileName = params.get("filename");
			if (fileName == null) {
				fileName = (String)request.getExtra(POST_CMD_FILENAME);
			}

			if (fileName == null) {
				fileName = UUID.randomUUID().toString();
			}
			File file = new File(request.getLocalPath(), fileName);

			if (file.exists()) {
				throw new HttpServer.Error("File already exists.");
			}

			try {
				long time = System.currentTimeMillis();
				FileOutputStream out = new FileOutputStream(file);
				Utils.copyStream(out, body);
				out.close();
				time = System.currentTimeMillis() - time;
				log("uploaded [%s @ %s]: `%s`", Utils.formatSize(file.length()), Utils.formatSpeed(file.length(), time), file.getAbsolutePath());
			}
			catch (IOException e) {
				throw new HttpServer.Error(e);
			}
		}
		else {
			log("invalid parameter: '%s': `%s`", name, Utils.readStream(body));
			throw new HttpServer.Error("Invalid command");
		}
	}

	@Override
	public long writeResponse(Response response, Exception error) throws IOException {
		File file = response.getLocalPath();

		File[] download = (File[]) response.getExtra(DOWNLOAD);

		// download requested.
		if (download != null) {
			String zipName = file.getName();

			// in case of a single file
			if (download.length == 1) {
				file = download[0];
				if (file.isDirectory()) {
					zipName = file.getName();
				}
				else {
					// do not zip a single file, just download it
					response.setAttachment(file.getName());
					response.write(file);
					return file.length();
				}
			}

			if (Utils.isNullOrEmpty(zipName)) {
				zipName = this.getClass().getSimpleName();
			}

			response.setAttachment(zipName + ".zip");
			response.writeZip(download);
			return 0;
		}

		if (file.isFile()) {
			response.write(file);
			return file.length();
		}

		template.reset();
		if (response.getExtra(RECURSIVE) != null) {
			Utils.processFilesRecursive(".", file, new Utils.FileProcessor() {
				int idx = 0;

				@Override
				public void onFile(String path, File file) {
					HtmlTemplate fileRow = template.add("fileRowFile");
					if (fileRow != null) {
						fileRow.set("name", path);
						fileRow.set("href", path);
						fileRow.set("size", Utils.formatSize(file.length()));
						fileRow.set("date", Utils.formatDate(file.lastModified()));
						fileRow.set("oddRow", idx % 2 != 0);
					}
					idx += 1;
				}

				@Override
				public void onDirectory(String path, File file) {
				}

				@Override
				public void onError(String path, File file, Exception error) {
					HtmlTemplate fileRow = template.add("fileRowFile");
					if (fileRow != null) {
						fileRow.set("name", file.getAbsolutePath());
						fileRow.set("href", "javascript:void(0);");
						fileRow.set("size", "Error");
						fileRow.set("date", "Error");
						fileRow.set("oddRow", idx % 2 != 0);
					}
					idx += 1;
				}
			});
		}
		else {
			File[] files = file.listFiles();
			if (files != null) {
				Arrays.sort(files, new Comparator<File>() {
					@Override
					public int compare(File lhs, File rhs) {
						// Directories on top
						if (lhs.isDirectory() != rhs.isDirectory()) {
							return lhs.isDirectory() ? -1 : 1;
						}
						return lhs.getName().compareToIgnoreCase(rhs.getName());
					}
				});

				int idx = 0;
				for (File f : files) {
					HtmlTemplate fileRow = template.add("fileRowFile");
					if (fileRow != null) {
						fileRow.set("name", f.getName());
						if (f.isDirectory()) {
							fileRow.set("href", f.getName() + "/");
							fileRow.set("size", "download");
						}
						else {
							fileRow.set("href", f.getName());
							fileRow.set("size", Utils.formatSize(f.length()));
						}
						fileRow.set("date", Utils.formatDate(f.lastModified()));
						fileRow.set("oddRow", idx % 2 != 0);
					}
					idx += 1;
				}
			}
		}

		if (error != null) {
			HtmlTemplate message = template.add("errorRow");
			if (message != null) {
				message.set("message", error.getMessage());
			}
			response.setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR);
		}
		response.write(template);
		return 0;
	}

	@Override
	boolean isAuthenticated(Request request) {
		if (this.auth == null) {
			return true;
		}

		String remoteAddress = request.getRemoteAddress().getHostName();
		if (authenticatedUsers.contains(remoteAddress)) {
			return true;
		}

		String authorization = request.getFirstHeader("authorization");
		if (authorization != null && authorization.startsWith("Basic ")) {
			authorization = Utils.base64Decode(authorization.substring(6));
			if (auth.equals(authorization)) {
				int sep = authorization.indexOf(':');
				String username = authorization.substring(0, sep);
				String password = authorization.substring(sep + 1);
				log("User authenticated: %s:%s@%s", username, password, remoteAddress);
				authenticatedUsers.add(remoteAddress);
				return true;
			}
		}
		return false;
	}

	private static String getContentLength(Request request, File file) {
		String contentSize = request.getFirstHeader(CONTENT_LENGTH);
		if (contentSize == null) {
			if (file.isDirectory()) {
				contentSize = "";
			}
			else {
				contentSize = Utils.formatSize(file.length());
			}
		}
		else {
			try {
				contentSize = Utils.formatSize(Long.parseLong(contentSize));
			}
			catch (Exception e) {
				// leave content size what it is.
			}
		}
		return contentSize;
	}
}
