package org.example.p2p.controller;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;

import org.example.p2p.Service.FileSharer;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class TestController {

    private final FileSharer fileSharer = new FileSharer();
    private final String uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerlink-uploads";

    public TestController() {
        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }
    }

    @GetMapping("/hi")
    public String hi() {
        return "Hello! Spring Boot server is running.";
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Integer>> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            String filename = file.getOriginalFilename();
            if (filename == null || filename.trim().isEmpty()) {
                filename = "unnamed-file";
            }

            String uniqueFilename = UUID.randomUUID().toString() + "-" + new File(filename).getName();
            String filePath = uploadDir + File.separator + uniqueFilename;

            file.transferTo(new File(filePath));

            // Register file with p2p port and get a port
            int port = fileSharer.offerFile(filePath);
            new Thread(() -> fileSharer.startFileServer(port)).start();

            return ResponseEntity.ok(Map.of("port", port));
        } catch (IOException e) {
            System.err.println("Error processing file upload: " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/download/{port}")
    public ResponseEntity<InputStreamResource> downloadFile(@PathVariable int port) {
        try {
            // Connect to the peer server on the specified port
            try (Socket socket = new Socket("localhost", port);
                    InputStream socketInput = socket.getInputStream()) {

                File tempFile = File.createTempFile("download-", ".tmp");
                String filename = "downloaded-file";

                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    // Read filename header from peer
                    ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                    int b;
                    while ((b = socketInput.read()) != -1) {
                        if (b == '\n')
                            break;
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

                // Prepare response with file
                FileInputStream fileInputStream = new FileInputStream(tempFile);
                InputStreamResource resource = new InputStreamResource(fileInputStream) {
                    @Override
                    public long contentLength() throws IOException {
                        return tempFile.length();
                    }
                };

                HttpHeaders headers = new HttpHeaders();
                headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
                headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

                // Clean up temp file after response is sent
                new Thread(() -> {
                    try {
                        Thread.sleep(5000); // Give time for download to complete
                        tempFile.delete();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();

                return ResponseEntity.ok()
                        .headers(headers)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource);

            } catch (IOException e) {
                System.err.println("Error downloading from peer: " + e.getMessage());
                return ResponseEntity.internalServerError().build();
            }

        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}