package org.example.p2p.Service;

import org.example.p2p.utils.UploadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;

public class FileSharer {

    private static final int MAX_CONCURRENT_DOWNLOADS = 5;
    private HashMap<Integer,String> availableFiles;

    public FileSharer(){
        availableFiles = new HashMap<>();
    }
    public int offerFile(String filePath){
        int port;
        while(true){
            port = UploadUtils.generatedCode();
            if(!availableFiles.containsKey(port)){
                availableFiles.put(port,filePath);
                return port;
            }
        }
    }

    public void startFileServer(int port){
        String filePath = availableFiles.get(port);
        if( filePath == null){
            System.out.println("No file is associated with port "+port);
            return;
        }
        try(ServerSocket serverSocket = new ServerSocket(port)){
            System.out.println("Serving the file "+new File(filePath).getName() + "on port " +port);
            for(int i=0;i < MAX_CONCURRENT_DOWNLOADS; i++){
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connection: "+ clientSocket.getInetAddress());
            new Thread(new FileSenderHandler(clientSocket,filePath)).start();

            }
        }catch (IOException e){
            System.err.println("Error handling file server on port "+port);
        }
    }

    private static class FileSenderHandler implements  Runnable{
        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath){
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run(){
            try(FileInputStream fis = new FileInputStream(filePath)){
                OutputStream oos = clientSocket.getOutputStream();
                String fileName = new File(filePath).getName();
                String header  = "Filename: "+fileName+"\n";
                oos.write(header.getBytes());

                byte[] buffer = new byte[4096];
                int byteRead;
                while((byteRead = fis.read(buffer)) != -1){
                    oos.write(buffer,0,byteRead);
                }
                System.out.println("File "+fileName +"sent to "+clientSocket.getInetAddress());
            } catch (Exception ex){
                System.err.println("Error sending file to the client "+ex.getMessage());
            } finally {
                try{
                    clientSocket.close();
                }catch (Exception ex){
                    System.err.println("Error closing socket: "+ex.getMessage());
                }
            }


        }
    }
}
