import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.ZipOutputStream;

public class WebShare extends HttpServer {

	public static final String DOWNLOAD = "download";
	public static final String RECURSIVE = "recursive";

	public static final String POST_CMD_MK_DIR = "mkdir";
	public static final String POST_CMD_DELETE = "delete";

	public static final String POST_CMD_FILENAME = "filename";
	public static final String POST_CMD_FILEDATA = "filedata";

	public static void main(String[] args) throws IOException {
		String host = "http://localhost";
		int port = 8090;
		String wdir = ".";

		boolean readOnly = true;
		String auth = null;

		int threads = 256;

		if (args.length > 0) {
			int arg;
			for (arg = 0; arg < args.length - 1; arg += 1) {
				if ("-host".equals(args[arg])) {
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
				/*else if ("-log".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						logFile = new File(args[arg]);
					}
				}*/
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
					wdir = path;
				}
			}
		}

		final File root = new File(wdir).getCanonicalFile();

		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", new WebShare(root, auth, readOnly));
		if (threads > 0) {
			server.setExecutor(Executors.newFixedThreadPool(threads));
		}
		server.start();
		log("Server started: %s:%s using %d threads in folder: `%s`", host, server.getAddress().getPort(), threads, root.getAbsolutePath());
	}

	private final File root;
	private final String auth;
	private final boolean readOnly;
	private Set<String> authenticated = new HashSet<String>();
	private final HtmlTemplate template = new HtmlTemplate(new File("FileList.html"));

	public WebShare(File root, String auth, boolean readOnly) {
		this.root = root;
		this.auth = auth;
		this.readOnly = readOnly;
	}

	@Override
	public void beginRequest(Request request) throws HttpServer.Error {
		request.put(DOWNLOAD, null);
		request.put(POST_CMD_FILENAME, null);

		File file = request.getFile(root);

		// no favicon for this server.
		if (request.path.equals("/favicon.ico")) {
			throw new HttpServer.Error("No favicon");
		}

		String contentSize = request.context.getRequestHeaders().getFirst(CONTENT_LENGTH);
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
		log("execute: %s[%s]: `%s`", request.method, contentSize, file.getAbsolutePath());

		if (auth != null) {
			// server is password protected.
			this.enforceAuthenticated(request);
		}
	}

	@Override
	public void processParam(Request request, String name, InputStream body, Map<String, String> params) throws HttpServer.Error {
		if (POST_CMD_MK_DIR.equals(name)) {
			if (readOnly) {
				throw new HttpServer.Error("Write support is not enabled");
			}
			String value = Utils.readStream(body);
			if (!Utils.isNullOrEmpty(value)) {
				File file = new File(request.getFile(root), value);

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
				for (String fileName : value.split("/")) {
					File file = new File(request.getFile(root), fileName);

					if (!file.exists()) {
						throw new HttpServer.Error("File does not exists: " + file.getAbsolutePath());
					}

					log("deleted: `%s`", file.getAbsolutePath());
					if (!file.delete()) {
						throw new HttpServer.Error("Can not delete file.");
					}
				}
			}
		}

		else if (DOWNLOAD.equals(name)) {
			String value = Utils.readStream(body);
			if (!Utils.isNullOrEmpty(value)) {
				String[] files = value.split("/");
				List<File> toZip = new ArrayList<File>();

				for (int i = 0; i < files.length; ++i) {

					// skip empty strings produced by: split("a//b/")
					if (files[i] == null || files[i].isEmpty()) {
						continue;
					}

					File file = new File(request.getFile(root), files[i]);

					if (!file.exists()) {
						throw new HttpServer.Error("File does not exists: " + file.getAbsolutePath());
					}
					toZip.add(file);
				}
				request.put(DOWNLOAD, toZip.toArray(new File[toZip.size()]));
			}
			else {
				request.put(DOWNLOAD, new File[] { request.getFile(root) });
			}
		}

		else if (RECURSIVE.equals(name)) {
			if (!request.getFile(root).isDirectory()) {
				throw new HttpServer.Error("Must recurse directories.");
			}
			request.put(RECURSIVE, true);
		}

		else if (POST_CMD_FILENAME.equals(name)) {
			request.put(POST_CMD_FILENAME, Utils.readStream(body));
		}

		else if (POST_CMD_FILEDATA.equals(name)) {
			if (readOnly) {
				throw new HttpServer.Error("Write support is not enabled");
			}

			// params should never be null for upload.
			String fileName = params.get("filename");
			if (fileName == null) {
				fileName = (String)request.get(POST_CMD_FILENAME);
			}

			if (fileName == null) {
				fileName = UUID.randomUUID().toString();
			}
			File file = new File(request.getFile(root), fileName);

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
	public void writeResponse(Request request, Exception error) throws HttpServer.Error, IOException {
		if (request.getFile(root) == null) {
			throw new HttpServer.Error(404, "File not found", null);
		}

		File file = request.getFile(root);
		File[] download = (File[]) request.get(DOWNLOAD);

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
					// do not zip a single file.
					downloadFile(request.context, file);
					return;
				}
			}

			if (Utils.isNullOrEmpty(zipName)) {
				zipName = this.getClass().getSimpleName();
			}

			downloadFile(request.context, zipName + ".zip", download);
			return;
		}

