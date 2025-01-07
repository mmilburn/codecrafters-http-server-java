import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

class Headers {

    private final Map<String, Object> headers = new HashMap<>();

    public void addHeader(String line) {
        String[] parts = line.split(":", 2);
        if (parts.length != 2) return;

        String key = parts[0].trim();
        String value = parts[1].trim();

        if (!headers.containsKey(key)) {
            if (value.matches("\\d+")) {
                headers.put(key, Integer.parseInt(value));
            } else if (value.contains(",")) {
                headers.put(key, Arrays.stream(value.split(","))
                        .map(String::trim)
                        .collect(Collectors.toSet()));
            } else {
                headers.put(key, value);
            }
        } else {
            System.err.printf("%s already present in headers!%n", key);
        }
    }

    public String getUserAgent() {
        return (String) headers.getOrDefault("User-Agent", "");
    }

    public int getContentLength() {
        return (int) headers.getOrDefault("Content-Length", 0);
    }

    public boolean isGzipAccepted() {
        Object encoding = headers.get("Accept-Encoding");
        return encoding instanceof Set && ((Set<?>) encoding).contains("gzip");
    }

    public Headers setContentLength(int length) {
        headers.put("Content-Length", length);
        return this;
    }

    public Headers setContentType(String contentType) {
        headers.put("Content-Type", contentType);
        return this;
    }

    public Headers setContentEncoding(String encoding) {
        headers.put("Content-Encoding", encoding);
        return this;
    }

    public Headers setContentEncodingGzip() {
        return setContentEncoding("gzip");
    }

    public boolean isContentEncodingGzip() {
        return "gzip".equals(headers.get("Content-Encoding"));
    }

    public List<String> getHeadersAsList() {
        return headers.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue()).collect(Collectors.toList());
    }
}

class HttpResponse {

    private final String status;
    private final Headers headers;
    private final byte[] body;

    public HttpResponse(String status, Headers headers, String body) {
        this.status = status;
        this.headers = headers;
        if (body != null) {
            this.body = headers.isContentEncodingGzip() ?
                    body.getBytes() : body.getBytes(StandardCharsets.UTF_8);
        } else {
            this.body = null;
        }
    }

    public byte[] toBytes() {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(status).append("\r\n");

        headers.getHeadersAsList().forEach(header -> response.append(header).append("\r\n"));

        response.append("\r\n");
        byte[] headerBytes = response.toString().getBytes(StandardCharsets.UTF_8);

        if (body != null) {
            byte[] result = new byte[headerBytes.length + body.length];
            System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
            System.arraycopy(body, 0, result, headerBytes.length, body.length);
            return result;
        }

        return headerBytes;
    }
}


class Response {
    private final HttpResponseBuilder builder;

    public Response(Path directoryPath, HttpRequest request) {
        this.builder = new HttpResponseBuilder().setStatus(200);
        Headers responseHeaders = new Headers().setContentType("text/plain");
        String body = null;

        if ("GET".equals(request.getMethod())) {
            if (request.getPath().startsWith("/echo/")) {
                body = request.getPath().substring(6).trim();
            } else if (request.getPath().startsWith("/user-agent")) {
                body = request.getUserAgent();
            } else if (request.getPath().startsWith("/files/")) {
                Path filePath = directoryPath.resolve(request.getPath().substring(7).trim());
                if (Files.exists(filePath)) {
                    try {
                        byte[] file = Files.readAllBytes(filePath);
                        body = new String(file, StandardCharsets.UTF_8);
                    }
                    catch (IOException ioNo) {
                        System.err.println(ioNo.getMessage());
                        builder.setStatus(500);
                    }
                } else {
                    builder.setStatus(404);
                }
            } else if (!(request.getPath().isEmpty() || request.getPath().equals("/"))) {
                builder.setStatus(404);
            }
        } else if ("POST".equals(request.getMethod())) {
            builder.setStatus(201);
        } else {
            builder.setStatus(501);
        }

        if (body != null) {
            if (request.useGzip()) {
                body = "gzipped-body"; // Example compression
                responseHeaders.setContentEncodingGzip();
            }
            builder.setBody(body);
        }

        builder.setHeaders(responseHeaders);
    }

