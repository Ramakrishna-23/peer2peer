package org.example.p2p.controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.example.p2p.Service.FileSharer;
import org.springframework.web.servlet.function.ServerRequest;


import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class FileController {
    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;


    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty(("java.io.tmpdir") + File.separator + "peerlink-uploads");
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdir();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(5);
        executorService.shutdown();
        System.out.println("API Server stopped");
    }

    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

            if (exchange.getRequestMethod().equals("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = "NOT FOUND";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream oos = exchange.getResponseBody()) {
                oos.write(response.getBytes());
            }
        }
    }


    private class MultipartParser {
        private final byte[] data;
        private final String boundary;

        public MultipartParser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                String dataAsString = new String(data);

                // first step is find the filename
                String fileMarker = "filename=\"";
                int filenameStart = dataAsString.indexOf(fileMarker);
                if (filenameStart == -1) return null;

                filenameStart += fileMarker.length();
                int filenameEnd = dataAsString.indexOf("\"", filenameStart);
                String fileName = dataAsString.substring(filenameStart, filenameEnd);

                // check what type of the file is this

                String contentTypeMarker = "Content-Type: ";
                String contentType = "application/octect-stream";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, filenameEnd);
                if (contentTypeStart != -1) {
                    contentTypeStart += contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeStart);
                }

                // finding the headers of the string
                String headerEndMarker = "\r\n\r\n";
                int headerEnd = dataAsString.indexOf(headerEndMarker);
                if (headerEnd == -1) return null;
                int contentStart = headerEnd + headerEndMarker.length();
                // finding where the boundary ends
                byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
                int contentEnd = findSequence(data, boundaryBytes, contentStart);

                if (contentEnd == -1) {
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }
                if (contentEnd == -1 || contentEnd <= contentStart) return null;

                // extract the actual bytes of the file
                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);
                return new ParseResult(fileName, contentType, fileContent);

            } catch (Exception e) {
                System.err.println("Error multiparsing the data:" + e.getMessage());
                return null;
            }
        }

        private int findSequence(byte[] data, byte[] sequence, int startPos) {
            outer:
            for (int i = startPos; i <= data.length - sequence.length; i++) {
                for (int j = 0; j < sequence.length; j++) {
                    if (data[i + j] != sequence[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        public static class ParseResult {
            public final String filename;
            public final String contentType;
            public final byte[] fileContent;

            public ParseResult(String filename, String contentType, byte[] fileContent) {
                this.filename = filename;
                this.contentType = contentType;
                this.fileContent = fileContent;
            }
        }
    }

    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            //Validate content type
            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                sendErrorResponse(exchange, 400, "Bad Request: Content-Type must be multipart/form-data");
                return;
            }
            try {
                // extract boundary from content type
                String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] requestData = baos.toByteArray();


                // parsing multipart data to extract data
                MultipartParser parser = new MultipartParser(requestData, boundary);
                MultipartParser.ParseResult result = parser.parse();

                if (result == null) {
                    sendErrorResponse(exchange, 400, "Bad Request:could not parse the file content ");
                    return;
                }

                String filename = result.filename;
                if (filename == null || filename.trim().isEmpty()) {
                    filename = "unnamed-file";
                }
                String uniqueFilename = UUID.randomUUID().toString() + "-" + new File(filename).getName();
                String filePath = uploadDir + File.separator + uniqueFilename;

                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    fos.write(result.fileContent);
                }

                // register file with p2p port and get a port
                int port = fileSharer.offerFile(filePath);

                new Thread(() -> fileSharer.startFileServer(port)).start();

                String jsonResponse = "{\"port\": " + port + "}";
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(jsonResponse.getBytes());
                }

            } catch (Exception e) {
                System.err.println("Error processingfile Upload: " + e.getMessage());
                sendErrorResponse(exchange, 500, "server error:" + e.getMessage());
            }
        }

        private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {

            exchange.sendResponseHeaders(statusCode, message.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(message.getBytes());
            }
        }
    }


    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            // Extract the port number from the URL path
            String path = exchange.getRequestURI().getPath();
            String portStr = path.substring(path.lastIndexOf('/') + 1);

            try {
                int port = Integer.parseInt(portStr);

                // FIXED: Proper try-with-resources syntax with semicolon
                try (Socket socket = new Socket("localhost", port);
                     InputStream socketInput = socket.getInputStream()) {

                    File tempFile = File.createTempFile("download-", ".tmp");
                    String filename = "downloaded-file";

                    try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                        // Read filename header from peer
                        ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                        int b;
                        while ((b = socketInput.read()) != -1) {
                            if (b == '\n') break;
                            headerBaos.write(b);
                        }

                        String header = headerBaos.toString().trim();
                        if (header.startsWith("Filename: ")) {
                            filename = header.substring("Filename: ".length());
                        }

                        // Read file content from peer
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = socketInput.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }

                    // FIXED: Send the file to client BEFORE deleting it
                    headers.add("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                    headers.add("Content-Type", "application/octet-stream");

                    exchange.sendResponseHeaders(200, tempFile.length());
                    try (OutputStream os = exchange.getResponseBody();
                         FileInputStream fis = new FileInputStream(tempFile)) {

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }

                    // Clean up temp file after sending
                    tempFile.delete();

                } catch (IOException e) {
                    // FIXED: Changed "form" to "from" and error code to 500
                    System.err.println("Error downloading from peer: " + e.getMessage());
                    sendErrorResponse(exchange, 500, "Error downloading file: " + e.getMessage());
                }

            } catch (NumberFormatException e) {
                sendErrorResponse(exchange, 400, "Bad Request: Invalid port number");
            }
        }

        // FIXED: Method moved OUTSIDE of handle() method, at class level
        private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(statusCode, message.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(message.getBytes());
            }
        }
    }
}