		if (file.isFile()) {
			writeResponse(request, 200, file);
			return;
		}

		if (!file.exists()) {
			error = new Exception("File not found");
		}

		template.clean();
		if (request.get(RECURSIVE) != null) {
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

		int responseCode = 200;
		if (error != null) {
			HtmlTemplate message = template.add("errorRow");
			if (message != null) {
				message.set("message", error.getMessage());
			}
			responseCode = 500;
		}

		writeResponse(request, responseCode, template);
	}

	private static void downloadFile(HttpExchange context, final File file) throws IOException {
		long size = file.length();
		context.getResponseHeaders().add(CONTENT_DISPOSITION, "attachment; filename=" + file.getName());
		context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_OCTET_STREAM);
		context.sendResponseHeaders(200, size);

		InputStream in = null;
		try {
			long time = System.currentTimeMillis();
			OutputStream out = context.getResponseBody();
			in = new FileInputStream(file);
			Utils.copyStream(out, in);
			time = System.currentTimeMillis() - time;
			log("downloaded [%s @ %s]: `%s`", Utils.formatSize(size), Utils.formatSpeed(file.length(), time), file.getAbsolutePath());
		} finally {
			if (in != null) {
				in.close();
			}
		}
	}

	private static void downloadFile(final HttpExchange context, final String zipFileName, final File... files) throws IOException {
		context.getResponseHeaders().add(CONTENT_DISPOSITION, "attachment; filename=" + zipFileName);
		context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_ARCHIVE_ZIP);
		context.sendResponseHeaders(200, 0);

		OutputStream out = context.getResponseBody();
		ZipOutputStream zip = null;
		long size = 0;
		try {
			long time = System.currentTimeMillis();
			zip = new ZipOutputStream(out);
			for (File toZip : files) {
				size += Utils.addToArchive(zip, "", toZip);
			}
			time = System.currentTimeMillis() - time;
			log("downloaded [%s @ %s]: `%s`", Utils.formatSize(size), Utils.formatSpeed(size, time), zipFileName);
		} finally {
			if (zip != null) {
				zip.close();
			}
		}
	}

	private void enforceAuthenticated(HttpServer.Request request) throws HttpServer.Error {
		HttpExchange context = request.context;
		String remoteAddress = context.getRemoteAddress().getHostName();
		if (authenticated.contains(remoteAddress)) {
			return;
		}

		String authorization = context.getRequestHeaders().getFirst("authorization");
		if (authorization != null && authorization.startsWith("Basic ")) {
			authorization = Utils.base64Decode(authorization.substring(6));
			if (auth != null && auth.equals(authorization)) {
				int sep = authorization.indexOf(':');
				String username = authorization.substring(0, sep);
				String password = authorization.substring(sep + 1);
				log("User authenticated: %s:%s@%s", username, password, remoteAddress);
				authenticated.add(remoteAddress);
				return;
			}
		}

		throw new HttpServer.Error(401, "401 Access denied", new HashMap<String, String>() {{
			put("WWW-Authenticate", "Basic realm=\"Home Server\"");
		}});
	}

}