    public byte[] toBytes() {
        return builder.build().toBytes();
    }
}


class HttpRequest {

    private final String method;
    private final String path;
    private final String version;
    private final Headers headers = new Headers();
    private final byte[] body;

    public HttpRequest(byte[] data) {
        List<String> lines = Arrays.asList(new String(data, StandardCharsets.UTF_8).split("\\r\\n"));

        String[] requestLine = lines.get(0).split(" ");
        this.method = requestLine[0].toUpperCase();
        this.path = requestLine[1];
        this.version = requestLine[2];

        int i = 1;
        while (i < lines.size() && !lines.get(i).isEmpty()) {
            headers.addHeader(lines.get(i).trim());
            i++;
        }

        int contentLength = headers.getContentLength();
        if (contentLength > 0) {
            this.body = Arrays.copyOfRange(data, data.length - contentLength, data.length);
        } else {
            this.body = null;
        }
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getVersion() {
        return version;
    }

    public byte[] getBody() {
        return body;
    }

    public Headers getHeaders() {
        return headers;
    }

    public boolean useGzip() {
        return headers.isGzipAccepted();
    }

    public String getUserAgent() {
        return headers.getUserAgent();
    }
}

class HttpResponseBuilder {

    private static final Map<Integer, String> STATUSES = new HashMap<>();

    static {
        STATUSES.put(200, "200 OK");
        STATUSES.put(201, "201 Created");
        STATUSES.put(404, "404 Not Found");
        STATUSES.put(500, "500 Internal Server Error");
        STATUSES.put(501, "501 Not Implemented");
    }


    private String statusLine;
    private Headers headers = new Headers();
    private String body;

    public HttpResponseBuilder setStatus(int statusCode) {
        this.statusLine = STATUSES.getOrDefault(statusCode, STATUSES.get(501));
        return this;
    }

    public HttpResponseBuilder setBody(String body) {
        this.body = body;
        return this;
    }

    public HttpResponseBuilder setHeaders(Headers headers) {
        this.headers = headers;
        return this;
    }

    public HttpResponse build() {
        if (body != null) {
            headers.setContentLength(body.length());
        } else {
            headers.setContentLength(0);
        }
        return new HttpResponse(statusLine, headers, body);
    }
}


class SimpleWebServer {

    private final int port;
    private final Path directoryPath;
    private final ExecutorService threadPool;

    public SimpleWebServer(int port, String directoryPath) {
        this.port = port;
        this.directoryPath = directoryPath != null ? Paths.get(directoryPath) : null;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() {
        // Validate directory
        if (directoryPath != null && (!Files.exists(directoryPath) || !Files.isDirectory(directoryPath))) {
            System.err.printf("Error: '%s' is not a valid directory.%n", directoryPath);
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.printf("Server started on port %d. Serving files from %s%n",
                    port, directoryPath != null ? directoryPath : "default location");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server encountered an error: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket) {
            byte[] buffer = new byte[8192];
            int read = clientSocket.getInputStream().read(buffer);
            if (read > 0) {
                HttpRequest request = new HttpRequest(buffer);
                Response response = new Response(directoryPath, request);

                clientSocket.getOutputStream().write(response.toBytes());
                clientSocket.getOutputStream().flush();
            }
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        }
    }
}

class CommandLineArgs {

    @Parameter(names = "--directory", description = "Path to the directory to serve files from")
    private String directory;

    public String getDirectory() {
        return directory;
    }
}

public class Main {
    public static void main(String[] args) {
        // Parse arguments using JCommander
        CommandLineArgs commandLineArgs = new CommandLineArgs();
        JCommander.newBuilder()
                .addObject(commandLineArgs)
                .build()
                .parse(args);

        String directory = commandLineArgs.getDirectory();
        SimpleWebServer server = new SimpleWebServer(4221, directory);
        server.start();
    }
}
